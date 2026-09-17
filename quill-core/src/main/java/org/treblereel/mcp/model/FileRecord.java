package org.treblereel.mcp.model;

public record FileRecord(
        int id,
        String projectPath,
        String repositoryPath,
        String kind,
        String origin,
        String lifecycle,
        String worktreeStatus,
        String module,
        String sourceSet
) {
    public FileRecord(int id, String projectPath, String repositoryPath, String kind,
            String origin, String lifecycle, String worktreeStatus) {
        this(id, projectPath, repositoryPath, kind, origin, lifecycle, worktreeStatus,
                null, null);
    }
}
