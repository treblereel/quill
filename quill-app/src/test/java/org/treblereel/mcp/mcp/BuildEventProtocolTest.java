package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildEventProtocolTest {

    @TempDir
    Path tempDir;

    @Test
    void discardsEventsOutsideVersionThreeContract() throws Exception {
        List<String> invalidEvents = List.of(
                "not-json",
                "{}",
                "{\"version\":1,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":1,\"failureMessagesBase64\":[]}",
                "{\"version\":2,\"buildTool\":\"ant\",\"successful\":true,\"finishedAt\":1,\"failureMessagesBase64\":[]}",
                "{\"version\":2,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":0,\"failureMessagesBase64\":[]}",
                "{\"version\":2,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":1,\"failureMessagesBase64\":[\"bad base64\"]}",
                "{\"version\":2,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":1,\"failureMessagesBase64\":[],\"extra\":true}");
        Path events = Files.createDirectories(tempDir.resolve(".quill/build-events"));
        for (int i = 0; i < invalidEvents.size(); i++) {
            Files.writeString(events.resolve("event-" + i + ".json"), invalidEvents.get(i));
        }

        assertNull(new BuildEventConsumer().consume(tempDir));
        assertFalse(Files.exists(events));
    }

    @Test
    void discardsEventTooFarInTheFuture() throws Exception {
        Path events = Files.createDirectories(tempDir.resolve(".quill/build-events"));
        long future = System.currentTimeMillis() + 10 * 60 * 1_000;
        Files.writeString(events.resolve("event.json"),
                "{\"version\":3,\"buildTool\":\"gradle\",\"successful\":true,"
                        + "\"finishedAt\":" + future
                        + ",\"captureScope\":\"task_output\","
                        + "\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}");

        assertNull(new BuildEventConsumer().consume(tempDir));
        assertFalse(Files.exists(events));
    }

    @Test
    void persistsFailedBuildForDiagnosticQueries() throws Exception {
        Path events = Files.createDirectories(tempDir.resolve(".quill/build-events"));
        String message = java.util.Base64.getEncoder().encodeToString(
                "Compilation failed\nmodule/src/main/java/acme/Broken.java:[12,7] cannot find symbol"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String diagnostic = java.util.Base64.getEncoder().encodeToString(
                "module/src/main/java/acme/Broken.java:[12,7] cannot find symbol"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.writeString(events.resolve("event.json"),
                "{\"version\":3,\"buildTool\":\"maven\",\"successful\":false,"
                        + "\"finishedAt\":" + System.currentTimeMillis()
                        + ",\"captureScope\":\"exception_chain\","
                        + "\"failureMessagesBase64\":[\"" + message + "\"],"
                        + "\"diagnosticsBase64\":[\"" + diagnostic + "\"]}");

        assertNull(new BuildEventConsumer().consume(tempDir));
        assertFalse(Files.exists(events));
        JsonNode state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                tempDir.resolve(".quill/build-state.json").toFile());
        assertFalse(state.path("successful").asBoolean());
        assertEquals("maven", state.path("buildTool").asText());
        assertTrue(state.path("failureMessages").get(0).asText().contains("Broken.java"));
        assertTrue(state.path("diagnostics").get(0).asText().contains("Broken.java"));
    }
}
