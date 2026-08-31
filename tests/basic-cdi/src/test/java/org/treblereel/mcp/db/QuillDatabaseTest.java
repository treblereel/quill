package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuillDatabaseTest {

    @TempDir Path tempDir;

    @Test
    void createSchemaCreatesAllTables() throws Exception {
        Path dbPath = tempDir.resolve(".quill/index.db");
        try (Connection conn = QuillDatabase.create(dbPath)) {
            for (String table : new String[]{"classes", "beans", "injection_points", "dependencies", "metadata"}) {
                ResultSet rs = conn.getMetaData().getTables(null, null, table, null);
                assertTrue(rs.next(), "Table " + table + " should exist");
            }
        }
    }

    @Test
    void openExistingDatabase() throws Exception {
        Path dbPath = tempDir.resolve(".quill/index.db");
        QuillDatabase.create(dbPath).close();
        try (Connection conn = QuillDatabase.open(dbPath)) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void openNonexistentThrows() {
        Path dbPath = tempDir.resolve("nonexistent/index.db");
        assertThrows(IllegalStateException.class, () -> QuillDatabase.open(dbPath));
    }
}
