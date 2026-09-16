package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

final class TestProjectCopies {

    private static final Set<String> GENERATED_DIRECTORIES = Set.of(
            ".gradle", ".kotlin", ".quill", "build", "out", "target");

    private TestProjectCopies() {}

    static Path copyFixture(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path relative = source.relativize(path);
                if (containsGeneratedDirectory(relative)) continue;

                Path target = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        return destination;
    }

    private static boolean containsGeneratedDirectory(Path relative) {
        for (Path segment : relative) {
            if (GENERATED_DIRECTORIES.contains(segment.toString())) return true;
        }
        return false;
    }
}
