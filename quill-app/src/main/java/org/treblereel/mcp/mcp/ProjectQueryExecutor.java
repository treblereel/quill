package org.treblereel.mcp.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Resolves project indexes and isolates failures while executing read-only tool queries. */
final class ProjectQueryExecutor {

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
        List<QueryResult> results = new ArrayList<>(projects.size());
        for (ProjectRegistry.ProjectEntry project : projects) {
            results.add(executeProject(project, query));
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
}
