package org.treblereel.mcp.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuillBuildEventSpyTest {

    @TempDir
    Path tempDir;

    @Test
    void writesCompleteBuildEventAtomically() throws Exception {
        QuillBuildEventSpy.writeEvent(tempDir);

        Path directory = tempDir.resolve(".quill/build-events");
        try (var files = Files.list(directory)) {
            var events = files.toList();
            assertEquals(1, events.size());
            assertTrue(events.getFirst().getFileName().toString().startsWith("maven-"));
            String json = Files.readString(events.getFirst());
            assertTrue(json.contains("\"buildTool\":\"maven\""));
            assertTrue(json.contains("\"successful\":true"));
        }
    }
}
