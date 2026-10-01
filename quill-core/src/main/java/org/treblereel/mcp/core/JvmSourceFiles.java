package org.treblereel.mcp.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Shared Java/Kotlin source-path conventions used by indexing and workspace fallbacks. */
public final class JvmSourceFiles {

    private static final List<String> EXTENSIONS = List.of(".java", ".kt");

    private JvmSourceFiles() {}

    public static boolean isSourceFile(Path path) {
        return isSourceFile(path.getFileName().toString());
    }

    public static boolean isSourceFile(String path) {
        return EXTENSIONS.stream().anyMatch(path::endsWith);
    }

    public static List<Path> conventionalCandidates(
            Path module, String sourceSet, String qualifiedClassName) {
        String topLevel = topLevelClassName(qualifiedClassName);
        Path relative = Path.of(topLevel.replace('.', '/'));
        return List.of(
                module.resolve("src").resolve(sourceSet).resolve("java")
                        .resolve(relative + ".java"),
                module.resolve("src").resolve(sourceSet).resolve("kotlin")
                        .resolve(relative + ".kt"));
    }

    public static Path findConventionalSource(
            Path module, String sourceSet, String qualifiedClassName) {
        return conventionalCandidates(module, sourceSet, qualifiedClassName).stream()
                .filter(Files::isRegularFile)
                .findFirst().orElse(null);
    }

    public static boolean isConventionalTestName(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        for (String extension : EXTENSIONS) {
            if (!name.endsWith(extension)) continue;
            String basename = name.substring(0, name.length() - extension.length());
            return basename.endsWith("Test") || basename.endsWith("Tests")
                    || basename.endsWith("IT") || basename.endsWith("ITCase")
                    || basename.endsWith("Spec");
        }
        return false;
    }

    private static String topLevelClassName(String className) {
        int nested = className.indexOf('$');
        return nested >= 0 ? className.substring(0, nested) : className;
    }
}
