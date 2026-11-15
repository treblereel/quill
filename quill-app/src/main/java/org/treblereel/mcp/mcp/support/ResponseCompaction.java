package org.treblereel.mcp.mcp.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes response fields that clients pay for on every call and do not use. In the recorded V15
 * sessions no agent passed a symbol id back in 793 calls. A full-detail request keeps every fact.
 */
public final class ResponseCompaction {

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
    private static final String COMMON = "_common";
    private static final int MIN_ITEMS_WITH_COMMON_FIELDS = 3;
    /** Quotes, a colon and a comma around a moved field, and the same plus braces around the new object. */
    private static final int FIELD_OVERHEAD = 4;
    private static final int COMMON_OVERHEAD = 6;
    private static final Set<String> DESCRIPTOR_DUPLICATES_SIGNATURE = Set.of("get_symbol_details", "get_context");

    private ResponseCompaction() {}

    public static String apply(String text, String tool, Map<String, Object> arguments) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JsonProcessingException notJson) {
            return text;
        }
        if (!(root instanceof ObjectNode object) || object.has("error_code") || object.has("error")) return text;
        if (!UNCHANGED.contains(tool)) {
            if (!full(arguments)) {
                if (object.path("_meta") instanceof ObjectNode meta && meta.path("stale_warning").isBoolean()
                        && !meta.path("stale_warning").asBoolean()) {
                    meta.remove(FRESH_META);
                }
                compact(object, tool);
            }
        }
        stateCommonFieldsOnce(object);
        // Nothing below loses a fact, so status tools and full detail get it too.
        dropRepeatedMeta(object, object.get("_meta"));
        dropRestatedPaging(object);
        stateRepeatsOnce(object, "", new java.util.HashMap<>());
        return object.toString();
    }

    /** The lossless part of {@link #apply}, for packets that never pass through the tool catalog. */
    public static void stateOnce(ObjectNode object) {
        dropRestatedPaging(object);
        stateRepeatsOnce(object, "", new java.util.HashMap<>());
    }

    /** Sections built by other queries carry the same index metadata as the response itself. */
    private static void dropRepeatedMeta(JsonNode node, JsonNode rootMeta) {
        if (rootMeta == null) return;
        for (JsonNode child : node) {
            if (child instanceof ObjectNode object && rootMeta.equals(object.get("_meta"))) object.remove("_meta");
            dropRepeatedMeta(child, rootMeta);
        }
    }

    /** {@code <list>_count} equal to the list's size and {@code <list>_has_more: false} restate the list. */
    private static void dropRestatedPaging(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> restated = new ArrayList<>();
            for (var entry : object.properties()) {
                if (!(entry.getValue() instanceof ArrayNode list)) continue;
                JsonNode count = object.get(entry.getKey() + "_count");
                if (count != null && count.isIntegralNumber() && count.asInt() == list.size()) restated.add(entry.getKey() + "_count");
                JsonNode more = object.get(entry.getKey() + "_has_more");
                if (more != null && more.isBoolean() && !more.asBoolean()) restated.add(entry.getKey() + "_has_more");
            }
            object.remove(restated);
            for (var entry : object.properties()) if (!PRESERVED_SUBTREES.contains(entry.getKey())) dropRestatedPaging(entry.getValue());
        } else if (node instanceof ArrayNode list) {
            for (JsonNode item : list) dropRestatedPaging(item);
        }
    }

    /** Below this a pointer saves too little to be worth reading. */
    private static final int MIN_REPEAT_BYTES = 120;

    /** Replaces a section identical to an earlier one with {@code {"same_as": "<JSON pointer>"}}. */
    private static void stateRepeatsOnce(JsonNode node, String pointer, Map<String, String> seen) {
        if (node instanceof ObjectNode object) {
            for (var entry : object.properties()) {
                if (PRESERVED_SUBTREES.contains(entry.getKey())) continue;
                String child = pointer + "/" + entry.getKey().replace("~", "~0").replace("/", "~1");
                JsonNode replacement = repeat(entry.getValue(), child, seen);
                if (replacement != null) entry.setValue(replacement);
            }
        } else if (node instanceof ArrayNode list) {
            for (int i = 0; i < list.size(); i++) {
                JsonNode replacement = repeat(list.get(i), pointer + "/" + i, seen);
                if (replacement != null) list.set(i, replacement);
            }
        }
    }

    private static JsonNode repeat(JsonNode value, String pointer, Map<String, String> seen) {
        if (!value.isContainerNode()) return null;
        String text = value.toString();
        if (text.length() >= MIN_REPEAT_BYTES) {
            String first = seen.putIfAbsent(text, pointer);
            if (first != null) return JSON.createObjectNode().put("same_as", first);
        }
        stateRepeatsOnce(value, pointer, seen);
        return null;
    }

    /** Moves a field with one value across a list's items to {@code <list>_common}: nothing is lost, so full detail gets it too. */
    private static void stateCommonFieldsOnce(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode child : node) stateCommonFieldsOnce(child);
            return;
        }
        if (!(node instanceof ObjectNode object)) return;
        List<String> fields = new ArrayList<>();
        object.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            if (PRESERVED_SUBTREES.contains(field)) continue;
            JsonNode value = object.get(field);
            stateCommonFieldsOnce(value);
            // GraphMetadata shares call edges by reference, which also covers partial repeats.
            if (value instanceof ArrayNode list && !field.equals("calls") && !object.has(field + COMMON)) {
                moveCommonFields(object, field, list);
            }
        }
    }

    private static void moveCommonFields(ObjectNode owner, String field, ArrayNode items) {
        if (items.size() < MIN_ITEMS_WITH_COMMON_FIELDS) return;
        for (JsonNode item : items) if (!(item instanceof ObjectNode)) return;
        ObjectNode first = (ObjectNode) items.get(0);
        ObjectNode common = JSON.createObjectNode();
        int saved = -(field.length() + COMMON.length() + COMMON_OVERHEAD);
        for (var entry : first.properties()) {
            boolean shared = true;
            for (JsonNode item : items) shared &= entry.getValue().equals(item.get(entry.getKey()));
            if (!shared) continue;
            common.set(entry.getKey(), entry.getValue());
            saved += (items.size() - 1) * (entry.getKey().length() + entry.getValue().toString().length() + FIELD_OVERHEAD);
        }
        if (common.isEmpty() || saved <= 0 || common.size() == first.size()) return;
        List<String> moved = new ArrayList<>();
        common.fieldNames().forEachRemaining(moved::add);
        for (JsonNode item : items) ((ObjectNode) item).remove(moved);
        owner.set(field + COMMON, common);
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
