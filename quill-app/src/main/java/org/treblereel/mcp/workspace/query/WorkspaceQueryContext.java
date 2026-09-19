package org.treblereel.mcp.workspace.query;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Per-request traversal budget and cycle guard for federated workspace queries. */
public final class WorkspaceQueryContext {

    public enum VisitDecision {
        ACCEPTED,
        ALREADY_VISITED,
        DEPTH_LIMIT,
        REPOSITORY_LIMIT
    }

    public enum RouteDecision {
        ACCEPTED,
        ROUTE_LIMIT
    }

    public record Visit(
            String repository, String module, String entity, String direction, int depth) {}

    private record VisitKey(
            String repository, String module, String entity, String direction) {}

    private final String startRepository;
    private final String traceId;
    private final int maxDepth;
    private final int maxRepositories;
    private final int maxRoutes;
    private final Set<VisitKey> visitKeys = new LinkedHashSet<>();
    private final Set<Visit> visited = new LinkedHashSet<>();
    private final Set<String> repositories = new LinkedHashSet<>();
    private final Set<WorkspaceHop> routes = new LinkedHashSet<>();

    public WorkspaceQueryContext(String startRepository, String traceId,
            int maxDepth, int maxRepositories, int maxRoutes) {
        if (startRepository == null || startRepository.isBlank()) {
            throw new IllegalArgumentException("startRepository is required");
        }
        if (maxDepth < 0 || maxRepositories < 1 || maxRoutes < 1) {
            throw new IllegalArgumentException("Traversal limits must be positive");
        }
        this.startRepository = startRepository.strip();
        this.traceId = traceId == null ? "" : traceId;
        this.maxDepth = maxDepth;
        this.maxRepositories = maxRepositories;
        this.maxRoutes = maxRoutes;
        repositories.add(this.startRepository);
    }

    public static WorkspaceQueryContext defaults(String startRepository, String traceId) {
        return new WorkspaceQueryContext(startRepository, traceId, 3, 20, 100);
    }

    public synchronized VisitDecision visit(String repository, String module, String entity,
            String direction, int depth) {
        if (depth > maxDepth) return VisitDecision.DEPTH_LIMIT;
        Visit visit = new Visit(normalize(repository), normalizeModule(module), normalize(entity),
                normalize(direction), depth);
        VisitKey key = new VisitKey(visit.repository(), visit.module(), visit.entity(),
                visit.direction());
        if (visitKeys.contains(key)) return VisitDecision.ALREADY_VISITED;
        if (!repositories.contains(visit.repository())
                && repositories.size() >= maxRepositories) {
            return VisitDecision.REPOSITORY_LIMIT;
        }
        repositories.add(visit.repository());
        visitKeys.add(key);
        visited.add(visit);
        return VisitDecision.ACCEPTED;
    }

    public synchronized RouteDecision addRoute(WorkspaceHop route) {
        if (routes.contains(route)) return RouteDecision.ACCEPTED;
        if (routes.size() >= maxRoutes) return RouteDecision.ROUTE_LIMIT;
        routes.add(route);
        return RouteDecision.ACCEPTED;
    }

    public String startRepository() {
        return startRepository;
    }

    public String traceId() {
        return traceId;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public synchronized List<Visit> visited() {
        return List.copyOf(visited);
    }

    public synchronized List<String> repositories() {
        return List.copyOf(repositories);
    }

    public synchronized List<WorkspaceHop> routes() {
        return List.copyOf(routes);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizeModule(String value) {
        String module = normalize(value).replace('\\', '/');
        return module.isEmpty() ? "." : module;
    }
}
