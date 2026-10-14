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
            root = org.treblereel.mcp.core.ProjectIdentity.canonical(root);
        }
        public boolean matches(String selector) {
            if (name.equalsIgnoreCase(selector)) return true;
            try {
                if (org.treblereel.mcp.core.ProjectIdentity.resolve(root).projectId().equals(selector)) return true;
            } catch (java.io.IOException ignored) { }
            try {
                Path path = Path.of(selector);
                return path.isAbsolute() && org.treblereel.mcp.core.ProjectIdentity.canonical(path).startsWith(root);
            } catch (RuntimeException invalid) { return false; }
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

    default List<Project> select(Snapshot snapshot, String selector) {
        if (selector == null || selector.isBlank()) return snapshot.projects();
        String normalized = selector.strip();
        return snapshot.projects().stream()
                .filter(project -> project.matches(normalized))
                .toList();
    }
}
