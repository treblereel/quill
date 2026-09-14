package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuillDatabaseTest {

    @TempDir Path tempDir;

    @Test
    void createSchemaCreatesAllTables() {
        Path dbPath = tempDir.resolve(".quill/index.db");
        Jdbi jdbi = QuillDatabase.create(dbPath);
        jdbi.useHandle(h -> {
            for (String table : new String[]{"classes", "beans", "injection_points", "dependencies", "metadata"}) {
                boolean exists = h.createQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name = :table")
                        .bind("table", table)
                        .mapTo(String.class)
                        .findFirst()
                        .isPresent();
                assertTrue(exists, "Table " + table + " should exist");
            }
        });
    }

    @Test
    void openExistingDatabase() {
        Path dbPath = tempDir.resolve(".quill/index.db");
        QuillDatabase.create(dbPath);
        Jdbi jdbi = QuillDatabase.open(dbPath);
        assertNotNull(jdbi);
        jdbi.useHandle(h -> assertNotNull(h.getConnection()));
    }

    @Test
    void openExistingDatabaseIsReadOnly() {
        Path dbPath = tempDir.resolve(".quill/read-only.db");
        QuillDatabase.create(dbPath);

        Jdbi jdbi = QuillDatabase.open(dbPath);

        assertThrows(Exception.class, () ->
                jdbi.useHandle(h -> h.execute("CREATE TABLE must_not_be_created (id INTEGER)")));
    }

    @Test
    void openNonexistentThrows() {
        Path dbPath = tempDir.resolve("nonexistent/index.db");
        assertThrows(IllegalStateException.class, () -> QuillDatabase.open(dbPath));
    }

    @Test
    void createSetsSchemaVersion() {
        Path dbPath = tempDir.resolve(".quill/versioned.db");
        Jdbi jdbi = QuillDatabase.create(dbPath);
        int version = jdbi.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
        assertEquals(QuillDatabase.SCHEMA_VERSION, version);
    }

    @Test
    void bulkLoadSchemaDefersSecondaryIndexes() {
        Path dbPath = tempDir.resolve(".quill/bulk.db");
        Jdbi jdbi = QuillDatabase.createForBulkLoad(dbPath);

        assertFalse(hasIndex(jdbi, "idx_classes_name"));
        IndexWriter.WriteTimings timings = IndexWriter.writeFresh(
                jdbi, List.of(), List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertTrue(hasIndex(jdbi, "idx_classes_name"));
        assertTrue(timings.insertsMillis() >= 0);
        assertTrue(timings.indexesMillis() >= 0);
        assertTrue(timings.transactionOverheadMillis() >= 0);
    }

    @Test
    void bulkLoadRequiresNewStagingPath() {
        Path dbPath = tempDir.resolve(".quill/existing.db");
        QuillDatabase.create(dbPath);

        assertThrows(IllegalArgumentException.class,
                () -> QuillDatabase.createForBulkLoad(dbPath));
    }

    @Test
    void createEnablesForeignKeys() {
        Path dbPath = tempDir.resolve(".quill/fk.db");
        Jdbi jdbi = QuillDatabase.create(dbPath);
        int fk = jdbi.withHandle(h ->
                h.createQuery("PRAGMA foreign_keys").mapTo(Integer.class).one());
        assertEquals(1, fk, "foreign_keys should be ON");
    }

    @Test
    void openEnablesForeignKeys() {
        Path dbPath = tempDir.resolve(".quill/fk-open.db");
        QuillDatabase.create(dbPath);
        Jdbi jdbi = QuillDatabase.open(dbPath);
        int fk = jdbi.withHandle(h ->
                h.createQuery("PRAGMA foreign_keys").mapTo(Integer.class).one());
        assertEquals(1, fk, "foreign_keys should be ON after open");
    }

    @Test
    void openRejectsNewerSchema() {
        Path dbPath = tempDir.resolve(".quill/future.db");
        QuillDatabase.create(dbPath);
        Jdbi raw = Jdbi.create("jdbc:sqlite:" + dbPath);
        raw.useHandle(h -> h.execute("PRAGMA user_version = 999"));

        var ex = assertThrows(QuillDatabase.SchemaVersionException.class,
                () -> QuillDatabase.open(dbPath));
        assertTrue(ex.getMessage().contains("newer Quill"));
    }

    @Test
    void openRejectsOlderSchema() {
        Path dbPath = tempDir.resolve(".quill/old.db");
        QuillDatabase.create(dbPath);
        Jdbi raw = Jdbi.create("jdbc:sqlite:" + dbPath);
        raw.useHandle(h -> h.execute("PRAGMA user_version = 0"));

        var ex = assertThrows(QuillDatabase.SchemaVersionException.class,
                () -> QuillDatabase.open(dbPath));
        assertTrue(ex.getMessage().contains("outdated schema"));
    }

    @Test
    void createRebuildsOlderSchemaInsteadOfMaskingIt() {
        Path dbPath = tempDir.resolve(".quill/old-rebuild.db");
        QuillDatabase.create(dbPath);
        Jdbi raw = Jdbi.create("jdbc:sqlite:" + dbPath);
        raw.useHandle(h -> {
            h.execute("CREATE TABLE legacy_marker (value TEXT)");
            h.execute("INSERT INTO legacy_marker(value) VALUES ('stale')");
            h.execute("PRAGMA user_version = 0");
        });

        Jdbi rebuilt = QuillDatabase.create(dbPath);
        int version = rebuilt.withHandle(h ->
                h.createQuery("PRAGMA user_version").mapTo(Integer.class).one());
        boolean legacyTableExists = rebuilt.withHandle(h -> h.createQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='legacy_marker'")
                .mapTo(Integer.class).one() > 0);

        assertEquals(QuillDatabase.SCHEMA_VERSION, version);
        assertFalse(legacyTableExists, "Outdated index must be recreated, not upgraded in place");
    }

    @Test
    void createRejectsNewerSchema() {
        Path dbPath = tempDir.resolve(".quill/future-create.db");
        QuillDatabase.create(dbPath);
        Jdbi.create("jdbc:sqlite:" + dbPath)
                .useHandle(h -> h.execute("PRAGMA user_version = 999"));

        assertThrows(QuillDatabase.SchemaVersionException.class,
                () -> QuillDatabase.create(dbPath));
    }

    private static boolean hasIndex(Jdbi jdbi, String name) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='index' AND name=:name")
                .bind("name", name)
                .mapTo(Integer.class)
                .one() > 0);
    }
}
