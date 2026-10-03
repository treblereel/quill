package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerificationPlanQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path tempDir;

    @Test
    void buildsFocusedAndFallbackMavenArgvFromWrapper() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Path wrapper = Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        JsonNode contexts = JSON.readTree("""
                [{"resolution":{"module":"runtime"}}]
                """);
        JsonNode tests = JSON.readTree("""
                {"tests":[{"class":"org.acme.OrderServiceTest"}]}
                """);

        JsonNode result = new VerificationPlanQueries().plan(tempDir, contexts, tests);

        assertEquals("wrapper", result.path("runner").path("kind").asText());
        assertEquals(List.of(wrapper.toAbsolutePath().toString(), "-pl", "runtime", "-am",
                        "test-compile"),
                strings(result.path("commands").get(0).path("argv")));
        assertEquals("quick_compile", result.path("commands").get(0).path("scope").asText());
        assertFalse(result.path("commands").get(0).path("executes_tests").asBoolean());
        assertTrue(result.path("commands").get(0).path("compiles_test_sources").asBoolean());
        assertEquals(List.of(wrapper.toAbsolutePath().toString(), "-pl", "runtime", "-am",
                        "-Dtest=org.acme.OrderServiceTest", "test"),
                strings(result.path("commands").get(1).path("argv")));
        assertEquals("focused", result.path("commands").get(1).path("scope").asText());
        assertEquals(List.of(wrapper.toAbsolutePath().toString(), "-pl", "runtime", "-am",
                        "test"),
                strings(result.path("commands").get(2).path("argv")));
    }

    @Test
    void buildsGradleTaskAndTestFiltersFromWrapper() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name='sample'\n");
        Path wrapper = Files.writeString(tempDir.resolve("gradlew"), "#!/bin/sh\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        JsonNode contexts = JSON.readTree("""
                [{"resolution":{"module":"services/payment"}}]
                """);
        JsonNode tests = JSON.readTree("""
                {"tests":[{"class":"org.acme.PaymentTest"}]}
                """);

        JsonNode result = new VerificationPlanQueries().plan(tempDir, contexts, tests);

        assertEquals(List.of(wrapper.toAbsolutePath().toString(),
                        ":services:payment:testClasses"),
                strings(result.path("commands").get(0).path("argv")));
        assertEquals(List.of(wrapper.toAbsolutePath().toString(),
                        ":services:payment:test", "--tests", "org.acme.PaymentTest"),
                strings(result.path("commands").get(1).path("argv")));
    }

    private static List<String> strings(JsonNode values) {
        return values.valueStream().map(JsonNode::asText).toList();
    }
}
