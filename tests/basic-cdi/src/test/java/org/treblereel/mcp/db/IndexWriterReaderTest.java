package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.*;

class IndexWriterReaderTest {

    @TempDir Path tempDir;
    Connection conn;

    @BeforeEach
    void setUp() {
        conn = QuillDatabase.create(tempDir.resolve("test.db"));
    }

    @Test
    void writeAndReadClasses() {
        var classes = List.of(
            new ClassRecord(0, "org.acme.OrderService", "CLASS", "java.lang.Object",
                    List.of(), "src/main/java/org/acme/OrderService.java", 10, true, 500),
            new ClassRecord(0, "org.acme.OrderDTO", "CLASS", "java.lang.Object",
                    List.of("Serializable"), "src/main/java/org/acme/OrderDTO.java", 5, false, 200)
        );
        IndexWriter.write(conn, classes, List.of(), List.of(), List.of(), Map.of("indexed_at", "2026-08-26"));

        var result = IndexReader.findAllClasses(conn);
        assertEquals(2, result.size());
        assertEquals("org.acme.OrderService", result.get(0).className());
        assertTrue(result.get(0).isBean());
        assertFalse(result.get(1).isBean());
    }

    @Test
    void writeAndReadBeans() {
        var classes = List.of(
            new ClassRecord(0, "org.acme.OrderService", "CLASS", "java.lang.Object",
                    List.of(), "src/main/java/org/acme/OrderService.java", 10, true, 500)
        );
        var beans = List.of(
            new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                    List.of(), false, null, null, null, null, List.of("OrderService", "Object"))
        );
        IndexWriter.write(conn, classes, beans, List.of(), List.of(), Map.of());

        var result = IndexReader.findBeans(conn, null);
        assertEquals(1, result.size());
        assertEquals("@ApplicationScoped", result.getFirst().scope());
    }

    @Test
    void readMetadata() {
        IndexWriter.write(conn, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26", "last_commit", "abc123"));

        var meta = IndexReader.getMetadata(conn);
        assertEquals("2026-08-26", meta.get("indexed_at"));
        assertEquals("abc123", meta.get("last_commit"));
    }
}
