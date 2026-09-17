package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.ProjectIndexStore;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.MetaEnvelope;

final class ToolResponseSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResponseSupport() {}

    static void appendMeta(ObjectNode root, Jdbi jdbi, int naiveTokens) {
        MetaEnvelope meta = MetaEnvelope.from(jdbi, 0, 0);
        ObjectNode metaNode = root.putObject("_meta");
        metaNode.put("index_id", meta.indexId());
        metaNode.put("indexed_at", meta.indexedAt());
        metaNode.put("indexed_commit", meta.lastCommit());
        if (meta.currentCommit() == null) metaNode.putNull("current_commit");
        else metaNode.put("current_commit", meta.currentCommit());
        metaNode.put("commit_stale", meta.commitStale());
        metaNode.put("worktree_dirty", meta.worktreeDirty());
        metaNode.put("worktree_changed_files", meta.worktreeChangedFiles());
        metaNode.put("structural_changed_files", meta.structuralChangedFiles());
        metaNode.put("structure_stale", meta.structureStale());
        metaNode.set("stale_reasons", JSON.valueToTree(meta.staleReasons()));
        metaNode.put("stale_warning", meta.staleWarning());
        String projectRoot = IndexReader.getMetadata(jdbi).get("project_root");
        if (projectRoot != null) {
            ProjectIndexStore.readRecovery(Path.of(projectRoot)).ifPresent(recovery -> {
                ObjectNode recoveryNode = metaNode.putObject("index_recovery");
                recoveryNode.put("reason", recovery.reason());
                recoveryNode.put("selected_index_id", recovery.selectedIndexId());
                recoveryNode.put("recovered_at", recovery.recoveredAt());
            });
        }
    }

    static void appendPage(ObjectNode root, int showing, int total, int limit, int offset) {
        boolean hasMore = offset + showing < total;
        root.put("showing", showing);
        root.put("total", total);
        root.put("limit", limit);
        root.put("offset", offset);
        root.put("has_more", hasMore);
        root.put("truncated", offset > 0 || hasMore);
        if (hasMore) root.put("next_offset", offset + showing);
    }

    static String errorResponse(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    static String classLookupError(
            Jdbi jdbi, ClassTargetResolver.Lookup lookup, String target) {
        ObjectNode root = ClassTargetResolver.errorResponse(JSON, lookup, target);
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    static boolean isProducer(String kind) {
        return "PRODUCER_METHOD".equals(kind) || "PRODUCER_FIELD".equals(kind);
    }
}
