package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectInitializerDiagnosticsTest {

    @Test
    void backgroundTaskRunsConcurrentlyWithCaller() throws Exception {
        CountDownLatch callerContinued = new CountDownLatch(1);
        try (var task = ProjectInitializer.BackgroundTask.start(
                "quill-test-concurrent", () -> callerContinued.await(2, TimeUnit.SECONDS))) {
            callerContinued.countDown();
            assertTrue(task.await());
        }
    }

    @Test
    void closingBackgroundTaskInterruptsOutstandingWork() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var task = ProjectInitializer.BackgroundTask.start("quill-test-background", () -> {
            started.countDown();
            try {
                release.await();
                return true;
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        });

        assertTrue(started.await(2, TimeUnit.SECONDS));
        task.close();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test
    void initializationNeverInvokesMaven(@TempDir Path project) throws Exception {
        Files.createFile(project.resolve("pom.xml"));
        Path sentinel = project.resolve("maven-was-invoked");
        Path unixWrapper = Files.writeString(project.resolve("mvnw"),
                "#!/bin/sh\ntouch maven-was-invoked\nexit 0\n");
        unixWrapper.toFile().setExecutable(true);
        Files.writeString(project.resolve("mvnw.cmd"),
                "@type nul > maven-was-invoked\r\n@exit /b 0\r\n");

        ProjectInitializer.InitializationResult result =
                ProjectInitializer.initializeDetailed(project, true);

        assertFalse(result.successful());
        assertEquals(ProjectInitializer.FailureReason.NO_COMPILED_CLASSES, result.reason());
        assertTrue(result.message().contains("No main .class files"), result.message());
        assertTrue(result.message().contains("Build the project with Maven or Gradle"),
                result.message());
        assertFalse(Files.exists(sentinel), "Quill must never start the Maven build");
        assertFalse(result.phaseMillis().containsKey("compilation"));
        assertTrue(result.phaseMillis().containsKey("class_discovery"));
    }
}
