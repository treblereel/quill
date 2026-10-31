package org.treblereel.mcp.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.db.*;

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
    @Test
    void escapesStageNamesWithoutBreakingTheJsonLine() throws Exception {
        String stage = "parse \"quoted\"\\path\n\tΣ";
        var output = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (var captured = new PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8)) {
            System.setErr(captured);
            try (var scope = new QueryMetrics()) {
                QueryMetrics.measure(stage, () -> {});
            }
        } finally {
            System.setErr(original);
        }
        String text = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, text.lines().count());
        var json = new ObjectMapper().readTree(text);
        assertEquals("quill_query_metrics", json.path("event").asText());
        assertEquals(1, json.path("stages_ms").size());
        assertTrue(json.path("stages_ms").get(stage).isNumber());
    }

    @Test
    void closingNestedScopeRestoresTheParentAndEmitsOnlyTheRoot() throws Exception {
        var output = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (var captured = new PrintStream(output)) {
            System.setErr(captured);
            try (var parent = new QueryMetrics()) {
                QueryMetrics.connectionOpened();
                var nested = new QueryMetrics();
                nested.close();
                nested.close();
                QueryMetrics.statementExecuted();
            }
        } finally {
            System.setErr(original);
        }
        assertEquals(1, output.toString().lines().count());
        var json = new ObjectMapper().readTree(output.toString());
        assertEquals(1, json.path("connection_opens").asInt());
        assertEquals(1, json.path("sql_statements").asInt());
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
    @Test void readersShareTheTaskHandleAndMemoizeOnlyInsideTheResponse() throws Exception {
        var db = QuillDatabase.create(root.resolve("db"));
        db.useHandle(h -> h.execute("INSERT INTO metadata VALUES('marker','first')"));
        var output = new ByteArrayOutputStream(); PrintStream original = System.err;
        try {
            System.setErr(new PrintStream(output));
            try (var metrics = new QueryMetrics()) {
                ReadQueryContext.read(db, context -> {
                    assertEquals("first", context.index.getMetadata().get("marker"));
                    assertEquals("first", context.index.getMetadata().get("marker"));
                    return null;
                });
            }
        } finally { System.setErr(original); }
        var json = new ObjectMapper().readTree(output.toString());
        assertEquals(1, json.path("connection_opens").asInt());
        assertEquals(1, json.path("sql_statements").asInt());
        db.useHandle(h -> h.execute("UPDATE metadata SET value='second'"));
        assertEquals("second", ReadQueryContext.read(db, context -> context.index.getMetadata().get("marker")));
    }
}
