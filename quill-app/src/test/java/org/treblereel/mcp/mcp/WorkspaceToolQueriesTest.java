package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.model.ExternalBeanRecord;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;

class WorkspaceToolQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path workspace;
    private QuillTools tools;
    private ProjectRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceManifestStore.initialize(workspace, 1);
        createRepository("engine", "engine-api", "io.casehub.engine.EngineService", "");
        createRepository("platform", "platform-api", "io.casehub.platform.PlatformService", """
                <dependencies><dependency><groupId>io.casehub</groupId>
                  <artifactId>engine-api</artifactId><version>1.0</version>
                </dependency></dependencies>
                """);
        var platform = QuillDatabase.create(
                workspace.resolve("platform/.quill/platform-index.db"));
        platform.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO classes(class_name, kind, source_file, is_bean, origin, module, source_set)
                    VALUES ('io.casehub.engine.EngineService', 'CLASS', NULL, 0,
                            'dependency', '.', 'main')
                    """);
            handle.execute("""
                    INSERT INTO dependencies(from_class_id, to_class_id, kind, evidence_lines)
                    VALUES (1, 2, 'CONSTRUCTS', '[21]')
                    """);
        });
        registry = new ProjectRegistry(new WorkspaceProjectScope(workspace));
        tools = new QuillTools(registry);
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
    void enrichesDependencyBeansWithWorkspaceProvider() throws Exception {
        var platform = QuillDatabase.openWritable(
                workspace.resolve("platform/.quill/platform-index.db"));
        IndexWriter.writeExternalBeans(platform, List.of(new ExternalBeanRecord(
                1, "io.casehub.engine.RemoteEngineBean", "CLASS", "@ApplicationScoped",
                List.of("@Default"), List.of(), false, false, null, List.of(), null,
                List.of("io.casehub.engine.RemoteEngineBean"), "cdi",
                "io.casehub:engine-api:1.0", "/tmp/engine-api-1.0.jar", List.of())));

        JsonNode result = JSON.readTree(tools.list_beans(
                Optional.of("RemoteEngineBean"), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("dependency"), Optional.empty(), Optional.empty(),
                Optional.of("platform")));

        JsonNode bean = result.path("beans").get(0);
        assertEquals("engine", bean.path("provider_repository").asText());
        assertEquals("build_required", bean.path("provider_status").asText());
        assertEquals(".", bean.path("provider_module").asText());
        assertTrue(result.path("workspace_provider_resolution").path("complete").asBoolean());
        assertFalse(result.path("workspace_provider_resolution")
                .path("additional_workspace_lookup_required").asBoolean(true));
        assertEquals("engine", result.path("workspace_provider_resolution")
                .path("providers").get(0).path("repository").asText());
    }

    @Test
    void enrichesProjectArtifactsWithWorkspaceProviderAndReadiness() throws Exception {
        String response = """
                {"dependencies":[{"id":"io.casehub:engine-api:1.0",
                  "used_by_modules":["."]}]}
                """;

        JsonNode result = JSON.readTree(new WorkspaceToolQueries(registry)
                .enrichProjectDependencies(response, "platform"));

        JsonNode resolution = result.path("dependencies").get(0)
                .path("workspace_resolution");
        assertEquals("resolved", resolution.path("status").asText());
        assertEquals("engine", resolution.path("provider_repository").asText());
        assertEquals(".", resolution.path("provider_module").asText());
        assertEquals("build_required", resolution.path("provider_index_status").asText());
        assertEquals("declared_dependency",
                resolution.path("candidates").get(0).path("evidence").asText());
        assertEquals(1, result.path("workspace_provider_resolution")
                .path("mapped_dependency_count").asInt());
    }

    @Test
    void workspaceToolsAreExplicitInSingleProjectMode() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools(new ProjectRegistry())
                .list_workspace_repositories(Optional.empty(), Optional.empty(), Optional.empty()));

        assertEquals("workspace_mode_required", result.path("error").asText());
        assertFalse(result.path("message").asText().isBlank());
    }

    @Test
    void selectedRepositoryReportsItsIndexProblemInsteadOfNotFound() throws Exception {
        Path isolated = Files.createDirectories(workspace.resolve("missing-index-workspace"));
        WorkspaceManifestStore.initialize(isolated, 1);
        Path repository = Files.createDirectories(isolated.resolve("broken"));
        Files.createDirectories(repository.resolve(".git"));
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>broken</artifactId><version>1</version>
                </project>
                """);
        QuillTools isolatedTools = new QuillTools(
                new ProjectRegistry(new WorkspaceProjectScope(isolated)));

        JsonNode result = JSON.readTree(isolatedTools.get_overview(
                Optional.of(false), Optional.of("broken")));

        assertEquals("build_required", result.path("error").asText(), result.toString());
        assertEquals("build_required", result.path("status").asText());
        assertEquals("broken", result.path("project").asText());
        assertEquals("maven", result.path("build_system").asText());
        assertEquals("mcp_client", result.path("decision_owner").asText());
        assertFalse(result.path("build_was_started").asBoolean(true));
        assertTrue(result.path("recommended_action").asText().contains("Decide whether"));
    }

    @Test
    void repositoryCatalogExposesBuildRequiredState() throws Exception {
        Path repository = Files.createDirectories(workspace.resolve("unbuilt"));
        Files.createDirectories(repository.resolve(".git"));
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>unbuilt</artifactId><version>1</version>
                </project>
                """);

        QuillTools refreshed = new QuillTools(
                new ProjectRegistry(new WorkspaceProjectScope(workspace)));
        JsonNode result = JSON.readTree(refreshed.list_workspace_repositories(
                Optional.empty(), Optional.of(10), Optional.empty()));
        JsonNode unbuilt = java.util.stream.StreamSupport.stream(
                        result.path("repositories").spliterator(), false)
                .filter(node -> "unbuilt".equals(node.path("name").asText()))
                .findFirst().orElseThrow();

        assertEquals("build_required", unbuilt.path("status").asText());
        assertEquals("mcp_client",
                unbuilt.path("availability").path("decision_owner").asText());
        assertFalse(unbuilt.path("availability").path("build_was_started").asBoolean(true));
    }

    @Test
    void findsUsagesOnlyInRepositoriesDependingOnTheProvider() throws Exception {
        JsonNode result = JSON.readTree(tools.find_workspace_usages(
                "io.casehub.engine.EngineService", Optional.of("engine"), Optional.empty(),
                Optional.empty(), Optional.empty()));

        assertEquals("engine", result.path("provider").path("repository").asText());
        assertEquals("io.casehub:engine-api",
                result.path("provider").path("coordinate").asText());
        assertEquals(1, result.path("candidate_consumer_count").asInt());
        assertEquals(1, result.path("queried_consumer_count").asInt());
        assertEquals(0, result.path("unavailable_consumer_count").asInt());
        assertEquals(1, result.path("total").asInt());
        JsonNode consumer = result.path("consumers").get(0);
        assertEquals("platform", consumer.path("repository").asText());
        assertEquals(1, consumer.path("usage").path("usage_group_count").asInt());
        assertEquals("constructor_call", consumer.path("usage").path("usages")
                .get(0).path("usage_kind").asText());
    }

    @Test
    void assessesProviderAndDownstreamRisk() throws Exception {
        JsonNode result = JSON.readTree(tools.assess_workspace_change_risk(
                "io.casehub.engine.EngineService", Optional.of("engine"), Optional.of(3)));

        assertEquals("engine", result.path("provider_repository").asText());
        assertEquals(1, result.path("direct_consumer_count").asInt());
        assertEquals(0, result.path("transitive_consumer_count").asInt());
        assertTrue(result.path("workspace_risk_score").asDouble()
                >= result.path("provider_risk").path("risk_score").asDouble());
        JsonNode downstream = result.path("downstream").get(0);
        assertEquals("platform", downstream.path("repository").asText());
        assertEquals(1, downstream.path("depth").asInt());
        assertEquals(1, downstream.path("usageGroups").asInt());
    }

    @Test
    void continuesDependencyClassQueryInProviderRepository() throws Exception {
        JsonNode result = JSON.readTree(tools.get_dependencies(
                "io.casehub.engine.EngineService", Optional.of("outbound"), Optional.of(1),
                Optional.of(true), Optional.of(20), Optional.empty(), Optional.empty(),
                Optional.of("platform")));

        assertEquals("dependency", result.path("origin").asText());
        JsonNode traversal = result.path("workspace_traversal");
        assertTrue(traversal.path("enabled").asBoolean());
        assertEquals("resolved", traversal.path("status").asText());
        assertTrue(traversal.path("complete").asBoolean());
        assertEquals("engine", traversal.path("provider").path("repository").asText());
        assertEquals("source",
                traversal.path("provider").path("data").path("origin").asText());
        assertEquals("platform", traversal.path("routes").get(0)
                .path("from_repository").asText());
        assertEquals("engine", traversal.path("routes").get(0)
                .path("to_repository").asText());
    }

    @Test
    void routesLocalClassNotFoundToWorkspaceProvider() throws Exception {
        var platform = QuillDatabase.openWritable(
                workspace.resolve("platform/.quill/platform-index.db"));
        platform.useHandle(handle -> {
            handle.execute("DELETE FROM dependencies WHERE to_class_id IN "
                    + "(SELECT id FROM classes WHERE "
                    + "class_name = 'io.casehub.engine.EngineService' "
                    + "AND origin = 'dependency')");
            handle.execute("DELETE FROM classes WHERE "
                    + "class_name = 'io.casehub.engine.EngineService' "
                    + "AND origin = 'dependency'");
        });

        JsonNode result = JSON.readTree(tools.get_dependencies(
                "io.casehub.engine.EngineService", Optional.of("outbound"), Optional.of(1),
                Optional.of(true), Optional.of(20), Optional.empty(), Optional.empty(),
                Optional.of("platform")));

        assertFalse(result.has("error"), result.toString());
        assertEquals("workspace_provider", result.path("origin").asText());
        assertEquals("not_found", result.path("local_resolution").path("status").asText());
        assertEquals("engine", result.path("workspace_traversal")
                .path("provider").path("repository").asText());
        assertEquals("source", result.path("workspace_traversal")
                .path("provider").path("data").path("origin").asText());
    }

    @Test
    void locatesUnindexedProviderSourceAndReportsBuildRequired() throws Exception {
        Path source = workspace.resolve(
                "engine/src/main/java/io/casehub/engine/UnbuiltService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package io.casehub.engine; class UnbuiltService {}");

        JsonNode result = JSON.readTree(tools.get_dependencies(
                "io.casehub.engine.UnbuiltService", Optional.of("outbound"), Optional.of(1),
                Optional.of(true), Optional.of(20), Optional.empty(), Optional.empty(),
                Optional.of("platform")));

        JsonNode traversal = result.path("workspace_traversal");
        assertEquals("provider_index_incomplete", traversal.path("status").asText());
        assertFalse(traversal.path("complete").asBoolean(true));
        assertEquals("engine", traversal.path("provider").path("repository").asText());
        assertEquals("src/main/java/io/casehub/engine/UnbuiltService.java",
                traversal.path("provider").path("source_file").asText());
        assertEquals("build_required", traversal.path("project_warnings").get(0)
                .path("status").asText());
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
