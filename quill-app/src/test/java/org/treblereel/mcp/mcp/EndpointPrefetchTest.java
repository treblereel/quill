package org.treblereel.mcp.mcp;

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
            var packet=PrefetchContext.collect(db,root,"Montrez GET /warehouse/items/{key}: ensuite expliquez GET /warehouse/items/{key}, merci.");
            assertEquals(2,packet.path("facts").size(),packet.toString());
            assertEquals("GET /warehouse/items/{key}",packet.path("endpoint_anchor").asText());
            assertEquals("fetch",packet.path("facts").get(0).path("endpoint").path("method").asText());
            assertEquals(2,packet.path("facts").get(1).path("methods").size());
            assertEquals("indexed_target_only",packet.path("facts").get(1).path("methods").get(1).path("source_evidence").path("status").asText());
            var follow=PrefetchContext.collect(db,root,"What dependencies does it use?",packet.path("endpoint_anchor").asText());
            assertEquals(packet.path("facts"),follow.path("facts"));
            assertFalse(PrefetchContext.collect(db,root,"POST /warehouse/items/{key}").path("facts").isEmpty());
            var missing=PrefetchContext.collect(db,root,"GET /warehouse",packet.path("endpoint_anchor").asText());
            assertTrue(missing.path("facts").isEmpty());assertFalse(missing.has("endpoint_anchor"));
            assertFalse(missing.path("unavailable").isEmpty());
            var multiple=PrefetchContext.collect(db,root,"GET /warehouse/items/{key} versus POST /warehouse/items/{key}");
            assertTrue(multiple.path("facts").isEmpty());assertFalse(multiple.path("ambiguities").isEmpty());
        } finally {QuillDatabase.retire(db);}
    }
    @Test void duplicateMappingsAreAmbiguousAndChangedSourcesNeverBecomeFreshFacts() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int fetch(){return 1;}}");
        try {
            routes(db,"GET","GET");var ambiguous=PrefetchContext.collect(db,root,"GET /warehouse/items/{key}");
            assertTrue(ambiguous.path("facts").isEmpty());assertFalse(ambiguous.path("ambiguities").isEmpty());
            routes(db,"GET");Files.writeString(root.resolve("src/main/java/p/Fixture.java"),"package p; class Fixture {int fetch(){return 2;}}");
            var stale=PrefetchContext.collect(db,root,"GET /warehouse/items/{key}");assertNotEquals("ready",stale.path("status").asText());assertTrue(stale.path("facts").isEmpty());
        } finally {QuillDatabase.retire(db);}
    }
}
