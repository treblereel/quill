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
            assertTrue(events.get(0).getFileName().toString().startsWith("maven-"));
            String json = Files.readString(events.get(0));
            assertTrue(json.contains("\"version\":3"));
            assertTrue(json.contains("\"buildTool\":\"maven\""));
            assertTrue(json.contains("\"successful\":true"));
            assertTrue(json.matches("(?s).*\"finishedAt\":[1-9][0-9]*.*"));
            assertTrue(json.contains("\"failureMessagesBase64\":[]"));
            assertTrue(json.contains("\"diagnosticsBase64\":[]"));
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
        assertTrue(json.contains("\"diagnosticsBase64\":[\""));
        assertTrue(!json.contains("bad \"token\""));
    }

    @Test
    void extractsLocatedCompilerDiagnosticsFromExceptionMessages() {
        var diagnostics = QuillBuildEventSpy.compilerDiagnostics(java.util.List.of("""
                Compilation failed
                /workspace/src/main/java/acme/Broken.java:[7,3] cannot find symbol
                lifecycle execution failed
                """));

        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).contains("Broken.java:[7,3]"));
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

    @Test
    void boundsPendingEventQueue() throws Exception {
        for (int index = 0; index < 24; index++) {
            QuillBuildEventSpy.writeEvent(tempDir);
        }

        try (var files = Files.list(tempDir.resolve(".quill/build-events"))) {
            assertEquals(16, files.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void writesSeparateRuntimeAndTestClasspathSnapshots() throws Exception {
        Path runtimeJar = tempDir.resolve("repo/runtime.jar");
        Path testJar = tempDir.resolve("repo/test.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.write(runtimeJar, new byte[] {1});
        Files.write(testJar, new byte[] {1});
        Path target = tempDir.resolve("module/target");

        QuillBuildEventSpy.writeClasspath(target.resolve("quill-classpath.txt"),
                java.util.List.of(tempDir.resolve("module/target/classes").toString(),
                        runtimeJar.toString()));
        QuillBuildEventSpy.writeClasspath(target.resolve("quill-test-classpath.txt"),
                java.util.List.of(runtimeJar.toString(), testJar.toString()));

        assertEquals(runtimeJar.toAbsolutePath().toString(),
                Files.readString(target.resolve("quill-classpath.txt")));
        assertEquals(runtimeJar.toAbsolutePath() + java.io.File.pathSeparator
                        + testJar.toAbsolutePath(),
                Files.readString(target.resolve("quill-test-classpath.txt")));
    }
}
