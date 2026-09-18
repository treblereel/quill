package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildProblemInspectorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void explainsWhenNoBuildHasBeenObserved() throws Exception {
        JsonNode result = JSON.readTree(
                BuildProblemInspector.inspect(tempDir, "all", null, 50, 0));

        assertEquals("unknown", result.path("build_status").asText());
        assertEquals(0, result.path("total").asInt());
        assertFalse(result.path("build_was_started").asBoolean());
        assertTrue(result.path("recommended_action").asText().contains("Run the project build"));
    }

    @Test
    void parsesLocationsDeduplicatesAndFiltersByModule() throws Exception {
        writeState(false, "maven", """
                org.apache.maven.plugin.compiler.CompilationFailureException: Compilation failure
                %s/module-a/src/main/java/acme/Broken.java:[12,7] cannot find symbol
                %s/module-a/src/main/java/acme/Broken.java:[12,7] cannot find symbol
                %s/module-b/src/main/kotlin/acme/Other.kt:8:3: unresolved reference
                """.formatted(tempDir, tempDir, tempDir));

        JsonNode result = JSON.readTree(BuildProblemInspector.inspect(
                tempDir, "error", "module-a", 1, 0));

        assertEquals("failed", result.path("build_status").asText());
        assertEquals("maven", result.path("build_tool").asText());
        assertEquals(1, result.path("total").asInt());
        JsonNode problem = result.path("problems").get(0);
        assertEquals("module-a/src/main/java/acme/Broken.java",
                problem.path("source").asText());
        assertEquals(12, problem.path("line").asInt());
        assertEquals(7, problem.path("column").asInt());
        assertEquals("module-a", problem.path("module").asText());
        assertEquals("cannot find symbol", problem.path("message").asText());
    }

    @Test
    void successfulBuildClearsReportedProblems() throws Exception {
        writeState(true, "gradle", "stale failure text");

        JsonNode result = JSON.readTree(
                BuildProblemInspector.inspect(tempDir, "all", null, 50, 0));

        assertEquals("success", result.path("build_status").asText());
        assertEquals(0, result.path("total").asInt());
        assertEquals("none", result.path("recommended_action").asText());
    }

    @Test
    void filtersCapturedProblemsBySeveralSourcePaths() throws Exception {
        writeState(false, "maven", """
                %s/module-a/src/main/java/acme/Broken.java:[12,7] cannot find symbol
                %s/module-b/src/main/java/acme/Other.java:[3,1] incompatible types
                java.lang.IllegalStateException: unlocated build failure
                """.formatted(tempDir, tempDir));

        JsonNode result = JSON.readTree(BuildProblemInspector.inspect(
                tempDir, "all", null,
                java.util.List.of("module-b/src/main/java/acme/Other.java"), 50, 0));

        assertEquals(1, result.path("total").asInt());
        assertEquals("module-b/src/main/java/acme/Other.java",
                result.path("problems").get(0).path("source").asText());
        assertEquals(1, result.path("requested_paths").size());
        assertEquals(0, result.path("unlocated_problems_excluded").asInt());
        assertFalse(result.path("build_was_started").asBoolean());
    }

    private void writeState(boolean successful, String buildTool, String message)
            throws Exception {
        Path quill = Files.createDirectories(tempDir.resolve(".quill"));
        var state = JSON.createObjectNode();
        state.put("version", 2);
        state.put("buildTool", buildTool);
        state.put("successful", successful);
        state.put("finishedAt", System.currentTimeMillis());
        state.putArray("failureMessages").add(message);
        JSON.writeValue(quill.resolve("build-state.json").toFile(), state);
    }
}
