package org.treblereel.mcp.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.Json;

/** Lossless, response-local graph metadata sharing at the MCP boundary. */
final class GraphMetadata {
    private static final ObjectMapper JSON = Json.MAPPER;
    private static final List<String> FIELDS = List.of("caller", "callee", "dispatch", "source_evidence");

    static String compact(String text) {
        try {
            JsonNode parsed = JSON.readTree(text);
            if (!(parsed instanceof ObjectNode root) 
                    || root.has("error") || root.has("error_code")
                    || root.has("shared_metadata") || root.has("reference_format")) return text;
            List<JsonNode> pages=root.findValues("calls");
            if(pages.isEmpty() || pages.stream().anyMatch(page->!page.isArray()))return text;
            // dispatch_ref already points into dispatch_targets; sharing dispatch here would give the
            // same name two meanings, and refusing the whole response left callers and callees inline.
            List<String> fields = root.has("dispatch_targets")
                    ? FIELDS.stream().filter(field -> !field.equals("dispatch")).toList() : FIELDS;
            Map<JsonNode, Integer> counts = new LinkedHashMap<>();
            for (JsonNode page : pages) for (JsonNode edge : page) {
                if (!edge.isObject()) return text;
                for (String field : fields) {
                    if (edge.has(field + "_ref")) return text;
                    JsonNode node = edge.get(field);
                    if (node != null && node.isObject()) counts.merge(node, 1, Integer::sum);
                }
            }
            Map<JsonNode, String> ids = new LinkedHashMap<>();
            counts.forEach((node, count) -> {
                if (count > 1) ids.put(node, "g" + (ids.size() + 1));
            });
            if (ids.isEmpty()) return text;
            ObjectNode shared = root.putObject("shared_metadata");
            ids.forEach((node, id) -> shared.set(id, node));
            root.put("reference_format", "In every nested calls array, FIELD_ref resolves to root shared_metadata[id], replacing FIELD without changing its meaning.");
            for (JsonNode page : pages) for (JsonNode edge : page) {
                for (String field : fields) {
                    String id = ids.get(edge.get(field));
                    if (id != null) {
                        ((ObjectNode) edge).remove(field);
                        ((ObjectNode) edge).put(field + "_ref", id);
                    }
                }
            }
            String compact = root.toString();
            return compact.getBytes(StandardCharsets.UTF_8).length < text.getBytes(StandardCharsets.UTF_8).length
                    ? compact : text;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return text;
        }
    }

    private GraphMetadata() {}
}
