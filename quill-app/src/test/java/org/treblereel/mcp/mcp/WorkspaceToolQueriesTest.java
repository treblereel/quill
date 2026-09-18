package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;

class WorkspaceToolQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path workspace;
    private QuillTools tools;

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceManifestStore.initialize(workspace, 1);
        createRepository("engine", "engine-api", "io.casehub.engine.EngineService", "");
        createRepository("platform", "platform-api", "io.casehub.platform.PlatformService", """
                <dependencies><dependency><groupId>io.casehub</groupId>
                  <artifactId>engine-api</artifactId><version>1.0</version>
                </dependency></dependencies>
                """);
        tools = new QuillTools(new ProjectRegistry(new WorkspaceProjectScope(workspace)));
    }

    @Test
    void listsIndexedRepositoriesAndModuleCoordinates() throws Exception {
        JsonNode result = JSON.readTree(tools.list_workspace_repositories(
                Optional.of(true), Optional.empty(), Optional.empty()));

        assertEquals(2, result.path("total").asInt());
        assertEquals(2, result.path("repositories").size());
        assertTrue(result.path("repositories").get(0).path("indexed").asBoolean());
        assertTrue(result.path("repositories").get(0).path("modules").isArray());
    }

    @Test
    void returnsCrossRepositoryDependencyEdges() throws Exception {
        JsonNode result = JSON.readTree(tools.get_workspace_dependencies(
                Optional.of("platform"), Optional.of("providers"), Optional.of(true),
                Optional.empty(), Optional.empty()));

        assertEquals(1, result.path("total").asInt());
        JsonNode edge = result.path("dependencies").get(0);
        assertEquals("platform", edge.path("consumerRepository").asText());
        assertEquals("engine", edge.path("providerRepository").asText());
        assertEquals("local_provider_binary_unresolved", edge.path("status").asText());
    }

    @Test
    void resolvesCoordinatesAndClassesToRepositoryCandidates() throws Exception {
        JsonNode coordinate = JSON.readTree(
                tools.resolve_workspace_entity("io.casehub:engine-api"));
        JsonNode className = JSON.readTree(
                tools.resolve_workspace_entity("io.casehub.engine.EngineService"));

        assertEquals("resolved", coordinate.path("resolution").asText());
        assertEquals("engine", coordinate.path("candidates").get(0)
                .path("repository").asText());
        assertEquals("resolved", className.path("resolution").asText());
        assertEquals("class", className.path("candidates").get(0).path("kind").asText());
        assertEquals("engine", className.path("candidates").get(0)
                .path("repository").asText());
    }

    @Test
    void workspaceToolsAreExplicitInSingleProjectMode() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools(new ProjectRegistry())
                .list_workspace_repositories(Optional.empty(), Optional.empty(), Optional.empty()));

        assertEquals("workspace_mode_required", result.path("error").asText());
        assertFalse(result.path("message").asText().isBlank());
    }

    private void createRepository(String name, String artifact, String className, String extra)
            throws Exception {
        Path root = Files.createDirectories(workspace.resolve(name));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>io.casehub</groupId><artifactId>%s</artifactId><version>1.0</version>
                  %s
                </project>
                """.formatted(artifact, extra));
        Path quill = Files.createDirectories(root.resolve(".quill"));
        String indexId = name + "-index";
        var jdbi = QuillDatabase.create(quill.resolve(indexId + ".db"));
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key, value) VALUES ('index_id', ?)", indexId);
            handle.execute("INSERT INTO metadata(key, value) VALUES ('indexed_at', '2026-09-18T00:00:00Z')");
            handle.execute("INSERT INTO metadata(key, value) VALUES ('last_commit', 'unknown')");
            handle.execute("INSERT INTO metadata(key, value) VALUES ('project_root', ?)",
                    root.toString());
            handle.execute("""
                    INSERT INTO classes(class_name, kind, source_file, is_bean, module, source_set)
                    VALUES (?, 'CLASS', ?, 0, '.', 'main')
                    """, className, "src/main/java/" + className.replace('.', '/') + ".java");
        });
        Files.writeString(quill.resolve("refs.json"), "{\"@worktree\":\"" + indexId + "\"}");
    }
}
