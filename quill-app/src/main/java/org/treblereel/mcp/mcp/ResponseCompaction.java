package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes response fields that clients pay for on every call and do not use. In the recorded V15
 * sessions no agent passed a symbol id back in 793 calls. A full-detail request keeps everything.
 */
final class ResponseCompaction {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** These tools report freshness or hand out identifiers, so those fields are their answer. */
    private static final Set<String> UNCHANGED = Set.of("change_session", "plan_change", "verify_change",
            "get_build_status", "get_worktree_status", "get_overview", "search_symbols",
            "get_symbol_at_position", "resolve_entities", "resolve_workspace_entity");
    /** Follow-up requests must stay callable, and candidates are how an agent settles an ambiguity. */
    private static final Set<String> PRESERVED_SUBTREES = Set.of("candidates", "next_page_request",
            "retry_with", "details_request", "full_evidence_request", "next_step", "arguments");
    private static final List<String> FRESH_META = List.of("indexed_at", "indexed_commit",
            "worktree_dirty", "worktree_changed_files");
    private static final Map<String, Set<String>> REMOVED_ANYWHERE = Map.of(
            "get_context", Set.of("dependency_path"),
            "find_configuration_references", Set.of("source_sha256"));
    private static final Map<String, Map<String, String>> REMOVED_FROM_PARENT = Map.of(
            "get_context", Map.of("risk", "signals"),
            "find_configuration_references", Map.of("field_reads", "basis"),
            "list_injection_points", Map.of("declarations", "artifact"));
    private static final Set<String> DESCRIPTOR_DUPLICATES_SIGNATURE = Set.of("get_symbol_details", "get_context");

    private ResponseCompaction() {}

    static String apply(String text, String tool, Map<String, Object> arguments) {
        if (UNCHANGED.contains(tool) || full(arguments)) return text;
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException notJson) {
            return text;
        }
        if (!(root instanceof ObjectNode object) || object.has("error_code") || object.has("error")) return text;
        if (object.path("_meta") instanceof ObjectNode meta && meta.path("stale_warning").isBoolean()
                && !meta.path("stale_warning").asBoolean()) {
            meta.remove(FRESH_META);
        }
        compact(object, tool);
        return object.toString();
    }

    private static boolean full(Map<String, Object> arguments) {
        return "full".equals(arguments.get("detail")) || "full".equals(arguments.get("evidence_detail"));
    }

    private static void compact(JsonNode node, String tool) {
        if (node.isArray()) {
            for (JsonNode child : node) compact(child, tool);
            return;
        }
        if (!(node instanceof ObjectNode object)) return;
        object.remove(List.of("symbol_id", "member_symbol_id_format"));
        object.remove(REMOVED_ANYWHERE.getOrDefault(tool, Set.of()));
        if (DESCRIPTOR_DUPLICATES_SIGNATURE.contains(tool) && object.has("signature")) object.remove("descriptor");
        REMOVED_FROM_PARENT.getOrDefault(tool, Map.of()).forEach((parent, field) -> {
            JsonNode value = object.path(parent);
            if (value instanceof ObjectNode single) single.remove(field);
            else if (value.isArray()) value.forEach(item -> { if (item instanceof ObjectNode row) row.remove(field); });
        });
        for (var entry : object.properties()) {
            if (!PRESERVED_SUBTREES.contains(entry.getKey())) compact(entry.getValue(), tool);
        }
    }
}
