package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.mcp.query.GitToolQueries;
import org.treblereel.mcp.mcp.query.ProjectOverviewQueries;
import org.treblereel.mcp.model.*;

/** Overview, build status, multi-project fan-out and worktree freshness. */
class QuillToolsProjectTest extends QuillToolsTestBase {

    @Test
    void getBuildStatusReportsIntegrationWithoutStartingBuild() throws Exception {
        Files.writeString(
                tempDir.resolve("pom.xml"),
                """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>sample</artifactId><version>1</version>
                </project>
                """);
        Path classes = Files.createDirectories(tempDir.resolve("target/classes/org/acme"));
        Files.write(classes.resolve("Sample.class"), new byte[] {0, 1, 2});

        JsonNode result = JSON.readTree(JDBI_READS.getBuildStatus(jdbi, tempDir));

        assertEquals("maven", result.path("build_system").asText());
        assertEquals(1, result.path("compiled_outputs").path("count").asInt());
        assertEquals("missing", result.path("integration").path("state").asText());
        assertEquals("integration_required", result.path("status").asText());
        assertEquals("fresh", result.path("compiled_outputs").path("status").asText());
        assertTrue(result.path("build_reason").isNull());
        assertTrue(result.path("action_required").asBoolean());
        assertFalse(result.path("build_was_started").asBoolean());
        assertEquals(0, result.path("build_events").path("pending").asInt());

        Path source = tempDir.resolve("src/main/java/org/acme/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; public final class Sample {}\n");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(
                classes.resolve("Sample.class"),
                java.nio.file.attribute.FileTime.fromMillis(now - 2_000));
        Files.setLastModifiedTime(source, java.nio.file.attribute.FileTime.fromMillis(now + 2_000));

        JsonNode stale = JSON.readTree(JDBI_READS.getBuildStatus(jdbi, tempDir));
        assertEquals("build_required", stale.path("status").asText());
        assertEquals("classes_stale", stale.path("build_reason").asText());
        assertEquals("stale", stale.path("compiled_outputs").path("status").asText());
        assertEquals(".", stale.path("compiled_outputs").path("stale_modules").get(0).asText());
    }

    @Test
    void inspectServiceDescriptorsPreservesProviderOrder() throws Exception {
        JsonNode result = JSON.readTree(JDBI_READS.inspectServiceDescriptors(jdbi, "Processor"));
        JsonNode descriptor = result.get("descriptors").get(0);
        assertEquals(2, descriptor.get("provider_count").asInt());
        assertEquals(
                "org.acme.FirstProcessor",
                descriptor.get("providers").get(0).get("provider").asText());
        assertEquals(1, descriptor.get("providers").get(0).get("position").asInt());
        assertEquals(3, descriptor.get("providers").get(1).get("line").asInt());
        assertTrue(descriptor.get("order_can_affect_execution").asBoolean());
        assertEquals("potentially_significant", descriptor.get("order_sensitivity").asText());
    }

    @Test
    void multiProjectResultsHaveDeterministicOrder() throws Exception {
        ProjectRegistry registry =
                new ProjectRegistry() {
                    @Override
                    public Resolution resolve() {
                        return new Resolution(
                                List.of(
                                        projectEntries.entry("z-project", tempDir, jdbi),
                                        projectEntries.entry("a-project", tempDir, jdbi)),
                                List.of());
                    }
                };

        JsonNode result =
                JSON.readTree(
                        new QuillTools(registry).get_overview(Optional.empty(), Optional.empty()));
        assertEquals("a-project", result.get("projects").get(0).get("project").asText());
        assertEquals("z-project", result.get("projects").get(1).get("project").asText());
    }

