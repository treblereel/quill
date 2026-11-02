package org.treblereel.mcp.mcp;

import org.treblereel.mcp.db.ReadQueryContext;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.junit.jupiter.api.io.TempDir;
class PrefetchContextTest {
    @TempDir Path root;
    @Test void arbitraryWordingUsesIndexedIdentifiersAndChangedSourceNeverBecomesFreshFacts() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int value;} ");
        var first=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Пожалуйста объясни p.Fixture."));
        assertEquals(1,first.path("facts").size(),first.toString());
        assertEquals(first.path("facts"),TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Unrelated wording: Fixture?")).path("facts"));
        assertEquals(1,TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"p.Fixture.value and Fixture.java")).path("facts").size());
        assertEquals(0,TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"No known identifiers here")).path("facts").size());
        Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"package p; class Fixture {long value;}");
        assertEquals(0,TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Fixture")).path("facts").size());
        
    }
    @Test void fieldTargetDeliversItsInjectionPointAndNamesFieldsThatAreNotInjected() throws Exception {
        var db=QuillDatabase.create(root.resolve("fields.db"));
        IndexWriter.writeIncremental(db,new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0,"x.Consumer","CLASS","java.lang.Object",List.of(),"Consumer.java",1,true,10),
                        new ClassRecord(0,"x.Message","CLASS","java.lang.Object",List.of(),"Message.java",1,true,10)))
                .beans(List.of(
                        new BeanRecord(0,1,"CLASS","@Singleton",List.of("@Default"),List.of(),false,null,null,null,null,List.of("x.Consumer")),
                        new BeanRecord(0,2,"CLASS","@Dependent",List.of("@Default"),List.of(),false,null,null,null,null,List.of("x.Message"))))
                .injectionPoints(List.of(InjectionPointRecord.staticAnalysis(0,1,"FIELD","x.Message",List.of("@Default"),"message",null,false,InjectionPointRecord.STATIC_CDI)))
                .dependencies(List.of()).metadata(Map.of("index_id","fields")).snapshot());
        var pack=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Что внедрено в x.Consumer#message и x.Consumer#missing?"));
        assertEquals("ready",pack.path("status").asText(),pack.toString());
        var fact=pack.path("facts").get(0);
        assertEquals("injection_point",fact.path("kind").asText(),pack.toString());
        assertEquals("x.Consumer#message",fact.path("target").asText());
        assertEquals("x.Message",fact.path("injection_point").path("required_type").asText());
        assertEquals("@Singleton",fact.path("owner_scope").asText());
        assertEquals("not_an_injection_point",pack.path("unavailable").get(0).path("status").asText());
    }
}
