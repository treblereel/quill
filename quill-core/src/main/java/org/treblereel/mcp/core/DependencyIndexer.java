package org.treblereel.mcp.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.IndexWriter;

public final class DependencyIndexer {

    private static final String CACHE_FORMAT = "quill-jandex-cache-v3";
    private static final String CACHE_FILE = "quill-dependencies.idx";
    private static final int MAX_INDEXING_SHARDS = 4;

    private DependencyIndexer() {}

    public enum Status { COMPLETE, DEGRADED, UNAVAILABLE }

    public record DependencyIndexResult(IndexView index, Status status, String detail) {}

    static final class ShardedIndex {
        private final List<Index> shards;
        private final IndexView view;

        ShardedIndex(List<Index> shards) {
            this.shards = List.copyOf(shards);
            List<IndexView> views = new ArrayList<>(shards);
            this.view = shards.size() == 1 ? shards.get(0) : CompositeIndex.create(views);
        }

        List<Index> shards() { return shards; }

        IndexView view() { return view; }
    }

    private record ClasspathRead(List<Path> jars, int missingJars, boolean readable) {}

    public static DependencyIndexResult buildDependencyIndex(Path projectRoot, List<Path> classesDirs) {
        BuildSystem buildSystem = detectBuildSystem(projectRoot, classesDirs);
        Map<Path, Path> classDirectoryOwners =
                mapClassDirectoriesToModules(projectRoot, buildSystem, classesDirs);
        Set<Path> moduleDirs = new LinkedHashSet<>(classDirectoryOwners.values());
        String buildFingerprint = buildFingerprint(projectRoot, buildSystem, moduleDirs);
        FileTime newestBuildFile = newestBuildFileTimestamp(projectRoot, buildSystem, moduleDirs);

        boolean anyStale = false;
        for (Path moduleDir : moduleDirs) {
            if (isStale(moduleDir, buildSystem, buildFingerprint, newestBuildFile)) {
                anyStale = true;
                break;
            }
        }

        boolean generationFailed = false;
        if (anyStale) {
            generationFailed = !generateClasspathFiles(projectRoot, buildSystem);
        }

        Set<Path> jars = new LinkedHashSet<>();
        int modulesResolved = 0;
        int missingJars = 0;
        for (Path moduleDir : moduleDirs) {
            Path cpFile = buildSystem.classpathFile(moduleDir);
            if (Files.exists(cpFile)) {
                ClasspathRead classpath = readClasspathFile(cpFile);
                jars.addAll(classpath.jars());
                missingJars += classpath.missingJars();
                if (classpath.readable()) modulesResolved++;
                if (!generationFailed && classpath.readable()) {
                    writeFingerprint(moduleDir, buildSystem, buildFingerprint);
                }
            }
        }

        if (modulesResolved == 0) {
            String detail = generationFailed
                    ? buildSystem.name().toLowerCase() + " dependency classpath generation failed"
                    : "no classpath files found";
            return new DependencyIndexResult(null, Status.UNAVAILABLE, detail);
        }

        if (jars.isEmpty()) {
            boolean degraded = generationFailed || modulesResolved < moduleDirs.size() || missingJars > 0;
            return new DependencyIndexResult(null,
                    degraded ? Status.DEGRADED : Status.COMPLETE,
                    degraded
                            ? dependencyDetail(generationFailed, modulesResolved, moduleDirs.size(), missingJars)
                            : "no dependency JARs in classpath");
        }

        String cacheFingerprint = dependencyCacheFingerprint(jars);
        ShardedIndex index = readCachedIndex(projectRoot, buildSystem, cacheFingerprint);
        boolean cacheHit = index != null;
        if (cacheHit) {
            System.err.println("[quill] Reusing dependency index for " + jars.size() + " JARs...");
        } else {
            System.err.println("[quill] Indexing " + jars.size() + " dependency JARs...");
            index = indexJars(jars);
            writeCachedIndex(projectRoot, buildSystem, cacheFingerprint, index);
        }

        if (generationFailed || modulesResolved < moduleDirs.size() || missingJars > 0) {
            return new DependencyIndexResult(index.view(), Status.DEGRADED,
                    dependencyDetail(generationFailed, modulesResolved, moduleDirs.size(), missingJars));
        }

        return new DependencyIndexResult(index.view(), Status.COMPLETE,
                jars.size() + (cacheHit ? " JARs loaded from cache" : " JARs indexed"));
    }

