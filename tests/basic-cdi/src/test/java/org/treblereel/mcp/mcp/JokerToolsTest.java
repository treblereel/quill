package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.JokerDatabase;
import org.treblereel.mcp.model.*;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;

class JokerToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;
    Connection conn;

    @BeforeEach
    void setUp() {
        conn = JokerDatabase.create(tempDir.resolve("test.db"));
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
        IndexWriter.write(conn, classes, beans, ips, deps,
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "abc1234"));

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
        IndexWriter.writeGitData(conn, gitStats, gitCommits, gitFiles);
    }

    @Test
    void getBeansReturnsAllBeans() {
        var tools = new JokerTools();
        String result = tools.getBeans(conn, null, null, null, null, null);
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("\"total\":3"));
    }

    @Test
    void getBeansFiltersByScope() {
        var tools = new JokerTools();
        String result = tools.getBeans(conn, null, "@ApplicationScoped", null, null, null);
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("AuditService"));
    }

    @Test
    void getBeansFiltersByProfile() {
        var tools = new JokerTools();
        String result = tools.getBeans(conn, null, null, null, "dev", null);
        assertTrue(result.contains("AuditService"));
        assertFalse(result.contains("OrderService"));
        assertTrue(result.contains("\"total\":1"));
    }

    @Test
    void getBeansFiltersByQualifier() {
        var tools = new JokerTools();
        String result = tools.getBeans(conn, null, null, null, null, "@Premium");
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("OrderService"));
        assertFalse(result.contains("AuditService"));
        assertTrue(result.contains("\"total\":1"));
    }

    @Test
    void getDependenciesShowsOutbound() {
        var tools = new JokerTools();
        String result = tools.getDependencies(conn, "OrderService", "outbound", 1);
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("CDI_INJECT"));
    }

    @Test
    void getDependenciesDepth2ExpandsTransitive() throws Exception {
        var tools = new JokerTools();
        String result = tools.getDependencies(conn, "OrderService", "outbound", 2);
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
        var tools = new JokerTools();
        String result = tools.getDependencies(conn, "OrderService", "outbound", 1);
        JsonNode root = JSON.readTree(result);
        JsonNode dependsOn = root.get("depends_on");
        assertNull(dependsOn.get(0).get("depends_on"),
                "depth=1 should not expand nested dependencies");
    }

    @Test
    void getInjectionPointsShowsResolution() {
        var tools = new JokerTools();
        String result = tools.getInjectionPoints(conn, "OrderService");
        assertTrue(result.contains("PaymentService"));
        assertTrue(result.contains("paymentService"));
        assertTrue(result.contains("unique") || result.contains("resolved_to"));
    }

    @Test
    void getHotspotsReturnsMostChanged() throws Exception {
        var tools = new JokerTools();
        String result = tools.getHotspots(conn, 10, null);
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
        var tools = new JokerTools();
        String result = tools.getHotspots(conn, 10, "2026-08-27");
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        for (JsonNode h : hotspots) {
            assertTrue(h.get("last_modified").asText().compareTo("2026-08-27") >= 0);
        }
    }

    @Test
    void getFileHistoryReturnsCommits() throws Exception {
        var tools = new JokerTools();
        String result = tools.getFileHistory(conn, "StripePaymentService", 10);
        JsonNode root = JSON.readTree(result);
        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        JsonNode commits = root.get("commits");
        assertNotNull(commits);
        assertEquals(3, commits.size());
        assertEquals("aaa1111", commits.get(0).get("hash").asText());
    }

    @Test
    void getCoChangesFindsCorrelatedFiles() throws Exception {
        var tools = new JokerTools();
        String result = tools.getCoChanges(conn, "OrderService", 10);
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
        var tools = new JokerTools();
        String result = tools.getRecentChanges(conn, 5);
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
    void gitToolsReturnErrorWithoutGitData() throws Exception {
        Connection noGitConn = JokerDatabase.create(tempDir.resolve("nogit.db"));
        IndexWriter.write(noGitConn, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "unknown"));

        var tools = new JokerTools();
        String result = tools.getHotspots(noGitConn, 10, null);
        assertTrue(result.contains("No git data available"));
        assertTrue(result.contains("git init"));

        result = tools.getFileHistory(noGitConn, "SomeClass", 10);
        assertTrue(result.contains("No git data available"));

        noGitConn.close();
    }
}
