package org.treblereel.mcp.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.model.ClassOccurrenceRecord;

/** Preserves every physical occurrence even when Jandex collapses duplicate FQCNs. */
public final class ClassOccurrenceScanner {

    private ClassOccurrenceScanner() {}

    public static List<ClassOccurrenceRecord> scan(
            Path projectRoot, ClassFileSnapshot snapshot, Map<Path, Path> directoryOwners,
            Map<String, Integer> logicalClassIds) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Map<Path, Path> normalizedOwners = directoryOwners.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().toAbsolutePath().normalize(),
                        entry -> entry.getValue().toAbsolutePath().normalize()));
        List<ClassOccurrenceRecord> result = new ArrayList<>();
        for (ClassFileSnapshot.Entry entry : snapshot.entries()) {
            Path output = entry.classesDirectory().toAbsolutePath().normalize();
            String relativeClass = normalize(output.relativize(entry.path()));
            String className = className(relativeClass);
            Integer classId = logicalClassIds.get(className);
            if (classId == null) continue;
            Path owner = normalizedOwners.getOrDefault(output, root);
            String sourceSet = sourceSet(output, owner);
            Path source = findSource(owner, sourceSet, className);
            result.add(new ClassOccurrenceRecord(
                    0, classId, className, relative(root, owner), sourceSet,
                    relative(root, output), relative(root, entry.path()),
                    source != null ? relative(root, source) : null,
                    source == null ? "orphan_output"
                            : isGenerated(source) ? "generated" : "source"));
        }
        result.sort(Comparator.comparing(ClassOccurrenceRecord::className)
                .thenComparing(ClassOccurrenceRecord::outputDirectory)
                .thenComparing(ClassOccurrenceRecord::classFile));
        List<ClassOccurrenceRecord> numbered = new ArrayList<>(result.size());
        for (int i = 0; i < result.size(); i++) {
            ClassOccurrenceRecord value = result.get(i);
            numbered.add(new ClassOccurrenceRecord(i + 1, value.classId(), value.className(),
                    value.module(), value.sourceSet(), value.outputDirectory(), value.classFile(),
                    value.sourceFile(), value.origin()));
        }
        return List.copyOf(numbered);
    }

    private static String className(String relativeClass) {
        String withoutSuffix = relativeClass.substring(0, relativeClass.length() - ".class".length());
        return withoutSuffix.replace('/', '.');
    }

    private static String sourceSet(Path output, Path owner) {
        String relative = normalize(owner.relativize(output));
        if (relative.contains("test-classes") || relative.matches(".*classes/[^/]+/test($|/.*)")) {
            return "test";
        }
        return "main";
    }

    private static Path findSource(Path module, String sourceSet, String className) {
        String topLevel = className.contains("$")
                ? className.substring(0, className.indexOf('$')) : className;
        Path javaPath = Path.of(topLevel.replace('.', '/') + ".java");
        Path kotlinPath = Path.of(topLevel.replace('.', '/') + ".kt");
        List<Path> roots = new ArrayList<>();
        roots.add(module.resolve("src").resolve(sourceSet).resolve("java"));
        roots.add(module.resolve("src").resolve(sourceSet).resolve("kotlin"));
        if (sourceSet.equals("test")) {
            roots.add(module.resolve("target/generated-test-sources/test-annotations"));
            roots.add(module.resolve("build/generated/sources/annotationProcessor/java/test"));
        } else {
            roots.add(module.resolve("target/generated-sources/annotations"));
            roots.add(module.resolve("build/generated/sources/annotationProcessor/java/main"));
        }
        for (Path sourceRoot : roots) {
            Path java = sourceRoot.resolve(javaPath);
            if (Files.isRegularFile(java)) return java.toAbsolutePath().normalize();
            Path kotlin = sourceRoot.resolve(kotlinPath);
            if (Files.isRegularFile(kotlin)) return kotlin.toAbsolutePath().normalize();
        }
        return null;
    }

    private static boolean isGenerated(Path path) {
        String normalized = normalize(path);
        return normalized.startsWith("target/generated-")
                || normalized.contains("/target/generated-")
                || normalized.startsWith("build/generated/")
                || normalized.contains("/build/generated/");
    }

    private static String relative(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root)) return ".";
        return normalized.startsWith(root) ? normalize(root.relativize(normalized)) : normalize(normalized);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
