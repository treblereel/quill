package org.treblereel.mcp.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
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
            assertTrue(json.contains("\"version\":2"));
            assertTrue(json.contains("\"buildTool\":\"maven\""));
            assertTrue(json.contains("\"successful\":true"));
            assertTrue(json.matches("(?s).*\"finishedAt\":[1-9][0-9]*.*"));
            assertTrue(json.contains("\"failureMessagesBase64\":[]"));
        }
    }

    @Test
    void writesFailedBuildMessagesWithoutJsonEscapingRisk() throws Exception {
        QuillBuildEventSpy.writeEvent(tempDir, false,
                java.util.List.of("Compilation failed:\nSample.java:[7,3] bad \"token\""));

        Path event;
        try (var files = Files.list(tempDir.resolve(".quill/build-events"))) {
            event = files.findFirst().orElseThrow();
        }
        String json = Files.readString(event);
        assertTrue(json.contains("\"successful\":false"));
        assertTrue(json.contains("\"failureMessagesBase64\":[\""));
        assertTrue(!json.contains("bad \"token\""));
    }

    @Test
    void recognizesInternalQuillMavenInvocation() {
        Properties properties = new Properties();
        properties.setProperty("quill.internal", "true");

        assertTrue(QuillBuildEventSpy.isInternal(properties));
    }

    @Test
    void keepsEventsAtReactorRootWhenProjectListStartsAtModule() {
        Path reactor = tempDir.resolve("reactor");
        Path selectedModule = reactor.resolve("module");

        assertEquals(reactor, QuillBuildEventSpy.eventRoot(reactor, selectedModule));
        assertEquals(selectedModule, QuillBuildEventSpy.eventRoot(null, selectedModule));
    }
}
