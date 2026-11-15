package org.treblereel.mcp.mcp.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.treblereel.mcp.core.Json;

/**
 * One tool answer, parsed once at the MCP boundary. A JSON object answer stays a tree while the
 * catalog post-processes it; anything else, including the catalog's own argument and
 * invocation failures, is plain text.
 */
public sealed interface ToolResult {

    /** Whether the answer is an error. */
    boolean error();

    /** The answer as sent to the client. */
    String text();

    /** A JSON object answer; it is an error when it carries error_code or the legacy error field. */
    record JsonAnswer(ObjectNode body) implements ToolResult {
        @Override
        public boolean error() {
            return isError(body);
        }

        @Override
        public String text() {
            return body.toString();
        }
    }

    /** A plain-text answer, or a failure the catalog reports itself. */
    record Text(String text, boolean error) implements ToolResult {}

    /** A failure reported by the catalog before or while invoking a tool. */
    static ToolResult failure(String message) {
        return new Text(message, true);
    }

    /** Parses a tool's raw answer; only text starting with an object brace is read as JSON. */
    static ToolResult of(String raw) {
        if (raw == null || raw.isBlank() || raw.charAt(0) != '{') return new Text(raw, false);
        try {
            JsonNode parsed = Json.MAPPER.readTree(raw);
            if (parsed instanceof ObjectNode object) return new JsonAnswer(object);
        } catch (Exception notJson) {
            // A brace that does not open valid JSON is ordinary text.
        }
        return new Text(raw, false);
    }

    /** Error shape shared by every JSON answer, current (error_code) and legacy (error). */
    static boolean isError(JsonNode answer) {
        return answer.has("error_code") || answer.has("error");
    }

    /**
     * A lookup answer that found nothing in this project: the class or bean is absent, or the
     * annotation is not indexed. Multi-project fan-out reports such answers as skipped.
     */
    static boolean isNotFound(JsonNode answer) {
        if ("CLASS_NOT_FOUND".equals(answer.path("error_code").asText())) return true;
        if (answer.path("annotation_indexed").isBoolean()
                && !answer.path("annotation_indexed").asBoolean()) return true;
        String message = answer.path("message").asText();
        if (!message.isBlank()) return message.startsWith("Not a bean:");
        String legacy = answer.path("error").asText();
        return legacy.equals("Class not found") || legacy.startsWith("Not a bean:");
    }
}
