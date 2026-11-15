package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the values configuration rows of one kind repeat once: wildcard queries return dozens of
 * rows with the same module, scope note and source basis. A field moves to
 * {@code row_defaults_by_kind.<kind>} only when every row of that kind carries it with the same
 * value; shared {@code declared_source} fields move under its {@code declared_source} entry.
 */
final class ConfigurationRowDefaults {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> ROW_FIELDS = List.of("entry_type", "module", "sourceSet",
            "configuration_scope", "activation", "status", "profile_count", "resolution_strategy",
            "confidence", "resolved", "annotation", "definition_files", "location_status", "resolution_status");
    private static final List<String> DECLARED_FIELDS = List.of("basis", "effective_value_proven",
            "source_complete", "status");

    private ConfigurationRowDefaults() {
    }

    static String compact(String json) {
        try {
            JsonNode root = JSON.readTree(json);
            if (!(root instanceof ObjectNode result) || result.path("references").size() < 2) return json;
            Map<String, List<ObjectNode>> byKind = new LinkedHashMap<>();
            result.path("references").valueStream().filter(ObjectNode.class::isInstance)
                    .map(ObjectNode.class::cast).filter(row -> row.path("kind").isTextual())
                    .forEach(row -> byKind.computeIfAbsent(row.path("kind").asText(),
                            kind -> new java.util.ArrayList<>()).add(row));
            ObjectNode defaults = JSON.createObjectNode();
            byKind.forEach((kind, rows) -> {
                if (rows.size() < 2) return;
                ObjectNode kindDefaults = hoist(rows, ROW_FIELDS);
                List<ObjectNode> declared = rows.stream().map(row -> row.path("declared_source"))
                        .filter(ObjectNode.class::isInstance).map(ObjectNode.class::cast).toList();
                if (declared.size() == rows.size()) {
                    ObjectNode declaredDefaults = hoist(declared, DECLARED_FIELDS);
                    if (!declaredDefaults.isEmpty()) kindDefaults.set("declared_source", declaredDefaults);
                }
                if (!kindDefaults.isEmpty()) defaults.set(kind, kindDefaults);
            });
            boolean grouped = groupTestProfileOverrides(result);
            if (defaults.isEmpty() && !grouped) return json;
            if (!defaults.isEmpty()) result.set("row_defaults_by_kind", defaults);
            return result.toString();
        } catch (JsonProcessingException invalid) {
            return json;
        }
    }

    private static final String TEST_PROFILE = "quarkus_test_profile";
    private static final List<String> PROFILE_FIELDS = List.of("declaring_class", "file", "method");

    /**
     * One test profile overrides several keys from the same getConfigOverrides() map; its class,
     * file and method are written once per profile instead of once per key.
     */
    private static boolean groupTestProfileOverrides(ObjectNode result) {
        if (!(result.get("references") instanceof com.fasterxml.jackson.databind.node.ArrayNode references)) return false;
        Map<String, ObjectNode> groups = new LinkedHashMap<>();
        var remaining = JSON.createArrayNode();
        for (JsonNode row : references) {
            if (!(row instanceof ObjectNode override) || !TEST_PROFILE.equals(row.path("kind").asText())
                    || !PROFILE_FIELDS.stream().allMatch(field -> row.path(field).isTextual())) {
                remaining.add(row);
                continue;
            }
            String profile = PROFILE_FIELDS.stream().map(field -> row.path(field).asText()).reduce("", (a, b) -> a + "\u0000" + b);
            ObjectNode group = groups.computeIfAbsent(profile, ignored -> {
                ObjectNode created = JSON.createObjectNode();
                PROFILE_FIELDS.forEach(field -> created.set(field, row.get(field)));
                created.put("kind", TEST_PROFILE);
                created.putArray("overrides");
                return created;
            });
            override.remove(PROFILE_FIELDS);
            override.remove("kind");
            group.withArray("overrides").add(override);
        }
        if (groups.isEmpty()) return false;
        result.set("references", remaining);
        var grouped = result.putArray("test_profile_overrides");
        groups.values().forEach(grouped::add);
        return true;
    }

    private static ObjectNode hoist(List<ObjectNode> rows, List<String> fields) {
        ObjectNode defaults = JSON.createObjectNode();
        for (String field : fields) {
            JsonNode first = rows.getFirst().get(field);
            if (first != null && rows.stream().allMatch(row -> first.equals(row.get(field)))) {
                defaults.set(field, first);
                rows.forEach(row -> row.remove(field));
            }
        }
        return defaults;
    }
}
