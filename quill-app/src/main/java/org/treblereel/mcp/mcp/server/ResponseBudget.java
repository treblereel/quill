package org.treblereel.mcp.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.Json;

/** Enforces a final safety budget across every successful MCP JSON response. */
public final class ResponseBudget {

    /** About 16k tokens per response; QUILL_MCP_MAX_RESPONSE_BYTES overrides it. */
    static final int DEFAULT_MAX_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = Json.MAPPER;

    private ResponseBudget() {}

    public static String apply(String json) {
        return apply(json, configuredMaxBytes());
    }

    public static String apply(String json, String tool, Map<String, Object> arguments) {
        return apply(json, configuredMaxBytes(), tool, arguments);
    }

    public static String apply(String json, int maxBytes) {
        return apply(json, maxBytes, null, Map.of());
    }

    private static String apply(String json, int maxBytes, String tool, Map<String, Object> arguments) {
        if (json == null || bytes(json) <= maxBytes) return json;
        try {
            JsonNode parsed = JSON.readTree(json);
            if (!(parsed instanceof ObjectNode root)) return json;
            int originalBytes = bytes(json);
            Map<String, Integer> omitted = new LinkedHashMap<>();
            ObjectNode budget = root.putObject("_response_budget");
            budget.put("truncated", true);
            budget.put("max_bytes", maxBytes);
            budget.put("original_bytes", originalBytes);
            if ("get_build_status".equals(tool)) {
                budget.put("continuation", "Retry get_build_status with a smaller limit at the same offset; module and source_set are exact evidence filters.");
                var retry = budget.putObject("retry_request");
                retry.put("tool", tool);
                ObjectNode args = JSON.valueToTree(arguments);
                args.put("detail", "full");
                args.put("limit", Math.max(1, root.path("limit").asInt(10) / 2));
                retry.set("arguments", args);
            } else {
                budget.put("continuation", "Reduce limit/depth or add a project, module, class, or library filter.");
            }

            int contentBudget = Math.max(256, maxBytes - 512);
            while (bytes(root.toString()) > contentBudget) {
                List<ArrayRef> arrays = new ArrayList<>();
                collectArrays(root, "", arrays);
                ArrayRef selected = arrays.stream()
                        .filter(ref -> ref.array().size() > 0)
                        .filter(ref -> !ref.path().equals("projects"))
                        .max(Comparator.comparingInt(ResponseBudget::lastItemBytes))
                        .orElse(null);
                if (selected == null) break;
                selected.array().remove(selected.array().size() - 1);
                omitted.merge(selected.path(), 1, Integer::sum);
            }
            budget.set("omitted_items", JSON.valueToTree(omitted));
            budget.put("response_bytes", bytes(root.toString()));
            return root.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    private static void collectArrays(JsonNode node, String path, List<ArrayRef> result) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                if (!entry.getKey().equals("_response_budget")) {
                    collectArrays(entry.getValue(), child(path, entry.getKey()), result);
                }
            });
        } else if (node instanceof ArrayNode array) {
            result.add(new ArrayRef(path, array));
            for (int i = 0; i < array.size(); i++) {
                collectArrays(array.get(i), path + "[]", result);
            }
        }
    }

    private static int lastItemBytes(ArrayRef ref) {
        if (ref.array().isEmpty()) return 0;
        return bytes(ref.array().get(ref.array().size() - 1).toString());
    }

    private static String child(String parent, String name) {
        return parent.isEmpty() ? name : parent + "." + name;
    }

    private static int configuredMaxBytes() {
        String value = System.getenv("QUILL_MCP_MAX_RESPONSE_BYTES");
        if (value == null || value.isBlank()) return DEFAULT_MAX_BYTES;
        try {
            return Math.max(4 * 1024, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return DEFAULT_MAX_BYTES;
        }
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record ArrayRef(String path, ArrayNode array) {}
}
