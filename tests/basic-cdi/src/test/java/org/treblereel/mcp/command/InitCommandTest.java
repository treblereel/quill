package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import java.util.stream.Collectors;
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
    void initIndexesBasicCdiProject() throws Exception {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();

        Path dbPath = QUILL_DIR.resolve("index.db");
        assertTrue(Files.exists(dbPath), "index.db should be created");

        try (Connection conn = QuillDatabase.open(dbPath)) {
            var classes = IndexReader.findAllClasses(conn);
            assertEquals(6, classes.size(), "Should index all 6 fixture classes");

            var beans = IndexReader.findBeans(conn, null);
            assertEquals(3, beans.size(), "Should find 3 CDI beans (Stripe, Mock, Order)");

            var ips = IndexReader.findInjectionPoints(conn, beans.stream()
                    .filter(b -> IndexReader.findClassById(conn, b.classId())
                            .map(c -> c.className().endsWith("OrderService")).orElse(false))
                    .findFirst().orElseThrow().id());
            assertFalse(ips.isEmpty(), "OrderService should have injection points");
            assertTrue(ips.stream().anyMatch(ip -> ip.targetType().contains("PaymentService")),
                    "OrderService should inject PaymentService");

            var meta = IndexReader.getMetadata(conn);
            assertNotNull(meta.get("indexed_at"));
            assertEquals(PROJECT_ROOT.toString(), meta.get("project_root"));
        }
    }

    @Test
    void beanClassIdsReferenceValidClasses() throws Exception {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();

        Path dbPath = QUILL_DIR.resolve("index.db");
        try (Connection conn = QuillDatabase.open(dbPath)) {
            var beans = IndexReader.findBeans(conn, null);
            var classIds = IndexReader.findAllClasses(conn).stream()
                    .map(c -> c.id()).collect(Collectors.toSet());

            for (var bean : beans) {
                assertTrue(classIds.contains(bean.classId()),
                        "Bean classId " + bean.classId() + " should reference a valid class");
            }
        }
    }

    @Test
    void reindexProducesConsistentIds() throws Exception {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();
        cmd.run();

        Path dbPath = QUILL_DIR.resolve("index.db");
        try (Connection conn = QuillDatabase.open(dbPath)) {
            var classes = IndexReader.findAllClasses(conn);
            assertEquals(6, classes.size(), "Re-index should not duplicate classes");

            var classIds = classes.stream().map(c -> c.id()).collect(Collectors.toSet());
            for (var bean : IndexReader.findBeans(conn, null)) {
                assertTrue(classIds.contains(bean.classId()),
                        "After re-index, bean classId " + bean.classId() + " must be valid");
            }
        }
    }
}
