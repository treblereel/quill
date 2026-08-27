package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.JokerDatabase;

class InitCommandTest {

    @TempDir Path tempDir;

    @Test
    void initCreatesIndexDb() throws Exception {
        // Set up a fake project with compiled test fixture classes
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.createFile(projectRoot.resolve("pom.xml"));
        Path classesDir = projectRoot.resolve("target/classes/org/treblereel/mcp/fixture");
        Files.createDirectories(classesDir);

        // Copy fixture .class files to the fake project
        copyFixtureClasses(classesDir);

        InitCommand cmd = new InitCommand();
        cmd.projectPath = projectRoot;
        cmd.run();

        Path dbPath = projectRoot.resolve(".joker/index.db");
        assertTrue(Files.exists(dbPath), "index.db should be created");

        try (Connection conn = JokerDatabase.open(dbPath)) {
            var classes = IndexReader.findAllClasses(conn);
            assertFalse(classes.isEmpty(), "Should have indexed classes");

            var beans = IndexReader.findBeans(conn, null);
            assertFalse(beans.isEmpty(), "Should have indexed beans");

            var meta = IndexReader.getMetadata(conn);
            assertNotNull(meta.get("indexed_at"));
        }
    }

    @Test
    void beanClassIdsReferenceValidClasses() throws Exception {
        // Verifies the classId mapping between BeanResolver and SQLite is correct
        Path projectRoot = tempDir.resolve("project2");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.createFile(projectRoot.resolve("pom.xml"));
        Path classesDir = projectRoot.resolve("target/classes/org/treblereel/mcp/fixture");
        Files.createDirectories(classesDir);

        copyFixtureClasses(classesDir);

        InitCommand cmd = new InitCommand();
        cmd.projectPath = projectRoot;
        cmd.run();

        Path dbPath = projectRoot.resolve(".joker/index.db");
        try (Connection conn = JokerDatabase.open(dbPath)) {
            var beans = IndexReader.findBeans(conn, null);
            var classes = IndexReader.findAllClasses(conn);

            // Every bean's classId must match a valid class row
            var classIds = classes.stream().map(c -> c.id()).collect(java.util.stream.Collectors.toSet());
            for (var bean : beans) {
                assertTrue(classIds.contains(bean.classId()),
                        "Bean classId " + bean.classId() + " for " + bean.kind()
                                + " should reference a valid class");
            }
        }
    }

    private void copyFixtureClasses(Path targetDir) throws Exception {
        for (String name : new String[]{
                "PaymentService", "StripePaymentService", "MockPaymentService",
                "OrderService", "OrderDTO", "Premium"}) {
            String resource = "org/treblereel/mcp/fixture/" + name + ".class";
            try (var is = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(is, "Fixture class not found: " + resource);
                Files.copy(is, targetDir.resolve(name + ".class"));
            }
        }
    }
}
