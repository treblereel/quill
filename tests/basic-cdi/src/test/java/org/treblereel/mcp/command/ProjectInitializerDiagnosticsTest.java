package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;

class ProjectInitializerDiagnosticsTest {

    @Test
    void reportsCompileCommandAndExitCode(@TempDir Path project) throws Exception {
        Files.createFile(project.resolve("pom.xml"));
        writeMavenWrapper(project, 7);

        ProjectInitializer.InitializationResult result =
                ProjectInitializer.initializeDetailed(project, true);

        assertFalse(result.successful());
        assertEquals(ProjectInitializer.FailureReason.COMPILATION_FAILED, result.reason());
        assertTrue(result.message().contains("exited with code 7"), result.message());
        assertTrue(result.message().contains(BuildSystem.isWindows() ? "mvnw.cmd" : "mvnw"),
                result.message());
        assertTrue(result.phaseMillis().containsKey("class_discovery"));
        assertTrue(result.phaseMillis().containsKey("compilation"));
    }

    @Test
    void distinguishesSuccessfulBuildWithoutMainBytecode(@TempDir Path project) throws Exception {
        Files.createFile(project.resolve("pom.xml"));
        writeMavenWrapper(project, 0);

        ProjectInitializer.InitializationResult result =
                ProjectInitializer.initializeDetailed(project, true);

        assertFalse(result.successful());
        assertEquals(ProjectInitializer.FailureReason.NO_COMPILED_CLASSES, result.reason());
        assertTrue(result.message().contains("no main .class files"), result.message());
        assertTrue(result.phaseMillis().containsKey("class_rediscovery"));
    }

    private static void writeMavenWrapper(Path project, int exitCode) throws Exception {
        if (BuildSystem.isWindows()) {
            Files.writeString(project.resolve("mvnw.cmd"), "@exit /b " + exitCode + "\r\n");
        } else {
            Path wrapper = Files.writeString(project.resolve("mvnw"),
                    "#!/bin/sh\nexit " + exitCode + "\n");
            wrapper.toFile().setExecutable(true);
        }
    }
}
