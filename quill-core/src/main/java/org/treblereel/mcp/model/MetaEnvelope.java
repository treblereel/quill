package org.treblereel.mcp.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.db.IndexReader;

public record MetaEnvelope(
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
    public static MetaEnvelope from(Jdbi jdbi, int responseTokens, int naiveTokens) {
        Map<String, String> meta = IndexReader.getMetadata(jdbi);
        String lastCommit = meta.getOrDefault("last_commit", "unknown");
        String projectRoot = meta.get("project_root");
        Path rootPath = projectRoot != null ? Path.of(projectRoot) : null;
        WorktreeInspector.Snapshot worktree = rootPath != null
                ? WorktreeInspector.inspect(rootPath) : WorktreeInspector.Snapshot.empty();
        String currentCommit = worktree.currentCommit();
        boolean commitStale = isCommitStale(lastCommit, currentCommit);
        String indexedStructure = meta.getOrDefault("indexed_structure_fingerprint",
                meta.get("indexed_worktree_fingerprint"));
        boolean structureChanged = indexedStructure != null
                && !indexedStructure.equals(worktree.structuralFingerprint());
        boolean compiledSnapshot = Boolean.parseBoolean(
                meta.getOrDefault("compiled_before_index", "false"));
        boolean structureStale = commitStale || structureChanged
                || (worktree.structuralDirty() && !compiledSnapshot);
        List<String> staleReasons = new ArrayList<>();
        if (commitStale) staleReasons.add("commit_changed_after_index");
        if (structureChanged) staleReasons.add("worktree_changed_after_index");
        if (worktree.structuralDirty() && !compiledSnapshot) {
            staleReasons.add("dirty_worktree_not_compiled");
        }
        double compression = responseTokens > 0 ? (double) naiveTokens / responseTokens : 0;
        return new MetaEnvelope(
                meta.getOrDefault("indexed_at", "unknown"),
                lastCommit, currentCommit, commitStale, worktree.dirty(), worktree.changes().size(),
                worktree.structuralChanges().size(),
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
