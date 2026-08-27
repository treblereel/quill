package org.treblereel.mcp.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import org.treblereel.mcp.db.IndexReader;

public record MetaEnvelope(
        String indexedAt,
        String lastCommit,
        boolean staleWarning,
        int responseTokens,
        int naiveTokens,
        double compression
) {
    public static MetaEnvelope from(Connection conn, Path projectRoot, int responseTokens, int naiveTokens) {
        Map<String, String> meta = IndexReader.getMetadata(conn);
        String lastCommit = meta.getOrDefault("last_commit", "unknown");
        boolean stale = isStale(projectRoot, lastCommit);
        double compression = responseTokens > 0 ? (double) naiveTokens / responseTokens : 0;
        return new MetaEnvelope(
                meta.getOrDefault("indexed_at", "unknown"),
                lastCommit, stale, responseTokens, naiveTokens, Math.round(compression * 10.0) / 10.0
        );
    }

    private static boolean isStale(Path projectRoot, String indexedCommit) {
        if (projectRoot == null || "unknown".equals(indexedCommit)) return false;
        try {
            Path headFile = projectRoot.resolve(".git/HEAD");
            if (!Files.exists(headFile)) return false;
            String head = Files.readString(headFile).trim();
            if (head.startsWith("ref: ")) {
                Path refFile = projectRoot.resolve(".git/" + head.substring(5));
                if (Files.exists(refFile)) {
                    head = Files.readString(refFile).trim();
                }
            }
            return !head.startsWith(indexedCommit);
        } catch (Exception e) {
            return false;
        }
    }
}
