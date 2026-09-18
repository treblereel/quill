package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
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
    private record IndexHandle(Jdbi jdbi, AtomicLong lastSeen) {}

    private final ConcurrentHashMap<Path, IndexHandle> databases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, AtomicLong> projectRoots = new ConcurrentHashMap<>();
    private final AtomicLong resolutionSequence = new AtomicLong();
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
        long resolutionId = resolutionSequence.incrementAndGet();
        List<ProjectEntry> result = new ArrayList<>();
        ProjectScope.Snapshot snapshot = scope.snapshot();
        List<String> errors = new ArrayList<>(snapshot.diagnostics());
        Set<Path> activeDatabases = ConcurrentHashMap.newKeySet();
        Set<Path> activeRoots = ConcurrentHashMap.newKeySet();
        for (ProjectScope.Project p : snapshot.projects()) {
            Path projectRoot = p.root().toAbsolutePath().normalize();
            activeRoots.add(projectRoot);
            projectRoots.compute(projectRoot, (ignored, seen) -> {
                if (seen == null) return new AtomicLong(resolutionId);
                seen.accumulateAndGet(resolutionId, Math::max);
                return seen;
            });
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
                activeDatabases.add(normalizedDb);
                IndexHandle handle = databases.compute(normalizedDb, (path, cached) -> {
                    if (cached == null) {
                        return new IndexHandle(QuillDatabase.open(path),
                                new AtomicLong(resolutionId));
                    }
                    cached.lastSeen().accumulateAndGet(resolutionId, Math::max);
                    return cached;
                });
                result.add(new ProjectEntry(p.name(), p.root(), handle.jdbi()));
            } catch (RuntimeException e) {
                errors.add("Project '" + p.name() + "': " + safeMessage(e));
            }
        }
        evictInactive(resolutionId, activeDatabases, activeRoots);
        return new Resolution(List.copyOf(result), List.copyOf(errors));
    }

    private void evictInactive(long resolutionId, Set<Path> activeDatabases,
            Set<Path> activeRoots) {
        databases.entrySet().removeIf(entry -> !activeDatabases.contains(entry.getKey())
                && entry.getValue().lastSeen().get() < resolutionId);
        projectRoots.entrySet().removeIf(entry -> {
            if (activeRoots.contains(entry.getKey())
                    || entry.getValue().get() >= resolutionId) return false;
            WorktreeSnapshotCache.shared().invalidate(entry.getKey());
            return true;
        });
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

    int cachedDatabaseCount() {
        return databases.size();
    }
}
