package org.treblereel.mcp.mcp.prefetch;

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
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

public class EndpointPrefetchTest {
    @TempDir Path root;
    public static void routes(Jdbi db, String... verbs) throws Exception {
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
            // Apollo AQ-032: copies of class and source pushed the handler body out of the budget.
            var endpoint=packet.path("facts").get(0).path("endpoint");
            assertFalse(endpoint.has("declaring_class"),endpoint.toString());
            assertFalse(endpoint.has("declaring_source"),endpoint.toString());
            assertEquals("p.Fixture",endpoint.path("class").asText());
            var handler=packet.path("facts").get(1).path("methods").get(0);
            assertFalse(handler.path("injection").has("target"),handler.toString());
            assertEquals("none: no direct owned-field or programmatic configuration reads",handler.path("configuration_references").asText(),handler.toString());
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
    /** V41-V62 CQ-032: over budget, the whole methods list went first and with it the handler's @PermissionsAllowed. */
    @Test void theBudgetKeepsTheHandlerBodyAndDropsCalleesFirst() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture { Service service; int fetch(){ return service.read(); }} class Service {int read(){return 7;}}");
        routes(db,"GET");
        var packet=(com.fasterxml.jackson.databind.node.ObjectNode)TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key}"));
        var handler=packet.path("facts").get(1).path("methods").get(0);
        assertFalse(handler.path("injection").has("resolution_context"),packet.toString());
        assertFalse(handler.path("source_evidence").has("symbol_id"),packet.toString());
        int size=packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        ((com.fasterxml.jackson.databind.node.ObjectNode)packet.path("facts").get(0)).put("notes","n".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES-size+300));
        PrefetchContext.boundContext(packet);
        var methods=packet.path("facts").get(1).path("methods");
        assertEquals("fetch",methods.path(0).path("method").asText(),packet.toString());
        assertTrue(methods.path(0).path("source_evidence").path("source").asText().contains("service.read()"),packet.toString());
    }
    /** V65 CQ-032: Claude ran up to 8 search_classes (PermissionChecker, SecurityIdentity, RbacHttp...) to find what grants @PermissionsAllowed. */
    @Test void aSecuredHandlerNamesTheApplicationSecurityProviders() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture { int fetch(){ return 1; }} class Auth {} class Other {}");
        try {
            routes(db,"GET");
            db.useHandle(h->h.execute("UPDATE class_members SET annotations='[\"io.quarkus.security.PermissionsAllowed\",\"jakarta.ws.rs.GET\"]' WHERE name='fetch'"));
            db.useHandle(h->h.execute("UPDATE classes SET interfaces='[\"io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism\"]' WHERE class_name='p.Auth'"));
            var security=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key}")).path("facts").get(0).path("endpoint").path("security").asText();
            assertTrue(security.startsWith("@PermissionsAllowed is checked by Quarkus against the request's SecurityIdentity"),security);
            assertTrue(security.contains("p.Auth (HttpAuthenticationMechanism)"),security);
            assertFalse(security.contains("p.Other"),security);
            db.useHandle(h->h.execute("UPDATE classes SET interfaces='[]' WHERE class_name='p.Auth'"));
            var none=TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key} again")).path("facts").get(0).path("endpoint").path("security").asText();
            assertTrue(none.contains("no application HttpAuthenticationMechanism"),none);
            db.useHandle(h->h.execute("UPDATE class_members SET annotations='[\"jakarta.ws.rs.GET\"]' WHERE name='fetch'"));
            assertFalse(TestReads.read(db, readScope -> PrefetchContext.collect(readScope,root,"GET /warehouse/items/{key}")).path("facts").get(0).path("endpoint").has("security"));
        } finally {}
    }
    @Test void anInjectionPointIsOneLine() throws Exception {
        var json=new ObjectMapper();
        assertEquals("reportAggregator io.cryostat.reports.AnalysisReportAggregator -> io.cryostat.reports.AnalysisReportAggregator @Singleton",
                EndpointPrefetch.injectionLine(json.readTree("{\"field\":\"reportAggregator\",\"required_type\":\"io.cryostat.reports.AnalysisReportAggregator\",\"qualifiers\":[\"@Default\"],\"resolution\":\"resolved\",\"reason\":\"UNIQUE_CONTEXT_CANDIDATE\",\"resolved_scope\":\"@Singleton\",\"resolved_to\":\"io.cryostat.reports.AnalysisReportAggregator\"}")));
        assertEquals("store x.Store [@Named(\"s3\")] (unsatisfied, NO_CANDIDATE)",
                EndpointPrefetch.injectionLine(json.readTree("{\"field\":\"store\",\"required_type\":\"x.Store\",\"qualifiers\":[\"@Named(\\\"s3\\\")\"],\"resolution\":\"unsatisfied\",\"reason\":\"NO_CANDIDATE\"}")));
        // V66 CQ-050: an unknown @CacheName Cache with one matching dependency producer.
        assertEquals("cache io.quarkus.cache.Cache [@io.quarkus.cache.CacheName] -> io.quarkus.cache.runtime.CacheProducer#produce (PRODUCER_METHOD @Dependent) (unknown, NO_CONTEXT_CANDIDATE)",
                EndpointPrefetch.injectionLine(json.readTree("{\"field\":\"cache\",\"required_type\":\"io.quarkus.cache.Cache\",\"qualifiers\":[\"@io.quarkus.cache.CacheName\"],\"resolution\":\"unknown\",\"reason\":\"NO_CONTEXT_CANDIDATE\",\"provider_evidence\":{\"single_match\":\"io.quarkus.cache.runtime.CacheProducer#produce (PRODUCER_METHOD @Dependent) is the one indexed provider matching the type and qualifier names; no application bean is a candidate\"}}")));
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
