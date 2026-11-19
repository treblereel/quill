package org.treblereel.mcp.mcp.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArchitectureBoundariesTest {
    @Test void transportStorageAndAnalysisKeepTheirDependencyDirection() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        for (var boundary : List.of(
                List.of("quill-app", "mcp", "command"),
                List.of("quill-app", "index", "command"),
                List.of("quill-app", "index", "mcp"),
                List.of("quill-app", "workspace", "mcp"),
                List.of("quill-core", "model", "core"),
                List.of("quill-core", "analysis", "db"))) {
            Path directory = root.resolve(boundary.get(0) + "/src/main/java/org/treblereel/mcp/" + boundary.get(1));
            try (var sources = Files.walk(directory)) {
                for (var file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    assertFalse(Files.readString(file).contains("org.treblereel.mcp." + boundary.get(2) + "."),
                            () -> file + " depends on forbidden package " + boundary.get(2));
                }
            }
        }
    }

    @Test void projectEntryRequiresItsGenerationOwner() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new ProjectRegistry.ProjectEntry("project", Path.of("."), null));
    }
}
