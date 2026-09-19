package org.treblereel.mcp.workspace.query;

import java.util.Set;

/** One evidence-backed transition between modules in a Quill workspace. */
public record WorkspaceHop(
        String fromRepository,
        String fromModule,
        String toRepository,
        String toModule,
        String coordinate,
        Set<String> scopes,
        String resolution,
        String confidence,
        String versionStatus,
        boolean crossRepository) {

    public WorkspaceHop {
        scopes = Set.copyOf(scopes);
    }
}
