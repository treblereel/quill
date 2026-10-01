package org.treblereel.mcp.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/** Compact output contracts for tools whose results are commonly chained by clients. */
final class ToolOutputSchemas {

    private ToolOutputSchemas() {}

    static Map<String, Object> schema(String name) {
        return switch (name) {
            case "overview" -> object(
                    field("capabilities", unconstrainedObject()),
                    field("project", unconstrainedObject()),
                    field("problems", unconstrainedObject()),
                    field("architecture_hubs", array()));
            case "symbol_search" -> object(
                    field("pattern", string()), field("kind", string()),
                    field("language_filter", string()), field("symbols", array()),
                    pageFields());
            case "symbol_details" -> object(
                    identityFields(), field("class", string()), field("kind", string()),
                    field("location", object()), field("members", array()), pageFields());
            case "position_symbol" -> object(
                    field("path", string()), field("line", integer()),
                    field("column", integer()), field("identifier", string()),
                    field("resolution", string()), field("confidence", string()),
                    field("selected", object()), field("candidates", array()),
                    field("limitations", array()));
            case "symbol_usages" -> object(
                    identityFields(), field("target", string()), field("kind", string()),
                    field("usages", array()), pageFields());
            case "call_hierarchy" -> object(
                    identityFields(), field("target", string()), field("method", string()),
                    field("calls", array()), pageFields());
            case "method_overrides" -> object(
                    identityFields(), field("target", string()), field("method", string()),
                    field("base_declarations", array()), field("overrides", array()),
                    pageFields());
            case "execution_order" -> object(
                    identityFields(), field("target", string()), field("method", string()),
                    field("bytecode_sequence", array()), field("ordering_analysis", object()));
            default -> throw new IllegalArgumentException("Unknown output schema: " + name);
        };
    }

    @SafeVarargs
    private static Map<String, Object> object(Map<String, Object>... groups) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> group : groups) properties.putAll(group);
        properties.putAll(errorFields());
        properties.put("_meta", unconstrainedObject());
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("additionalProperties", true);
        return schema;
    }

    private static Map<String, Object> identityFields() {
        return fields(
                field("symbol_id", string()), field("language", string()),
                field("source_name", string()), field("jvm_name", string()),
                field("jvm_descriptor", string()));
    }

    private static Map<String, Object> pageFields() {
        return fields(
                field("showing", integer()), field("total", integer()),
                field("limit", integer()), field("offset", integer()),
                field("has_more", bool()), field("next_offset", integer()));
    }

    private static Map<String, Object> errorFields() {
        return fields(
                field("error_code", string()), field("message", string()),
                field("retryable", bool()),
                field("retry_with", unconstrainedObject()));
    }

    @SafeVarargs
    private static Map<String, Object> fields(Map<String, Object>... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) result.putAll(field);
        return result;
    }

    private static Map<String, Object> field(String name, Map<String, Object> schema) {
        return Map.of(name, schema);
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer");
    }

    private static Map<String, Object> bool() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> array() {
        return Map.of("type", "array", "items", unconstrainedObject());
    }

    private static Map<String, Object> unconstrainedObject() {
        return Map.of("type", "object", "additionalProperties", true);
    }
}
