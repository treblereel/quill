package org.treblereel.mcp.mcp.project;

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
    void eventCleanupToleratesAnotherConsumerAndPreservesNewEvents() throws Exception {
        Path events = Files.createDirectories(tempDir.resolve(".quill/build-events"));
        BuildEventConsumer.removeIfEmpty(events);
        assertFalse(Files.exists(events));
        assertDoesNotThrow(() -> BuildEventConsumer.removeIfEmpty(events));

        Files.createDirectories(events);
        Path nextBuild = Files.writeString(events.resolve("next-build.json"), "{}");
        BuildEventConsumer.removeIfEmpty(events);
        assertTrue(Files.exists(nextBuild), "Cleanup must preserve events from the next build");
    }

    @Test
    void unpublishedTemporaryEventIsLeftToItsWriter() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve(".quill/build-events"));
        Path temporary = Files.writeString(directory.resolve(".maven-writer.tmp"), "{unfinished");
        var consumer = new BuildEventConsumer(Runnable::run);
        assertFalse(BuildEventConsumer.hasPendingEvents(tempDir));
        assertNull(consumer.consume(tempDir));
        assertEquals("{unfinished", Files.readString(temporary));
    }

    @Test
    void asyncConsumptionSharesOneJobAndPersistsFailuresBeforeQueriesResume() throws Exception {
        Path events = Files.createDirectories(tempDir.resolve(".quill/build-events"));
        Files.writeString(events.resolve("event.json"),
                "{\"version\":3,\"buildTool\":\"maven\",\"successful\":false,"
                        + "\"finishedAt\":" + System.currentTimeMillis()
                        + ",\"captureScope\":\"exception_chain\","
                        + "\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}");
        var jobs = new java.util.ArrayDeque<Runnable>();
        var consumer = new BuildEventConsumer(jobs::add);
        var first = consumer.consumeAsync(tempDir);
        assertNotNull(first);
        assertFalse(first.isDone());
        assertSame(first, consumer.consumeAsync(tempDir));
        assertEquals(ProjectRegistry.ReadinessReason.BUILD_EVENTS_QUEUED, consumer.reason(tempDir));
        assertTrue(consumer.elapsedMs(tempDir) >= 0);
        assertEquals(1, jobs.size());
        jobs.remove().run();
        assertTrue(first.isDone());
        assertNull(first.join());
        assertNull(consumer.consumeAsync(tempDir));
        assertFalse(Files.exists(events));
        JsonNode state = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                tempDir.resolve(".quill/build-state.json").toFile());
        assertFalse(state.path("successful").asBoolean());
    }

    @Test
    void asyncFailureIsReportedAndKeepsTheEventForRetry() throws Exception {
        Path events = Files.createDirectories(tempDir.resolve(".quill/build-events"));
        Files.writeString(events.resolve("event.json"),
                "{\"version\":3,\"buildTool\":\"maven\",\"successful\":false,"
                        + "\"finishedAt\":" + System.currentTimeMillis()
                        + ",\"captureScope\":\"exception_chain\","
                        + "\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}");
        Files.createDirectory(tempDir.resolve(".quill/build-state.json"));
        var jobs = new java.util.ArrayDeque<Runnable>();
        var consumer = new BuildEventConsumer(jobs::add);
        var failed = consumer.consumeAsync(tempDir);
        jobs.remove().run();
        assertNotNull(failed.join());
        assertSame(failed, consumer.consumeAsync(tempDir));
        assertTrue(BuildEventConsumer.hasPendingEvents(tempDir));
        Files.delete(tempDir.resolve(".quill/build-state.json"));
        var retry = consumer.consumeAsync(tempDir);
        assertNotSame(failed, retry);
        jobs.remove().run();
        assertNull(retry.join());
        assertFalse(BuildEventConsumer.hasPendingEvents(tempDir));
    }

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

        assertTrue(BuildEventConsumer.hasPendingEvents(tempDir));
        assertNull(new BuildEventConsumer().consume(tempDir));
        assertFalse(BuildEventConsumer.hasPendingEvents(tempDir));
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
