package org.treblereel.mcp.model;

public record GitCommitFile(
        int commitId,
        Integer classId,
        String filePath,
        String changeType
) {}
