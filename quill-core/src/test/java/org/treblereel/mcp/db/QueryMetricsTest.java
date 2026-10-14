package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.SourceFileHashes;

class QueryMetricsTest {
    @TempDir Path root;
    @Test void countsActualWorkAndClosesOnceOnFailureWithoutLeakingScope() throws Exception {
        var output=new ByteArrayOutputStream();PrintStream original=System.err;
        try {
            System.setErr(new PrintStream(output));
            QueryMetrics scope=new QueryMetrics();
            try(scope) {
                assertNull(QueryMetrics.start());
                QueryMetrics.connectionOpened();QueryMetrics.statementExecuted();
                Path source=root.resolve("sample.java");Files.writeString(source,"abc");SourceFileHashes.hash(source);
                assertThrows(IllegalStateException.class,()->QueryMetrics.measure("failure",()->{throw new IllegalStateException("private detail");}));
            }
            scope.close();QueryMetrics.connectionOpened();
        } finally {System.setErr(original);}
        String text=output.toString();assertEquals(1,text.lines().count());assertFalse(text.contains("private detail"));
        var json=new ObjectMapper().readTree(text);
        assertEquals(1,json.path("connection_opens").asInt());assertEquals(1,json.path("sql_statements").asInt());
        assertEquals(1,json.path("source_files_read").asInt());assertEquals(3,json.path("source_bytes_read").asInt());
        assertTrue(json.path("stages_ms").has("failure"));
        assertEquals("ok",QueryMetrics.measure("disabled",()->"ok"));
    }
    @Test void jdbiInstrumentationCountsNestedSqlAndConnectionOpenSeparately() throws Exception {
        var db=QuillDatabase.create(root.resolve("db.sqlite"));
        var output=new ByteArrayOutputStream();PrintStream original=System.err;
        try {
            System.setErr(new PrintStream(output));
            try(var scope=new QueryMetrics()) {
                db.useHandle(h->{h.createQuery("SELECT 1").mapTo(Integer.class).one();db.withHandle(nested->nested.createQuery("SELECT 2").mapTo(Integer.class).one());});
            }
        } finally {System.setErr(original);}
        var json=new ObjectMapper().readTree(output.toString());
        assertEquals(1,json.path("connection_opens").asInt());assertEquals(2,json.path("sql_statements").asInt());
    }
}
