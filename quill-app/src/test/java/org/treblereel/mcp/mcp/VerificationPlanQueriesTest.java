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
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId>
                <artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
                <modules><module>runtime</module></modules></project>
                """);
        Files.createDirectories(tempDir.resolve("runtime"));
        Files.writeString(tempDir.resolve("runtime/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId>
                <artifactId>runtime</artifactId><version>1</version></project>
                """);
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

        assertEquals(List.of(wrapper.toAbsolutePath().toString(), "testClasses"),
                strings(result.path("commands").get(0).path("argv")));
        assertEquals(List.of(wrapper.toAbsolutePath().toString(),
                        "test", "--tests", "org.acme.PaymentTest"),
                strings(result.path("commands").get(1).path("argv")));
        assertEquals(List.of("services/payment"),
                strings(result.path("unresolved_modules")));
        assertFalse(result.path("module_selection_complete").asBoolean());
    }

    @Test
    void avoidsGuessingGradleTasksWhenDiscoveryIsUnavailable() throws Exception {
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name='sample'\n");
        Path wrapper = Files.writeString(tempDir.resolve("gradlew"), "#!/bin/sh\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        JsonNode contexts = JSON.readTree("""
                [{"resolution":{"module":"common"}},
                 {"resolution":{"module":"services/payment"}}]
                """);

        JsonNode result = new VerificationPlanQueries().plan(
                tempDir, contexts, JSON.createObjectNode());

        assertEquals(List.of(wrapper.toAbsolutePath().toString(), "testClasses"),
                strings(result.path("commands").get(0).path("argv")));
        assertEquals(List.of(wrapper.toAbsolutePath().toString(), "test"),
                strings(result.path("commands").get(1).path("argv")));
        assertEquals(List.of("common", "services/payment"),
                strings(result.path("unresolved_modules")));
    }

    @Test
    void fallsBackToReactorBuildInsteadOfNamingUnknownMavenModule() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId>
                <artifactId>root</artifactId><version>1</version></project>
                """);
        Path wrapper = Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        JsonNode contexts = JSON.readTree("""
                [{"resolution":{"module":"missing"}}]
                """);

        JsonNode result = new VerificationPlanQueries().plan(
                tempDir, contexts, JSON.createObjectNode());

        assertEquals(List.of(wrapper.toAbsolutePath().toString(), "test-compile"),
                strings(result.path("commands").get(0).path("argv")));
        assertEquals(List.of("missing"), strings(result.path("unresolved_modules")));
        assertFalse(result.path("module_selection_complete").asBoolean());
    }

    @Test
    void mapsGradleCustomProjectDirectoryToEvaluatedProjectPath() throws Exception {
        Path project = fixture("tests/gradle-multimodule");
        JsonNode contexts = JSON.readTree("""
                [{"resolution":{"module":"common"}},
                 {"resolution":{"module":"modules/custom"}}]
                """);

        JsonNode result = new VerificationPlanQueries().plan(
                project, contexts, JSON.createObjectNode());

        List<String> argv = strings(result.path("commands").get(0).path("argv"));
        assertEquals(List.of("testClasses"),
                argv.subList(1, argv.size()));
        assertEquals(List.of(":common", ":custom"),
                strings(result.path("command_modules")));
        assertTrue(result.path("module_selection_complete").asBoolean());
    }

    @Test
    void selectsMultipleExistingMavenModules() throws Exception {
        Path project = fixture("tests/multimodule-maven");
        JsonNode contexts = JSON.readTree("""
                [{"resolution":{"module":"common"}},
                 {"resolution":{"module":"service"}}]
                """);

        JsonNode result = new VerificationPlanQueries().plan(
                project, contexts, JSON.createObjectNode());

        List<String> argv = strings(result.path("commands").get(0).path("argv"));
        assertEquals(List.of("test-compile"),
                argv.subList(1, argv.size()));
        assertEquals("repository", result.path("verification_scope").path("kind").asText());
        assertEquals("external_parent", result.path("verification_scope").path("reason").asText());
        assertTrue(result.path("module_selection_complete").asBoolean());
    }

    private static Path fixture(String relative) throws Exception {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(current.resolve(relative), current.resolve("..").resolve(relative))) {
            Path normalized = candidate.normalize();
            if (Files.isDirectory(normalized)) return normalized.toRealPath();
        }
        throw new IllegalStateException("Fixture not found: " + relative);
    }

    private static List<String> strings(JsonNode values) {
        return values.valueStream().map(JsonNode::asText).toList();
    }
}
