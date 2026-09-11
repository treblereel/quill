package org.treblereel.mcp.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;

public final class DependencyIndexer {

    private DependencyIndexer() {}

    public enum Status { COMPLETE, DEGRADED, UNAVAILABLE }

    public record DependencyIndexResult(Index index, Status status, String detail) {}

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

        System.err.println("[quill] Indexing " + jars.size() + " dependency JARs...");
        Index index = indexJars(jars);

        if (generationFailed || modulesResolved < moduleDirs.size() || missingJars > 0) {
            return new DependencyIndexResult(index, Status.DEGRADED,
                    dependencyDetail(generationFailed, modulesResolved, moduleDirs.size(), missingJars));
        }

        return new DependencyIndexResult(index, Status.COMPLETE,
                jars.size() + " JARs indexed");
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
        return Map.copyOf(result);
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

    static Index indexJars(Collection<Path> jars) {
        Indexer indexer = new Indexer();
        for (Path jar : jars) {
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
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
}
