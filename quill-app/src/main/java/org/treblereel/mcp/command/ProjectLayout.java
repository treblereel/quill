package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ClassFileSnapshot;
import org.treblereel.mcp.core.DependencyIndexer;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.GradleProjectDiscovery;
import org.treblereel.mcp.core.MavenProjectDiscovery;

/** Discovers compiled project layout and fingerprints the build inputs it represents. */
final class ProjectLayout {

    record CompiledOutput(Path directory, Path moduleDirectory, String sourceSet) {
        CompiledOutput {
            directory = directory.toAbsolutePath().normalize();
            moduleDirectory = moduleDirectory.toAbsolutePath().normalize();
            if (!sourceSet.equals("main") && !sourceSet.equals("test")) {
                throw new IllegalArgumentException("Unsupported source set: " + sourceSet);
            }
        }
    }

    record ClassesDiscovery(
            List<CompiledOutput> outputs, List<Path> moduleDirectories,
            String moduleScope, boolean complete) {
        ClassesDiscovery {
            outputs = List.copyOf(outputs);
            moduleDirectories = moduleDirectories.stream()
                    .map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        }

        List<Path> classesDirectories() {
            return outputs.stream().map(CompiledOutput::directory).toList();
        }
    }

    private ProjectLayout() {}

    static List<Path> findClassesDirs(Path root, boolean refreshGradleClasspath) {
        return discoverClassesDirs(root, refreshGradleClasspath).classesDirectories();
    }

    static ClassesDiscovery discoverClassesDirs(Path root, boolean refreshGradleClasspath) {
        BuildSystem buildSystem = BuildSystem.detect(root);
        if (buildSystem == BuildSystem.MAVEN) {
            MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(root);
            Map<Path, CompiledOutput> result = new java.util.LinkedHashMap<>();
            for (Path module : discovery.moduleDirectories()) {
                addOutput(result, module.resolve("target/classes"), module, "main");
                addOutput(result, module.resolve("target/test-classes"), module, "test");
            }
            if (!discovery.complete()) {
                for (Path path : scanClassesDirs(root, ProjectLayout::isMavenClassesDir)) {
                    Path module = path.getParent().getParent();
                    addOutput(result, path, module,
                            path.endsWith("target/test-classes") ? "test" : "main");
                }
            }
            return new ClassesDiscovery(
                    List.copyOf(result.values()), discovery.moduleDirectories(),
                    "maven_reactor", discovery.complete());
        }

        GradleProjectDiscovery.Discovery discovery = refreshGradleClasspath
                ? GradleProjectDiscovery.discoverAndWriteClasspath(root)
                : GradleProjectDiscovery.discover(root);
        Map<Path, CompiledOutput> result = new java.util.LinkedHashMap<>();
        for (Path path : discovery.classesDirectories()) {
            Path module = discovery.classDirectoryOwners().get(path);
            String sourceSet = discovery.classDirectorySourceSets().get(path);
            if (module != null && sourceSet != null) addOutput(result, path, module, sourceSet);
        }
        if (!discovery.complete()) {
            for (Path path : scanClassesDirs(root, ProjectLayout::isGradleClassesDir)) {
                Path module = org.treblereel.mcp.core.BuildSystem.GRADLE.moduleDir(path);
                addOutput(result, path, module, gradleSourceSet(path));
            }
        }
        return new ClassesDiscovery(
                List.copyOf(result.values()), discovery.moduleDirectories(),
                "gradle_multiproject", discovery.complete());
    }

