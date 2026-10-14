package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JavaDispatchEvidenceTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path root; Jdbi db;
    @BeforeEach void fixture() throws Exception {
        db=JavaEvidenceFixture.index(root,"""
            package p;
            interface Contract { Number value(); }
            class Parent implements Contract {
                public Number value(){return 1;}
                public final Number locked(){return 1;}
                public static Number utility(){return 1;}
                public Number value(int n){return n;}
            }
            class Child extends Parent {
                public Integer value(){return 2;}
                public Number value(int n){return n;}
                Number superValue(){return super.value();}
            }
            abstract class AbstractChild extends Parent { public abstract Number value(); }
            class Caller {
                Number invoke(Contract c){return c.value();}
                Number parent(Parent p){return p.value();}
                Number fixed(Parent p){return p.locked();}
                Number utility(){return Parent.utility();}
            }
            """);
    }
    JsonNode usages(String owner,String descriptor,int limit,int offset) throws Exception {
        return JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db,owner,"value","method",descriptor,"all",limit,offset));
    }
    JsonNode calls(String method) throws Exception {
        return JSON.readTree(new CallHierarchyQueries().getCallHierarchy(db,"p.Caller",method,null,"outbound",false,1,20,0));
    }
    @Test void interfaceCallsHaveExactTargetAndSeparateCovariantImplementationCandidates() throws Exception {
        var response=calls("invoke"); var call=response.path("calls").get(0);
        assertEquals("p.Contract",call.path("callee").path("class").asText());
        var dispatch=call.path("dispatch");
        assertEquals("bytecode_symbolic_reference",dispatch.path("target_basis").asText());
        assertFalse(dispatch.path("runtime_target_proven").asBoolean());
        assertEquals(Set.of("p.Parent","p.Child"),new HashSet<>(dispatch.path("possible_targets").valueStream()
                .map(c -> c.path("class").asText()).toList()));
        var child=dispatch.path("possible_targets").valueStream().filter(c -> c.path("class").asText().equals("p.Child")).findFirst().orElseThrow();
        assertEquals("()Ljava/lang/Integer;",child.path("descriptor").asText());
        assertTrue(child.path("symbol_id").asText().startsWith("quill:symbol:v2:"));
        assertEquals(1,usages("p.Contract","()Ljava/lang/Number;",10,0).path("total").asInt());
    }
    @Test void parentContractCallersDoNotBecomeExactCallsToAnOverride() throws Exception {
        var result=usages("p.Child","()Ljava/lang/Integer;",10,0);
        // javac emits a covariant bridge value():Number which invokes value():Integer.
        assertEquals(1,result.path("total").asInt());
        assertEquals("p.Child",result.path("usages").get(0).path("caller").path("class").asText());
        assertEquals("()Ljava/lang/Number;",result.path("usages").get(0).path("caller").path("descriptor").asText());
        var context=result.path("dispatch_context");
        assertEquals(2,context.path("total").asInt(),result.toString());
        assertEquals(Set.of("p.Contract","p.Parent"),new HashSet<>(context.path("possible_callers").valueStream()
                .map(c -> c.path("declared_target").path("class").asText()).toList()));
        assertFalse(context.path("runtime_dispatch_proven").asBoolean());
        assertFalse(context.path("candidate_coverage_complete").asBoolean());
        assertFalse(context.path("possible_callers").valueStream().anyMatch(c -> c.path("invocation_kind").asText().equals("special")));
        assertEquals(0,usages("p.Child","(I)Ljava/lang/Number;",10,0).path("dispatch_context").path("total").asInt());
    }
    @Test void finalAndStaticMethodsDoNotExpandAndPossibleCallerPagesHaveTheirOwnCounts() throws Exception {
        for (String method:List.of("fixed","utility")) {
            var dispatch=calls(method).path("calls").get(0).path("dispatch");
            assertEquals("fixed_symbolic_target",dispatch.path("status").asText()); assertTrue(dispatch.path("possible_targets").isEmpty());
        }
        var first=usages("p.Child","()Ljava/lang/Integer;",1,0).path("dispatch_context");
        var second=usages("p.Child","()Ljava/lang/Integer;",1,1).path("dispatch_context");
        assertEquals(2,first.path("total").asInt()); assertTrue(first.path("has_more").asBoolean());
        assertFalse(second.path("has_more").asBoolean());
        assertNotEquals(first.path("possible_callers").get(0),second.path("possible_callers").get(0));
        assertEquals("find_symbol_usages",first.path("next_page_request").path("tool").asText());
    }
    @Test void manyImplementationsHaveBoundedDeterministicCandidatesAndCyclesTerminate() throws Exception {
        String source="package p; interface Contract {int value();} class Caller {int invoke(Contract c){return c.value();}}";
        for (int i=0;i<25;i++) source+="class Child"+i+" implements Contract {public int value(){return "+i+";}}";
        db=JavaEvidenceFixture.index(root.resolve("wide"),source);
        var first=calls("invoke").path("calls").get(0).path("dispatch");
        assertEquals(25,first.path("possible_target_count").asInt()); assertEquals(20,first.path("possible_targets").size());
        assertTrue(first.path("truncated").asBoolean());
        assertEquals(first,calls("invoke").path("calls").get(0).path("dispatch"));
        db.useHandle(h -> h.execute("UPDATE classes SET superclass='p.Child1' WHERE class_name='p.Child0'"));
        db.useHandle(h -> h.execute("UPDATE classes SET superclass='p.Child0' WHERE class_name='p.Child1'"));
        assertEquals(25,calls("invoke").path("calls").get(0).path("dispatch").path("possible_target_count").asInt());
    }
    @Test void duplicateOwnersAndMissingDeclarationsCannotProduceConfidentCandidates() throws Exception {
        db.useHandle(h -> h.execute("INSERT INTO classes(class_name,kind,source_file,source_line,is_bean,source_tokens) VALUES('p.Contract','INTERFACE','other.java',1,0,1)"));
        var dispatch=calls("invoke").path("calls").get(0).path("dispatch");
        assertEquals("owner_ambiguous",dispatch.path("status").asText()); assertTrue(dispatch.path("possible_targets").isEmpty());
        db.useHandle(h -> h.execute("DELETE FROM class_members WHERE name='value' AND class_id IN(SELECT id FROM classes WHERE class_name='p.Parent')"));
        assertEquals("declaration_not_indexed",calls("parent").path("calls").get(0).path("dispatch").path("status").asText());
    }
}
