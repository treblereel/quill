package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.ProjectIndexStore;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.MetaEnvelope;

final class ToolResponseSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResponseSupport() {}

    static void appendMeta(ObjectNode root, Jdbi jdbi, int naiveTokens) {
        appendMeta(root, jdbi, naiveTokens, null, null);
    }

    static void appendMeta(ObjectNode root, Jdbi jdbi, int naiveTokens,
            String targetSource, String targetModule) {
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
        appendStaleScope(metaNode, meta, projectRoot, targetSource, targetModule);
        if (projectRoot != null) {
            ProjectIndexStore.readRecovery(Path.of(projectRoot)).ifPresent(recovery -> {
                ObjectNode recoveryNode = metaNode.putObject("index_recovery");
                recoveryNode.put("reason", recovery.reason());
                recoveryNode.put("selected_index_id", recovery.selectedIndexId());
                recoveryNode.put("recovered_at", recovery.recoveredAt());
            });
        }
    }

    private static void appendStaleScope(ObjectNode metaNode, MetaEnvelope meta,
            String projectRoot, String targetSource, String targetModule) {
        if (!meta.structureStale()) {
            metaNode.put("stale_scope", "current");
            metaNode.put("answer_confidence", "high");
            return;
        }
        if (projectRoot == null || targetSource == null || targetSource.isBlank()) {
            metaNode.put("stale_scope", "repository");
            metaNode.put("answer_confidence", "low");
            metaNode.put("recommended_action",
                    "Compile structural changes, then refresh the Quill index");
            return;
        }
        if (meta.commitStale()) {
            metaNode.put("stale_scope", "repository");
            metaNode.put("answer_confidence", "low");
            metaNode.put("target_source_changed", false);
            metaNode.put("target_module_changed", false);
            metaNode.put("recommended_action",
                    "Refresh the Quill index at the current commit before consequential decisions");
            return;
        }

        WorktreeInspector.Snapshot snapshot = WorktreeSnapshotCache.shared()
                .get(Path.of(projectRoot));
        String source = normalize(targetSource);
        String module = targetModule == null || targetModule.isBlank()
                ? "." : normalize(targetModule);
        List<String> changed = snapshot.structuralChanges().stream()
                .map(WorktreeInspector.Change::projectPath)
                .map(ToolResponseSupport::normalize)
                .toList();
        boolean targetChanged = changed.stream().anyMatch(path -> path.equals(source));
        boolean moduleChanged = !".".equals(module)
                && changed.stream().anyMatch(path -> path.equals(module)
                        || path.startsWith(module + "/"));
        if (targetChanged) {
            metaNode.put("stale_scope", "target");
            metaNode.put("answer_confidence", "low");
        } else if (moduleChanged) {
            metaNode.put("stale_scope", "module");
            metaNode.put("answer_confidence", "medium");
        } else {
            metaNode.put("stale_scope", "outside_target_module");
            metaNode.put("answer_confidence", "medium");
        }
        metaNode.put("target_source_changed", targetChanged);
        metaNode.put("target_module_changed", moduleChanged);
        metaNode.put("recommended_action",
                "Compile structural changes, then refresh the Quill index before consequential decisions");
    }

    private static String normalize(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
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
        return errorResponse("INVALID_ARGUMENT", message);
    }

    static String errorResponse(String code, String message) {
        return appendError(JSON.createObjectNode(), code, message).toString();
    }

    static ObjectNode appendError(ObjectNode root, String code, String message) {
        root.put("error", message);
        root.put("error_code", code);
        root.put("message", message);
        root.put("retryable", false);
        return root;
    }

    static void appendRetryWith(ObjectNode root, String argument, String guidance) {
        root.put("retryable", true);
        root.putObject("retry_with").put(argument, guidance);
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
