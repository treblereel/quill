package org.treblereel.mcp.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/** Resolves project indexes and isolates failures while executing read-only tool queries. */
final class ProjectQueryExecutor {

    private static final ExecutorService QUERY_EXECUTOR = Executors.newFixedThreadPool(
            queryWorkers(), Thread.ofPlatform().daemon()
                    .name("quill-workspace-query-", 0).factory());

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

    Batch execute(String selector, Function<ProjectRegistry.ProjectEntry, String> query) {
        ProjectRegistry.Resolution resolution = selector == null || selector.isBlank()
                ? registry.resolve() : registry.resolve(selector);
        return new Batch(resolution, executeProjects(resolution.projects(), query));
    }

    List<QueryResult> executeProjects(List<ProjectRegistry.ProjectEntry> projects,
            Function<ProjectRegistry.ProjectEntry, String> query) {
        if (projects.size() <= 1) {
            return projects.stream().map(project -> executeProject(project, query)).toList();
        }
        List<Future<QueryResult>> pending = projects.stream()
                .map(project -> QUERY_EXECUTOR.submit(() -> executeProject(project, query)))
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
            Function<ProjectRegistry.ProjectEntry, String> query) {
        try {
            return new QueryResult(project.name(), query.apply(project), null);
        } catch (Exception error) {
            return new QueryResult(project.name(), null, ProjectRegistry.safeMessage(error));
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
