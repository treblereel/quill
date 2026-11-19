package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexGenerationTest {
    @TempDir Path root;

    private Path database(String name, String marker) {
        Path path = root.resolve(name);
        IndexWriter.writeIncremental(QuillDatabase.create(path), new IndexFixture()
                .metadata(Map.of("marker", marker)).snapshot());
        return path;
    }

    private String marker(Jdbi database) {
        return database.withHandle(handle -> handle.createQuery("SELECT value FROM metadata WHERE key='marker'")
                .mapTo(String.class).one());
    }

    @Test
    void closingReleasesIdleReadersAndLateReadersRemainDisposable() throws Exception {
        Path path = database("index.db", "old");
        try (var generation = IndexGeneration.open(path)) {
            Jdbi database = generation.jdbi();
            assertEquals("old", marker(database));
            var graph = generation.readWithGraph(context -> context.graph());
            assertSame(graph, generation.readWithGraph(context -> context.graph()));
            generation.close();
            generation.close();
            assertFalse(generation.matches(path));
            assertEquals("old", marker(database));
            assertEquals("old", marker(database));
            assertNotSame(graph, generation.readWithGraph(context -> context.graph()));
            assertNotSame(generation.readWithGraph(context -> context.graph()), generation.readWithGraph(context -> context.graph()));
        }
    }

    @Test
    void replacedPathNeverSuppliesNewRowsToAnOldGeneration() throws Exception {
        Path path = database("index.db", "old");
        Path replacement = database("next.db", "new");
        try (var generation = IndexGeneration.open(path); var active = generation.jdbi().open()) {
            var lease = active.getConnection();
            assertTrue(generation.matches(path));
            Files.move(replacement, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            assertFalse(generation.matches(path));
            // The generation's initial idle reader is held above, forcing a new physical open.
            assertThrows(RuntimeException.class, () -> marker(generation.jdbi()));
            generation.close();
            assertFalse(lease.isClosed());
            assertEquals("old", active.createQuery("SELECT value FROM metadata WHERE key='marker'")
                    .mapTo(String.class).one());
            try (var next = IndexGeneration.open(path)) {
                assertEquals("new", marker(next.jdbi()));
                assertThrows(RuntimeException.class, () -> marker(generation.jdbi()));
            }
            active.close();
            assertTrue(lease.isClosed());
        }
    }

    @Test
    void openingMissingOrIncompatibleIndexesDoesNotCreateOrRewriteThem() throws Exception {
        Path missing = root.resolve("missing.db");
        assertThrows(IllegalStateException.class, () -> IndexGeneration.open(missing));
        assertFalse(Files.exists(missing));
        Path path = database("old.db", "preserved");
        Jdbi.create("jdbc:sqlite:" + path).useHandle(handle -> handle.execute("PRAGMA user_version=0"));
        assertThrows(IndexSchema.SchemaVersionException.class, () -> IndexGeneration.open(path));
        assertEquals(0, IndexSchema.inspectVersion(path));
        assertEquals("preserved", marker(Jdbi.create("jdbc:sqlite:" + path)));
    }
}
