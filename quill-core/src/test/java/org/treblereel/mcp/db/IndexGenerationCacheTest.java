package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexGenerationCacheTest {
    @TempDir Path root;
    private final IndexGenerationCache cache = new IndexGenerationCache();
    IndexGeneration fixture(String name,boolean indexedMember) {
        Path path=root.resolve(name+".db");var writer=QuillDatabase.create(path);
        writer.useHandle(h->{
            h.execute("INSERT INTO classes(id,class_name,kind,interfaces) VALUES(1,'p.Base','CLASS','[]')");
            h.execute("INSERT INTO classes(id,class_name,kind,superclass,interfaces) VALUES(2,'p.Child','CLASS','p.Base','[]')");
            if(indexedMember)h.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES(1,'METHOD','run','run():void','()V','void','[]','public','[]')");
        });return IndexGeneration.open(path, cache);
    }
    @Test void cacheIsImmutableBoundedAndDifferentGenerationsDoNotShareUnknowns() throws Exception {
        var oldGeneration=fixture("old",false);var currentGeneration=fixture("new",true);
        var missing=oldGeneration.jdbi();var current=currentGeneration.jdbi();
        try {
            assertSame(currentGeneration.readWithGraph(context -> context.graph()),currentGeneration.readWithGraph(context -> context.graph()));
            assertFalse(oldGeneration.readWithGraph( context -> context.methods().resolve("p.Child","run","()V")).resolved());
            var resolution=currentGeneration.readWithGraph( context -> context.methods().resolve("p.Child","run","()V"));assertTrue(resolution.resolved());assertEquals("p.Base",resolution.owner().className());
            var graph=currentGeneration.readWithGraph(context -> context.graph());
            assertEquals(List.of("p.Child","p.Base"),graph.path(graph.classes.get(1),graph.classes.get(0)));
            assertThrows(UnsupportedOperationException.class,()->graph.members.clear());
            assertThrows(UnsupportedOperationException.class,()->graph.classes.getFirst().interfaces().add("p.Other"));
            var retained=new ArrayList<IndexGeneration>();
            try {for(int i=0;i<10;i++){var db=fixture("extra"+i,true);retained.add(db);db.readWithGraph(context -> context.graph());}assertTrue(cache.size()<=8);}
            finally {retained.forEach(IndexGeneration::close);}
            // In-flight readers retain a valid graph after eviction.
            assertEquals(2,graph.classes.size());
        } finally {oldGeneration.close();currentGeneration.close();}
    }
    @Test void parallelReadsShareOneSnapshotAndRetirementDoesNotMutateIt() throws Exception {
        var generation=fixture("parallel",true);var db=generation.jdbi();var executor=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Future<org.treblereel.mcp.analysis.JvmTypeGraph>>();
            for(int i=0;i<8;i++)tasks.add(executor.submit(()->generation.readWithGraph(context -> context.graph())));
            var first=tasks.getFirst().get(5,TimeUnit.SECONDS);
            for(var task:tasks)assertSame(first,task.get(5,TimeUnit.SECONDS));
            generation.close();assertEquals(2,first.classes.size());
        } finally {generation.close();executor.shutdownNow();}
    }
    @Test void writableSnapshotsNeverRetainStaleDeclarations() {
        var db=QuillDatabase.create(root.resolve("mutable.db"));
        assertEquals(0,ReadQueryContext.readWithGraph(db, context -> context.graph()).classes.size());
        db.useHandle(h->h.execute("INSERT INTO classes(class_name,kind,interfaces) VALUES('p.Added','CLASS','[]')"));
        assertEquals(1,ReadQueryContext.readWithGraph(db, context -> context.graph()).classes.size());
    }
    @Test void replacingTheSamePathRetiresItsFileIdentity() throws Exception {
        var generation=fixture("same",true);var db=generation.jdbi();Path path=root.resolve("same.db"),replacement=root.resolve("replacement.db");
        QuillDatabase.create(replacement);assertTrue(generation.matches(path));
        try(var pinned=db.open()) {
            Files.move(replacement,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            assertFalse(generation.matches(path));
            assertEquals(2,pinned.createQuery("SELECT count(*) FROM classes").mapTo(Integer.class).one().intValue());
            generation.close();
            var next=IndexGeneration.open(path);
            try {assertEquals(0,next.readWithGraph(context -> context.graph()).classes.size());}
            finally {next.close();}
        } finally {generation.close();}
    }
    @Test void aLargeUnrelatedInventoryDoesNotDisableDeclaredMethodResolution() {
        var db = QuillDatabase.create(root.resolve("large.db"));
        db.useTransaction(h -> {
            var classes = h.prepareBatch("INSERT INTO classes(id,class_name,kind,interfaces) VALUES(:id,:name,'CLASS','[]')");
            for (int id = 1; id <= 5001; id++) classes.bind("id", id).bind("name", "p.C" + id).add();
            classes.execute();
            h.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES(1,'METHOD','run','run():void','()V','void','[]','public','[]')");
        });
        try (var generation = IndexGeneration.open(root.resolve("large.db"))) {
            var graph = generation.readWithGraph(context -> context.graph());
            assertEquals("member_evidence_not_loaded", new org.treblereel.mcp.analysis.JvmMethodResolver(graph)
                    .resolve("p.C1", "run", "()V").status());
            var resolved = generation.readWithGraph( context -> context.methods().resolve("p.C1", "run", "()V"));
            assertTrue(resolved.resolved());
            assertEquals("declared", resolved.status());
        }
    }
    @Test void aSlowGenerationDoesNotBlockAnotherGeneration() throws Exception {
        try (var slow = fixture("slow", true); var fast = fixture("fast", true);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            slow.jdbi().setSqlLogger(new org.jdbi.v3.core.statement.SqlLogger() {
                @Override public void logBeforeExecution(org.jdbi.v3.core.statement.StatementContext context) {
                    if (!context.getRawSql().contains("SELECT * FROM classes")) return;
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
            });
            var pending = executor.submit(() -> slow.readWithGraph(context -> context.graph()));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(2, executor.submit(() -> fast.readWithGraph(context -> context.graph()))
                        .get(2, TimeUnit.SECONDS).classes.size());
            } finally { release.countDown(); }
            assertEquals(2, pending.get(2, TimeUnit.SECONDS).classes.size());
        }
    }
    @Test void failedInitializationCompletesWaitersAndCanBeRetried() throws Exception {
        try (var generation = fixture("failure", true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
            generation.jdbi().setSqlLogger(new org.jdbi.v3.core.statement.SqlLogger() {
                @Override public void logBeforeExecution(org.jdbi.v3.core.statement.StatementContext context) {
                    if (context.getRawSql().contains("SELECT * FROM classes") && fail.compareAndSet(true, false))
                        throw new IllegalStateException("test graph loading failure");
                }
            });
            var pending = new ArrayList<Future<org.treblereel.mcp.analysis.JvmTypeGraph>>();
            for (int i = 0; i < 4; i++) pending.add(executor.submit(() -> generation.readWithGraph(context -> context.graph())));
            int failures = 0;
            for (var task : pending) {
                try { assertEquals(2, task.get(5, TimeUnit.SECONDS).classes.size()); }
                catch (ExecutionException failed) { failures++; }
            }
            assertTrue(failures > 0);
            var retried = generation.readWithGraph(context -> context.graph());
            assertEquals(2, retried.classes.size());
            assertSame(retried, generation.readWithGraph(context -> context.graph()));
        }
    }

    @Test void sparseInventoriesAndDerivedResultsConsumeTheCacheBudget() {
        var classes = new ArrayList<org.treblereel.mcp.model.ClassRecord>();
        for (int i = 0; i < 6000; i++) classes.add(new org.treblereel.mcp.model.ClassRecord(
                i, "p.C"+i, "CLASS", null, List.of(), "C.java", 1, false, 0));
        var graph = new org.treblereel.mcp.analysis.JvmTypeGraph(classes, Map.of(), Set.of());
        assertEquals(0, graph.memberCount);
        assertTrue(graph.retainedWeight() >= 12000);
        long initial = graph.retainedWeight();
        graph.aliases("test", () -> List.of(1,2,3));
        assertTrue(graph.retainedWeight() > initial);
        try (var generation = fixture("oversized", true)) {
            cache.retain(generation, new org.treblereel.mcp.analysis.JvmTypeGraph(
                    java.util.stream.IntStream.range(0, 60000).mapToObj(id -> new org.treblereel.mcp.model.ClassRecord(
                            id, "p.L"+id,"CLASS",null,List.of(),"L.java",1,false,0)).toList(), Map.of(), Set.of()));
            assertEquals(0, cache.size());
        }
    }

    @Test void sqlOnlyReadsNeverInitializeOrRequireTheTypeGraph() {
        try (var generation = fixture("sql-only", true)) {
            var loads = new java.util.concurrent.atomic.AtomicInteger();
            generation.jdbi().setSqlLogger(new org.jdbi.v3.core.statement.SqlLogger() {
                @Override public void logBeforeExecution(org.jdbi.v3.core.statement.StatementContext context) {
                    if (context.getRawSql().contains("SELECT * FROM classes")) loads.incrementAndGet();
                }
            });
            for (int i=0;i<4;i++) generation.read(context -> {
                assertFalse(context.hasGraph());
                context.index.getMetadata();
                context.git.hasGitData();
                return null;
            });
            assertEquals(0, loads.get());
            assertThrows(IllegalStateException.class, () -> generation.read(context -> context.graph()));
            assertEquals(0, loads.get());
            assertEquals(2, generation.readWithGraph(context -> {
                assertSame(context.graph().classes, context.symbols.findAllClasses());
                return context.graph().classes.size();
            }).intValue());
            assertEquals(1, loads.get());
        }
    }

    @Test void derivedWeightTracksAllCachesAndLruEvictionWithoutRescanning() {
        var classes = List.of(
                new org.treblereel.mcp.model.ClassRecord(1,"p.Base","CLASS",null,List.of(),"Base.java",1,false,0),
                new org.treblereel.mcp.model.ClassRecord(2,"p.Child","CLASS","p.Base",List.of(),"Child.java",1,false,0));
        var graph = new org.treblereel.mcp.analysis.JvmTypeGraph(classes, Map.of(), Set.of());
        long base = graph.retainedWeight();
        graph.descendants(classes.getFirst());
        assertEquals(base+2, graph.retainedWeight());
        graph.descendants(classes.getFirst());
        assertEquals(base+2, graph.retainedWeight());
        graph.ancestry(classes.getLast());
        assertEquals(base+7, graph.retainedWeight());
        graph.resolution("missing", () -> new org.treblereel.mcp.analysis.JvmMethodResolver.Resolution("unknown",null,null,List.of()));
        assertEquals(base+8, graph.retainedWeight());
        graph.dispatch("call", () -> "");
        assertEquals(base+9, graph.retainedWeight());
        for (int i=0;i<257;i++) graph.aliases("key"+i, () -> List.of(1));
        assertEquals(base+9+256, graph.retainedWeight());
        graph.aliases("key0", () -> List.of(1,2));
        assertEquals(base+9+257, graph.retainedWeight());
        graph.aliases("key0", () -> { throw new AssertionError("Cache hit must not reload"); });
        assertEquals(base+9+257, graph.retainedWeight());
    }

    @Test void graphWaitersDoNotBorrowTaskConnections() throws Exception {
        try (var generation = fixture("waiting", true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            generation.jdbi().setSqlLogger(new org.jdbi.v3.core.statement.SqlLogger() {
                @Override public void logBeforeExecution(org.jdbi.v3.core.statement.StatementContext context) {
                    if (!context.getRawSql().contains("SELECT * FROM classes")) return;
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            });
            var tasks = new ArrayList<Future<Integer>>();
            tasks.add(executor.submit(() -> generation.readWithGraph(c -> c.graph().classes.size())));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                for (int i=0;i<8;i++) tasks.add(executor.submit(() -> generation.readWithGraph(c -> c.graph().classes.size())));
                assertEquals(Map.of(), executor.submit(() -> generation.read(c -> c.index.getMetadata()))
                        .get(2, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            for (var task : tasks) assertEquals(2, task.get(3, TimeUnit.SECONDS));
        }
    }

    @Test void retirementDoesNotWaitForOrRetainAnInFlightLoad() throws Exception {
        try (var generation = fixture("retiring", true); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            generation.jdbi().setSqlLogger(new org.jdbi.v3.core.statement.SqlLogger() {
                @Override public void logBeforeExecution(org.jdbi.v3.core.statement.StatementContext context) {
                    if (!context.getRawSql().contains("SELECT * FROM classes")) return;
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                }
            });
            var pending = executor.submit(() -> generation.readWithGraph(context -> context.graph()));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                executor.submit(generation::close).get(2, TimeUnit.SECONDS);
                assertEquals(0, cache.size());
            } finally { release.countDown(); }
            assertEquals(2, pending.get(2, TimeUnit.SECONDS).classes.size());
            assertEquals(0, cache.size());
        }
    }
}
