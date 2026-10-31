package org.treblereel.mcp.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record MetaEnvelope(
        String indexId,
        String indexedAt,
        String lastCommit,
        String currentCommit,
        boolean commitStale,
        boolean worktreeDirty,
        int worktreeChangedFiles,
        int structuralChangedFiles,
        boolean structureStale,
        List<String> staleReasons,
        boolean staleWarning,
        int responseTokens,
        int naiveTokens,
        double compression
) {
    public static MetaEnvelope from(Map<String,String> meta, String currentCommit, String structuralFingerprint,
            boolean structuralDirty, boolean dirty, int changedFiles, int structuralChanges,
            int responseTokens, int naiveTokens) {
        String lastCommit = meta.getOrDefault("last_commit", "unknown");
        boolean commitStale = isCommitStale(lastCommit, currentCommit);
        String indexedStructure = meta.get("indexed_structure_fingerprint");
        boolean structureChanged = indexedStructure != null
                && !indexedStructure.equals(structuralFingerprint);
        boolean compiledSnapshot = Boolean.parseBoolean(
                meta.getOrDefault("compiled_before_index", "false"));
        boolean structureStale = commitStale || structureChanged
                || (structuralDirty && !compiledSnapshot);
        List<String> staleReasons = new ArrayList<>();
        if (commitStale) staleReasons.add("commit_changed_after_index");
        if (structureChanged) staleReasons.add("worktree_changed_after_index");
        if (structuralDirty && !compiledSnapshot) {
            staleReasons.add("dirty_worktree_not_compiled");
        }
        double compression = responseTokens > 0 ? (double) naiveTokens / responseTokens : 0;
        return new MetaEnvelope(
                meta.get("index_id"),
                meta.getOrDefault("indexed_at", "unknown"),
                lastCommit, currentCommit, commitStale, dirty, changedFiles,
                structuralChanges,
                structureStale, List.copyOf(staleReasons), structureStale,
                responseTokens, naiveTokens, Math.round(compression * 10.0) / 10.0
        );
    }

    private static boolean isCommitStale(String indexedCommit, String currentCommit) {
        if (currentCommit == null || "unknown".equals(indexedCommit)) return false;
        return indexedCommit.length() < currentCommit.length()
                ? !currentCommit.startsWith(indexedCommit)
                : !currentCommit.equals(indexedCommit);
    }
}
