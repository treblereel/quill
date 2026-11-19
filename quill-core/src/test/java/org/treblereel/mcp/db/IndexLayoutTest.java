package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.MethodCallRecord;

class IndexLayoutTest {
    @TempDir Path root;

    @Test
    void onlyFreshWritesVacuumAndRetainAllConstraints() {
        var db = QuillDatabase.createForBulkLoad(root.resolve("staging.db"));
        var statements = new ArrayList<String>();
        db.setSqlLogger(new SqlLogger() {
            @Override public void logAfterExecution(StatementContext context) {
                statements.add(context.getRawSql());
            }
        });
        var snapshot = snapshot();
        IndexWriter.writeFresh(db, snapshot);
        assertEquals(1, statements.stream().filter("VACUUM"::equals).count());
        db.useHandle(h -> {
            assertEquals(0, h.createQuery("PRAGMA freelist_count").mapTo(Integer.class).one());
            assertEquals(0, h.createQuery("SELECT count(*) FROM sqlite_master WHERE name IN ('idx_calls_from','idx_fields_from','sqlite_autoindex_method_calls_1','sqlite_autoindex_field_accesses_1')")
                    .mapTo(Integer.class).one());
            assertThrows(RuntimeException.class, () -> h.execute("INSERT INTO method_calls SELECT * FROM method_calls LIMIT 1"));
            assertEquals("ok", h.createQuery("PRAGMA quick_check").mapTo(String.class).one());
            assertTrue(h.createQuery("PRAGMA foreign_key_check").mapToMap().list().isEmpty());
        });
        statements.clear();
        var unchanged = IndexWriter.writeIncremental(db, snapshot);
        assertEquals(0, unchanged.rowsInserted());
        assertEquals(0, unchanged.rowsDeleted());
        assertFalse(statements.contains("VACUUM"));
    }

    @Test
    void invocationKindsAndSameNamedClassesHaveStablePagesAcrossAllCallQueries() {
        var db = QuillDatabase.createForBulkLoad(root.resolve("ordered.db"));
        IndexWriter.writeFresh(db, snapshot());
        db.useHandle(h -> {
            var q = new UsageRepository(h);
            var outgoing = q.findMethodCalls(1, "run", "()V", "outbound", 20, 0, List.of(1));
            assertEquals(List.of("dynamic:1:2", "dynamic:1:4", "virtual:1:2", "virtual:1:4"), keys(outgoing));
            assertEquals(outgoing, q.findAdjacentMethodCalls(1, "run", "()V", "outbound", List.of(1)));
            var expected = List.of("dynamic:1:2", "dynamic:1:4", "dynamic:3:2", "dynamic:3:4",
                    "virtual:1:2", "virtual:1:4", "virtual:3:2", "virtual:3:4");
            assertEquals(expected, keys(q.findExactMethodUsages(List.of(2, 4), "go", "()V", 20, 0)));
            var paged = new ArrayList<UsageRepository.MethodCallView>();
            for (int offset = 0; offset < 8; offset++) {
                paged.addAll(q.findMethodCalls(2, "go", "()V", "inbound", 1, offset, List.of(2, 4)));
            }
            assertEquals(expected, keys(paged));
            assertEquals(8, q.countExactMethodUsages(List.of(2, 4), "go", "()V"));
        });
    }

    private List<String> keys(List<UsageRepository.MethodCallView> calls) {
        return calls.stream().map(c -> c.invocationKind() + ":" + c.fromClassId() + ":" + c.toClassId()).toList();
    }

    private IndexSnapshot snapshot() {
        var classes = new ArrayList<ClassRecord>();
        for (int id = 1; id <= 4; id++) classes.add(new ClassRecord(id,
                id % 2 == 1 ? "acme.Caller" : "acme.Target", "CLASS", null, List.of(), "File.java", 1, false, 0));
        var calls = new ArrayList<MethodCallRecord>();
        for (int from : List.of(3, 1)) for (int to : List.of(4, 2)) for (String kind : List.of("virtual", "dynamic")) {
            calls.add(new MethodCallRecord(from, "run", "()V", to, "go", "()V", kind, 1, List.of(10)));
        }
        return new IndexFixture().classes(classes).methodCalls(calls).snapshot();
    }
}
