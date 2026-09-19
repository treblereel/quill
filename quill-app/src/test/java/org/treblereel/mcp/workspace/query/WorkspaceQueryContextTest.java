package org.treblereel.mcp.workspace.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceQueryContextTest {

    @Test
    void guardsCyclesDepthRepositoriesAndRoutes() {
        WorkspaceQueryContext context = new WorkspaceQueryContext("engine", "trace", 1, 2, 1);

        assertEquals(WorkspaceQueryContext.VisitDecision.ACCEPTED,
                context.visit("engine", ".", "Engine", "outbound", 0));
        assertEquals(WorkspaceQueryContext.VisitDecision.ALREADY_VISITED,
                context.visit("engine", ".", "Engine", "outbound", 0));
        assertEquals(WorkspaceQueryContext.VisitDecision.ALREADY_VISITED,
                context.visit("engine", ".", "Engine", "outbound", 1));
        assertEquals(WorkspaceQueryContext.VisitDecision.ACCEPTED,
                context.visit("connectors", "core", "Connector", "outbound", 1));
        assertEquals(WorkspaceQueryContext.VisitDecision.REPOSITORY_LIMIT,
                context.visit("platform", ".", "Platform", "outbound", 1));
        assertEquals(WorkspaceQueryContext.VisitDecision.DEPTH_LIMIT,
                context.visit("engine", ".", "Nested", "outbound", 2));

        WorkspaceHop first = hop("engine", "connectors");
        assertEquals(WorkspaceQueryContext.RouteDecision.ACCEPTED, context.addRoute(first));
        assertEquals(WorkspaceQueryContext.RouteDecision.ACCEPTED, context.addRoute(first));
        assertEquals(WorkspaceQueryContext.RouteDecision.ROUTE_LIMIT,
                context.addRoute(hop("connectors", "platform")));
        assertEquals(2, context.repositories().size());
        assertEquals(1, context.routes().size());
    }

    private static WorkspaceHop hop(String from, String to) {
        return new WorkspaceHop(from, ".", to, ".", "org.acme:api", Set.of("compile"),
                "main", "declared_dependency", "workspace_coordinates", "high",
                "version_match", true);
    }
}
