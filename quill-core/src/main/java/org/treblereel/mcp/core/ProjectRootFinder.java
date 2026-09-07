package org.treblereel.mcp.core;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectRootFinder {

    private ProjectRootFinder() {}

    public static Path find(Path explicitPath) {
        Path start = (explicitPath != null) ? explicitPath : Path.of(System.getProperty("user.dir"));
        start = start.toAbsolutePath().normalize();

        Path current = start;
        Path nearestGradleBuild = null;
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))) return current;
            if (hasGradleSettings(current)) return current;
            if (nearestGradleBuild == null && hasGradleBuild(current)) nearestGradleBuild = current;
            current = current.getParent();
        }

        if (nearestGradleBuild != null) return nearestGradleBuild;

        throw new IllegalArgumentException(
                "No Maven or Gradle project found at or above: " + start
                        + ". Expected pom.xml, settings.gradle[.kts], or build.gradle[.kts]");
    }

    private static boolean hasGradleSettings(Path dir) {
        return Files.isRegularFile(dir.resolve("settings.gradle"))
                || Files.isRegularFile(dir.resolve("settings.gradle.kts"));
    }

    private static boolean hasGradleBuild(Path dir) {
        return Files.isRegularFile(dir.resolve("build.gradle"))
                || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }
}
