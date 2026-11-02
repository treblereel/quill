package org.treblereel.mcp.mcp;

import org.treblereel.mcp.db.IndexReader;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.*;
import org.treblereel.mcp.model.FrameworkEndpointRecord;

class EndpointPrefetchTest {
    @TempDir Path root;
    static void routes(Jdbi db, String... verbs) throws Exception {
        int id=IndexReader.findAllClasses(db).stream().filter(c->c.className().equals("p.Fixture")).findFirst().orElseThrow().id();
        var routes=new ArrayList<FrameworkEndpointRecord>();
        for(String verb:verbs)routes.add(new FrameworkEndpointRecord(id,"p.Fixture","fetch","fetch()","()I","jax-rs",List.of(verb),List.of("/warehouse"),List.of("/items/{key}"),List.of()));
        String value=new ObjectMapper().writeValueAsString(routes);
        db.useHandle(h->h.createUpdate("INSERT OR REPLACE INTO metadata(key,value) VALUES ('framework_endpoints_detail',:value)").bind("value",value).execute());
    }
    @Test void indexedRoutesWorkWithoutClassNamesAndFollowUpsReuseTheOperation() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture { Service service; int fetch(){ return service.read(); }} class Service {int read(){return 7;}}");
        try {
            routes(db,"GET","POST");
            var packet=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"Montrez GET /warehouse/items/{key}: ensuite expliquez GET /warehouse/items/{key}, merci."));
            assertEquals(2,packet.path("facts").size(),packet.toString());
            assertEquals("GET /warehouse/items/{key}",packet.path("endpoint_anchor").asText());
            assertEquals("fetch",packet.path("facts").get(0).path("endpoint").path("method").asText());
            assertEquals(2,packet.path("facts").get(1).path("methods").size());
            assertEquals("indexed_target_only",packet.path("facts").get(1).path("methods").get(1).path("source_evidence").path("status").asText());
            var follow=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"What dependencies does it use?",packet.path("endpoint_anchor").asText()));
            assertEquals(packet.path("facts"),follow.path("facts"));
            assertFalse(TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"POST /warehouse/items/{key}")).path("facts").isEmpty());
            var missing=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse",packet.path("endpoint_anchor").asText()));
            assertTrue(missing.path("facts").isEmpty());assertFalse(missing.has("endpoint_anchor"));
            assertFalse(missing.path("unavailable").isEmpty());
            var multiple=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key} versus POST /warehouse/items/{key}"));
            assertTrue(multiple.path("facts").isEmpty());assertFalse(multiple.path("ambiguities").isEmpty());
        } finally {}
    }
    @Test void handlerFieldReadsOfOtherClassesAreListed() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture { Service service; int fetch(){ return service.count; }} class Service {int count;}");
        routes(db,"GET");
        var packet=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key}"));
        var handler=packet.path("facts").get(1).path("methods").get(0);
        assertEquals("p.Service#count read_instance bytecode lines [1]",handler.path("other_class_field_accesses").get(0).asText(),packet.toString());
    }
    @Test void duplicateMappingsAreAmbiguousAndChangedSourcesNeverBecomeFreshFacts() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int fetch(){return 1;}}");
        try {
            routes(db,"GET","GET");var ambiguous=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key}"));
            assertTrue(ambiguous.path("facts").isEmpty());assertFalse(ambiguous.path("ambiguities").isEmpty());
            routes(db,"GET");Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"package p; class Fixture {int fetch(){return 2;}}");
            var stale=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key}"));assertNotEquals("ready",stale.path("status").asText());assertTrue(stale.path("facts").isEmpty());
        } finally {}
    }
}
