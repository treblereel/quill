package org.treblereel.mcp.mcp.project;

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
    void preservesLegalPlusInProfilesAndRejectsUnsafeDelimiters() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>app</artifactId><version>1</version></project>");
        assertTrue(Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n").toFile().setExecutable(true));
        var capture = tempDir.resolve("target/quill-java-test.properties"); Files.createDirectories(capture.getParent());
        var contexts = JSON.createArrayNode(); contexts.addObject().putObject("resolution").put("module", ".").put("source_set", "test");
        for (String id : List.of("jdk22+", "bad,other", "bad profile")) {
            Files.writeString(capture, "sourceSet=test\nactiveProfile.count=1\nactiveProfile.0=" + id + "\n");
            var result = new VerificationPlanQueries().plan(tempDir, contexts, JSON.createObjectNode());
            assertEquals(id.equals("jdk22+"), result.path("profile_selection_complete").asBoolean(), id);
            if (id.equals("jdk22+")) assertTrue(strings(result.path("commands").get(0).path("argv")).contains("-Pjdk22+"));
            else assertTrue(result.path("commands").isEmpty());
        }
    }

    @Test
    void combinesInheritedProfileSubsetsButRejectsUnrelatedVariants() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>a</module><module>b</module></modules></project>");
        var wrapper = Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n"); assertTrue(wrapper.toFile().setExecutable(true));
        for (String name : List.of("a", "b")) {
            var module = Files.createDirectories(tempDir.resolve(name));
            Files.writeString(module.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>"+name+"</artifactId><version>1</version></project>");
            Files.createDirectories(module.resolve("target"));
        }
        var a=tempDir.resolve("a/target/quill-java-main.properties"); var b=tempDir.resolve("b/target/quill-java-test.properties");
        Files.writeString(a,"activeProfile.count=1\nactiveProfile.0=shared\n");
        Files.writeString(b,"activeProfile.count=2\nactiveProfile.0=integration-tests\nactiveProfile.1=shared\n");
        var contexts=JSON.readTree("[{\"resolution\":{\"module\":\"a\",\"source_set\":\"main\"}},{\"resolution\":{\"module\":\"b\",\"source_set\":\"test\"}}]");
        var result=new VerificationPlanQueries().plan(tempDir,contexts,JSON.createObjectNode());
        assertTrue(result.path("profile_selection_complete").asBoolean());
        assertEquals(List.of("integration-tests","shared"),strings(result.path("active_profiles")));
        Files.writeString(a,"activeProfile.count=1\nactiveProfile.0=other-variant\n");
        result=new VerificationPlanQueries().plan(tempDir,contexts,JSON.createObjectNode());
        assertFalse(result.path("profile_selection_complete").asBoolean());
        assertFalse(result.path("commands_executable").asBoolean()); assertTrue(result.path("commands").isEmpty());
    }

    @Test
    void preservesTheProfilesOfTheRequestedTestSourceSet() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>app</artifactId><version>1</version></project>");
        var wrapper = Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        var capture = tempDir.resolve("target/quill-java-test.properties"); Files.createDirectories(capture.getParent());
        Files.writeString(capture, "sourceSet=test\nactiveProfile.count=1\nactiveProfile.0=integration-tests\n");
        var contexts = JSON.readTree("[{\"resolution\":{\"module\":\".\",\"source_set\":\"test\"}}]");
        var result = new VerificationPlanQueries().plan(tempDir, contexts, JSON.createObjectNode());
        assertEquals(List.of("integration-tests"), strings(result.path("active_profiles")));
        assertTrue(result.path("profile_selection_complete").asBoolean());
        assertEquals(List.of(wrapper.toString(), "test-compile", "-Pintegration-tests"), strings(result.path("commands").get(0).path("argv")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) contexts.get(0).path("resolution")).put("source_set", "integration-test");
        result = new VerificationPlanQueries().plan(tempDir, contexts, JSON.createObjectNode());
        assertEquals(List.of("integration-tests"), strings(result.path("active_profiles")));
        assertEquals(List.of(wrapper.toString(), "test-compile", "-Pintegration-tests"), strings(result.path("commands").get(0).path("argv")));
        assertEquals("integration-test", VerificationPlanQueries.capturedSourceSet(org.treblereel.mcp.core.BuildSystem.GRADLE, "integration-test"));
    }

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

    /**
     * A copy without build outputs: the reactor builds these fixtures with this checkout's Quill
     * build integration, whose captures record the machine's active Maven profiles.
     */
    private Path fixture(String relative) throws Exception {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(current.resolve(relative), current.resolve("..").resolve(relative))) {
            Path source = candidate.normalize();
            if (!Files.isDirectory(source)) continue;
            Path copy = tempDir.resolve("fixture-" + source.getFileName()).toAbsolutePath();
            try (var paths = Files.walk(source)) {
                for (Path path : (Iterable<Path>) paths::iterator) {
                    Path target = copy.resolve(source.relativize(path).toString());
                    if (source.relativize(path).toString().matches("(.*/)?(target|build|\\.gradle)(/.*)?")) continue;
                    if (Files.isDirectory(path)) Files.createDirectories(target);
                    else Files.copy(path, target);
                }
            }
            return copy.toRealPath();
        }
        throw new IllegalStateException("Fixture not found: " + relative);
    }

    private static List<String> strings(JsonNode values) {
        return values.valueStream().map(JsonNode::asText).toList();
    }
}
