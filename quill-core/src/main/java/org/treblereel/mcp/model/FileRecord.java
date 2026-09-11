package org.treblereel.mcp.model;

public record FileRecord(
        int id,
        String projectPath,
        String repositoryPath,
        String kind,
        String origin,
        String lifecycle,
        String worktreeStatus
) {}
