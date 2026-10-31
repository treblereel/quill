package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadQueryContextTest {
    @TempDir Path root;

    @Test void observationsAreIsolatedByResponseAndDatabaseEvenAfterFailure() {
        var a = QuillDatabase.create(root.resolve("a"));
        var b = QuillDatabase.create(root.resolve("b"));
        var reads = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ReadQueryContext.read(a, context -> {
            assertEquals(1, context.sourceFreshness(() -> IntNode.valueOf(reads.incrementAndGet())).asInt());
            assertEquals(1, context.sourceFreshness(() -> IntNode.valueOf(reads.incrementAndGet())).asInt());
            assertEquals(2, ReadQueryContext.read(b, other -> other.sourceFreshness(
                    () -> IntNode.valueOf(reads.incrementAndGet())).asInt()).intValue());
            assertEquals(1, context.sourceFreshness(() -> IntNode.valueOf(reads.incrementAndGet())).asInt());
            throw new IllegalStateException("expected");
        }));
        assertEquals(3, ReadQueryContext.read(a, context -> context.sourceFreshness(
                () -> IntNode.valueOf(reads.incrementAndGet())).asInt()).intValue());
    }

    @Test void errorsAndNestedDifferentDatabasesKeepTheirOwnScope() {
        var a = QuillDatabase.create(root.resolve("a"));
        var b = QuillDatabase.create(root.resolve("b"));
        a.useHandle(h -> h.execute("INSERT INTO metadata VALUES('marker','a')"));
        b.useHandle(h -> h.execute("INSERT INTO metadata VALUES('marker','b')"));
        assertEquals("a", ReadQueryContext.read(a, context -> {
            assertEquals("a", context.index.getMetadata().get("marker"));
            assertThrows(IllegalStateException.class, () -> ReadQueryContext.read(b, other -> {
                assertEquals("b", other.index.getMetadata().get("marker"));
                throw new IllegalStateException("expected");
            }));
            return context.index.getMetadata().get("marker");
        }));
        a.useHandle(h -> h.execute("UPDATE metadata SET value='new'"));
        assertEquals("new", ReadQueryContext.read(a, context -> context.index.getMetadata().get("marker")));
    }

    @Test void repositoriesCannotUseTheHandleAfterTheTaskEnds() {
        var db = QuillDatabase.create(root.resolve("closed"));
        var repository = new AtomicReference<SymbolRepository>();
        assertThrows(IllegalStateException.class, () -> ReadQueryContext.read(db, context -> {
            repository.set(context.symbols);
            throw new IllegalStateException("expected");
        }));
        assertThrows(RuntimeException.class, () -> repository.get().findClassByName("absent"));
    }

    @Test void backgroundSupplierGetsItsOwnLeaseAfterTheForegroundScopeCloses() throws Exception {
        var db = QuillDatabase.create(root.resolve("background"));
        db.useHandle(h -> h.execute("INSERT INTO metadata VALUES('marker','background')"));
        var pending = ReadQueryContext.read(db, context -> context.independentRead(
                background -> background.index.getMetadata().get("marker")));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertEquals("background", executor.submit(pending::get).get(2, TimeUnit.SECONDS));
        }
    }
    @Test void nestedReadsShareTheOwnerLeaseWhenThePoolIsFull() throws Exception {
        Path path = root.resolve("nested-pool.db");
        IndexWriter.writeFresh(QuillDatabase.create(path), new IndexFixture()
                .metadata(java.util.Map.of("marker", "nested")).snapshot());
        try (var generation = IndexGeneration.open(path); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new java.util.concurrent.CyclicBarrier(4);
            var pending = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            try {
                for (int i = 0; i < 4; i++) pending.add(executor.submit(() -> generation.read(parent -> {
                    try { barrier.await(2, TimeUnit.SECONDS); }
                    catch (Exception failure) { throw new IllegalStateException(failure); }
                    String nested = generation.read(child -> child.index.getMetadata().get("marker"));
                    assertEquals(nested, parent.index.getMetadata().get("marker"));
                    return nested;
                })));
                for (var task : pending) assertEquals("nested", task.get(3, TimeUnit.SECONDS));
            } finally { pending.forEach(task -> task.cancel(true)); }
        }
    }
}
