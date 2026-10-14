package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;

class ReadQueryContextTest {
    @TempDir Path root;
    @Test void nestedReadersShareOneHandleAndMemoizeOnlyInsideTheResponse() throws Exception {
        var db=QuillDatabase.create(root.resolve("db"));db.useHandle(h->h.execute("INSERT INTO metadata VALUES('marker','first')"));
        var output=new ByteArrayOutputStream();PrintStream original=System.err;
        try {
            System.setErr(new PrintStream(output));
            try(var metrics=new QueryMetrics()) {
                ReadQueryContext.read(db,()->{
                    assertEquals("first",IndexReader.getMetadata(db).get("marker"));
                    assertEquals("first",ReadQueryContext.read(db,()->IndexReader.getMetadata(db).get("marker")));
                    return null;
                });
            }
        } finally {System.setErr(original);}
        var json=new ObjectMapper().readTree(output.toString());assertEquals(1,json.path("connection_opens").asInt());assertEquals(1,json.path("sql_statements").asInt());
        db.useHandle(h->h.execute("UPDATE metadata SET value='second'"));
        assertEquals("second",ReadQueryContext.read(db,()->IndexReader.getMetadata(db).get("marker")));
    }
    @Test void errorsAndNestedDifferentDatabasesRestoreTheCorrectScope() {
        var a=QuillDatabase.create(root.resolve("a"));var b=QuillDatabase.create(root.resolve("b"));
        a.useHandle(h->h.execute("INSERT INTO metadata VALUES('marker','a')"));b.useHandle(h->h.execute("INSERT INTO metadata VALUES('marker','b')"));
        assertEquals("a",ReadQueryContext.read(a,()->{
            assertEquals("a",IndexReader.getMetadata(a).get("marker"));
            assertThrows(IllegalStateException.class,()->ReadQueryContext.read(b,()->{
                assertEquals("b",IndexReader.getMetadata(b).get("marker"));throw new IllegalStateException("expected");
            }));
            return IndexReader.getMetadata(a).get("marker");
        }));
        a.useHandle(h->h.execute("UPDATE metadata SET value='new'"));
        assertEquals("new",IndexReader.getMetadata(a).get("marker"));
    }
}
