package org.treblereel.mcp.workspace.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.treblereel.mcp.mcp.WorkspaceProjectScope;
import org.treblereel.mcp.workspace.WorkspaceDependencyGraph;

/** Resolves dependency boundaries onto local workspace providers without executing builds. */
public final class WorkspaceQueryRouter {

    private final WorkspaceProjectScope scope;

    public WorkspaceQueryRouter(WorkspaceProjectScope scope) {
        this.scope = scope;
    }

    public WorkspaceRoute resolveDependency(
            String consumerRepository, String consumerModule, String coordinate) {
        if (scope == null) {
            return new WorkspaceRoute("workspace_mode_required", List.of(), false,
                    List.of("Start Quill with --workspace <path>"));
        }
        if (consumerRepository == null || consumerRepository.isBlank()
                || coordinate == null || coordinate.isBlank()) {
            return new WorkspaceRoute("invalid_request", List.of(), false,
                    List.of("consumerRepository and coordinate are required"));
        }
        String repository = consumerRepository.strip();
        String module = normalizeModule(consumerModule);
        String ga = ga(coordinate);
        String version = version(coordinate);
        WorkspaceDependencyGraph.Result graph =
                WorkspaceDependencyGraph.discover(scope.manifest());
        List<WorkspaceDependencyGraph.Edge> matches = graph.edges().stream()
                .filter(edge -> edge.consumerRepository().equals(repository))
                .filter(edge -> edge.consumerModule().equals(module))
                .filter(edge -> edge.coordinate().equals(ga))
                .filter(edge -> version == null || version.equals(edge.checkoutVersion()))
                .sorted(Comparator.comparing(WorkspaceDependencyGraph.Edge::providerRepository)
                        .thenComparing(WorkspaceDependencyGraph.Edge::providerModule))
                .toList();
        if (matches.isEmpty()) {
            return new WorkspaceRoute("not_found", List.of(), graph.complete(),
                    graph.diagnostics());
        }
        List<WorkspaceHop> candidates = new ArrayList<>();
        for (WorkspaceDependencyGraph.Edge edge : matches) {
            candidates.add(new WorkspaceHop(edge.consumerRepository(), edge.consumerModule(),
                    edge.providerRepository(), edge.providerModule(), edge.coordinate(),
                    edge.scopes(), edge.sourceSet(), edge.evidence(), "workspace_coordinates",
                    edge.ambiguousProvider() ? "low" : "high", edge.status(),
                    edge.crossRepository()));
        }
        boolean ambiguous = matches.size() > 1
                || matches.stream().anyMatch(WorkspaceDependencyGraph.Edge::ambiguousProvider);
        return new WorkspaceRoute(ambiguous ? "ambiguous" : "resolved", candidates,
                graph.complete() && !ambiguous, graph.diagnostics());
    }

    private static String normalizeModule(String module) {
        if (module == null || module.isBlank()) return ".";
        String value = module.strip().replace('\\', '/');
        if (value.equals("./")) return ".";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String ga(String coordinate) {
        String[] parts = coordinate.strip().split(":");
        return parts.length >= 2 ? parts[0] + ':' + parts[1] : coordinate.strip();
    }

    private static String version(String coordinate) {
        String[] parts = coordinate.strip().split(":");
        return parts.length >= 3 && !parts[2].isBlank() ? parts[2] : null;
    }
}
