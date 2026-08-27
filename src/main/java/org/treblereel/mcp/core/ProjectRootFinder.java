package org.treblereel.mcp.core;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectRootFinder {

    private ProjectRootFinder() {}

    public static Path find(Path explicitPath) {
        Path start = (explicitPath != null) ? explicitPath : Path.of(System.getProperty("user.dir"));
        start = start.toAbsolutePath().normalize();

        if (isProjectRoot(start)) {
            return start;
        }

        Path current = start;
        while (current != null) {
            if (isProjectRoot(current)) {
                return current;
            }
            current = current.getParent();
        }

        throw new IllegalArgumentException(
                "No Maven project found at or above: " + start
                        + ". Expected directory with pom.xml and src/main/java");
    }

    private static boolean isProjectRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("src/main/java"));
    }
}
