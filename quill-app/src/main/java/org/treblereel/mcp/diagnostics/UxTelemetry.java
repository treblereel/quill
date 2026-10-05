package org.treblereel.mcp.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Local, content-free measurements of MCP tool usage for UX evaluation. */
public final class UxTelemetry {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object WRITE_LOCK = new Object();
    private static final long MAX_LOG_BYTES = 5L * 1024 * 1024;
    private static volatile Path logFile;
    private static volatile String sessionId;
    private static volatile String toolProfile;

    private UxTelemetry() {}

    public static void configure(boolean enabled, Path root, String profile) {
        logFile = enabled
                ? root.toAbsolutePath().normalize().resolve(".quill/telemetry/mcp-tools.jsonl")
                : null;
        sessionId = enabled ? UUID.randomUUID().toString() : null;
        toolProfile = profile;
    }

    public static boolean enabled() {
        return logFile != null;
    }

    public static Path logFile() {
        return logFile;
    }

    public static void record(String tool, long durationMillis,
            McpSchema.CallToolResult result) {
        record(tool, null, durationMillis, result);
    }

    public static void record(String tool, String routedTool, long durationMillis,
            McpSchema.CallToolResult result) {
        Path destination = logFile;
        if (destination == null) return;

        JsonNode payload = payload(result);
        ObjectNode event = JSON.createObjectNode();
        event.put("schema_version", 1);
        event.put("timestamp", Instant.now().toString());
        event.put("session_id", sessionId);
        event.put("tool_profile", toolProfile);
        event.put("tool", tool);
        if (routedTool != null && !routedTool.isBlank()) event.put("routed_tool", routedTool);
        event.put("duration_ms", Math.max(0, durationMillis));
        event.put("response_bytes", responseBytes(result));
        event.put("status", Boolean.TRUE.equals(result.isError()) ? "error" : "ok");
        copyText(payload, event, "error_code");
        copyText(payload.path("_meta"), event, "answer_confidence");
        copyTrue(payload, event, "truncated");
        copyTrue(payload, event, "has_more");
        copyTrue(payload.path("_meta"), event, "stale_warning");

        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(destination.getParent());
                rotate(destination);
                Files.writeString(destination, event + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // UX telemetry is best effort and must never disturb the MCP client.
            }
        }
    }

    private static JsonNode payload(McpSchema.CallToolResult result) {
        Object structured = result.structuredContent();
        if (structured != null) return JSON.valueToTree(structured);
        List<McpSchema.Content> content = result.content();
        if (content != null && !content.isEmpty()
                && content.getFirst() instanceof McpSchema.TextContent text) {
            try {
                return JSON.readTree(text.text());
            } catch (Exception ignored) {
                return JSON.createObjectNode();
            }
        }
        return JSON.createObjectNode();
    }

    private static long responseBytes(McpSchema.CallToolResult result) {
        try {
            if (result.structuredContent() != null) {
                return JSON.writeValueAsBytes(result.structuredContent()).length;
            }
            long bytes = 0;
            if (result.content() != null) {
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent text) {
                        bytes += text.text().getBytes(StandardCharsets.UTF_8).length;
                    }
                }
            }
            return bytes;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static void copyText(JsonNode source, ObjectNode destination, String field) {
        JsonNode value = source.path(field);
        if (value.isTextual() && !value.asText().isBlank()) {
            destination.put(field, value.asText());
        }
    }

    private static void copyTrue(JsonNode source, ObjectNode destination, String field) {
        if (source.path(field).asBoolean(false)) destination.put(field, true);
    }

    private static void rotate(Path destination) throws IOException {
        if (!Files.isRegularFile(destination) || Files.size(destination) < MAX_LOG_BYTES) return;
        Files.move(destination, destination.resolveSibling(destination.getFileName() + ".1"),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
