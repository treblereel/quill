package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.ProjectIndexStore;
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

    private final ProjectScope scope;
    private final SingleProjectScope mutableScope;
    private final ConcurrentHashMap<Path, Jdbi> databases = new ConcurrentHashMap<>();
    private final BuildEventConsumer buildEvents = new BuildEventConsumer();

    public ProjectRegistry() {
        this(new SingleProjectScope());
    }

    public ProjectRegistry(ProjectScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.mutableScope = scope instanceof SingleProjectScope single ? single : null;
    }

    public void register(Path projectPath) {
        if (mutableScope == null) {
            throw new IllegalStateException("Projects are managed by "
                    + scope.getClass().getSimpleName());
        }
        mutableScope.register(projectPath);
    }

    public List<ProjectEntry> initialized() {
        return resolve().projects();
    }

    public Resolution resolve() {
        List<ProjectEntry> result = new ArrayList<>();
        ProjectScope.Snapshot snapshot = scope.snapshot();
        List<String> errors = new ArrayList<>(snapshot.diagnostics());
        for (ProjectScope.Project p : snapshot.projects()) {
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
        List<ProjectScope.Project> snapshot = scope.snapshot().projects();
        executor.execute(() -> {
            try {
                Resolution resolution = resolve();
                resolution.projects().forEach(entry ->
                        ProjectDependencyQueries.prewarm(entry.jdbi(), entry.root()));
            } catch (RuntimeException ignored) {
                // A normal request will report the same project-specific error.
            }
        });
        for (ProjectScope.Project project : snapshot) {
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
        return scope.snapshot().projects().isEmpty();
    }
}