    @Test
    void singleProjectResultKeepsTheUnwrappedResponseContract() throws Exception {
        ProjectRegistry registry = singleProjectRegistry("only-project");
        QuillTools tools = new QuillTools(registry);

        JsonNode implicit = JSON.readTree(tools.get_overview(Optional.of(false), Optional.empty()));
        JsonNode explicit =
                JSON.readTree(tools.get_overview(Optional.of(false), Optional.of("ONLY-PROJECT")));

        assertTrue(implicit.has("project"));
        assertFalse(
                implicit.has("projects"),
                "A single-project response must not gain a workspace-style wrapper");
        assertEquals(
                implicit,
                explicit,
                "Selecting the only project explicitly must not change the payload");
    }

    @Test
    void singleProjectUnknownFilterReportsTheConfiguredProject() throws Exception {
        QuillTools tools = new QuillTools(singleProjectRegistry("only-project"));

        JsonNode result =
                JSON.readTree(tools.get_overview(Optional.empty(), Optional.of("missing")));

        assertEquals(
                "Project 'missing' not found. Available: [only-project]",
                result.path("error").asText());
    }

    @Test
    void getOverviewReturnsProjectSummary() throws Exception {
        String result = JDBI_READS.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);

        JsonNode project = root.get("project");
        assertNotNull(project);
        JsonNode capabilities = root.path("capabilities");
        assertEquals(1, capabilities.path("contract_version").asInt());
        assertEquals(
                List.of("java", "kotlin"),
                capabilities
                        .path("supported_languages")
                        .valueStream()
                        .map(JsonNode::asText)
                        .toList());
        assertTrue(capabilities.path("stable_symbol_ids").asBoolean());
        assertEquals(3, capabilities.path("symbol_id_version").asInt());
        assertEquals("project", capabilities.path("symbol_id_scope").asText());
        assertTrue(capabilities.path("workspace_symbol_ids_require_project").asBoolean());
        assertTrue(capabilities.path("machine_readable_errors").asBoolean());
        assertTrue(
                capabilities.path("symbol_id_consumers").toString().contains("find_symbol_usages"));
        assertEquals(4, project.get("classes").asInt());
        assertEquals(3, project.get("beans").asInt());
        assertEquals("degraded", project.get("dependency_index").asText());
        assertEquals("1/2 modules resolved", project.get("dependency_index_detail").asText());
        assertEquals(0, project.get("configuration_definitions").asInt());
        assertEquals(0, project.get("configuration_usages").asInt());

        JsonNode byScope = root.get("beans_by_scope");
        assertNotNull(byScope);
        assertTrue(byScope.has("@ApplicationScoped"));

        JsonNode byKind = root.get("beans_by_kind");
        assertNotNull(byKind);
        assertTrue(byKind.has("CLASS"));

        JsonNode hubs = root.get("architecture_hubs");
        assertNotNull(hubs);
        assertTrue(hubs.size() >= 1);
        assertTrue(hubs.get(0).has("total_dependents"));
        assertTrue(hubs.get(0).has("source_dependents"));
        assertTrue(hubs.get(0).has("generated_dependents"));
        assertFalse(
                hubs.get(0).has("dependents"),
                "The overview must not expose an unexplained mixed dependent count");
        JsonNode rankings = root.get("architecture_hub_rankings");
        assertNotNull(rankings);
        assertTrue(rankings.path("source").isArray());
        assertTrue(rankings.path("generated").isArray());
        assertTrue(rankings.path("production").isArray());
        assertTrue(rankings.path("test").isArray());

        JsonNode problems = root.get("problems");
        assertNotNull(problems);
        assertTrue(problems.has("unsatisfied_count"));
        assertTrue(problems.has("ambiguous_count"));
        assertEquals(0, problems.get("unsatisfied_count").asInt());
        assertEquals(1, problems.get("unknown_count").asInt());
        assertEquals(
                "NO_STATIC_CANDIDATE",
                problems.get("unknown_injection_points_sample").get(0).get("reason").asText());

        JsonNode gitSummary = root.get("git_summary");
        assertNotNull(gitSummary);
        assertFalse(gitSummary.isNull());
        assertEquals(3, gitSummary.get("total_commits_indexed").asInt());
        assertTrue(gitSummary.get("top_hotspots").size() >= 1);

