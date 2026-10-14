package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexGenerationCacheTest {
    @TempDir Path root;
    Jdbi fixture(String name,boolean indexedMember) {
        Path path=root.resolve(name+".db");var writer=QuillDatabase.create(path);
        writer.useHandle(h->{
            h.execute("INSERT INTO classes(id,class_name,kind,interfaces) VALUES(1,'p.Base','CLASS','[]')");
            h.execute("INSERT INTO classes(id,class_name,kind,superclass,interfaces) VALUES(2,'p.Child','CLASS','p.Base','[]')");
            if(indexedMember)h.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations) VALUES(1,'METHOD','run','run():void','()V','void','[]','public','[]')");
        });return QuillDatabase.openPooled(path);
    }
    @Test void cacheIsImmutableBoundedAndDifferentGenerationsDoNotShareUnknowns() throws Exception {
        var missing=fixture("old",false);var current=fixture("new",true);
        try {
            assertSame(IndexGenerationCache.get(current),IndexGenerationCache.get(current));
            assertFalse(new JvmMethodResolver(missing).resolve("p.Child","run","()V").resolved());
            var resolution=new JvmMethodResolver(current).resolve("p.Child","run","()V");assertTrue(resolution.resolved());assertEquals("p.Base",resolution.owner().className());
            var graph=IndexGenerationCache.get(current);
            assertEquals(List.of("p.Child","p.Base"),graph.path(graph.classes.get(1),graph.classes.get(0)));
            assertThrows(UnsupportedOperationException.class,()->graph.members.clear());
            assertThrows(UnsupportedOperationException.class,()->graph.classes.getFirst().interfaces().add("p.Other"));
            var retained=new ArrayList<Jdbi>();
            try {for(int i=0;i<10;i++){var db=fixture("extra"+i,true);retained.add(db);IndexGenerationCache.get(db);}assertTrue(IndexGenerationCache.size()<=8);}
            finally {retained.forEach(QuillDatabase::retire);}
            // In-flight readers retain a valid graph after eviction.
            assertEquals(2,graph.classes.size());
        } finally {QuillDatabase.retire(missing);QuillDatabase.retire(current);}
    }
    @Test void parallelReadsShareOneSnapshotAndRetirementDoesNotMutateIt() throws Exception {
        var db=fixture("parallel",true);var executor=Executors.newFixedThreadPool(4);
        try {
            var tasks=new ArrayList<Future<IndexGenerationCache.Graph>>();
            for(int i=0;i<8;i++)tasks.add(executor.submit(()->ReadQueryContext.read(db,()->IndexGenerationCache.get(db))));
            var first=tasks.getFirst().get(5,TimeUnit.SECONDS);
            for(var task:tasks)assertSame(first,task.get(5,TimeUnit.SECONDS));
            QuillDatabase.retire(db);assertEquals(2,first.classes.size());
        } finally {QuillDatabase.retire(db);executor.shutdownNow();}
    }
    @Test void writableSnapshotsNeverRetainStaleDeclarations() {
        var db=QuillDatabase.create(root.resolve("mutable.db"));
        assertEquals(0,IndexGenerationCache.get(db).classes.size());
        db.useHandle(h->h.execute("INSERT INTO classes(class_name,kind,interfaces) VALUES('p.Added','CLASS','[]')"));
        assertEquals(1,IndexGenerationCache.get(db).classes.size());
    }
    @Test void replacingTheSamePathRetiresItsFileIdentity() throws Exception {
        var db=fixture("same",true);Path path=root.resolve("same.db"),replacement=root.resolve("replacement.db");
        QuillDatabase.create(replacement);assertTrue(QuillDatabase.matchesGeneration(db,path));
        try(var pinned=db.open()) {
            Files.move(replacement,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            assertFalse(QuillDatabase.matchesGeneration(db,path));
            assertEquals(2,pinned.createQuery("SELECT count(*) FROM classes").mapTo(Integer.class).one().intValue());
            QuillDatabase.retire(db);
            var next=QuillDatabase.openPooled(path);
            try {assertEquals(0,IndexGenerationCache.get(next).classes.size());}
            finally {QuillDatabase.retire(next);}
        } finally {QuillDatabase.retire(db);}
    }
}
