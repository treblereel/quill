package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.ReadQueryContext;

class EntryPointQueriesTest {
    @TempDir Path root;
    private Jdbi fixture() throws Exception {
        return JavaEvidenceFixture.index(root, Map.of(
            "src/main/java/jakarta/enterprise/event/Observes.java", """
                package jakarta.enterprise.event;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Observes { String during() default "IN_PROGRESS"; }
                """,
            "src/main/java/p/Fixture.java", """
                package p;
                import jakarta.enterprise.event.Observes;
                interface Marker {}
                class Base<T> {}
                class Fixture {
                    static class Created extends Base<String> implements Marker {}
                    static class Other {}
                    void exact(@Observes(during="AFTER_SUCCESS") @Region("east") Created e) {}
                    void parent(@Observes Base<String> e) {}
                    void marker(@Observes Marker e) {}
                    void any(@Observes Object e) {}
                    void other(@Observes Other e) {}
                }
                """,
            "src/main/java/p/Region.java", """
                package p;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Region { String value(); }
                """,
            "src/test/java/p/TestObserver.java", """
                package p;
                class TestObserver { void test(@jakarta.enterprise.event.Observes Fixture.Created e) {} }
                """));
    }
    private JsonNode query(Jdbi db, String kind, String type, boolean tests, int limit, int offset) throws Exception {
        return new ObjectMapper().readTree(ReadQueryContext.<String>read(db, r ->
                new EntryPointQueries().findEntryPoints(r, kind, null, false, tests, limit, offset, type)));
    }
    @Test void filtersBeforePaginationAndRetainsGenericCandidates() throws Exception {
        var db=fixture();var page=query(db,"observer","p.Fixture.Created",false,1,0);
        assertEquals(4,page.path("total").asInt(),page.toString());
        assertEquals(5,page.path("observer_selection").path("scanned").asInt());
        assertEquals(3,page.path("observer_selection").path("type_compatible").asInt());
        assertEquals(1,page.path("observer_selection").path("uncertain").asInt());
        assertTrue(page.path("has_more").asBoolean());
        var all=query(db,"observer","p.Fixture$Created",false,20,0);
        assertFalse(all.path("entry_points").toString().contains("\"method\":\"other\""));
        assertTrue(all.path("entry_points").toString().contains("AFTER_SUCCESS"));
        var exact=all.path("entry_points").valueStream().filter(n -> n.path("method").asText().equals("exact")).findFirst().orElseThrow();
        assertEquals("east",exact.path("observer").path("parameter_annotation_values").path("p.Region").path("value").asText());
        assertEquals(5,query(db,"observer","p.Fixture.Created",true,20,0).path("total").asInt());
        assertEquals(5,query(db,"observer",null,false,20,0).path("total").asInt());
        assertEquals(0,query(db,"observer","p.Fixture.Created",false,20,99).path("showing").asInt());
    }
    @Test void missingHierarchyDoesNotTurnUncertaintyIntoNoObservers() throws Exception {
        var value=query(fixture(),"observer","missing.Event",false,20,0);
        assertEquals(5,value.path("total").asInt());
        assertEquals(4,value.path("observer_selection").path("uncertain").asInt());
        assertEquals("missing.Event",value.path("observer_selection").path("unindexed_types").get(0).asText());
    }
    @Test void rejectsEventFilterForOtherKinds() throws Exception {
        assertEquals("INVALID_ARGUMENT",query(fixture(),"scheduled","p.Fixture.Created",false,20,0).path("error_code").asText());
    }
    @Test void boundedSummaryPreservesPaginationAndFullEvidence() throws Exception {
        var db=fixture();
        db.useHandle(h -> {
            for(int n=0;n<80;n++) h.execute("INSERT INTO class_members(class_id,kind,name,signature,type_name,parameter_types,modifiers,annotations,annotation_details) SELECT class_id,kind,?, ?,type_name,parameter_types,modifiers,annotations,annotation_details FROM class_members WHERE name='exact'", "observer"+n,"observer"+n+"(p.Fixture$Created):void");
        });
        var page=query(db,"observer","p.Fixture.Created",false,200,0);
        assertTrue(page.path("budget_limited").asBoolean(),page.toString());
        assertTrue(page.path("entry_points").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=6000);
        assertEquals(84,page.path("total").asInt());
        assertEquals(page.path("showing").asInt(),page.path("next_page_request").path("offset").asInt());
        var full=new ObjectMapper().readTree(ReadQueryContext.<String>read(db, r -> new EntryPointQueries().findEntryPoints(r,"observer",null,false,false,200,0,"p.Fixture.Created","full")));
        assertEquals(84,full.path("showing").asInt());
        var observer=full.path("entry_points").get(1);
        assertFalse(observer.path("annotation_values").has("jakarta.enterprise.event.Observes"),observer.toString());
        assertEquals("AFTER_SUCCESS",observer.path("observer").path("during").asText(),observer.toString());
        assertTrue(observer.has("detection_rule"),"Full detail keeps the detection evidence");
        assertFalse(page.path("entry_points").get(1).has("annotation_values"));
    }
    @Test void oversizedSingleRowHasExplicitFullDetailRecovery() throws Exception {
        var db=fixture();
        db.useHandle(h -> {
            h.execute("DELETE FROM class_members WHERE name!='exact'");
            String old=h.createQuery("SELECT annotation_details FROM class_members").mapTo(String.class).one();
            try {
                var annotations=(com.fasterxml.jackson.databind.node.ArrayNode)new ObjectMapper().readTree(old);
                annotations.addObject().put("annotationName","p."+"Q".repeat(7000))
                        .put("parameterIndex",0).put("targetKind","METHOD_PARAMETER");
                h.execute("UPDATE class_members SET annotation_details=?",annotations.toString());
            } catch(Exception e) { throw new RuntimeException(e); }
        });
        var page=query(db,"observer","p.Fixture.Created",false,20,0);
        assertEquals(0,page.path("showing").asInt());
        assertEquals(1,page.path("total").asInt());
        assertEquals("full",page.path("next_page_request").path("detail").asText());
    }
}
