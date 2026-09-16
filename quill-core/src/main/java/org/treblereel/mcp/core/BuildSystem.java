package org.treblereel.mcp.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Build-tool-specific project layout without linking either build tool into Quill. */
public enum BuildSystem {
    MAVEN,
    GRADLE;

    public static BuildSystem detect(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized.resolve("pom.xml"))) return MAVEN;
        if (hasGradleMarker(normalized)) return GRADLE;
        throw new IllegalArgumentException("Unsupported Java project at " + normalized
                + ": expected pom.xml, settings.gradle[.kts], or build.gradle[.kts]");
    }

    public static boolean hasGradleMarker(Path dir) {
        return Files.isRegularFile(dir.resolve("settings.gradle"))
                || Files.isRegularFile(dir.resolve("settings.gradle.kts"))
                || Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    public Path moduleDir(Path classesDir) {
        Path normalized = classesDir.toAbsolutePath().normalize();
        Path current = normalized;
        while (current != null) {
            if (this == MAVEN && current.getFileName() != null
                    && current.getFileName().toString().equals("target")) {
                return current.getParent();
            }
            if (this == GRADLE && current.getFileName() != null
                    && current.getFileName().toString().equals("build")) {
                return current.getParent();
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Class directory is outside a " + name().toLowerCase()
                + " build output: " + classesDir);
    }

    public Path classpathFile(Path moduleDir) {
        return moduleDir.resolve(this == MAVEN ? "target" : "build")
                .resolve("quill-classpath.txt");
    }

    public Path classpathFingerprintFile(Path moduleDir) {
        return moduleDir.resolve(this == MAVEN ? "target" : "build")
                .resolve("quill-classpath.sha256");
    }

    /** Builds a platform-specific invocation of this project's wrapper or installed build tool. */
    public List<String> command(Path root, String... arguments) {
        return command(root, isWindows(), arguments);
    }

    List<String> command(Path root, boolean windows, String... arguments) {
        Path normalized = root.toAbsolutePath().normalize();
        String unixWrapper = this == MAVEN ? "mvnw" : "gradlew";
        String windowsWrapper = this == MAVEN ? "mvnw.cmd" : "gradlew.bat";
        String installedTool = this == MAVEN ? "mvn" : "gradle";

        List<String> result = new ArrayList<>();
        if (windows) {
            result.add("cmd.exe");
            result.add("/d");
            result.add("/c");
            Path wrapper = normalized.resolve(windowsWrapper);
            result.add(Files.isRegularFile(wrapper) ? wrapper.toString() : installedTool);
        } else {
            Path wrapper = normalized.resolve(unixWrapper);
            result.add(Files.isExecutable(wrapper) ? wrapper.toString() : installedTool);
        }
        result.addAll(Arrays.asList(arguments));
        return List.copyOf(result);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
    }
}
