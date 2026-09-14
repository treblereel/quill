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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
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

    private static final String CACHE_FORMAT = "quill-jandex-cache-v4";
    private static final String CACHE_FILE = "quill-dependencies.idx";
    private static final String FAILURE_CACHE_FORMAT = "quill-classpath-failure-v1";
    private static final String FAILURE_CACHE_FILE = "quill-classpath.failed";
    static final Duration GENERATION_FAILURE_BACKOFF = Duration.ofMinutes(5);
    private static final int MAX_INDEXING_SHARDS = 8;

    private DependencyIndexer() {}

    public enum Status { COMPLETE, DEGRADED, UNAVAILABLE }

    public record DependencyIndexResult(
            IndexView index, Status status, String detail, Map<String, Long> timings) {
        public DependencyIndexResult(IndexView index, Status status, String detail) {
            this(index, status, detail, Map.of());
        }

        public DependencyIndexResult {
            timings = Collections.unmodifiableMap(new LinkedHashMap<>(timings));
        }
    }

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

    record ClasspathGenerationResult(boolean successful, String detail) {
        static ClasspathGenerationResult success() {
            return new ClasspathGenerationResult(true, "");
        }

        static ClasspathGenerationResult failure(String detail) {
            return new ClasspathGenerationResult(false, detail);
        }
    }

    private record GenerationFailure(String detail) {}

    private record BuildInputSnapshot(
            String fingerprint, FileTime newestModified) {}

    @FunctionalInterface
    interface ClasspathGenerator {
        ClasspathGenerationResult generate(Path projectRoot, BuildSystem buildSystem);
    }

    public static DependencyIndexResult buildDependencyIndex(Path projectRoot, List<Path> classesDirs) {
        return buildDependencyIndex(projectRoot, classesDirs,
                DependencyIndexer::generateClasspathFilesDetailed, System.currentTimeMillis());
    }

    static DependencyIndexResult buildDependencyIndex(Path projectRoot, List<Path> classesDirs,
            ClasspathGenerator generator, long nowMillis) {
        Map<String, Long> timings = new LinkedHashMap<>();
        timings.put("dependency_classpath", 0L);
        timings.put("dependency_cache_read", 0L);
        timings.put("dependency_jar_index", 0L);
        timings.put("dependency_cache_write", 0L);
        long classpathStartedAt = System.nanoTime();
        BuildSystem buildSystem = detectBuildSystem(projectRoot, classesDirs);
        Map<Path, Path> classDirectoryOwners =
                mapClassDirectoriesToModules(projectRoot, buildSystem, classesDirs);
        Set<Path> moduleDirs = new LinkedHashSet<>(classDirectoryOwners.values());
        BuildInputSnapshot buildInputs = buildInputSnapshot(
                projectRoot, buildSystem, moduleDirs);
        String buildFingerprint = buildInputs.fingerprint();
        FileTime newestBuildFile = buildInputs.newestModified();

        boolean anyStale = false;
        for (Path moduleDir : moduleDirs) {
            if (isStale(moduleDir, buildSystem, buildFingerprint, newestBuildFile)) {
                anyStale = true;
                break;
            }
        }

        String generationIssue = null;
        if (anyStale) {
            GenerationFailure cachedFailure = readGenerationFailure(
                    projectRoot, buildSystem, buildFingerprint, nowMillis);
            if (cachedFailure != null) {
                generationIssue = cachedFailure.detail()
                        + "; retry suppressed for unchanged build files for up to "
                        + GENERATION_FAILURE_BACKOFF.toMinutes() + " minutes";
            } else {
                ClasspathGenerationResult generated = generator.generate(projectRoot, buildSystem);
                if (generated.successful()) {
                    deleteGenerationFailure(projectRoot, buildSystem);
                } else {
                    generationIssue = generated.detail();
                    if (!Thread.currentThread().isInterrupted()) {
                        writeGenerationFailure(projectRoot, buildSystem, buildFingerprint,
                                nowMillis, generationIssue);
                    }
                }
            }
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
                if (generationIssue == null && classpath.readable()) {
                    writeFingerprint(moduleDir, buildSystem, buildFingerprint);
                }
            }
        }
        timings.put("dependency_classpath", elapsedMillis(classpathStartedAt));

        if (modulesResolved == 0) {
            String detail = generationIssue != null
                    ? generationIssue
                    : "no classpath files found";
            return new DependencyIndexResult(null, Status.UNAVAILABLE, detail, timings);
        }

        if (jars.isEmpty()) {
            boolean degraded = generationIssue != null
                    || modulesResolved < moduleDirs.size() || missingJars > 0;
            return new DependencyIndexResult(null,
                    degraded ? Status.DEGRADED : Status.COMPLETE,
                    degraded
                            ? dependencyDetail(generationIssue, modulesResolved,
                                    moduleDirs.size(), missingJars)
                            : "no dependency JARs in classpath", timings);
        }

        long cacheReadStartedAt = System.nanoTime();
        String cacheFingerprint = dependencyCacheFingerprint(jars);
        ShardedIndex index = readCachedIndex(projectRoot, buildSystem, cacheFingerprint);
        timings.put("dependency_cache_read", elapsedMillis(cacheReadStartedAt));
        boolean cacheHit = index != null;
        if (cacheHit) {
            System.err.println("[quill] Reusing dependency index for " + jars.size() + " JARs...");
        } else {
            System.err.println("[quill] Indexing " + jars.size() + " dependency JARs...");
            long jarIndexStartedAt = System.nanoTime();
            index = indexJars(jars);
            timings.put("dependency_jar_index", elapsedMillis(jarIndexStartedAt));
            long cacheWriteStartedAt = System.nanoTime();
            writeCachedIndex(projectRoot, buildSystem, cacheFingerprint, index);
            timings.put("dependency_cache_write", elapsedMillis(cacheWriteStartedAt));
        }

        if (generationIssue != null || modulesResolved < moduleDirs.size() || missingJars > 0) {
            return new DependencyIndexResult(index.view(), Status.DEGRADED,
                    dependencyDetail(generationIssue, modulesResolved,
                            moduleDirs.size(), missingJars), timings);
        }

        return new DependencyIndexResult(index.view(), Status.COMPLETE,
                jars.size() + (cacheHit ? " JARs loaded from cache" : " JARs indexed"),
                timings);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    private static String dependencyDetail(
            String generationIssue, int modulesResolved, int moduleCount, int missingJars) {
        List<String> reasons = new ArrayList<>();
        if (generationIssue != null) reasons.add(generationIssue + "; using cached data");
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
        BuildInputSnapshot buildInputs = buildInputSnapshot(
                moduleDir, buildSystem, List.of());
        return isStale(moduleDir, buildSystem, buildInputs.fingerprint(),
                buildInputs.newestModified());
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
        return buildInputSnapshot(projectRoot, buildSystem, moduleDirectories).fingerprint();
    }

    private static BuildInputSnapshot buildInputSnapshot(
            Path projectRoot, BuildSystem buildSystem, Collection<Path> moduleDirectories) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileTime newest = FileTime.fromMillis(0);
            for (Path buildFile : buildFiles(projectRoot, buildSystem, moduleDirectories)) {
                digest.update(buildFile.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try {
                    digest.update(Files.readAllBytes(buildFile));
                    FileTime modified = Files.getLastModifiedTime(buildFile);
                    if (modified.compareTo(newest) > 0) newest = modified;
                } catch (IOException e) {
                    digest.update((byte) 1);
                    newest = FileTime.fromMillis(Long.MAX_VALUE);
                }
                digest.update((byte) 0);
            }
            return new BuildInputSnapshot(
                    HexFormat.of().formatHex(digest.digest()), newest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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
        List<Path> independentScanRoots = new ArrayList<>();
        for (Path candidate : scanRoots) {
            if (independentScanRoots.stream().noneMatch(candidate::startsWith)) {
                independentScanRoots.removeIf(existing -> existing.startsWith(candidate));
                independentScanRoots.add(candidate);
            }
        }
        for (Path scanRoot : independentScanRoots) {
            if (Files.isDirectory(scanRoot)) {
                try {
                    Files.walkFileTree(scanRoot, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path directory, BasicFileAttributes attributes) {
                            if (!directory.equals(scanRoot) && isBuildOutputDirectory(directory)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(
                                Path file, BasicFileAttributes attributes) {
                            if (attributes.isRegularFile() && isBuildFile(file, buildSystem)) {
                                files.add(file.toAbsolutePath().normalize());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
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

    private static boolean isBuildOutputDirectory(Path directory) {
        String name = directory.getFileName().toString();
        return name.equals("target") || name.equals("build") || name.equals(".gradle");
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
        return generateClasspathFilesDetailed(projectRoot, buildSystem).successful();
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

    private static ClasspathGenerationResult generateClasspathFilesDetailed(
            Path projectRoot, BuildSystem buildSystem) {
        if (buildSystem == BuildSystem.GRADLE) {
            return generateGradleClasspathFiles(projectRoot)
                    ? ClasspathGenerationResult.success()
                    : ClasspathGenerationResult.failure(
                            "Gradle dependency classpath discovery failed");
        }
        Process process = null;
        try {
            process = new ProcessBuilder(buildSystem.command(projectRoot,
                    "dependency:build-classpath", "-DincludeScope=runtime",
                    "-Dmdep.outputFile=target/quill-classpath.txt", "-q"))
                    .directory(projectRoot.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            int exit = process.waitFor();
            if (exit != 0) {
                String detail = "Maven dependency classpath generation exited with code " + exit;
                System.err.println("[quill] Warning: " + detail);
                return ClasspathGenerationResult.failure(detail);
            }
            return ClasspathGenerationResult.success();
        } catch (InterruptedException e) {
            terminate(process);
            Thread.currentThread().interrupt();
            String detail = "Dependency classpath resolution was interrupted";
            System.err.println("[quill] Warning: " + detail);
            return ClasspathGenerationResult.failure(detail);
        } catch (IOException e) {
            String detail = "Could not resolve dependency classpath: " + e.getMessage();
            System.err.println("[quill] Warning: " + detail);
            return ClasspathGenerationResult.failure(detail);
        }
    }

    private static void terminate(Process process) {
        if (process == null) return;
        try (var descendants = process.descendants()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // Best effort; destroying the wrapper process is still preferable to leaving it alive.
        }
        process.destroyForcibly();
    }

    private static boolean generateGradleClasspathFiles(Path projectRoot) {
        return GradleProjectDiscovery.discover(projectRoot, true).complete();
    }

    private static GenerationFailure readGenerationFailure(Path projectRoot,
            BuildSystem buildSystem, String expectedFingerprint, long nowMillis) {
        Path failureCache = generationFailurePath(projectRoot, buildSystem);
        if (!Files.isRegularFile(failureCache)) return null;
        try {
            List<String> lines = Files.readAllLines(failureCache, StandardCharsets.UTF_8);
            if (lines.size() != 4 || !FAILURE_CACHE_FORMAT.equals(lines.get(0))
                    || !expectedFingerprint.equals(lines.get(1))) {
                return null;
            }
            long failedAtMillis = Long.parseLong(lines.get(2));
            long ageMillis = nowMillis - failedAtMillis;
            if (ageMillis < 0 || ageMillis >= GENERATION_FAILURE_BACKOFF.toMillis()) {
                return null;
            }
            String detail = new String(Base64.getDecoder().decode(lines.get(3)),
                    StandardCharsets.UTF_8);
            return new GenerationFailure(detail);
        } catch (IOException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void writeGenerationFailure(Path projectRoot, BuildSystem buildSystem,
            String fingerprint, long failedAtMillis, String detail) {
        Path failureCache = generationFailurePath(projectRoot, buildSystem);
        try {
            Files.createDirectories(failureCache.getParent());
            String encodedDetail = Base64.getEncoder().encodeToString(
                    detail.getBytes(StandardCharsets.UTF_8));
            Files.writeString(failureCache,
                    FAILURE_CACHE_FORMAT + "\n" + fingerprint + "\n" + failedAtMillis + "\n"
                            + encodedDetail + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // The failure cache is optional; dependency discovery remains correct without it.
        }
    }

    private static void deleteGenerationFailure(Path projectRoot, BuildSystem buildSystem) {
        try {
            Files.deleteIfExists(generationFailurePath(projectRoot, buildSystem));
        } catch (IOException ignored) {
            // A stale marker is ignored after its fingerprint or backoff expires.
        }
    }

    static Path generationFailurePath(Path projectRoot, BuildSystem buildSystem) {
        return projectRoot.resolve(buildSystem == BuildSystem.MAVEN ? "target" : "build")
                .resolve(FAILURE_CACHE_FILE);
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
            List<byte[]> serializedShards = new ArrayList<>(shardCount);
            for (int i = 0; i < shardCount; i++) {
                int length = input.readInt();
                if (length < 1 || length > cacheSize) return null;
                byte[] serialized = input.readNBytes(length);
                if (serialized.length != length) return null;
                serializedShards.add(serialized);
            }
            return new ShardedIndex(readIndexShards(serializedShards));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static List<Index> readIndexShards(List<byte[]> serializedShards) throws IOException {
        if (serializedShards.size() == 1) {
            return List.of(readIndexShard(serializedShards.get(0)));
        }

        int threadCount = Math.min(serializedShards.size(),
                Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Index>> futures = new ArrayList<>(serializedShards.size());
            for (byte[] serialized : serializedShards) {
                futures.add(executor.submit(() -> readIndexShard(serialized)));
            }
            // Await in cache order so CompositeIndex keeps classpath shadowing semantics.
            List<Index> shards = new ArrayList<>(serializedShards.size());
            for (Future<Index> future : futures) shards.add(future.get());
            return shards;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Dependency cache loading was interrupted", e);
        } catch (ExecutionException e) {
            throw new IOException("Could not deserialize dependency cache", e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Index readIndexShard(byte[] serialized) throws IOException {
        return new IndexReader(new ByteArrayInputStream(serialized)).read();
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
