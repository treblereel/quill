package org.treblereel.mcp.model;

public record GitFileStats(
        int id,
        String filePath,
        Integer classId,
        int commitCount,
        String lastModified,
        String lastAuthor,
        String firstCommit,
        int distinctAuthors
) {}
