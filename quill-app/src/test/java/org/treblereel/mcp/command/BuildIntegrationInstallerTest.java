package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildIntegrationInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void mavenInstallIsIdempotentAndUninstallPreservesOtherExtensions() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path extensions = tempDir.resolve(".mvn/extensions.xml");
        Files.createDirectories(extensions.getParent());
        Files.writeString(extensions, """
                <?xml version="1.0" encoding="UTF-8"?>
                <extensions>
                    <extension>
                        <groupId>example</groupId>
                        <artifactId>existing</artifactId>
                        <version>1</version>
                    </extension>
                </extensions>
                """);

        assertEquals(BuildIntegrationInstaller.Result.INSTALLED,
                BuildIntegrationInstaller.install(tempDir));
        String installed = Files.readString(extensions);
        assertTrue(installed.contains("quill-maven-extension"));
        assertTrue(installed.contains("<artifactId>existing</artifactId>"));
        assertEquals(BuildIntegrationInstaller.Result.UNCHANGED,
                BuildIntegrationInstaller.install(tempDir));

        assertEquals(BuildIntegrationInstaller.Result.REMOVED,
                BuildIntegrationInstaller.uninstall(tempDir));
        String cleaned = Files.readString(extensions);
        assertFalse(cleaned.contains("quill-maven-extension"));
        assertTrue(cleaned.contains("<artifactId>existing</artifactId>"));
    }

    @Test
    void gradleInstallAndUninstallPreserveUserSettings() throws Exception {
        Path settings = tempDir.resolve("settings.gradle.kts");
        String userContent = "rootProject.name = \"sample\"\n";
        Files.writeString(settings, userContent);

        assertEquals(BuildIntegrationInstaller.Result.INSTALLED,
                BuildIntegrationInstaller.install(tempDir));
        assertEquals(BuildIntegrationInstaller.Result.UNCHANGED,
                BuildIntegrationInstaller.install(tempDir));
        assertTrue(Files.readString(settings).contains("gradle.buildFinished"));

        assertEquals(BuildIntegrationInstaller.Result.REMOVED,
                BuildIntegrationInstaller.uninstall(tempDir));
        assertEquals(userContent, Files.readString(settings));
    }

    @Test
    void cleanRemovesBuildIntegrationAndEntireQuillDirectory() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        assertEquals(BuildIntegrationInstaller.Result.INSTALLED,
                BuildIntegrationInstaller.install(tempDir));
        Files.createDirectories(tempDir.resolve(".quill/build-events"));
        Files.writeString(tempDir.resolve(".quill/build-events/event.json"), "{}");

        CleanCommand clean = new CleanCommand();
        clean.projectPath = tempDir;
        clean.call();

        assertFalse(Files.exists(tempDir.resolve(".quill")));
        assertFalse(Files.exists(tempDir.resolve(".mvn/extensions.xml")));
    }

    @Test
    void uninstallDeletesSettingsFileCreatedByQuill() throws Exception {
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }\n");

        assertEquals(BuildIntegrationInstaller.Result.INSTALLED,
                BuildIntegrationInstaller.install(tempDir));
        Path settings = tempDir.resolve("settings.gradle");
        assertTrue(Files.isRegularFile(settings));

        assertEquals(BuildIntegrationInstaller.Result.REMOVED,
                BuildIntegrationInstaller.uninstall(tempDir));
        assertFalse(Files.exists(settings));
    }

    @Test
    void successfulGradleBuildWritesEvent() throws Exception {
        assumeGradleAvailable();
        Files.writeString(tempDir.resolve("settings.gradle.kts"),
                "rootProject.name = \"integration-test\"\n");
        runSuccessfulGradleBuild();
    }

    @Test
    void successfulGroovyGradleBuildWritesEvent() throws Exception {
        assumeGradleAvailable();
        Files.writeString(tempDir.resolve("settings.gradle"),
                "rootProject.name = 'integration-test'\n");
        runSuccessfulGradleBuild();
    }

    private void runSuccessfulGradleBuild() throws Exception {
        assertEquals(BuildIntegrationInstaller.Result.INSTALLED,
                BuildIntegrationInstaller.install(tempDir));

        Process build = new ProcessBuilder("gradle", "help", "--quiet", "--no-daemon")
                .directory(tempDir.toFile()).redirectErrorStream(true).start();
        String output = new String(build.getInputStream().readAllBytes());
        assertEquals(0, build.waitFor(), output);
        try (var events = Files.list(tempDir.resolve(".quill/build-events"))) {
            Path event = events.filter(path -> path.getFileName().toString()
                            .startsWith("gradle-"))
                    .findFirst().orElseThrow();
            String json = Files.readString(event);
            assertTrue(json.contains("\"version\":1"));
            assertTrue(json.contains("\"buildTool\":\"gradle\""));
            assertTrue(json.contains("\"successful\":true"));
            assertTrue(json.matches("(?s).*\"finishedAt\":[1-9][0-9]*.*"));
        }

        try (var events = Files.list(tempDir.resolve(".quill/build-events"))) {
            for (Path event : events.toList()) Files.delete(event);
        }
        Process internal = new ProcessBuilder(
                "gradle", "-Dquill.internal=true", "help", "--quiet", "--no-daemon")
                .directory(tempDir.toFile()).redirectErrorStream(true).start();
        String internalOutput = new String(internal.getInputStream().readAllBytes());
        assertEquals(0, internal.waitFor(), internalOutput);
        try (var events = Files.list(tempDir.resolve(".quill/build-events"))) {
            assertTrue(events.findAny().isEmpty(),
                    "Quill's internal Gradle invocation must not create a build event");
        }
    }

    private static void assumeGradleAvailable() throws Exception {
        Process probe;
        try {
            probe = new ProcessBuilder("gradle", "--version").redirectErrorStream(true).start();
        } catch (Exception unavailable) {
            assumeTrue(false, "Gradle is not installed");
            return;
        }
        assumeTrue(probe.waitFor() == 0, "Gradle is not available");
    }
}
