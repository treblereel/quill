package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.ProjectIndexStore;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.QuillDatabase;

public class ProjectRegistry {

    private static final Executor PREWARM_EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "quill-mcp-prewarm");
        thread.setDaemon(true);
        return thread;
    });

    public record ProjectEntry(String name, Path root, Jdbi jdbi) {}
    public record Resolution(List<ProjectEntry> projects, List<String> errors) {}

    private record RegisteredProject(String name, Path root) {}

    private final List<RegisteredProject> projects = new ArrayList<>();
    private final ConcurrentHashMap<Path, Jdbi> databases = new ConcurrentHashMap<>();
    private final BuildEventConsumer buildEvents = new BuildEventConsumer();

    public void register(Path projectPath) {
        try {
            Path resolved = ProjectRootFinder.find(projectPath);
            String name = resolved.getFileName().toString();
            String uniqueName = dedup(name);
            projects.add(new RegisteredProject(uniqueName, resolved));
        } catch (IllegalArgumentException e) {
            Path fallback = projectPath == null
                    ? Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
                    : projectPath.toAbsolutePath().normalize();
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
            String buildEventError = buildEvents.consume(p.root());
            if (buildEventError != null) {
                errors.add("Project '" + p.name() + "': " + buildEventError);
            }
            Path dbPath = ProjectIndexStore.findBestAvailableDb(p.root());
            if (dbPath == null) {
                errors.add("Project '" + p.name() + "' is not indexed. "
                        + "Run: quill init --project " + p.root());
                continue;
            }
            try {
                Path normalizedDb = dbPath.toAbsolutePath().normalize();
                Jdbi jdbi = databases.computeIfAbsent(normalizedDb, QuillDatabase::open);
                result.add(new ProjectEntry(p.name(), p.root(), jdbi));
            } catch (RuntimeException e) {
                errors.add("Project '" + p.name() + "': " + safeMessage(e));
            }
        }
        return new Resolution(List.copyOf(result), List.copyOf(errors));
    }

    public void prewarm() {
        prewarm(PREWARM_EXECUTOR);
    }

    void prewarm(Executor executor) {
        List<RegisteredProject> snapshot = List.copyOf(projects);
        executor.execute(() -> {
            try {
                resolve();
            } catch (RuntimeException ignored) {
                // A normal request will report the same project-specific error.
            }
        });
        for (RegisteredProject project : snapshot) {
            executor.execute(() -> {
                try {
                    WorktreeSnapshotCache.shared().get(project.root());
                } catch (RuntimeException ignored) {
                    // Live worktree metadata is optional and can be retried by a request.
                }
            });
        }
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
