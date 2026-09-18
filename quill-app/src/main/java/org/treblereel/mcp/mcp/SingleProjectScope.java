package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.treblereel.mcp.core.ProjectRootFinder;

/** Static project scope used by the traditional single- or repeated-project CLI options. */
public final class SingleProjectScope implements ProjectScope {

    private final List<Project> projects = new ArrayList<>();
    private long revision;

    public synchronized void register(Path projectPath) {
        Path root = resolveRoot(projectPath);
        String baseName = root.getFileName() == null ? root.toString() : root.getFileName().toString();
        projects.add(new Project(deduplicate(baseName), root));
        revision++;
    }

    @Override
    public synchronized Snapshot snapshot() {
        return new Snapshot(revision, projects);
    }

    private static Path resolveRoot(Path projectPath) {
        try {
            return ProjectRootFinder.find(projectPath);
        } catch (IllegalArgumentException ignored) {
            return projectPath == null
                    ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                    : projectPath.toAbsolutePath().normalize();
        }
    }

    private String deduplicate(String name) {
        String candidate = name;
        int suffix = 1;
        while (containsName(candidate)) candidate = name + "-" + suffix++;
        return candidate;
    }

    private boolean containsName(String name) {
        return projects.stream().anyMatch(project -> project.name().equals(name));
    }
}