        assertTrue(root.has("_meta"));
        assertTrue(root.path("_meta").has("indexed_commit"));
        assertFalse(root.path("_meta").has("compression"));
    }

    @Test
    void getOverviewWithoutGitDataShowsNull() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit-overview.db"));
        var classes =
                List.of(
                        new ClassRecord(
                                0,
                                "org.acme.Foo",
                                "CLASS",
                                null,
                                List.of(),
                                "Foo.java",
                                1,
                                true,
                                100));
        var beans =
                List.of(
                        new BeanRecord(
                                0,
                                1,
                                "CLASS",
                                "@ApplicationScoped",
                                List.of("@Default"),
                                List.of(),
                                false,
                                null,
                                null,
                                null,
                                null,
                                List.of("Foo")));
        IndexWriter.writeIncremental(
                noGitJdbi,
                new IndexFixture()
                        .classes(classes)
                        .beans(beans)
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-08-26T14:30:00",
                                        "last_commit",
                                        "unknown"))
                        .snapshot());

        String result = JDBI_READS.getOverview(noGitJdbi);
        JsonNode root = JSON.readTree(result);
        assertTrue(root.get("git_summary").isNull());
        assertEquals(1, root.get("project").get("beans").asInt());
    }

    @Test
    void compactOverviewOmitsDiagnosticSamplesAndDuplicateRankings() throws Exception {
        ProjectOverviewQueries overview = new ProjectOverviewQueries(new GitToolQueries());
        JsonNode root =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi, readScope -> overview.getOverview(readScope, false)));

        assertFalse(root.has("architecture_hub_rankings"));
        assertFalse(root.path("problems").has("unknown_injection_points_sample"));
        assertEquals(1, root.path("problems").path("unknown_count").asInt());

        JsonNode detailed =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi, readScope -> overview.getOverview(readScope, true)));
        assertTrue(detailed.has("architecture_hub_rankings"));
        assertTrue(detailed.path("problems").has("unknown_injection_points_sample"));
    }

    @Test
    void documentationOnlyWorktreeDoesNotMakeStructureStale() throws Exception {
        Path repository = tempDir.resolve("docs-repository");
        Path source = repository.resolve("src/main/java/example/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; class App {}\n");
        String head;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            head =
                    git.commit()
                            .setMessage("initial")
                            .setAuthor("Test", "test@example.com")
                            .setSign(false)
                            .call()
                            .getName();
        }
        WorktreeInspector.Snapshot indexed = WorktreeInspector.inspect(repository);
        Jdbi docsDb = QuillDatabase.create(tempDir.resolve("docs.db"));
        IndexWriter.writeIncremental(
                docsDb,
                new IndexFixture()
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-09-09T00:00:00Z",
                                        "last_commit",
                                        head,
                                        "project_root",
                                        repository.toString(),
                                        "indexed_structure_fingerprint",
                                        indexed.structuralFingerprint(),
                                        "compiled_before_index",
                                        "false"))
                        .snapshot());

        Files.writeString(repository.resolve("README.md"), "documentation only\n");
        JsonNode meta = JSON.readTree(JDBI_READS.getOverview(docsDb)).path("_meta");

        assertTrue(meta.path("worktree_dirty").asBoolean());
        assertEquals(1, meta.path("worktree_changed_files").asInt());
        assertEquals(0, meta.path("structural_changed_files").asInt());
        assertFalse(meta.path("structure_stale").asBoolean());
        assertTrue(meta.path("stale_reasons").isEmpty());
    }

    @Test
    void responsesExposeDirtyWorktreeFreshnessAndResourceOverlay() throws Exception {
        Path repository = tempDir.resolve("dirty-repository");
        Path service =
                repository.resolve(
                        "src/main/resources/META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "example.FirstProcessor\nexample.SecondProcessor\n");

        GitAnalyzer.GitAnalysisResult analysis;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial")
                    .setAuthor("Test", "test@example.com")
                    .setSign(false)
                    .call();
            analysis = GitAnalyzer.analyze(repository, 10, Map.of());
        }
        WorktreeInspector.Snapshot indexed = WorktreeInspector.inspect(repository);
        Jdbi dirtyDb = QuillDatabase.create(tempDir.resolve("dirty.db"));
        IndexWriter.writeIncremental(
                dirtyDb,
                new IndexFixture()
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-09-08T00:00:00Z",
                                        "last_commit",
                                        analysis.headHash(),
                                        "project_root",
                                        repository.toString(),
                                        "indexed_structure_fingerprint",
                                        indexed.structuralFingerprint(),
                                        "compiled_before_index",
                                        "false"))
                        .snapshot());
        IndexFixture.writeGitData(
                dirtyDb, analysis.fileStats(), analysis.commits(), analysis.commitFiles());

        Files.writeString(service, "example.SecondProcessor\nexample.FirstProcessor\n");

        JsonNode overview = JSON.readTree(JDBI_READS.getOverview(dirtyDb));
        JsonNode meta = overview.path("_meta");
        assertEquals(analysis.headHash(), meta.path("indexed_commit").asText());
        assertEquals(analysis.headHash(), meta.path("current_commit").asText());
        assertTrue(meta.path("worktree_dirty").asBoolean());
        assertTrue(meta.path("structure_stale").asBoolean());
        assertFalse(meta.path("commit_stale").asBoolean());
        assertTrue(meta.path("stale_reasons").toString().contains("worktree_changed_after_index"));
        assertTrue(meta.path("stale_reasons").toString().contains("dirty_worktree_not_compiled"));

        JsonNode hotspots = JSON.readTree(JDBI_READS.getHotspots(dirtyDb, 10, null, false));
        assertTrue(
                hotspots.path("worktree_changes")
                        .toString()
                        .contains("javax.annotation.processing.Processor"));
        assertTrue(hotspots.path("worktree_changes").toString().contains("modified"));

        JsonNode trackedRisk =
                JSON.readTree(
                        JDBI_READS.getRisk(
                                dirtyDb,
                                "src/main/resources/META-INF/services/javax.annotation.processing.Processor"));
        assertEquals("file", trackedRisk.path("target_type").asText());
        assertEquals("service_descriptor", trackedRisk.path("kind").asText());
        assertEquals("modified", trackedRisk.path("worktree_status").asText());
        assertTrue(trackedRisk.path("risk_score").asDouble() >= 6.0);
        assertTrue(trackedRisk.path("signals").has("file_criticality"));
        assertTrue(trackedRisk.path("signals").has("git_churn"));
        assertFalse(trackedRisk.path("signals").has("fan_in"));

        Path untrackedService =
                repository.resolve("src/main/resources/META-INF/services/example.NewProvider");
        Files.writeString(untrackedService, "example.Provider\n");
        WorktreeSnapshotCache.shared().invalidate(repository);
        JsonNode untrackedRisk =
                JSON.readTree(
                        JDBI_READS.getRisk(
                                dirtyDb,
                                "src/main/resources/META-INF/services/example.NewProvider"));
        assertEquals("file", untrackedRisk.path("target_type").asText());
        assertEquals("untracked", untrackedRisk.path("worktree_status").asText());
        assertEquals("CRITICAL", untrackedRisk.path("risk_level").asText());
        assertEquals(10.0, untrackedRisk.path("risk_score").asDouble());
        assertTrue(
                untrackedRisk
                        .path("signals")
                        .path("git")
                        .path("note")
                        .asText()
                        .contains("No Git history"));

        Files.writeString(repository.resolve("pom.xml"), "<project/>\n");
        WorktreeSnapshotCache.shared().invalidate(repository);
        JsonNode buildRisk = JSON.readTree(JDBI_READS.getRisk(dirtyDb, "./pom.xml"));
        assertEquals("file", buildRisk.path("target_type").asText());
        assertEquals("build_configuration", buildRisk.path("kind").asText());
        assertEquals("CRITICAL", buildRisk.path("risk_level").asText());
        assertEquals("untracked", buildRisk.path("worktree_status").asText());
    }
}
