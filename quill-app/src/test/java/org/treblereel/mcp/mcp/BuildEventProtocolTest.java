package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildEventProtocolTest {

    @TempDir
    Path tempDir;

    @Test
    void discardsEventsOutsideVersionOneContract() throws Exception {
        List<String> invalidEvents = List.of(
                "not-json",
                "{}",
                "{\"version\":2,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":1}",
                "{\"version\":1,\"buildTool\":\"ant\",\"successful\":true,\"finishedAt\":1}",
                "{\"version\":1,\"buildTool\":\"maven\",\"successful\":false,\"finishedAt\":1}",
                "{\"version\":1,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":0}",
                "{\"version\":1,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":\"1\"}",
                "{\"version\":1,\"buildTool\":\"maven\",\"successful\":true,\"finishedAt\":1,\"extra\":true}");
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
                "{\"version\":1,\"buildTool\":\"gradle\",\"successful\":true,"
                        + "\"finishedAt\":" + future + "}");

        assertNull(new BuildEventConsumer().consume(tempDir));
        assertFalse(Files.exists(events));
    }
}
