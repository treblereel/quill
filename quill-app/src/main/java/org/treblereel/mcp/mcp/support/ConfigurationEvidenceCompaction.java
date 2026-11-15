package org.treblereel.mcp.mcp.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.Json;

/**
 * Removes what a configuration answer restates once its evidence is gathered: file hashes, row ids,
 * resolution bookkeeping, "not proven" flags the resolution scope already states, empty sections and
 * the paging of a complete page. In V32 CQ-038 these were ~85% of the fact the agent answered from.
 * Every key, location, declared value, consumer, activation note and gap stays. Runs last, after the
 * evidence builders that read the hashes.
 */
public final class ConfigurationEvidenceCompaction {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final List<String> HASHES = List.of("sha256", "source_sha256", "binary_sha256");
    private static final List<String> PAGING = List.of("showing", "total", "limit", "offset", "has_more", "truncated",
            "definition_count", "usage_count", "source_definition_limit", "source_file_limit");
    private static final Map<String, String> DEFAULTS = Map.of("module", ".", "sourceSet", "main",
            "key_profile", "default", "file_profile", "default_or_custom_source");
    private static final String KEY_AT_LINE = "current key at indexed line";
    private static final String LIMITATION = "Consumers: supported annotations and common APIs; env, YAML-list and dynamic keys unknown";

    private ConfigurationEvidenceCompaction() {
    }

    public static String compact(String json) {
        try {
            if (!(JSON.readTree(json) instanceof ObjectNode evidence) || evidence.has("error_code") || evidence.has("error"))
                return json;
            compact(evidence);
            return evidence.toString();
        } catch (JsonProcessingException invalid) {
            return json;
        }
    }

    public static void compact(ObjectNode evidence) {
        dropHashesAndNulls(evidence);
        if ("all".equals(evidence.path("kind").asText(null))) evidence.remove("kind");
        evidence.remove(List.of("values_indexed", "effective_runtime_values_proven"));
        if (evidence.has("limitations")) evidence.putArray("limitations").add(LIMITATION);
        for (JsonNode node : evidence.path("references")) if (node instanceof ObjectNode row) row(row);
        if (evidence.path("configuration_resources") instanceof ObjectNode resources) resources(resources, evidence.path("references"));
        if (evidence.path("key_constants") instanceof ObjectNode constants && constants.path("items").isEmpty()
                && !constants.path("truncated").asBoolean()) evidence.remove("key_constants");
        if (evidence.path("test_profile_coverage") instanceof ObjectNode coverage) {
            if (coverage.path("unknown_declarations").asInt(-1) == 0) evidence.remove("test_profile_coverage");
            else falseFlags(coverage, "activation_proven", "runtime_values_proven");
        }
        if (evidence.path("field_annotations") instanceof ObjectNode annotations && "ready".equals(annotations.path("status").asText())
                && !annotations.path("truncated").asBoolean() && fieldsOnly(annotations, "status", "scope", "truncated"))
            evidence.remove("field_annotations");
        if (!evidence.path("has_more").asBoolean() && !evidence.path("truncated").asBoolean() && evidence.path("offset").asInt() == 0
                && evidence.path("total").asInt(-1) == evidence.path("references").size()) {
            evidence.remove(PAGING);
            evidence.put("indexed_references_complete", true);
        }
    }

    private static void row(ObjectNode row) {
        DEFAULTS.forEach((field, value) -> { if (value.equals(row.path(field).asText(null))) row.remove(field); });
        if (row.path("declared_source") instanceof ObjectNode declared) {
            if (declared.path("source_complete").asBoolean()) declared.remove("source_complete");
            falseFlags(declared, "effective_value_proven");
            if (KEY_AT_LINE.equals(declared.path("basis").asText(null))) declared.remove("basis");
        }
        if (row.path("base_key").equals(row.path("key"))) row.remove("base_key");
        if (row.path("profile_count").asInt(-1) == row.path("profile_classes").size()) row.remove("profile_count");
        JsonNode profiles = row.path("profile_classes");
        if (profiles.size() == 1 && profiles.get(0).equals(row.path("declaring_class")) && !row.has("profile_count"))
            row.remove("profile_classes");
        if (row.has("resolution_status")) {
            row.remove(List.of("classId", "resolution_strategy", "confidence", "resolved"));
            if (row.path("definition_count").asInt(-1) == row.path("definition_files").size()) row.remove("definition_count");
        }
    }

    /**
     * Files checked without a match become one list; a matched file stays only for lines no
     * definition row already gives.
     */
    private static void resources(ObjectNode resources, JsonNode references) {
        java.util.Set<String> rows = new java.util.HashSet<>();
        for (JsonNode row : references) if (row.has("file")) rows.add(row.path("file").asText() + ":" + row.path("line").asInt());
        if (!(resources.path("files") instanceof ObjectNode files)) return;
        ObjectNode matches = JSON.createObjectNode();
        ArrayNode absent = JSON.createArrayNode();
        for (Map.Entry<String, JsonNode> file : files.properties()) {
            JsonNode found = file.getValue().path("matches");
            if (found.isEmpty()) absent.add(file.getKey());
            else if (!stated(found, file.getKey(), rows)) matches.set(file.getKey(), found);
        }
        resources.remove(List.of("files", "scope"));
        if ("ready".equals(resources.path("status").asText())) resources.remove("status");
        if (!resources.path("truncated").asBoolean()) resources.remove("truncated");
        resources.put("scope", "Listed property files; custom/external/env/runtime values unknown");
        if (!matches.isEmpty()) resources.set("matches", matches);
        if (!absent.isEmpty()) resources.set("checked_without_match", absent);
    }

    private static boolean stated(JsonNode matches, String file, java.util.Set<String> rows) {
        for (JsonNode lines : matches) for (JsonNode line : lines) if (!rows.contains(file + ":" + line.asInt())) return false;
        return true;
    }

    private static void dropHashesAndNulls(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(HASHES);
            List<String> nulls = new ArrayList<>();
            object.properties().forEach(field -> {
                if (field.getValue().isNull()) nulls.add(field.getKey());
                else dropHashesAndNulls(field.getValue());
            });
            object.remove(nulls);
        } else if (node instanceof ArrayNode array) {
            array.forEach(ConfigurationEvidenceCompaction::dropHashesAndNulls);
        }
    }

    private static void falseFlags(ObjectNode node, String... flags) {
        for (String flag : flags) if (node.path(flag).isBoolean() && !node.path(flag).asBoolean()) node.remove(flag);
    }

    private static boolean fieldsOnly(ObjectNode node, String... allowed) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return List.of(allowed).containsAll(names);
    }
}
