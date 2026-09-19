package org.treblereel.mcp.workspace.query;

import java.util.List;

/** Resolution of a dependency coordinate onto zero, one, or several workspace providers. */
public record WorkspaceRoute(
        String status,
        List<WorkspaceHop> candidates,
        boolean complete,
        List<String> diagnostics) {

    public WorkspaceRoute {
        candidates = List.copyOf(candidates);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean resolved() {
        return "resolved".equals(status) && candidates.size() == 1;
    }
}