    static List<Path> findSourceRoots(List<Path> moduleDirectories) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path module : moduleDirectories) {
            for (Path candidate : List.of(
                    module.resolve("src/main/java"),
                    module.resolve("src/main/kotlin"),
                    module.resolve("src/test/java"),
                    module.resolve("src/test/kotlin"),
                    module.resolve("target/generated-sources/annotations"),
                    module.resolve("target/generated-test-sources/test-annotations"),
                    module.resolve("build/generated/sources/annotationProcessor/java/main"),
                    module.resolve("build/generated/sources/annotationProcessor/java/test"),
                    module.resolve("build/generated/ksp/main/kotlin"),
                    module.resolve("build/generated/ksp/test/kotlin"))) {
                if (Files.isDirectory(candidate)) roots.add(candidate.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(roots);
    }

    static String computeStateFingerprint(Path root, List<Path> classesDirs) {
        return computeStateFingerprint(
                root, classesDirs, ClassFileSnapshot.capture(classesDirs).fingerprint());
    }

    static List<Path> staleTestOutputModules(List<CompiledOutput> outputs) {
        List<Path> stale = new java.util.ArrayList<>();
        Map<Path, List<CompiledOutput>> byModule = outputs.stream()
                .filter(output -> output.sourceSet().equals("test"))
                .collect(java.util.stream.Collectors.groupingBy(
                        CompiledOutput::moduleDirectory, java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        for (var entry : byModule.entrySet()) {
            long newestSource = Math.max(latestModified(entry.getKey().resolve("src/test/java")),
                    latestModified(entry.getKey().resolve("src/test/kotlin")));
            long newestClass = entry.getValue().stream()
                    .mapToLong(output -> latestModified(output.directory())).max().orElse(0);
            if (newestSource > newestClass) stale.add(entry.getKey());
        }
        return List.copyOf(stale);
    }

    static boolean hasTestSources(Path module) {
        for (Path directory : List.of(
                module.resolve("src/test/java"),
                module.resolve("src/test/kotlin"),
                module.resolve("target/generated-test-sources"),
                module.resolve("build/generated/sources/annotationProcessor/java/test"),
                module.resolve("build/generated/ksp/test/kotlin"))) {
            if (containsSourceFiles(directory)) return true;
        }
        return false;
    }

    static String computeStateFingerprint(
            Path root, List<Path> classesDirs, String classContentFingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BuildSystem buildSystem = BuildSystem.detect(root);
            Map<Path, Path> owners =
                    DependencyIndexer.mapClassDirectoriesToModules(root, buildSystem, classesDirs);
            updateDigest(digest, "head", GitAnalyzer.resolveHead(root));
            updateDigest(digest, "build",
                    DependencyIndexer.buildFingerprint(root, buildSystem, owners.values()));
            updateDigest(digest, "classFiles", classContentFingerprint);

            Map<Path, List<Path>> outputsByModule = classesDirs.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .filter(owners::containsKey)
                    .collect(java.util.stream.Collectors.groupingBy(
                            owners::get, java.util.LinkedHashMap::new,
                            java.util.stream.Collectors.toList()));
            for (Path classesDir : classesDirs.stream().map(path -> path.toAbsolutePath().normalize())
                    .filter(path -> !owners.containsKey(path)).sorted().toList()) {
                updateDigest(digest, "classpathMissing", classesDir.toString());
            }
            for (var entry : outputsByModule.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                Path moduleDir = entry.getKey();
                Path classpathFile = buildSystem.classpathFile(moduleDir);
                updateClasspathIdentity(digest, root, classpathFile, "classpathMissing", moduleDir);
                if (entry.getValue().stream().anyMatch(ProjectLayout::isTestOutputDirectory)) {
                    updateClasspathIdentity(digest, root, buildSystem.testClasspathFile(moduleDir),
                            "testClasspathMissing", moduleDir);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private static List<Path> scanClassesDirs(Path root, Predicate<Path> outputDirectory) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isDirectory)
                    .filter(outputDirectory)
                    .filter(path -> !hasPathSegment(path, ".gradle"))
                    .filter(path -> !hasPathSegment(path, "buildSrc"))
                    .filter(ProjectLayout::containsClassFiles)
                    .toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    private static boolean containsClassFiles(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (Stream<Path> classFiles = Files.walk(directory)) {
            return classFiles.anyMatch(file -> file.toString().endsWith(".class"));
        } catch (IOException error) {
            return false;
        }
    }

    private static long latestModified(Path directory) {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.getLastModifiedTime(file).toMillis();
                } catch (IOException ignored) {
                    return 0;
                }
            }).max().orElse(0);
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static boolean containsSourceFiles(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).anyMatch(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".java") || name.endsWith(".kt");
            });
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) return true;
        }
        return false;
    }

    private static void addOutput(Map<Path, CompiledOutput> outputs, Path directory,
            Path module, String sourceSet) {
        if (!containsClassFiles(directory)) return;
        CompiledOutput output = new CompiledOutput(directory, module, sourceSet);
        outputs.putIfAbsent(output.directory(), output);
    }

    private static boolean isMavenClassesDir(Path path) {
        return path.endsWith("target/classes") || path.endsWith("target/test-classes");
    }

    private static boolean isGradleClassesDir(Path path) {
        Path relative;
        try {
            relative = path.toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return false;
        }
        for (int i = 0; i + 3 < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("build")
                    && relative.getName(i + 1).toString().equals("classes")
                    && (relative.getName(i + 3).toString().equals("main")
                        || relative.getName(i + 3).toString().equals("test"))) {
                return i + 4 == relative.getNameCount();
            }
        }
        return false;
    }

    private static String gradleSourceSet(Path path) {
        return path.getFileName().toString().equals("test") ? "test" : "main";
    }

    private static boolean isTestOutputDirectory(Path path) {
        return path.endsWith("target/test-classes")
                || path.getFileName() != null && path.getFileName().toString().equals("test")
                        && path.toString().contains("build" + java.io.File.separator + "classes");
    }

    private static void updateClasspathIdentity(MessageDigest digest, Path root, Path classpathFile,
            String missingKey, Path moduleDir) {
        if (!Files.isRegularFile(classpathFile)) {
            updateDigest(digest, missingKey, moduleDir.toString());
            return;
        }
        updateFileIdentity(digest, root, classpathFile);
        for (Path jar : DependencyIndexer.parseClasspathFile(classpathFile).stream()
                .map(path -> path.toAbsolutePath().normalize()).sorted().toList()) {
            updateFileIdentity(digest, root, jar);
        }
    }

    private static void updateFileIdentity(MessageDigest digest, Path base, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            Path normalizedBase = base.toAbsolutePath().normalize();
            String name = normalized.startsWith(normalizedBase)
                    ? normalizedBase.relativize(normalized).toString() : normalized.toString();
            updateDigest(digest, "file", name);
            updateDigest(digest, "size", Long.toString(Files.size(normalized)));
            updateDigest(digest, "mtime",
                    Long.toString(Files.getLastModifiedTime(normalized).toMillis()));
        } catch (IOException error) {
            updateDigest(digest, "missing", normalized.toString());
        }
    }

    private static void updateDigest(MessageDigest digest, String key, String value) {
        digest.update(key.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '=');
        if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
