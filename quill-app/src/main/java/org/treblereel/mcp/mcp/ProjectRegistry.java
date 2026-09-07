package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.QuillDatabase;

public class ProjectRegistry {

    public record ProjectEntry(String name, Path root, Jdbi jdbi) {}
    public record Resolution(List<ProjectEntry> projects, List<String> errors) {}

    private record RegisteredProject(String name, Path root) {}

    private final List<RegisteredProject> projects = new ArrayList<>();

    public void register(Path projectPath) {
        try {
            Path resolved = ProjectRootFinder.find(projectPath);
            String name = resolved.getFileName().toString();
            String uniqueName = dedup(name);
            projects.add(new RegisteredProject(uniqueName, resolved));
        } catch (IllegalArgumentException e) {
            Path fallback = projectPath.toAbsolutePath().normalize();
            String name = fallback.getFileName().toString();
            projects.add(new RegisteredProject(dedup(name), fallback));
        }
    }

    public List<ProjectEntry> initialized() {
        return resolve().projects();
    }

    public Resolution resolve() {
        List<ProjectEntry> result = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (RegisteredProject p : projects) {
            Path dbPath = ProjectInitializer.findDbForHead(p.root());
            if (dbPath == null) {
                errors.add("Project '" + p.name() + "' is not indexed. "
                        + "Run: quill init --project " + p.root());
                continue;
            }
            try {
                result.add(new ProjectEntry(p.name(), p.root(), QuillDatabase.open(dbPath)));
            } catch (RuntimeException e) {
                errors.add("Project '" + p.name() + "': " + safeMessage(e));
            }
        }
        return new Resolution(List.copyOf(result), List.copyOf(errors));
    }

    public List<String> uninitializedErrors() {
        return resolve().errors();
    }

    static String safeMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        try {
            String message = current.getMessage();
            return message == null || message.isBlank()
                    ? current.getClass().getSimpleName() : message;
        } catch (RuntimeException messageFailure) {
            return current.getClass().getSimpleName();
        }
    }

    public boolean isEmpty() {
        return projects.isEmpty();
    }

    private String dedup(String name) {
        String candidate = name;
        int suffix = 1;
        while (hasName(candidate)) {
            candidate = name + "-" + suffix++;
        }
        return candidate;
    }

    private boolean hasName(String name) {
        for (RegisteredProject p : projects) {
            if (p.name().equals(name)) return true;
        }
        return false;
    }
}
