package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.ServiceProviderScanner;
import org.treblereel.mcp.model.FrameworkEndpointRecord;

class ProjectInitializerDiagnosticsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void serializesServiceRegistrationsWithoutReflectiveRecordAccess() throws Exception {
        String json = ProjectInitializer.serviceRegistrationsJson(List.of(
                new ServiceProviderScanner.Registration(
                        "com.acme.Service", "com.acme.Provider", "module/META-INF/services/com.acme.Service", 3)));

        var registration = JSON.readTree(json).get(0);
        assertEquals("com.acme.Service", registration.path("serviceType").asText());
        assertEquals("com.acme.Provider", registration.path("providerType").asText());
        assertEquals("module/META-INF/services/com.acme.Service",
                registration.path("descriptorPath").asText());
        assertEquals(3, registration.path("line").asInt());
    }

    @Test
    void serializesFrameworkEndpointsWithoutReflectiveRecordAccess() throws Exception {
        String json = ProjectInitializer.frameworkEndpointsJson(List.of(
                new FrameworkEndpointRecord(7, "com.acme.Orders", "create",
                        "create(com.acme.Order):void", "(Lcom/acme/Order;)V",
                        "spring", List.of("POST"), List.of("/orders"),
                        List.of("/{id}"), List.of("PostMapping")),
                new FrameworkEndpointRecord(8, "com.acme.Child", "read", "read():void", "()V",
                        "spring", List.of("GET"), List.of(), List.of("/read"), List.of("GetMapping"),
                        null, "external.Base", List.of("method:external.Base#read()V"))));

        var endpoint = JSON.readTree(json).get(0);
        assertEquals(7, endpoint.path("classId").asInt());
        assertEquals("spring", endpoint.path("framework").asText());
        assertEquals("POST", endpoint.path("httpMethods").get(0).asText());
        assertEquals("/orders", endpoint.path("classPaths").get(0).asText());
        assertEquals("/{id}", endpoint.path("methodPaths").get(0).asText());
        assertEquals(7, endpoint.path("declaringClassId").asInt());
        assertEquals("com.acme.Orders", endpoint.path("declaringClassName").asText());
        assertTrue(endpoint.path("mappingSources").isArray());
        var external = JSON.readTree(json).get(1);
        assertTrue(external.has("declaringClassId"));
        assertTrue(external.path("declaringClassId").isNull());
        assertEquals("external.Base", external.path("declaringClassName").asText());
        assertEquals("method:external.Base#read()V", external.path("mappingSources").get(0).asText());
    }

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
