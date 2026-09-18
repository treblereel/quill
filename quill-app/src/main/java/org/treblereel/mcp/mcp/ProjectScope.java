package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.util.List;

/** Supplies an immutable view of the projects addressable by one MCP server. */
public interface ProjectScope {

    record Project(String name, Path root) {
        public Project {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Project name must not be blank");
            }
            root = root.toAbsolutePath().normalize();
        }
    }

    record Snapshot(long revision, List<Project> projects, List<String> diagnostics) {
        public Snapshot {
            projects = List.copyOf(projects);
            diagnostics = List.copyOf(diagnostics);
        }

        public Snapshot(long revision, List<Project> projects) {
            this(revision, projects, List.of());
        }
    }

    Snapshot snapshot();
}
