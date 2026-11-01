package org.treblereel.mcp.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.BiFunction;
import org.treblereel.mcp.db.ReadQueryContext;

/** Resolves project indexes and isolates failures while executing read-only tool queries. */
final class ProjectQueryExecutor {

    private static final ExecutorService QUERY_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("quill-workspace-query-", 0).factory());
    private static final Semaphore QUERY_SLOTS = new Semaphore(queryWorkers(), true);

    record QueryResult(String project, String json, String error) {
        boolean successful() {
            return error == null;
        }
    }

    record Batch(ProjectRegistry.Resolution resolution, List<QueryResult> results) {
        Batch {
            results = List.copyOf(results);
        }
    }

    private final ProjectRegistry registry;

    ProjectQueryExecutor(ProjectRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    Batch execute(String selector, BiFunction<ProjectRegistry.ProjectEntry, ReadQueryContext, String> query) {
        return execute(selector, query, false);
    }

    Batch execute(String selector, BiFunction<ProjectRegistry.ProjectEntry, ReadQueryContext, String> query, boolean withGraph) {
        ProjectRegistry.Resolution resolution = selector == null || selector.isBlank()
                ? registry.resolve() : registry.resolve(selector);
        return new Batch(resolution, executeProjects(resolution.projects(), query, false, withGraph));
    }

    List<QueryResult> executeProjects(List<ProjectRegistry.ProjectEntry> projects,
            BiFunction<ProjectRegistry.ProjectEntry, ReadQueryContext, String> query) {
        return executeProjects(projects, query, false);
    }

    List<QueryResult> executeProjects(List<ProjectRegistry.ProjectEntry> projects,
            BiFunction<ProjectRegistry.ProjectEntry, ReadQueryContext, String> query, boolean nested) {
        return executeProjects(projects, query, nested, false);
    }

    private List<QueryResult> executeProjects(List<ProjectRegistry.ProjectEntry> projects,
            BiFunction<ProjectRegistry.ProjectEntry, ReadQueryContext, String> query, boolean nested, boolean withGraph) {
        // Nested work stays on the owner thread and shares its handle and admission slot.
        if (nested || projects.size() <= 1) {
            return projects.stream().map(project -> executeProject(project, query, !nested, withGraph))
                    .sorted(Comparator.comparing(QueryResult::project)).toList();
        }
        List<Future<QueryResult>> pending = projects.stream()
                .map(project -> QUERY_EXECUTOR.submit(() -> executeProject(project, query, true, withGraph)))
                .toList();
        List<QueryResult> results = new ArrayList<>(pending.size());
        try {
            for (Future<QueryResult> future : pending) results.add(future.get());
        } catch (InterruptedException interrupted) {
            pending.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace query interrupted", interrupted);
        } catch (ExecutionException failed) {
            pending.forEach(future -> future.cancel(true));
            throw new IllegalStateException("Workspace query worker failed",
                    failed.getCause());
        }
        results.sort(Comparator.comparing(QueryResult::project));
        return List.copyOf(results);
    }

    QueryResult executeProject(ProjectRegistry.ProjectEntry project,
            BiFunction<ProjectRegistry.ProjectEntry, ReadQueryContext, String> query) {
        return executeProject(project, query, true, false);
    }

    private QueryResult executeProject(ProjectRegistry.ProjectEntry project,
            BiFunction<ProjectRegistry.ProjectEntry, ReadQueryContext, String> query, boolean acquire, boolean withGraph) {
        boolean admitted = false;
        try (var metrics = org.treblereel.mcp.diagnostics.QueryMetrics.start()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (acquire) { QUERY_SLOTS.acquire(); admitted = true; }
            org.treblereel.mcp.core.WorktreeSnapshotCache.shared().refresh(project.root());
            String response = withGraph ? project.readWithGraph(context -> query.apply(project, context))
                    : project.read(context -> query.apply(project, context));
            return new QueryResult(project.name(), response, null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Workspace query interrupted", interrupted);
        } catch (Exception error) {
            return new QueryResult(project.name(), null, ProjectRegistry.safeMessage(error));
        } finally {
            if (admitted) QUERY_SLOTS.release();
        }
    }

    private static int queryWorkers() {
        int automatic = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        String configured = System.getenv("QUILL_WORKSPACE_QUERY_WORKERS");
        if (configured == null || configured.isBlank()) return automatic;
        try {
            int value = Integer.parseInt(configured.strip());
            return value > 0 ? Math.min(value, 32) : automatic;
        } catch (NumberFormatException ignored) {
            return automatic;
        }
    }
}
