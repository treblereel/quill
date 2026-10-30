package org.treblereel.mcp.mcp;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class MethodImpactQueriesTest {
    @TempDir Path root;
    private static final String SOURCE="""
        package p;
        class Fixture { int map(String x){return x.length();} int map(int x){return x;} }
        class Consumer {int use(){var f=new Fixture();return f.map("a")+f.map("b");}}
        class ConsumerTest {int verify(){return new Fixture().map("c");}}
        """;
    @Test void exactImpactCountsPagesAndOverloadsWithoutRepeatedGraphs() throws Exception {
        var db=JavaEvidenceFixture.index(root,SOURCE);
        try {
            db.useHandle(h->h.execute("UPDATE classes SET source_set='test' WHERE class_name='p.ConsumerTest'"));
            var data=new MethodImpactQueries().get(db,"p.Fixture","map(java.lang.String)",200);
            assertEquals(1,data.path("production_callers").size(),data.toString());
            assertEquals(1,data.path("test_callers").size());
            assertEquals(2,data.path("reference_page_counts").path("caller_methods").asInt());
            assertEquals(3,data.path("reference_page_counts").path("invocation_occurrences").asInt());
            assertEquals(1,data.path("counts").path("tests").path("methods").asInt());
            assertEquals(1,data.path("counts").path("tests").path("call_sites").asInt());
            assertEquals(2,data.path("counts").path("production").path("call_sites").asInt());
            assertEquals(data.path("counts"),new MethodImpactQueries().get(db,"p.Fixture","map(String)",200).path("counts"));
            assertTrue(data.path("answer_complete").asBoolean(),data.toString());
            assertFalse(data.toString().contains("source_evidence"));
            assertEquals(0,new MethodImpactQueries().get(db,"p.Fixture","map(int)",200).path("production_callers").size());
            assertFalse(new MethodImpactQueries().get(db,"p.Fixture","map(java.lang.String)",1).path("answer_complete").asBoolean());
            assertTrue(new MethodImpactQueries().get(db,"p.Fixture","map",200).has("error_code"));
            var projection=new ObjectMapper().readTree(new ContextQueries().getContext(db,java.util.List.of("p.Fixture#map(java.lang.String)"),false,25,"impact"));
            assertTrue(projection.path("answer_complete").asBoolean(),projection.toString());
            assertFalse(projection.has("impacted_tests"));
        } finally {QuillDatabase.retire(db);}
    }
    @Test void literalSelectorsNotQuestionPhrasesDrivePrefetchAndSourceChangesBlockIt() throws Exception {
        var db=JavaEvidenceFixture.index(root,SOURCE);
        try {
            var data=PrefetchContext.collect(db,root,"N'importe quoi: p.Fixture#map(java.lang.String)?");
            assertEquals("exact indexed method impact",data.path("basis").asText());
            assertEquals(1,data.path("facts").size(),data.toString());
            assertEquals("direct_method_impact",data.path("facts").get(0).path("kind").asText());
            Files.writeString(root.resolve("src/main/java/p/Fixture.java"),SOURCE.replace("x.length()","0"));
            assertEquals(0,PrefetchContext.collect(db,root,"p.Fixture#map(java.lang.String)").path("facts").size());
        } finally {QuillDatabase.retire(db);}
    }
    @Test void inheritedContractsAndOverridesRemainSeparateFromDirectReferences() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; interface Contract {int map(String x);} class Fixture implements Contract {public int map(String x){return 1;}} class Child extends Fixture {public int map(String x){return 2;}}");
        try {
            var data=new MethodImpactQueries().get(db,"p.Fixture","map(java.lang.String)",200);
            assertEquals(1,data.path("override_evidence").path("selected_declarations").size(),data.toString());
            assertEquals(1,data.path("override_evidence").path("overrides").size());
            assertEquals("p.Contract",data.path("interface_contracts").get(0).path("class").asText());
            assertEquals(0,data.path("production_callers").size());
        } finally {QuillDatabase.retire(db);}
    }
    @Test void shortTypesKeepOverloadAmbiguityAndArrayDimensions() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Left {static class Item{}} class Right {static class Item{}} class Fixture {void map(Left.Item x){} void map(Right.Item x){} void array(String[] values){}}");
        try {
            assertTrue(new MethodImpactQueries().get(db,"p.Fixture","map(Item)",200).has("error_code"));
            assertFalse(new MethodImpactQueries().get(db,"p.Fixture","map(p.Left$Item)",200).has("error_code"));
            assertFalse(new MethodImpactQueries().get(db,"p.Fixture","array(String[])",200).has("error_code"));
            assertTrue(new MethodImpactQueries().get(db,"p.Fixture","array(String)",200).has("error_code"));
        } finally {QuillDatabase.retire(db);}
    }
    @Test void indirectApplicationCallersAreBoundedAndNeverClaimRuntimeClosure() throws Exception {
        var db=JavaEvidenceFixture.index(root,"package p; class Fixture {int map(String x){return 1;}} class First {int call(){return new Fixture().map(\"x\");}} class Second {int entry(){return new First().call();}} class Third {int start(){return new Second().entry();}}");
        try {
            var data=new MethodImpactQueries().get(db,"p.Fixture","map(String)",200);
            assertEquals(1,data.path("counts").path("production").path("methods").asInt());
            assertEquals(2,data.path("indirect_production").size(),data.toString());
            assertEquals(2,java.util.stream.StreamSupport.stream(data.path("indirect_production").spliterator(),false)
                    .mapToInt(g->g.path("counts").path("call_sites").asInt()).sum());
            assertFalse(data.path("indirect_scope").path("transitive_closure_proven").asBoolean());
            assertEquals(2,data.path("indirect_counts").path("call_sites").asInt());
            assertEquals(2,data.path("indirect_counts").path("reference_instruction_sites").asInt());
            assertEquals(0,data.path("indirect_counts").path("callback_reference_sites").asInt());
            assertTrue(data.path("answer_complete").asBoolean());
        } finally {QuillDatabase.retire(db);}
    }
}
