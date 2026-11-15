package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes what every injection point of a bean repeats once: the static-analysis limitations, the
 * applied and unsupported rule lists of the resolution trace, and the provider evidence scope.
 * When every point (or every trace or provider evidence object) carries a field, its most common
 * value moves to {@code point_defaults} and only differing values stay on the point; empty
 * provider profile lists are dropped.
 */
final class InjectionPointDefaults {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InjectionPointDefaults() {
    }

    static String compact(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode result)) return json;
            List<ObjectNode> points = objects(result.path("injection_points").valueStream().toList());
            for (ObjectNode point : points) {
                point.path("provider_evidence").path("declarations").forEach(declaration -> {
                    if (declaration instanceof ObjectNode node && node.path("profiles").isEmpty()
                            && node.path("profiles").isArray()) node.remove("profiles");
                });
            }
            if (points.size() < 2) return result.toString();
            ObjectNode defaults = hoist(points, List.of("limitations", "resolution_strategy"));
            nested(points, "resolution_trace", List.of("applied_rules", "unsupported_rules"), defaults);
            nested(points, "provider_evidence", List.of("scope", "runtime_resolution_proven"), defaults);
            if (!defaults.isEmpty()) result.set("point_defaults", defaults);
            return result.toString();
        } catch (JsonProcessingException invalid) {
            return json;
        }
    }

    private static void nested(List<ObjectNode> points, String field, List<String> fields, ObjectNode defaults) {
        List<ObjectNode> values = objects(points.stream().map(point -> point.path(field)).toList());
        if (values.size() < 2) return;
        ObjectNode shared = hoist(values, fields);
        if (!shared.isEmpty()) defaults.set(field, shared);
    }

    private static List<ObjectNode> objects(List<JsonNode> values) {
        return values.stream().filter(ObjectNode.class::isInstance).map(ObjectNode.class::cast).toList();
    }

    /** The most common value becomes the default when every row has the field; others keep theirs. */
    private static ObjectNode hoist(List<ObjectNode> rows, List<String> fields) {
        ObjectNode defaults = JSON.createObjectNode();
        for (String field : fields) {
            if (!rows.stream().allMatch(row -> row.has(field))) continue;
            Map<JsonNode, Long> counts = rows.stream().map(row -> row.get(field))
                    .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
            var common = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            if (common.getValue() < 2) continue;
            defaults.set(field, common.getKey());
            rows.stream().filter(row -> common.getKey().equals(row.get(field))).forEach(row -> row.remove(field));
        }
        return defaults;
    }
}
