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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ClassFileSnapshot;
import org.treblereel.mcp.core.DependencyIndexer;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.GradleProjectDiscovery;
import org.treblereel.mcp.core.MavenProjectDiscovery;

/** Discovers compiled project layout and fingerprints the build inputs it represents. */
final class ProjectLayout {

    record ClassesDiscovery(
            List<Path> classesDirectories, String moduleScope, boolean complete) {
        ClassesDiscovery {
            classesDirectories = List.copyOf(classesDirectories);
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
            LinkedHashSet<Path> result = new LinkedHashSet<>();
            discovery.moduleDirectories().stream()
                    .map(module -> module.resolve("target/classes"))
                    .filter(ProjectLayout::containsClassFiles)
                    .forEach(result::add);
            if (!discovery.complete()) {
                result.addAll(scanClassesDirs(root, path -> path.endsWith("target/classes")));
            }
            return new ClassesDiscovery(
                    List.copyOf(result), "maven_reactor", discovery.complete());
        }

        GradleProjectDiscovery.Discovery discovery = refreshGradleClasspath
                ? GradleProjectDiscovery.discoverAndWriteClasspath(root)
                : GradleProjectDiscovery.discover(root);
        LinkedHashSet<Path> result = discovery.classesDirectories().stream()
                .filter(ProjectLayout::containsClassFiles)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!discovery.complete()) {
            result.addAll(scanClassesDirs(root, ProjectLayout::isGradleMainClassesDir));
        }
        return new ClassesDiscovery(
                List.copyOf(result), "gradle_multiproject", discovery.complete());
    }

    static List<Path> findSourceRoots(List<Path> moduleDirectories) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path module : moduleDirectories) {
            for (Path candidate : List.of(
                    module.resolve("src/main/java"),
                    module.resolve("src/main/kotlin"),
                    module.resolve("target/generated-sources/annotations"),
                    module.resolve("build/generated/sources/annotationProcessor/java/main"),
                    module.resolve("build/generated/ksp/main/kotlin"))) {
                if (Files.isDirectory(candidate)) roots.add(candidate.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(roots);
    }

    static String computeStateFingerprint(Path root, List<Path> classesDirs) {
        return computeStateFingerprint(
                root, classesDirs, ClassFileSnapshot.capture(classesDirs).fingerprint());
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

            for (Path classesDir : classesDirs.stream()
                    .map(path -> path.toAbsolutePath().normalize()).sorted().toList()) {
                Path moduleDir = owners.get(classesDir);
                if (moduleDir == null) {
                    updateDigest(digest, "classpathMissing", classesDir.toString());
                    continue;
                }
                Path classpathFile = buildSystem.classpathFile(moduleDir);
                if (Files.isRegularFile(classpathFile)) {
                    updateFileIdentity(digest, root, classpathFile);
                    for (Path jar : DependencyIndexer.parseClasspathFile(classpathFile).stream()
                            .map(path -> path.toAbsolutePath().normalize()).sorted().toList()) {
                        updateFileIdentity(digest, root, jar);
                    }
                } else {
                    updateDigest(digest, "classpathMissing", moduleDir.toString());
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

    private static boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) return true;
        }
        return false;
    }

    private static boolean isGradleMainClassesDir(Path path) {
        Path relative;
        try {
            relative = path.toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            return false;
        }
        for (int i = 0; i + 3 < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("build")
                    && relative.getName(i + 1).toString().equals("classes")
                    && relative.getName(i + 3).toString().equals("main")) {
                return i + 4 == relative.getNameCount();
            }
        }
        return false;
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
