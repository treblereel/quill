package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JokerDatabaseTest {

    @TempDir Path tempDir;

    @Test
    void createSchemaCreatesAllTables() throws Exception {
        Path dbPath = tempDir.resolve(".joker/index.db");
        try (Connection conn = JokerDatabase.create(dbPath)) {
            for (String table : new String[]{"classes", "beans", "injection_points", "dependencies", "metadata"}) {
                ResultSet rs = conn.getMetaData().getTables(null, null, table, null);
                assertTrue(rs.next(), "Table " + table + " should exist");
            }
        }
    }

    @Test
    void openExistingDatabase() throws Exception {
        Path dbPath = tempDir.resolve(".joker/index.db");
        JokerDatabase.create(dbPath).close();
        try (Connection conn = JokerDatabase.open(dbPath)) {
            assertNotNull(conn);
            assertFalse(conn.isClosed());
        }
    }

    @Test
    void openNonexistentThrows() {
        Path dbPath = tempDir.resolve("nonexistent/index.db");
        assertThrows(IllegalStateException.class, () -> JokerDatabase.open(dbPath));
    }
}
