package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
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
                new InjectionPointRecord(0, 1, "FIELD", "PaymentService",
                        List.of("@Default"), "paymentService", 2, false)
        );
        var deps = List.of(
                new DependencyRecord(1, 3, "CDI_INJECT", 1),
                new DependencyRecord(3, 4, "CDI_INJECT", null)
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
                Optional.empty(), Optional.empty(), Optional.empty());

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
    void getInjectionPointsShowsResolution() {
        var tools = new QuillTools();
        String result = tools.getInjectionPoints(jdbi, "OrderService");
        assertTrue(result.contains("PaymentService"));
        assertTrue(result.contains("paymentService"));
        assertTrue(result.contains("unique") || result.contains("resolved_to"));
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

        JsonNode problems = root.get("problems");
        assertNotNull(problems);
        assertTrue(problems.has("unsatisfied_count"));
        assertTrue(problems.has("ambiguous_count"));

        JsonNode gitSummary = root.get("git_summary");
        assertNotNull(gitSummary);
        assertFalse(gitSummary.isNull());
        assertEquals(3, gitSummary.get("total_commits_indexed").asInt());
        assertTrue(gitSummary.get("top_hotspots").size() >= 1);

        assertTrue(root.has("_meta"));
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
