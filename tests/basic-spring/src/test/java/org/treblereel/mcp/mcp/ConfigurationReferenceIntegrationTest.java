package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.command.UpdateCommand;
import org.treblereel.mcp.db.IndexReader;

class ConfigurationReferenceIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FIXTURE = Path.of(System.getProperty("user.dir"));

    @TempDir Path tempDir;

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
    }

    private static Path createProject(Path project, boolean gradle) throws Exception {
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("src/main/resources/application.properties"),
                "orders.region=us-west\n");
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

    private static String metadata(Path project, String key) {
        ProjectRegistry registry = new ProjectRegistry();
        registry.register(project);
        var resolved = registry.resolve();
        assertTrue(resolved.errors().isEmpty(), resolved.errors().toString());
        return IndexReader.getMetadata(resolved.projects().getFirst().jdbi()).get(key);
    }
}
