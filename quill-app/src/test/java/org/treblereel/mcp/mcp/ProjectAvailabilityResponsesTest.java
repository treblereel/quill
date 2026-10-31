package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.workspace.SingleProjectScope;

class ProjectAvailabilityResponsesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void missingClassesRecommendOnlyTheModuleThatNeedsCompilation() throws Exception {
        mavenModules();
        try (var registry = registry()) {
            var result = JSON.readTree(ProjectAvailabilityResponses.error(registry.resolve().issues()));
            assertEquals("classes_missing", result.path("reason").asText());
            assertEquals("build", result.path("action").asText());
            assertFalse(result.path("automatic_retry").asBoolean());
            assertFalse(result.has("retry_after_ms"));
            assertEquals(List.of("service"), JSON.convertValue(result.path("stale_modules"), List.class));
            assertCommand(result, List.of(root.resolve("mvnw").toString(), "-pl", "service", "-am", "test-compile"));
        }
    }

    @Test void buildStatusIsCompactWithoutAnIndexAndItsDetailsRequestIsExecutable() throws Exception {
        mavenModules();
        try (var registry = registry()) {
            var tools = new QuillTools(registry);
            var summary = JSON.readTree(tools.get_build_status(Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            assertEquals("classes_missing", summary.path("reason").asText());
            assertEquals("summary", summary.path("detail").asText());
            assertEquals(1, summary.path("stale_modules_count").asInt());
            assertFalse(summary.has("verification_plan"));
            assertFalse(summary.has("diagnostics"));
            var method = QuillTools.class.getMethod("get_build_status", Optional.class, Optional.class,
                    Optional.class, Optional.class, Optional.class, Optional.class);
            Map<String, Object> args = JSON.convertValue(summary.at("/details_request/arguments"), Map.class);
            var full = JSON.readTree(McpToolCatalog.invokeMethod(tools, method, args).text());
            assertEquals("full", full.path("detail").asText());
            assertEquals("classes_missing", full.path("reason").asText());
            assertEquals("service", full.path("stale_modules").get(0).asText());
            assertCommand(full, List.of(root.resolve("mvnw").toString(), "-pl", "service", "-am", "test-compile"));
        }
    }

    @Test void gradleBuildGuidanceUsesTheProjectRunnerAndTestClassesTask() throws Exception {
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='app'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path source = root.resolve("src/main/java/Example.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, "class Example {}");
        Path wrapper = root.resolve("gradlew"); Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        try (var registry = registry()) {
            var result = JSON.readTree(ProjectAvailabilityResponses.error(registry.resolve().issues()));
            assertEquals("classes_missing", result.path("reason").asText());
            assertEquals("gradle", result.path("build_system").asText());
            assertCommand(result, List.of(wrapper.toString(), "testClasses"));
        }
    }

    @Test void failedBuildDiagnosticsAreAvailableWithoutAnIndex() throws Exception {
        mavenModules();
        Files.createDirectories(root.resolve("service/target/classes"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", root.resolve("service/target/classes").toString(),
                root.resolve("service/src/main/java/Example.java").toString()));
        Files.createDirectories(root.resolve(".quill"));
        Files.writeString(root.resolve(".quill/build-state.json"), """
                {"successful":false,"buildTool":"maven","finishedAt":123,
                 "diagnostics":[],"failureMessages":["Compilation failed"]}
                """);
        try (var registry = registry()) {
            var result = JSON.readTree(ProjectAvailabilityResponses.error(registry.resolve().issues()));
            assertEquals("build_required", result.path("status").asText());
            assertEquals("build_required", result.path("error").asText());
            assertEquals("build_failed", result.path("reason").asText());
            assertEquals("last_build_failed", result.path("build_reason").asText());
            assertEquals("fix_build", result.path("action").asText());
            assertFalse(result.path("automatic_retry").asBoolean());
            assertEquals("failed", result.path("diagnostics").path("build_status").asText());
            var tools = new QuillTools(registry);
            var diagnostics = JSON.readTree(tools.get_build_problems(Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            assertEquals("failed", diagnostics.path("build_status").asText());
            assertTrue(diagnostics.path("total").asInt() > 0);
            var fileProblems = JSON.readTree(tools.get_file_problems(List.of("service/src/main/java/Example.java"),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            assertEquals("failed", fileProblems.path("build_status").asText());
        }
    }

    @Test void diagnosticsDoNotWaitForAQueuedRefresh() throws Exception {
        mavenModules();
        var jobs = new java.util.ArrayDeque<Runnable>();
        var scope = new SingleProjectScope(); scope.register(root);
        try (var registry = new ProjectRegistry(scope, new BuildEventConsumer(jobs::add))) {
            Path events = Files.createDirectories(root.resolve(".quill/build-events"));
            Files.writeString(events.resolve("result.json"), event(false, System.currentTimeMillis()));
            assertEquals("refresh_pending", registry.resolve().issues().getFirst().code());
            var tools = new QuillTools(registry);
            var response = JSON.readTree(tools.get_build_problems(Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            assertEquals("unknown", response.path("build_status").asText());
            assertFalse(response.has("error"));
            var status = JSON.readTree(tools.get_build_status(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
            assertEquals("refresh_pending", status.path("status").asText());
            assertEquals("build_events_queued", status.path("reason").asText());
            assertEquals("unknown", status.path("diagnostics_summary").path("build_status").asText());
            assertEquals(1, jobs.size());
        }
    }

    @Test void editsAfterBuildExplainWhyAnotherBuildIsNeededIncludingTestSources() throws Exception {
        mavenModules();
        try (var git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("fixture").setAuthor("Test", "test@example.com").call();
            long finished = System.currentTimeMillis() - 2000;
            Path test = root.resolve("service/src/test/java/ExampleTest.java");
            Files.createDirectories(test.getParent()); Files.writeString(test, "class ExampleTest {}");
            Path events = Files.createDirectories(root.resolve(".quill/build-events"));
            Files.writeString(events.resolve("result.json"), event(true, finished));
            assertNull(new BuildEventConsumer().consume(root));
            assertFalse(Files.exists(events));
            try (var registry = registry()) {
                var result = JSON.readTree(ProjectAvailabilityResponses.error(registry.resolve().issues()));
                assertEquals("inputs_changed_after_build", result.path("reason").asText());
                assertEquals("build", result.path("action").asText());
                assertTrue(result.path("message").asText().contains("ExampleTest.java"));
                assertCommand(result, List.of(root.resolve("mvnw").toString(), "-pl", "service", "-am", "test-compile"));
            }
        }
    }

    @Test void diagnosticsSupportMultipleProjectsAndRejectUnknownSelectors() throws Exception {
        Path first = Files.createDirectories(root.resolve("first"));
        Path second = Files.createDirectories(root.resolve("second"));
        Files.writeString(first.resolve("pom.xml"), "<project/>");
        Files.writeString(second.resolve("pom.xml"), "<project/>");
        try (var registry = new ProjectRegistry()) {
            registry.register(first); registry.register(second);
            var tools = new QuillTools(registry);
            var result = JSON.readTree(tools.get_build_problems(Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            assertEquals(2, result.path("projects").size());
            assertEquals("unknown", result.path("projects").get(0).path("data").path("build_status").asText());
            var filtered = JSON.readTree(tools.get_build_problems(Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("second")));
            assertEquals("unknown", filtered.path("build_status").asText());
            var unknown = JSON.readTree(tools.get_build_problems(Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of("absent")));
            assertTrue(unknown.path("error").asText().contains("not found"));
        }
    }

    @Test void refreshFailureNeverSuggestsWaitingWithoutAScheduledRetry() throws Exception {
        var issue = new ProjectRegistry.ProjectIssue("app", root, "index_refresh_failed", "maven",
                "Disk full", "Inspect the refresh failure", false);
        var result = JSON.readTree(ProjectAvailabilityResponses.error(issue));
        assertEquals("index_refresh_failed", result.path("reason").asText());
        assertEquals("inspect_error", result.path("action").asText());
        assertFalse(result.path("automatic_retry").asBoolean());
        assertFalse(result.has("retry_after_ms"));
        var workspace = JSON.createObjectNode();
        ProjectAvailabilityResponses.append(workspace, List.of(issue));
        assertEquals(result.path("reason"), workspace.path("unavailable_projects").get(0).path("reason"));
    }

    private void mavenModules() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>parent</artifactId>
                <version>1</version><packaging>pom</packaging><modules><module>service</module></modules></project>
                """);
        Path module = Files.createDirectories(root.resolve("service"));
        Files.writeString(module.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><parent><groupId>test</groupId><artifactId>parent</artifactId>
                <version>1</version></parent><artifactId>service</artifactId></project>
                """);
        Path source = module.resolve("src/main/java/Example.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, "class Example {}");
        Path wrapper = root.resolve("mvnw"); Files.writeString(wrapper, "#!/bin/sh\nexit 0\n");
        assertTrue(wrapper.toFile().setExecutable(true));
    }

    private ProjectRegistry registry() {
        var registry = new ProjectRegistry(); registry.register(root); return registry;
    }

    private void assertCommand(JsonNode response, List<String> expected) {
        var plan = response.path("verification_plan");
        assertEquals(root.toString(), plan.path("working_directory").asText());
        var command = plan.path("commands").get(0);
        assertEquals("quick_compile", command.path("scope").asText());
        assertFalse(command.path("executes_tests").asBoolean());
        assertEquals(expected, JSON.convertValue(command.path("argv"), List.class));
    }

    private static String event(boolean successful, long finished) {
        return "{\"version\":3,\"buildTool\":\"maven\",\"successful\":" + successful
                + ",\"finishedAt\":" + finished
                + ",\"captureScope\":\"exception_chain\",\"failureMessagesBase64\":[],\"diagnosticsBase64\":[]}";
    }
}
