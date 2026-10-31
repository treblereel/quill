package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.MethodCallRecord;

class MethodEvidenceTest {
    @TempDir Path root;

    private IndexSnapshot snapshot(List<MethodCallRecord> calls) {
        return new IndexFixture().classes(List.of(
                new ClassRecord(1, "acme.Caller", "CLASS", null, List.of(), "Caller.java", 1, false, 0),
                new ClassRecord(2, "acme.Target", "CLASS", null, List.of(), "Target.java", 1, false, 0)))
                .methodCalls(calls).snapshot();
    }

    private MethodCallRecord call(String descriptor, String target, List<String> edges) {
        return new MethodCallRecord(1, "run", descriptor, 2, target, "()V", "virtual",
                2, List.of(10, 20), List.of(2, 4), 1, 1, edges,
                List.of("Executor.execute"), List.of("4|20|Executor|execute|()V|scheduling"));
    }

    private int count(Jdbi db, String table) {
        return db.withHandle(h -> h.createQuery("SELECT count(*) FROM " + table).mapTo(Integer.class).one());
    }

    @Test
    void sharedEvidenceRoundTripsAllCallQueriesAndKeepsOverloadsSeparate() {
        var db = QuillDatabase.createForBulkLoad(root.resolve("fresh.db"));
        var edges = List.of("1>2", "2>3");
        var calls = List.of(call("()V", "a", edges), call("()V", "b", edges),
                call("(I)V", "a", List.of("1>5")));
        IndexWriter.writeFresh(db, snapshot(calls));
        assertEquals(3, count(db, "method_calls"));
        assertEquals(2, count(db, "method_evidence"));
        db.useHandle(h -> {
            var queries = new UsageRepository(h);
            var outbound = queries.findMethodCalls(1, "run", "()V", "outbound", 10, 0, List.of(1));
            assertEquals(2, outbound.size());
            assertEquals(outbound, queries.findAdjacentMethodCalls(1, "run", "()V", "outbound", List.of(1)));
            assertEquals(outbound.getFirst(), queries.findExactMethodUsages(List.of(2), "a", "()V", 10, 0)
                    .stream().filter(c -> c.fromDescriptor().equals("()V")).findFirst().orElseThrow());
            for (var c : outbound) {
                assertEquals(edges, c.callerControlFlowEdges());
                assertEquals(1, c.callerBranchCount());
                assertEquals(1, c.callerExceptionHandlerCount());
                assertEquals(List.of("Executor.execute"), c.callerAsyncBoundaries());
                assertEquals(calls.getFirst().callerExternalCalls(), c.callerExternalCalls());
                assertEquals(List.of(10, 20), c.evidenceLines());
                assertEquals(List.of(2, 4), c.instructionOrdinals());
                assertEquals(2, c.occurrenceCount());
            }
            assertEquals(List.of("1>5"), queries.findMethodCalls(1, "run", "(I)V", "outbound", 10, 0, List.of(1))
                    .getFirst().callerControlFlowEdges());
        });
    }

    @Test
    void emptyMethodEvidenceIsStoredAsArraysAndRoundTrips() {
        var db = QuillDatabase.create(root.resolve("empty.db"));
        IndexWriter.writeFresh(db, snapshot(List.of(new MethodCallRecord(1, "run", "()V",
                2, "a", "()V", "virtual", 1, List.of(10)))));
        var result = db.withHandle(h -> new UsageRepository(h)
                .findMethodCalls(1, "run", "outbound", 10, 0)).getFirst();
        assertEquals(List.of(), result.callerControlFlowEdges());
        assertEquals(List.of(), result.callerAsyncBoundaries());
        assertEquals(List.of(), result.callerExternalCalls());
    }

    @Test
    void incrementalEvidenceChangesOnceWithoutRewritingEdgesAndRemovesUnusedMethods() {
        var db = QuillDatabase.create(root.resolve("delta.db"));
        var initial = List.of(call("()V", "a", List.of("1>2")), call("()V", "b", List.of("1>2")));
        IndexWriter.writeFresh(db, snapshot(initial));
        var unchanged = IndexWriter.writeIncremental(db, snapshot(initial));
        assertEquals(0, unchanged.rowsDeleted());
        assertEquals(0, unchanged.rowsInserted());
        var changed = List.of(call("()V", "a", List.of("1>3")), call("()V", "b", List.of("1>3")));
        var delta = IndexWriter.writeIncremental(db, snapshot(changed));
        assertEquals(1, delta.rowsDeleted());
        assertEquals(1, delta.rowsInserted());
        assertEquals(2, count(db, "method_calls"));
        assertEquals(List.of("1>3"), db.withHandle(h -> new UsageRepository(h)
                .findMethodCalls(1, "run", "outbound", 10, 0)).getFirst().callerControlFlowEdges());
        IndexWriter.writeIncremental(db, snapshot(List.of()));
        assertEquals(0, count(db, "method_calls"));
        assertEquals(0, count(db, "method_evidence"));
    }

    @Test
    void conflictingEvidenceRollsBackTheWholeIncrementalWrite() {
        var db = QuillDatabase.create(root.resolve("rollback.db"));
        var original = call("()V", "a", List.of("1>2"));
        IndexWriter.writeFresh(db, snapshot(List.of(original)));
        assertThrows(IllegalArgumentException.class, () -> IndexWriter.writeIncremental(db,
                snapshot(List.of(original, call("()V", "b", List.of("1>9"))))));
        assertEquals(1, count(db, "method_calls"));
        assertEquals(1, count(db, "method_evidence"));
        assertEquals(List.of("1>2"), db.withHandle(h -> new UsageRepository(h)
                .findMethodCalls(1, "run", "outbound", 10, 0)).getFirst().callerControlFlowEdges());
    }
}