    private static String dependencyDetail(
            boolean generationFailed, int modulesResolved, int moduleCount, int missingJars) {
        List<String> reasons = new ArrayList<>();
        if (generationFailed) reasons.add("Build-tool classpath generation failed; using cached data");
        if (modulesResolved < moduleCount) {
            reasons.add(modulesResolved + "/" + moduleCount + " modules resolved");
        }
        if (missingJars > 0) reasons.add(missingJars + " classpath JARs missing");
        return String.join("; ", reasons);
    }

    public static Map<Path, Path> mapClassDirectoriesToModules(
            Path projectRoot, BuildSystem buildSystem, List<Path> classesDirs) {
        Map<Path, Path> result = new LinkedHashMap<>();
        List<Path> unresolved = new ArrayList<>();
        for (Path classesDir : classesDirs) {
            Path normalized = classesDir.toAbsolutePath().normalize();
            try {
                result.put(normalized, buildSystem.moduleDir(normalized));
            } catch (IllegalArgumentException e) {
                unresolved.add(normalized);
            }
        }

        if (buildSystem == BuildSystem.GRADLE && !unresolved.isEmpty()) {
            GradleProjectDiscovery.Discovery discovery =
                    GradleProjectDiscovery.discover(projectRoot);
            for (Path classesDir : unresolved) {
                Path module = discovery.classDirectoryOwners().get(classesDir);
                if (module != null) result.put(classesDir, module);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    static boolean isStale(Path moduleDir) {
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(moduleDir);
        } catch (IllegalArgumentException ignored) {
            buildSystem = Files.isDirectory(moduleDir.resolve("build"))
                    ? BuildSystem.GRADLE : BuildSystem.MAVEN;
        }
        return isStale(moduleDir, buildSystem, buildFingerprint(moduleDir, buildSystem),
                newestBuildFileTimestamp(moduleDir, buildSystem));
    }

    private static boolean isStale(Path moduleDir, BuildSystem buildSystem,
                                   String expectedFingerprint, FileTime newestBuildFile) {
        Path cpFile = buildSystem.classpathFile(moduleDir);
        if (!Files.exists(cpFile)) return true;

        Path fingerprintFile = buildSystem.classpathFingerprintFile(moduleDir);
        try {
            if (Files.exists(fingerprintFile)) {
                return !Files.readString(fingerprintFile).trim().equals(expectedFingerprint);
            }
            // Backward-compatible first run: accept an existing classpath only when
            // it is newer than every build file that can affect the build.
            return newestBuildFile.compareTo(Files.getLastModifiedTime(cpFile)) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    public static String pomFingerprint(Path projectRoot) {
        return buildFingerprint(projectRoot);
    }

    public static String buildFingerprint(Path projectRoot) {
        return buildFingerprint(projectRoot, BuildSystem.detect(projectRoot));
    }

    static String buildFingerprint(Path projectRoot, BuildSystem buildSystem) {
        return buildFingerprint(projectRoot, buildSystem, List.of());
    }

    public static String buildFingerprint(
            Path projectRoot, BuildSystem buildSystem, Collection<Path> moduleDirectories) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path buildFile : buildFiles(projectRoot, buildSystem, moduleDirectories)) {
                digest.update(buildFile.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try {
                    digest.update(Files.readAllBytes(buildFile));
                } catch (IOException e) {
                    digest.update((byte) 1);
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static FileTime newestBuildFileTimestamp(Path projectRoot, BuildSystem buildSystem) {
        return newestBuildFileTimestamp(projectRoot, buildSystem, List.of());
    }

    private static FileTime newestBuildFileTimestamp(
            Path projectRoot, BuildSystem buildSystem, Collection<Path> moduleDirectories) {
        FileTime newest = FileTime.fromMillis(0);
        for (Path buildFile : buildFiles(projectRoot, buildSystem, moduleDirectories)) {
            try {
                FileTime modified = Files.getLastModifiedTime(buildFile);
                if (modified.compareTo(newest) > 0) newest = modified;
            } catch (IOException e) {
                return FileTime.fromMillis(Long.MAX_VALUE);
            }
        }
        return newest;
    }

    private static List<Path> buildFiles(
            Path projectRoot, BuildSystem buildSystem, Collection<Path> moduleDirectories) {
        Set<Path> files = new LinkedHashSet<>();
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Set<Path> scanRoots = new LinkedHashSet<>();
        scanRoots.add(normalizedRoot);
        moduleDirectories.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .forEach(scanRoots::add);
        for (Path scanRoot : scanRoots) {
            if (Files.isDirectory(scanRoot)) {
                try (var walk = Files.walk(scanRoot)) {
                    walk.filter(p -> isBuildFile(p, buildSystem))
                            .filter(p -> !isBuildOutput(p))
                            .map(p -> p.toAbsolutePath().normalize())
                            .forEach(files::add);
                } catch (IOException e) {
                    // Ancestor build files collected below still provide a stable fallback.
                }
            }
        }

        Path current = normalizedRoot;
        while (current != null) {
            for (String name : buildSystem == BuildSystem.MAVEN
                    ? List.of("pom.xml")
                    : List.of("settings.gradle", "settings.gradle.kts", "build.gradle",
                            "build.gradle.kts", "gradle.properties")) {
                Path candidate = current.resolve(name);
                if (Files.isRegularFile(candidate)) files.add(candidate.toAbsolutePath().normalize());
            }
            current = current.getParent();
        }
        return files.stream().sorted().toList();
    }

    private static boolean isBuildFile(Path path, BuildSystem buildSystem) {
        String name = path.getFileName().toString();
        if (buildSystem == BuildSystem.MAVEN) return name.equals("pom.xml");
        if (path.toString().contains(File.separator + "buildSrc" + File.separator)) {
            return Files.isRegularFile(path);
        }
        return name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("gradle.properties") || name.equals("libs.versions.toml")
                || name.equals("gradle-wrapper.properties");
    }

    private static boolean isBuildOutput(Path path) {
        String value = path.toString();
        return value.contains(File.separator + "target" + File.separator)
                || value.contains(File.separator + "build" + File.separator)
                || value.contains(File.separator + ".gradle" + File.separator);
    }

    private static void writeFingerprint(Path moduleDir, BuildSystem buildSystem, String fingerprint) {
        try {
            Files.writeString(buildSystem.classpathFingerprintFile(moduleDir), fingerprint);
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not persist dependency classpath fingerprint for "
                    + moduleDir + ": " + e.getMessage());
        }
    }

    static boolean generateClasspathFiles(Path projectRoot) {
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(projectRoot);
        } catch (IllegalArgumentException ignored) {
            buildSystem = BuildSystem.MAVEN;
        }
        return generateClasspathFiles(projectRoot, buildSystem);
    }

    private static BuildSystem detectBuildSystem(Path projectRoot, List<Path> classesDirs) {
        try {
            return BuildSystem.detect(projectRoot);
        } catch (IllegalArgumentException ignored) {
            return classesDirs.stream().anyMatch(path -> path.toString().contains(
                    File.separator + "build" + File.separator + "classes" + File.separator))
                    ? BuildSystem.GRADLE : BuildSystem.MAVEN;
        }
    }

    private static boolean generateClasspathFiles(Path projectRoot, BuildSystem buildSystem) {
        if (buildSystem == BuildSystem.GRADLE) return generateGradleClasspathFiles(projectRoot);
        try {
            int exit = new ProcessBuilder(buildSystem.command(projectRoot,
                    "dependency:build-classpath", "-DincludeScope=runtime",
                    "-Dmdep.outputFile=target/quill-classpath.txt", "-q"))
                    .directory(projectRoot.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor();
            if (exit != 0) {
                System.err.println("[quill] Warning: mvn dependency:build-classpath exited with code " + exit);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[quill] Warning: dependency classpath resolution was interrupted");
            return false;
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not resolve dependency classpath: " + e.getMessage());
            return false;
        }
    }

    private static boolean generateGradleClasspathFiles(Path projectRoot) {
        return GradleProjectDiscovery.discover(projectRoot, true).complete();
    }

    public static List<Path> parseClasspathFile(Path cpFile) {
        return readClasspathFile(cpFile).jars();
    }

    private static ClasspathRead readClasspathFile(Path cpFile) {
        try {
            String classpath = Files.readString(cpFile).trim();
            if (classpath.isEmpty()) return new ClasspathRead(List.of(), 0, true);
            List<Path> jars = new ArrayList<>();
            int missing = 0;
            for (String entry : classpath.split(File.pathSeparator)) {
                Path path = Path.of(entry);
                if (!path.toString().endsWith(".jar")) continue;
                if (Files.exists(path)) jars.add(path);
                else missing++;
            }
            return new ClasspathRead(List.copyOf(jars), missing, true);
        } catch (IOException e) {
            return new ClasspathRead(List.of(), 0, false);
        }
    }

    static ShardedIndex indexJars(Collection<Path> jars) {
        List<Path> orderedJars = List.copyOf(jars);
        if (orderedJars.isEmpty()) {
            return new ShardedIndex(List.of(indexJarBatch(List.of())));
        }

        int shardCount = Math.min(orderedJars.size(), Math.min(MAX_INDEXING_SHARDS,
                Runtime.getRuntime().availableProcessors()));
        Map<Path, Set<String>> selectedClasses = selectClasspathClasses(orderedJars);
        if (shardCount == 1) {
            return new ShardedIndex(List.of(indexJarBatch(orderedJars, selectedClasses)));
        }

        List<List<Path>> batches = contiguousBatches(orderedJars, shardCount);
        ExecutorService executor = Executors.newFixedThreadPool(shardCount);
        try {
            List<Future<Index>> futures = new ArrayList<>(shardCount);
            for (List<Path> batch : batches) {
                futures.add(executor.submit(() -> indexJarBatch(batch, selectedClasses)));
            }
            List<Index> indexes = new ArrayList<>(shardCount);
            for (Future<Index> future : futures) indexes.add(future.get());
            return new ShardedIndex(indexes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Dependency indexing was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Parallel dependency indexing failed", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<List<Path>> contiguousBatches(List<Path> jars, int batchCount) {
        List<List<Path>> batches = new ArrayList<>(batchCount);
        int baseSize = jars.size() / batchCount;
        int remainder = jars.size() % batchCount;
        int start = 0;
        for (int i = 0; i < batchCount; i++) {
            int size = baseSize + (i < remainder ? 1 : 0);
            batches.add(jars.subList(start, start + size));
            start += size;
        }
        return batches;
    }

    private static Map<Path, Set<String>> selectClasspathClasses(List<Path> jars) {
        Set<String> claimed = new HashSet<>();
        Map<Path, Set<String>> selected = new LinkedHashMap<>();
        for (Path jar : jars) {
            Set<String> jarClasses = new HashSet<>();
            try (JarFile jf = openJar(jar); var entries = jf.versionedStream()) {
                entries.filter(DependencyIndexer::isClassEntry).forEach(entry -> {
                    String name = entry.getName();
                    // module-info.class has the same path in every modular JAR but describes
                    // a distinct named module, so classpath shadowing does not apply to it.
                    if (name.equals("module-info.class") || claimed.add(name)) {
                        jarClasses.add(name);
                    }
                });
            } catch (IOException e) {
                // Keep the empty selection; the indexing pass will skip the unreadable JAR too.
            }
            selected.put(jar, Set.copyOf(jarClasses));
        }
        return Collections.unmodifiableMap(selected);
    }

    private static Index indexJarBatch(Collection<Path> jars) {
        return indexJarBatch(jars, selectClasspathClasses(List.copyOf(jars)));
    }

    private static Index indexJarBatch(
            Collection<Path> jars, Map<Path, Set<String>> selectedClasses) {
        Indexer indexer = new Indexer();
        for (Path jar : jars) {
            Set<String> selected = selectedClasses.getOrDefault(jar, Set.of());
            try (JarFile jf = openJar(jar); var entries = jf.versionedStream()) {
                Iterator<JarEntry> iterator = entries.iterator();
                while (iterator.hasNext()) {
                    JarEntry entry = iterator.next();
                    if (isClassEntry(entry) && selected.contains(entry.getName())) {
                        try (InputStream is = jf.getInputStream(entry)) {
                            indexer.index(is);
                        } catch (Exception e) {
                            // skip problematic class files
                        }
                    }
                }
            } catch (IOException e) {
                // skip unreadable JARs
            }
        }
        return indexer.complete();
    }

    private static JarFile openJar(Path jar) throws IOException {
        return new JarFile(jar.toFile(), false, ZipFile.OPEN_READ, JarFile.runtimeVersion());
    }

    private static boolean isClassEntry(JarEntry entry) {
        return entry.getName().endsWith(".class") && !entry.isDirectory();
    }

    static String dependencyCacheFingerprint(Collection<Path> jars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(CACHE_FORMAT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(JarFile.runtimeVersion().feature())
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            for (Path jar : jars) {
                Path normalized = jar.toAbsolutePath().normalize();
                digest.update(normalized.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Long.toString(Files.size(normalized))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.getLastModifiedTime(normalized).toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            return null;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static ShardedIndex readCachedIndex(
            Path projectRoot, BuildSystem buildSystem, String fingerprint) {
        if (fingerprint == null) return null;
        Path cache = dependencyCachePath(projectRoot, buildSystem);
        if (!Files.isRegularFile(cache)) return null;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(cache)))) {
            if (!CACHE_FORMAT.equals(input.readUTF()) || !fingerprint.equals(input.readUTF())) {
                return null;
            }
            int shardCount = input.readInt();
            if (shardCount < 1 || shardCount > MAX_INDEXING_SHARDS) return null;
            long cacheSize = Files.size(cache);
            List<Index> shards = new ArrayList<>(shardCount);
            for (int i = 0; i < shardCount; i++) {
                int length = input.readInt();
                if (length < 1 || length > cacheSize) return null;
                byte[] serialized = input.readNBytes(length);
                if (serialized.length != length) return null;
                shards.add(new IndexReader(new ByteArrayInputStream(serialized)).read());
            }
            return new ShardedIndex(shards);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void writeCachedIndex(Path projectRoot, BuildSystem buildSystem,
            String fingerprint, ShardedIndex index) {
        if (fingerprint == null) return;
        Path cache = dependencyCachePath(projectRoot, buildSystem);
        Path temporary = null;
        try {
            Files.createDirectories(cache.getParent());
            temporary = Files.createTempFile(cache.getParent(), ".quill-dependencies-", ".tmp");
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeUTF(CACHE_FORMAT);
                output.writeUTF(fingerprint);
                output.writeInt(index.shards().size());
                for (Index shard : index.shards()) {
                    ByteArrayOutputStream serialized = new ByteArrayOutputStream();
                    new IndexWriter(serialized).write(shard);
                    output.writeInt(serialized.size());
                    serialized.writeTo(output);
                }
            }
            try {
                Files.move(temporary, cache, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // The cache is optional; indexing remains correct without it.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an unpublished cache file.
                }
            }
        }
    }

    private static Path dependencyCachePath(Path projectRoot, BuildSystem buildSystem) {
        return projectRoot.resolve(buildSystem == BuildSystem.MAVEN ? "target" : "build")
                .resolve(CACHE_FILE);
    }
}
