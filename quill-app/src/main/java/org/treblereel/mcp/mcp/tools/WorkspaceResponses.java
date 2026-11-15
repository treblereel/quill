package org.treblereel.mcp.mcp.tools;

import org.treblereel.mcp.index.BuildStatusInspector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.treblereel.mcp.model.MetaEnvelope;
import org.treblereel.mcp.mcp.project.ProjectRegistry;

/** JSON fragments and error shapes shared by the workspace tool responses. */
final class WorkspaceResponses {

    private static final ObjectMapper JSON = new ObjectMapper();

    static void appendDiagnostics(ObjectNode root, List<String> diagnostics) {
        int limit = Math.min(10, diagnostics.size());
        root.put("diagnostic_count", diagnostics.size());
        root.set("diagnostics", JSON.valueToTree(diagnostics.subList(0, limit)));
        root.put("diagnostics_truncated", limit < diagnostics.size());
    }

    static ObjectNode providerJson(ProviderClass provider) {
        ObjectNode node = JSON.createObjectNode();
        node.put("repository", provider.repository());
        node.put("module", provider.module());
        node.put("className", provider.className());
        node.put("sourceFile", provider.sourceFile());
        return node;
    }

    static boolean appendFreshness(ObjectNode parent,
            ProjectRegistry.Resolution resolution) {
        ObjectNode freshness = parent.putObject("index_freshness");
        if (resolution.projects().isEmpty()) {
            freshness.put("status", "unavailable");
            freshness.put("complete", false);
            return false;
        }
        MetaEnvelope meta = resolution.projects().getFirst().read(reader ->
                org.treblereel.mcp.index.BuildStatusInspector.freshness(reader, 0, 0));
        freshness.put("status", meta.structureStale() ? "stale" : "current");
        freshness.put("complete", !meta.structureStale());
        freshness.put("indexed_at", meta.indexedAt());
        freshness.put("indexed_commit", meta.lastCommit());
        if (meta.currentCommit() == null) freshness.putNull("current_commit");
        else freshness.put("current_commit", meta.currentCommit());
        freshness.put("worktree_dirty", meta.worktreeDirty());
        freshness.put("structure_stale", meta.structureStale());
        freshness.set("stale_reasons", JSON.valueToTree(meta.staleReasons()));
        return !meta.structureStale();
    }

    static boolean isClassNotFound(JsonNode node) {
        return "CLASS_NOT_FOUND".equals(node.path("error_code").asText())
                || errorMessage(node).startsWith("Class not found");
    }

    static boolean hasToolError(JsonNode node) {
        return node.has("error_code") || node.has("error");
    }

    static String errorCode(JsonNode node, String fallback) {
        String code = node.path("error_code").asText();
        if (!code.isBlank()) return code;
        String legacy = node.path("error").asText();
        return legacy.isBlank() ? fallback : legacy;
    }

    static String errorMessage(JsonNode node) {
        return errorMessage(node, "");
    }

    static String errorMessage(JsonNode node, String fallback) {
        String message = node.path("message").asText();
        if (!message.isBlank()) return message;
        return node.path("error").asText(fallback);
    }

    static String workspaceRequired() {
        return JSON.createObjectNode().put("error", "workspace_mode_required")
                .put("message", "Start Quill with --workspace <path>").toString();
    }

    static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    record ProviderClass(String repository, String module, String className,
            String sourceFile) {}

    private WorkspaceResponses() {}
}
