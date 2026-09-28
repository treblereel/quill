package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.command.UpdateCommand;
import org.treblereel.mcp.db.IndexReader;

class ConfigurationReferenceIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of(System.getProperty("user.dir"));

    @TempDir(cleanup = CleanupMode.NEVER) Path tempDir;

    @AfterEach
    void cleanupTempDirectory() throws Exception {
        IOException failure = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                if (!Files.exists(tempDir)) return;
                try (var paths = Files.walk(tempDir)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
                return;
            } catch (IOException error) {
                failure = error;
                Thread.sleep(200);
            }
        }
        throw failure;
    }

    @Test
    void mavenIndexPublishesAndIncrementallyRefreshesConfigurationGraph() throws Exception {
        Path project = createProject(tempDir.resolve("maven-config"), false);

        assertTrue(ProjectInitializer.initialize(project, true));
        JsonNode initial = query(project, "orders.region");
        assertEquals(2, initial.path("total").asInt(), initial.toString());
        assertEquals(1, initial.path("definition_count").asInt());
        assertEquals(1, initial.path("usage_count").asInt());
        assertTrue(initial.path("references").valueStream()
                .filter(value -> value.path("entry_type").asText().equals("usage"))
                .allMatch(value -> value.path("resolved").asBoolean()));
        assertProgrammaticReferences(project);

        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "orders.currency=CAD\n");
        UpdateCommand.updateAfterSuccessfulBuild(project);

        JsonNode removed = query(project, "orders.region");
        assertEquals(0, removed.path("definition_count").asInt());
        assertEquals(1, removed.path("usage_count").asInt());
        assertFalse(removed.path("references").valueStream()
                .filter(value -> value.path("entry_type").asText().equals("usage"))
                .findFirst().orElseThrow().path("resolved").asBoolean());
        JsonNode added = query(project, "orders.currency");
        assertEquals(1, added.path("definition_count").asInt());
        assertEquals("incremental", metadata(project, "database_write_mode"));
    }

    @Test
    void gradleLayoutPublishesConfigurationDefinitionsAndConsumers() throws Exception {
        Path project = createProject(tempDir.resolve("gradle-config"), true);

        assertTrue(ProjectInitializer.initialize(project, true));

        JsonNode result = query(project, "orders.region");
        assertEquals(2, result.path("total").asInt(), result.toString());
        assertEquals(1, result.path("definition_count").asInt());
        assertEquals(1, result.path("usage_count").asInt());
        assertTrue(result.path("references").valueStream()
                .anyMatch(value -> value.path("className").asText().endsWith("OrderController")));
        assertProgrammaticReferences(project);
    }

    private static Path createProject(Path project, boolean gradle) throws Exception {
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.createDirectories(project.resolve("src/test/resources/fixtures"));
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "orders.region=us-west\nruntime.mode=prod\n");
        Path packageResources = project.resolve(
                "src/main/resources/org/treblereel/mcp/fixture/spring");
        Files.createDirectories(packageResources);
        Files.writeString(packageResources.resolve("local.txt"), "local\n");
        Files.writeString(project.resolve("src/main/resources/messages_en_CA.properties"),
                "greeting=hello\n");
        Files.writeString(project.resolve("src/test/resources/fixtures/test.json"), "{}\n");
        copyTree(FIXTURE.resolve("src/main/java"), project.resolve("src/main/java"));
        if (gradle) {
            Files.writeString(project.resolve("settings.gradle"),
                    "rootProject.name = 'configuration-integration'\n");
            Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }\n");
            Path wrapper = Files.writeString(project.resolve("gradlew"), "#!/bin/sh\nexit 7\n");
            assertTrue(wrapper.toFile().setExecutable(true));
            copyTree(FIXTURE.resolve("target/classes"),
                    project.resolve("build/classes/java/main"));
            Files.writeString(project.resolve(".gitignore"), "/build/\n/.quill/\n");
        } else {
            Files.writeString(project.resolve("pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>test</groupId><artifactId>configuration-integration</artifactId>
                      <version>1</version>
                    </project>
                    """);
            copyTree(FIXTURE.resolve("target/classes"), project.resolve("target/classes"));
            Files.writeString(project.resolve(".gitignore"), "/target/\n/.quill/\n");
        }
        try (Git git = Git.init().setDirectory(project.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture")
                    .setAuthor("Quill Test", "quill@example.test").setSign(false).call();
        }
        return project;
    }

    private static void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static JsonNode query(Path project, String key) throws Exception {
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);
        var resolved = registry.resolve();
        assertTrue(resolved.errors().isEmpty(), resolved.errors().toString());
        String response = new QuillTools().findConfigurationReferences(
                resolved.projects().getFirst().jdbi(), key, null, "all", null, 20, 0);
        return JSON.readTree(response);
    }

    private static JsonNode queryResource(Path project, String path) throws Exception {
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);
        var resolved = registry.resolve();
        assertTrue(resolved.errors().isEmpty(), resolved.errors().toString());
        String response = new QuillTools().findResourceReferences(
                resolved.projects().getFirst().jdbi(), path, null, null, 20, 0);
        return JSON.readTree(response);
    }

    private static void assertProgrammaticReferences(Path project) throws Exception {
        JsonNode config = query(project, "runtime.mode");
        assertEquals(1, config.path("definition_count").asInt(), config.toString());
        assertEquals(1, config.path("usage_count").asInt(), config.toString());
        assertTrue(config.path("references").valueStream()
                .filter(value -> value.path("entry_type").asText().equals("usage"))
                .allMatch(value -> value.path("resolution_status").asText().equals("resolved")));
        JsonNode dynamic = query(project, "<dynamic>");
        assertEquals(1, dynamic.path("usage_count").asInt(), dynamic.toString());
        assertEquals("unknown", dynamic.path("references").get(0)
                .path("resolution_status").asText());

        JsonNode relative = queryResource(project,
                "org/treblereel/mcp/fixture/spring/local.txt");
        assertEquals(1, relative.path("definition_count").asInt(), relative.toString());
        assertEquals(1, relative.path("usage_count").asInt(), relative.toString());

        JsonNode testResource = queryResource(project, "fixtures/test.json");
        assertEquals(1, testResource.path("definition_count").asInt(), testResource.toString());
        assertEquals("test", testResource.path("references").get(0)
                .path("sourceSet").asText());

        JsonNode bundle = queryResource(project, "messages.properties");
        JsonNode bundleUsage = bundle.path("references").valueStream()
                .filter(value -> value.path("entry_type").asText().equals("usage"))
                .findFirst().orElseThrow();
        assertEquals("resolved", bundleUsage.path("resolution_status").asText());
        assertEquals("resource_bundle_family",
                bundleUsage.path("resolution_strategy").asText());

        JsonNode external = queryResource(project, "file:/tmp/quill-external.txt");
        assertEquals("unsupported_mechanism",
                external.path("references").get(0).path("resolution_status").asText());
    }

    private static String metadata(Path project, String key) {
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);
        var resolved = registry.resolve();
        assertTrue(resolved.errors().isEmpty(), resolved.errors().toString());
        return IndexReader.getMetadata(resolved.projects().getFirst().jdbi()).get(key);
    }
}
