package org.treblereel.mcp.model;

public record GitCommitRecord(
        int id,
        String hash,
        String shortHash,
        String author,
        String authorEmail,
        String committedAt,
        String message
) {}
