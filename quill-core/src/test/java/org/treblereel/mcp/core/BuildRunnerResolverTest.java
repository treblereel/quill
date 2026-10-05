package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildRunnerResolverTest {

    @TempDir Path tempDir;

    @Test
    void prefersExecutableUnixWrapper() throws Exception {
        Path wrapper = Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n");
        assertTrue(wrapper.toFile().setExecutable(true));

        BuildRunnerResolver.Runner runner = BuildRunnerResolver.resolve(
                tempDir, BuildSystem.MAVEN, false, "");

        assertTrue(runner.available());
        assertEquals("wrapper", runner.kind());
        assertEquals("project", runner.source());
        assertEquals(List.of(wrapper.toAbsolutePath().toString()), runner.argvPrefix());
    }

    @Test
    void fallsBackToInstalledToolOnlyWhenPresentOnPath() throws Exception {
        Files.writeString(tempDir.resolve("mvnw"), "not executable\n");
        Path bin = Files.createDirectory(tempDir.resolve("bin"));
        Path maven = Files.writeString(bin.resolve("mvn"), "#!/bin/sh\n");
        assertTrue(maven.toFile().setExecutable(true));

        BuildRunnerResolver.Runner runner = BuildRunnerResolver.resolve(
                tempDir, BuildSystem.MAVEN, false, bin.toString());

        assertTrue(runner.available());
        assertEquals("system", runner.kind());
        assertEquals("PATH", runner.source());
        assertEquals(List.of(maven.toAbsolutePath().toString()), runner.argvPrefix());
    }

    @Test
    void reportsUnavailableInsteadOfInventingSystemCommand() throws Exception {
        Files.writeString(tempDir.resolve("gradlew"), "not executable\n");

        BuildRunnerResolver.Runner runner = BuildRunnerResolver.resolve(
                tempDir, BuildSystem.GRADLE, false, "");

        assertFalse(runner.available());
        assertEquals("unavailable", runner.kind());
        assertTrue(runner.argvPrefix().isEmpty());
        assertTrue(runner.unavailableReason().contains("not executable"));
    }

    @Test
    void usesWindowsWrapperThroughCmd() throws Exception {
        Path wrapper = Files.writeString(tempDir.resolve("mvnw.cmd"), "@echo off\r\n");

        BuildRunnerResolver.Runner runner = BuildRunnerResolver.resolve(
                tempDir, BuildSystem.MAVEN, true, "");

        assertEquals(List.of("cmd.exe", "/d", "/c", wrapper.toAbsolutePath().toString()),
                runner.argvPrefix());
    }
}
