package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

class InitCommandTest {

    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void initIndexesBasicCdiProject() {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.indexOnly = true;
        cmd.run();

        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        assertNotNull(dbPath, "index db should be created");

        Jdbi jdbi = QuillDatabase.open(dbPath);
        var classes = IndexReader.findAllClasses(jdbi);
        assertEquals(8, classes.size(), "Should index all 8 fixture classes");

        var beans = IndexReader.findBeans(jdbi, null);
        assertTrue(beans.size() >= 3, "Should find at least 3 CDI beans (Stripe, Mock, Order), plus non-bean classes");

        var ips = IndexReader.findInjectionPoints(jdbi, beans.stream()
                .filter(b -> IndexReader.findClassById(jdbi, b.classId())
                        .map(c -> c.className().endsWith("OrderService")).orElse(false))
                .findFirst().orElseThrow().id());
        assertFalse(ips.isEmpty(), "OrderService should have injection points");
        assertTrue(ips.stream().anyMatch(ip -> ip.targetType().contains("PaymentService")),
                "OrderService should inject PaymentService");

        var meta = IndexReader.getMetadata(jdbi);
        assertNotNull(meta.get("indexed_at"));
        assertEquals(PROJECT_ROOT.toString(), meta.get("project_root"));
        assertNotNull(meta.get("dependency_index"));
        assertNotNull(meta.get("dependency_index_detail"));
    }

    @Test
    void detailedInitializationReportsOrderedPhaseTimings() {
        var result = ProjectInitializer.initializeDetailed(PROJECT_ROOT, true);

        assertTrue(result.successful(), result.diagnostic());
        assertEquals(List.of("lock_wait", "class_discovery", "worktree_snapshot",
                        "index_setup", "application_scan", "dependency_classpath",
                        "dependency_cache_read", "dependency_jar_index",
                        "dependency_cache_write", "dependency_total", "analysis_setup",
                        "bean_resolution", "bytecode_analysis", "git_analysis",
                        "file_inventory", "index_metadata", "database_schema",
                        "database_inserts", "database_indexes",
                        "database_transaction_overhead",
                        "publication_validation", "atomic_publication", "activation"),
                List.copyOf(result.phaseMillis().keySet()));
        assertTrue(result.phaseMillis().values().stream().allMatch(value -> value >= 0));
        assertTrue(result.phaseMillis().values().stream()
                .allMatch(value -> value <= result.elapsedMillis()));
        assertTrue(result.phaseMillis().get("dependency_total")
                >= result.phaseMillis().get("dependency_cache_read"));
        assertTrue(result.timingsDiagnostic().contains("total=" + result.elapsedMillis() + "ms"));
    }

    @Test
    void beanClassIdsReferenceValidClasses() {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.indexOnly = true;
        cmd.run();

        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        Jdbi jdbi = QuillDatabase.open(dbPath);
        var beans = IndexReader.findBeans(jdbi, null);
        var classIds = IndexReader.findAllClasses(jdbi).stream()
                .map(c -> c.id()).collect(Collectors.toSet());

        for (var bean : beans) {
            assertTrue(classIds.contains(bean.classId()),
                    "Bean classId " + bean.classId() + " should reference a valid class");
        }
    }

    @Test
    void isBeanMatchesArcResolution() {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.indexOnly = true;
        cmd.run();

        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        Jdbi jdbi = QuillDatabase.open(dbPath);
        var classes = IndexReader.findAllClasses(jdbi);
        var beans = IndexReader.findBeans(jdbi, null);
        var beanClassIds = beans.stream().map(b -> b.classId()).collect(Collectors.toSet());

        for (var cls : classes) {
            boolean isBeanInTable = cls.isBean();
            boolean hasBeanRecord = beanClassIds.contains(cls.id());
            assertEquals(hasBeanRecord, isBeanInTable,
                    "classes.is_bean for " + cls.className() + " should match presence in beans table");
        }
    }

    @Test
    void reindexProducesConsistentIds() {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.indexOnly = true;
        cmd.run();
        cmd.run();

        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        Jdbi jdbi = QuillDatabase.open(dbPath);
        var classes = IndexReader.findAllClasses(jdbi);
        assertEquals(8, classes.size(), "Re-index should not duplicate classes");

        var classIds = classes.stream().map(c -> c.id()).collect(Collectors.toSet());
        for (var bean : IndexReader.findBeans(jdbi, null)) {
            assertTrue(classIds.contains(bean.classId()),
                    "After re-index, bean classId " + bean.classId() + " must be valid");
        }
    }
}
