package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.model.*;

class QuillToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;
    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = QuillDatabase.create(tempDir.resolve("test.db"));
        var classes = List.of(
                new ClassRecord(0, "org.acme.OrderService", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/org/acme/OrderService.java", 10, true, 500),
                new ClassRecord(0, "org.acme.PaymentService", "INTERFACE", null,
                        List.of(), "src/main/java/org/acme/PaymentService.java", 5, false, 100),
                new ClassRecord(0, "org.acme.StripePaymentService", "CLASS", "java.lang.Object",
                        List.of("org.acme.PaymentService"), "src/main/java/org/acme/StripePaymentService.java", 8, true, 300),
                new ClassRecord(0, "org.acme.AuditService", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/org/acme/AuditService.java", 5, true, 200)
        );
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("OrderService", "Object")),
                new BeanRecord(0, 3, "CLASS", "@ApplicationScoped", List.of("@Default", "@Premium"),
                        List.of(), false, null, List.of("prod"), null, null, List.of("PaymentService", "StripePaymentService", "Object")),
                new BeanRecord(0, 4, "CLASS", "@Dependent", List.of("@Default"),
                        List.of(), false, null, List.of("dev", "test"), null, null, List.of("AuditService", "Object"))
        );
        var ips = List.of(
                InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "PaymentService",
                        List.of("@Default"), "paymentService", 2, false,
                        InjectionPointRecord.STATIC_CDI).withResolution(2, false,
                        new ResolutionTrace(List.of(new ResolutionCandidate(2,
                                "org.acme.StripePaymentService",
                                CandidateDisposition.SELECTED,
                                "UNIQUE_ELIGIBLE_CANDIDATE",
                                List.of("TYPE_ASSIGNABILITY", "QUALIFIER_MATCHING"))),
                                List.of("TYPE_ASSIGNABILITY", "QUALIFIER_MATCHING"),
                                List.of("RUNTIME_CDI_EXTENSIONS"))),
                InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "RuntimeProvidedService",
                        List.of("@Default"), "runtimeProvidedService", null, false,
                        InjectionPointRecord.STATIC_CDI)
        );
        var deps = List.of(
                new DependencyRecord(1, 3, "CDI_INJECT", 1),
                new DependencyRecord(3, 4, "CONSTRUCTS", null, 1, List.of(42))
        );
        IndexWriter.write(jdbi, classes, beans, ips, deps,
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "abc1234",
                        "dependency_index", "degraded",
                        "dependency_index_detail", "1/2 modules resolved"));

        var gitCommits = List.of(
                new GitCommitRecord(1, "aaa1111aaa1111aaa1111aaa1111aaa1111aaa111", "aaa1111",
                        "dev1", "dev1@test.com", "2026-08-28T10:00:00Z", "feat: add order service"),
                new GitCommitRecord(2, "bbb2222bbb2222bbb2222bbb2222bbb2222bbb222", "bbb2222",
                        "dev2", "dev2@test.com", "2026-08-27T09:00:00Z", "fix: payment edge case"),
                new GitCommitRecord(3, "ccc3333ccc3333ccc3333ccc3333ccc3333ccc333", "ccc3333",
                        "dev1", "dev1@test.com", "2026-08-26T08:00:00Z", "initial commit")
        );
        var gitFiles = List.of(
                new GitCommitFile(1, 1, "src/main/java/org/acme/OrderService.java", "MODIFY"),
                new GitCommitFile(1, 3, "src/main/java/org/acme/StripePaymentService.java", "MODIFY"),
                new GitCommitFile(2, 3, "src/main/java/org/acme/StripePaymentService.java", "MODIFY"),
                new GitCommitFile(3, 1, "src/main/java/org/acme/OrderService.java", "ADD"),
                new GitCommitFile(3, 2, "src/main/java/org/acme/PaymentService.java", "ADD"),
                new GitCommitFile(3, 3, "src/main/java/org/acme/StripePaymentService.java", "ADD")
        );
        var gitStats = List.of(
                new GitFileStats(1, "src/main/java/org/acme/OrderService.java", 1, 2,
                        "2026-08-28T10:00:00Z", "dev1", "2026-08-26T08:00:00Z", 1),
                new GitFileStats(2, "src/main/java/org/acme/StripePaymentService.java", 3, 3,
                        "2026-08-28T10:00:00Z", "dev1", "2026-08-26T08:00:00Z", 2),
                new GitFileStats(3, "src/main/java/org/acme/PaymentService.java", 2, 1,
                        "2026-08-26T08:00:00Z", "dev1", "2026-08-26T08:00:00Z", 1)
        );
        IndexWriter.writeGitData(jdbi, gitStats, gitCommits, gitFiles);

        var externalDeps = List.of(
                new ExternalDepRecord(1, "jakarta.enterprise.context.ApplicationScoped", "ANNOTATION"),
                new ExternalDepRecord(1, "jakarta.inject.Inject", "ANNOTATION"),
                new ExternalDepRecord(3, "jakarta.enterprise.context.ApplicationScoped", "ANNOTATION"),
                new ExternalDepRecord(3, "com.stripe.Stripe", "FIELD"),
                new ExternalDepRecord(3, "com.stripe.model.Charge", "METHOD"),
                new ExternalDepRecord(4, "jakarta.enterprise.context.Dependent", "ANNOTATION")
        );
        IndexWriter.writeExternalDeps(jdbi, externalDeps);
    }

    @Test
    void getBeansReturnsAllBeans() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, null, null);
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("\"total\":3"));
    }

    @Test
    void singleProjectQueryFailureReturnsStructuredToolError() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("DROP TABLE dependencies");
            handle.execute("DROP TABLE injection_points");
            handle.execute("DROP TABLE beans");
        });
        ProjectRegistry registry = new ProjectRegistry() {
            @Override
            public Resolution resolve() {
                return new Resolution(
                        List.of(new ProjectEntry("broken-project", tempDir, jdbi)),
                        List.of());
            }
        };

        String result = new QuillTools(registry).list_beans(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());

        JsonNode root = JSON.readTree(result);
        assertTrue(root.get("error").asText().contains("broken-project"));
        assertTrue(root.get("error").asText().contains("beans"));
    }

    @Test
    void getBeansFiltersByScope() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, "@ApplicationScoped", null, null, null);
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("AuditService"));
    }

    @Test
    void getBeansFiltersByProfile() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, "dev", null);
        assertTrue(result.contains("AuditService"));
        assertFalse(result.contains("OrderService"));
        assertTrue(result.contains("\"total\":1"));
    }

    @Test
    void getBeansFiltersByQualifier() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, null, "@Premium");
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("OrderService"));
        assertFalse(result.contains("AuditService"));
        assertTrue(result.contains("\"total\":1"));
    }

    @Test
    void getDependenciesShowsOutbound() {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "OrderService", "outbound", 1);
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("CDI_INJECT"));
    }

    @Test
    void getDependenciesReturnsCallSiteEvidence() throws Exception {
        JsonNode result = JSON.readTree(
                new QuillTools().getDependencies(jdbi, "AuditService", "inbound", 1));
        JsonNode edge = result.get("depended_by").get(0);
        assertEquals("CONSTRUCTS", edge.get("kind").asText());
        assertEquals("src/main/java/org/acme/StripePaymentService.java",
                edge.get("evidence").get(0).get("file").asText());
        assertEquals(42, edge.get("evidence").get(0).get("line").asInt());
    }

    @Test
    void dependencyGraphIsBounded() throws Exception {
        jdbi.useHandle(handle -> {
            var classes = handle.prepareBatch(
                    "INSERT INTO classes(class_name, kind, is_bean) VALUES (:name, 'CLASS', 0)");
            for (int i = 0; i < 250; i++) {
                classes.bind("name", "org.acme.Generated" + i).add();
            }
            classes.execute();
            var dependencies = handle.prepareBatch(
                    "INSERT INTO dependencies(from_class_id, to_class_id, kind) VALUES (1, :target, 'CLASS_REFERENCE')");
            for (int id = 5; id < 255; id++) {
                dependencies.bind("target", id).add();
            }
            dependencies.execute();
        });

        JsonNode result = JSON.readTree(
                new QuillTools().getDependencies(jdbi, "OrderService", "outbound", 1));
        assertEquals(200, result.get("depends_on").size());
        assertTrue(result.get("truncated").asBoolean());
        assertEquals(200, result.get("node_limit").asInt());
    }

    @Test
    void multiProjectResultsHaveDeterministicOrder() throws Exception {
        ProjectRegistry registry = new ProjectRegistry() {
            @Override
            public Resolution resolve() {
                return new Resolution(List.of(
                        new ProjectEntry("z-project", tempDir, jdbi),
                        new ProjectEntry("a-project", tempDir, jdbi)), List.of());
            }
        };

        JsonNode result = JSON.readTree(new QuillTools(registry).get_overview(Optional.empty()));
        assertEquals("a-project", result.get("projects").get(0).get("project").asText());
        assertEquals("z-project", result.get("projects").get(1).get("project").asText());
    }

    @Test
    void getDependenciesDepth2ExpandsTransitive() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "OrderService", "outbound", 2);
        JsonNode root = JSON.readTree(result);
        JsonNode dependsOn = root.get("depends_on");
        assertEquals(1, dependsOn.size());
        assertEquals("org.acme.StripePaymentService", dependsOn.get(0).get("class").asText());
        JsonNode nested = dependsOn.get(0).get("depends_on");
        assertNotNull(nested, "depth=2 should expand nested depends_on");
        assertEquals(1, nested.size());
        assertEquals("org.acme.AuditService", nested.get(0).get("class").asText());
    }

    @Test
    void getDependenciesDepth1DoesNotExpandNested() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "OrderService", "outbound", 1);
        JsonNode root = JSON.readTree(result);
        JsonNode dependsOn = root.get("depends_on");
        assertNull(dependsOn.get(0).get("depends_on"),
                "depth=1 should not expand nested dependencies");
    }

    @Test
    void getInjectionPointsShowsResolutionEvidenceAndKeepsMissingCandidateUnknown()
            throws Exception {
        var tools = new QuillTools();
        String result = tools.getInjectionPoints(jdbi, "OrderService");
        JsonNode root = JSON.readTree(result);
        assertEquals(2, root.get("injection_points").size());
        JsonNode unknown = root.get("injection_points").get(1);
        assertEquals("unknown", unknown.get("resolution").asText());
        assertEquals("STATIC_CDI", unknown.get("resolution_strategy").asText());
        assertEquals("NO_STATIC_CANDIDATE", unknown.get("reason").asText());
        assertEquals("low", unknown.get("confidence").asText());
        assertFalse(unknown.get("limitations").isEmpty());
        assertEquals(0, root.get("unsatisfied").size());
        assertEquals("runtimeProvidedService", root.get("unknown").get(0).asText());
        JsonNode selected = root.get("injection_points").get(0)
                .get("resolution_trace").get("candidates").get(0);
        assertEquals("org.acme.StripePaymentService", selected.get("class").asText());
        assertEquals("src/main/java/org/acme/StripePaymentService.java",
                selected.get("file").asText());
        assertEquals("selected", selected.get("disposition").asText());
        assertEquals("UNIQUE_ELIGIBLE_CANDIDATE", selected.get("reason").asText());
    }

    @Test
    void getHotspotsReturnsMostChanged() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, null);
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        assertNotNull(hotspots);
        assertTrue(hotspots.size() >= 2);
        assertEquals("src/main/java/org/acme/StripePaymentService.java",
                hotspots.get(0).get("file").asText());
        assertEquals(3, hotspots.get(0).get("commit_count").asInt());
    }

    @Test
    void hotspotPaginationReportsFullTotalAndTruncation() throws Exception {
        JsonNode root = JSON.readTree(new QuillTools().getHotspots(jdbi, 1, null));

        assertEquals(1, root.path("showing").asInt());
        assertEquals(3, root.path("total").asInt());
        assertTrue(root.path("truncated").asBoolean());
    }

    @Test
    void getHotspotsFiltersBySince() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, "2026-08-27");
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        for (JsonNode h : hotspots) {
            assertTrue(h.get("last_modified").asText().compareTo("2026-08-27") >= 0);
        }
    }

    @Test
    void getFileHistoryReturnsCommits() throws Exception {
        var tools = new QuillTools();
        String result = tools.getFileHistory(jdbi, "StripePaymentService", 10);
        JsonNode root = JSON.readTree(result);
        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        JsonNode commits = root.get("commits");
        assertNotNull(commits);
        assertEquals(3, commits.size());
        assertEquals("aaa1111", commits.get(0).get("hash").asText());
    }

    @Test
    void getCoChangesFindsCorrelatedFiles() throws Exception {
        var tools = new QuillTools();
        String result = tools.getCoChanges(jdbi, "OrderService", 10);
        JsonNode root = JSON.readTree(result);
        assertEquals("org.acme.OrderService", root.get("target").asText());
        JsonNode coChanges = root.get("co_changes");
        assertNotNull(coChanges);
        assertTrue(coChanges.size() >= 1);
        boolean hasStripe = false;
        for (JsonNode co : coChanges) {
            if (co.get("file").asText().contains("StripePaymentService")) {
                hasStripe = true;
                assertTrue(co.get("co_change_count").asInt() >= 1);
                assertTrue(co.get("coupling_ratio").asDouble() > 0);
            }
        }
        assertTrue(hasStripe, "OrderService should co-change with StripePaymentService");
    }

    @Test
    void getRecentChangesReturnsCommitsWithFiles() throws Exception {
        var tools = new QuillTools();
        String result = tools.getRecentChanges(jdbi, 5);
        JsonNode root = JSON.readTree(result);
        JsonNode changes = root.get("recent_changes");
        assertNotNull(changes);
        assertEquals(3, changes.size());
        assertEquals("aaa1111", changes.get(0).get("commit").asText());
        JsonNode files = changes.get(0).get("files");
        assertNotNull(files);
        assertTrue(files.size() >= 1);
    }

    @Test
    void getOverviewReturnsProjectSummary() throws Exception {
        var tools = new QuillTools();
        String result = tools.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);

        JsonNode project = root.get("project");
        assertNotNull(project);
        assertEquals(4, project.get("classes").asInt());
        assertEquals(3, project.get("beans").asInt());
        assertEquals("degraded", project.get("dependency_index").asText());
        assertEquals("1/2 modules resolved", project.get("dependency_index_detail").asText());

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
        assertFalse(hubs.get(0).has("dependents"),
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
        assertEquals("NO_STATIC_CANDIDATE",
                problems.get("unknown_injection_points_sample").get(0)
                        .get("reason").asText());

        JsonNode gitSummary = root.get("git_summary");
        assertNotNull(gitSummary);
        assertFalse(gitSummary.isNull());
        assertEquals(3, gitSummary.get("total_commits_indexed").asInt());
        assertTrue(gitSummary.get("top_hotspots").size() >= 1);

        assertTrue(root.has("_meta"));
        assertEquals("indexed_source_coverage",
                root.path("_meta").path("compression_baseline").asText());
        assertFalse(root.path("_meta").path("compression_is_agent_token_savings").asBoolean());
    }

    @Test
    void getOverviewWithoutGitDataShowsNull() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit-overview.db"));
        var classes = List.of(
                new ClassRecord(0, "org.acme.Foo", "CLASS", null, List.of(), "Foo.java", 1, true, 100));
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("Foo")));
        IndexWriter.write(noGitJdbi, classes, beans, List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "unknown"));

        var tools = new QuillTools();
        String result = tools.getOverview(noGitJdbi);
        JsonNode root = JSON.readTree(result);
        assertTrue(root.get("git_summary").isNull());
        assertEquals(1, root.get("project").get("beans").asInt());
    }

    @Test
    void getRiskReturnsScoreAndSignals() throws Exception {
        var tools = new QuillTools();
        String result = tools.getRisk(jdbi, "StripePaymentService");
        JsonNode root = JSON.readTree(result);

        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        assertEquals("class", root.get("target_type").asText());
        assertTrue(root.has("risk_score"));
        assertTrue(root.get("risk_score").asDouble() >= 0);
        assertTrue(root.get("risk_score").asDouble() <= 10);

        String level = root.get("risk_level").asText();
        assertTrue(List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(level));

        JsonNode signals = root.get("signals");
        assertNotNull(signals);
        assertTrue(signals.has("fan_in"));
        assertTrue(signals.has("fan_out"));
        assertTrue(signals.has("git_churn"));
        assertTrue(signals.has("bus_factor"));
        assertTrue(signals.has("coupling"));

        assertTrue(signals.get("git_churn").get("value").asInt() >= 1);

        String recommendation = root.get("recommendation").asText();
        assertNotNull(recommendation);
        assertFalse(recommendation.isEmpty());

        assertTrue(root.has("_meta"));

        JsonNode sourcePath = JSON.readTree(tools.getRisk(jdbi,
                "src/main/java/org/acme/StripePaymentService.java"));
        assertEquals("class", sourcePath.path("target_type").asText());
        assertEquals("org.acme.StripePaymentService", sourcePath.path("target").asText());
    }

    @Test
    void getRiskWithoutGitDataExcludesGitSignals() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit-risk.db"));
        var classes = List.of(
                new ClassRecord(0, "org.acme.Bar", "CLASS", null, List.of(), "Bar.java", 1, true, 100));
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("Bar")));
        IndexWriter.write(noGitJdbi, classes, beans, List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "unknown"));

        var tools = new QuillTools();
        String result = tools.getRisk(noGitJdbi, "Bar");
        JsonNode root = JSON.readTree(result);

        assertEquals("org.acme.Bar", root.get("target").asText());
        assertTrue(root.has("risk_score"));

        JsonNode signals = root.get("signals");
        assertTrue(signals.has("fan_in"));
        assertTrue(signals.has("fan_out"));
        assertTrue(signals.has("git"));
        assertTrue(signals.get("git").get("note").asText().contains("unavailable"));

        assertTrue(root.get("recommendation").asText().contains("Git data unavailable"));
    }

    @Test
    void getRiskClassNotFound() {
        var tools = new QuillTools();
        String result = tools.getRisk(jdbi, "NonExistentClass");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Class not found"));
    }

    @Test
    void getExternalDepsForClassShowsTypesByKind() throws Exception {
        var tools = new QuillTools();
        String result = tools.getExternalDeps(jdbi, "StripePaymentService", null, 20);
        JsonNode root = JSON.readTree(result);

        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        JsonNode deps = root.get("external_dependencies");
        assertNotNull(deps);
        assertTrue(deps.has("annotation"));
        assertTrue(deps.has("field"));
        assertTrue(deps.has("method"));

        boolean hasStripe = false;
        for (JsonNode t : deps.get("field")) {
            if (t.asText().contains("com.stripe")) hasStripe = true;
        }
        assertTrue(hasStripe, "Should include com.stripe.Stripe as FIELD dependency");
        assertEquals(3, root.get("total_external_types").asInt());
    }

    @Test
    void getExternalDepsLibrarySummary() throws Exception {
        var tools = new QuillTools();
        String result = tools.getExternalDeps(jdbi, null, null, 20);
        JsonNode root = JSON.readTree(result);

        JsonNode libraries = root.get("libraries");
        assertNotNull(libraries);
        assertTrue(libraries.size() >= 2);

        boolean hasJakarta = false;
        boolean hasStripe = false;
        for (JsonNode lib : libraries) {
            String pkg = lib.get("package").asText();
            if (pkg.startsWith("jakarta.")) hasJakarta = true;
            if (pkg.startsWith("com.stripe")) hasStripe = true;
        }
        assertTrue(hasJakarta, "Should include jakarta libraries");
        assertTrue(hasStripe, "Should include com.stripe library");
    }

    @Test
    void getExternalDepsFilterByLibrary() throws Exception {
        var tools = new QuillTools();
        String result = tools.getExternalDeps(jdbi, null, "com.stripe", 20);
        JsonNode root = JSON.readTree(result);

        assertEquals("com.stripe", root.get("library_filter").asText());
        JsonNode classes = root.get("classes_using_library");
        assertNotNull(classes);
        assertEquals(1, classes.size());
        assertEquals("org.acme.StripePaymentService", classes.get(0).asText());
    }

    @Test
    void getBeansRespectsLimit() throws Exception {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, null, null, 1);
        JsonNode root = JSON.readTree(result);
        assertEquals(1, root.get("showing").asInt());
        assertEquals(3, root.get("total").asInt());
    }

    @Test
    void getHotspotsSinceRecomputesCounts() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, "2026-08-28");
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        assertNotNull(hotspots);
        for (JsonNode h : hotspots) {
            String file = h.get("file").asText();
            int count = h.get("commit_count").asInt();
            if (file.contains("OrderService")) {
                assertEquals(1, count,
                        "OrderService had 1 commit on 2026-08-28, not lifetime count of 2");
            }
        }
    }

    @Test
    void getHotspotsSinceExcludesOlderCommits() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, "2026-08-28");
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        for (JsonNode h : hotspots) {
            assertFalse(h.get("file").asText().equals("src/main/java/org/acme/PaymentService.java"),
                    "PaymentService.java only has commits before 2026-08-28 and should be excluded");
        }
    }

    @Test
    void currentHotspotsExcludeDeletedFilesButHistoryRemainsAddressableByPath() throws Exception {
        Path repository = tempDir.resolve("history-repository");
        Path current = repository.resolve("src/main/java/example/Current.java");
        Path deleted = repository.resolve("src/main/java/example/Deleted.java");
        java.nio.file.Files.createDirectories(current.getParent());
        java.nio.file.Files.writeString(current, "package example; class Current {}\n");
        java.nio.file.Files.writeString(deleted, "package example; class Deleted {}\n");

        GitAnalyzer.GitAnalysisResult analysis;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
            java.nio.file.Files.delete(deleted);
            git.rm().addFilepattern("src/main/java/example/Deleted.java").call();
            git.commit().setMessage("delete stale class").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
            analysis = GitAnalyzer.analyze(repository, 10,
                    Map.of("src/main/java/example/Current.java", 1));
        }

        Jdbi historyDb = QuillDatabase.create(tempDir.resolve("history.db"));
        IndexWriter.write(historyDb, List.of(new ClassRecord(0, "example.Current", "CLASS",
                        "java.lang.Object", List.of(), "src/main/java/example/Current.java",
                        1, false, 10)), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-09-08T00:00:00Z",
                        "last_commit", analysis.headHash(),
                        "project_root", repository.toString()));
        IndexWriter.writeGitData(historyDb, analysis.fileStats(), analysis.commits(),
                analysis.commitFiles());
        historyDb.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle)
                        VALUES (:path, :path, 'java', 'source', 'historical')""")
                .bind("path", "src/main/java/example/Deleted.java")
                .execute());

        var tools = new QuillTools();
        JsonNode currentOnly = JSON.readTree(tools.getHotspots(historyDb, 10, null, false));
        assertFalse(currentOnly.path("hotspots").toString().contains("Deleted.java"));
        JsonNode overview = JSON.readTree(tools.getOverview(historyDb));
        assertFalse(overview.path("git_summary").path("top_hotspots").toString()
                .contains("Deleted.java"));

        JsonNode withHistory = JSON.readTree(tools.getHotspots(historyDb, 10, null, true));
        assertTrue(withHistory.path("hotspots").toString().contains("Deleted.java"));
        assertTrue(withHistory.path("hotspots").toString().contains("historical"));

        JsonNode history = JSON.readTree(tools.getFileHistory(historyDb,
                "src/main/java/example/Deleted.java", 10));
        assertEquals("historical", history.path("lifecycle").asText());
        assertEquals(2, history.path("commits").size());

        JsonNode missingClass = JSON.readTree(tools.getRisk(historyDb, "example.Deleted"));
        assertEquals("Class not found", missingClass.path("error").asText());
        assertTrue(missingClass.path("candidates").toString().contains("Deleted.java"));
        assertTrue(missingClass.path("candidates").toString().contains("historical"));
        assertEquals(1, missingClass.path("candidates").size());
        assertTrue(missingClass.path("_meta").isObject());
    }

    @Test
    void searchClassesIncludesOriginAndLifecycle() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools().searchClasses(jdbi, "OrderService", 10));

        assertEquals("source", result.path("classes").get(0).path("origin").asText());
        assertEquals("current", result.path("classes").get(0).path("lifecycle").asText());
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
            head = git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setSign(false).call().getName();
        }
        WorktreeInspector.Snapshot indexed = WorktreeInspector.inspect(repository);
        Jdbi docsDb = QuillDatabase.create(tempDir.resolve("docs.db"));
        IndexWriter.write(docsDb, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-09-09T00:00:00Z", "last_commit", head,
                        "project_root", repository.toString(),
                        "indexed_structure_fingerprint", indexed.structuralFingerprint(),
                        "compiled_before_index", "false"));

        Files.writeString(repository.resolve("README.md"), "documentation only\n");
        JsonNode meta = JSON.readTree(new QuillTools().getOverview(docsDb)).path("_meta");

        assertTrue(meta.path("worktree_dirty").asBoolean());
        assertEquals(1, meta.path("worktree_changed_files").asInt());
        assertEquals(0, meta.path("structural_changed_files").asInt());
        assertFalse(meta.path("structure_stale").asBoolean());
        assertTrue(meta.path("stale_reasons").isEmpty());
    }

    @Test
    void responsesExposeDirtyWorktreeFreshnessAndResourceOverlay() throws Exception {
        Path repository = tempDir.resolve("dirty-repository");
        Path service = repository.resolve(
                "src/main/resources/META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "example.FirstProcessor\nexample.SecondProcessor\n");

        GitAnalyzer.GitAnalysisResult analysis;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
            analysis = GitAnalyzer.analyze(repository, 10, Map.of());
        }
        WorktreeInspector.Snapshot indexed = WorktreeInspector.inspect(repository);
        Jdbi dirtyDb = QuillDatabase.create(tempDir.resolve("dirty.db"));
        IndexWriter.write(dirtyDb, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-09-08T00:00:00Z",
                        "last_commit", analysis.headHash(),
                        "project_root", repository.toString(),
                        "indexed_structure_fingerprint", indexed.structuralFingerprint(),
                        "compiled_before_index", "false"));
        IndexWriter.writeGitData(dirtyDb, analysis.fileStats(), analysis.commits(),
                analysis.commitFiles());

        Files.writeString(service, "example.SecondProcessor\nexample.FirstProcessor\n");

        var tools = new QuillTools();
        JsonNode overview = JSON.readTree(tools.getOverview(dirtyDb));
        JsonNode meta = overview.path("_meta");
        assertEquals(analysis.headHash(), meta.path("indexed_commit").asText());
        assertEquals(analysis.headHash(), meta.path("current_commit").asText());
        assertTrue(meta.path("worktree_dirty").asBoolean());
        assertTrue(meta.path("structure_stale").asBoolean());
        assertFalse(meta.path("commit_stale").asBoolean());
        assertTrue(meta.path("stale_reasons").toString()
                .contains("worktree_changed_after_index"));
        assertTrue(meta.path("stale_reasons").toString()
                .contains("dirty_worktree_not_compiled"));

        JsonNode hotspots = JSON.readTree(tools.getHotspots(dirtyDb, 10, null, false));
        assertTrue(hotspots.path("worktree_changes").toString()
                .contains("javax.annotation.processing.Processor"));
        assertTrue(hotspots.path("worktree_changes").toString().contains("modified"));

        JsonNode trackedRisk = JSON.readTree(tools.getRisk(dirtyDb,
                "src/main/resources/META-INF/services/javax.annotation.processing.Processor"));
        assertEquals("file", trackedRisk.path("target_type").asText());
        assertEquals("service_descriptor", trackedRisk.path("kind").asText());
        assertEquals("modified", trackedRisk.path("worktree_status").asText());
        assertTrue(trackedRisk.path("risk_score").asDouble() >= 6.0);
        assertTrue(trackedRisk.path("signals").has("file_criticality"));
        assertTrue(trackedRisk.path("signals").has("git_churn"));
        assertFalse(trackedRisk.path("signals").has("fan_in"));

        Path untrackedService = repository.resolve(
                "src/main/resources/META-INF/services/example.NewProvider");
        Files.writeString(untrackedService, "example.Provider\n");
        WorktreeSnapshotCache.shared().invalidate(repository);
        JsonNode untrackedRisk = JSON.readTree(tools.getRisk(dirtyDb,
                "src/main/resources/META-INF/services/example.NewProvider"));
        assertEquals("file", untrackedRisk.path("target_type").asText());
        assertEquals("untracked", untrackedRisk.path("worktree_status").asText());
        assertEquals("CRITICAL", untrackedRisk.path("risk_level").asText());
        assertEquals(10.0, untrackedRisk.path("risk_score").asDouble());
        assertTrue(untrackedRisk.path("signals").path("git").path("note").asText()
                .contains("No Git history"));

        Files.writeString(repository.resolve("pom.xml"), "<project/>\n");
        WorktreeSnapshotCache.shared().invalidate(repository);
        JsonNode buildRisk = JSON.readTree(tools.getRisk(dirtyDb, "./pom.xml"));
        assertEquals("file", buildRisk.path("target_type").asText());
        assertEquals("build_configuration", buildRisk.path("kind").asText());
        assertEquals("CRITICAL", buildRisk.path("risk_level").asText());
        assertEquals("untracked", buildRisk.path("worktree_status").asText());
    }

    @Test
    void gitToolsReturnErrorWithoutGitData() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit.db"));
        IndexWriter.write(noGitJdbi, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "unknown"));

        var tools = new QuillTools();
        String result = tools.getHotspots(noGitJdbi, 10, null);
        assertTrue(result.contains("No git data available"));
        assertTrue(result.contains("git init"));

        result = tools.getFileHistory(noGitJdbi, "SomeClass", 10);
        assertTrue(result.contains("No git data available"));
    }
}
