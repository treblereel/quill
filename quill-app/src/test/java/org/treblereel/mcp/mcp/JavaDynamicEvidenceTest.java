package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.Path;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class JavaDynamicEvidenceTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path root; Jdbi db;
    @BeforeEach void fixture() throws Exception {
        db=JavaEvidenceFixture.index(root,"""
            package p;
            class Store { static Integer persist(){return 1;} private static void hidden(){} private int hiddenField; }
            class Publisher { static void dispatch(){} }
            class CallbackOwner {
                java.util.function.Supplier<Integer> create(){
                    java.util.function.Supplier<Integer> callback=Store::persist;
                    Publisher.dispatch(); return callback;
                }
                void invoke(){Store.persist(); Publisher.dispatch();}
            }
            """);
        db.useHandle(h -> h.execute("INSERT INTO metadata(key,value) VALUES('invokedynamic_evidence_protocol','2')"));
    }
    @Test void callbackCreationIsNotPersistenceInvocationOrAProofOfExecutionOrder() throws Exception {
        var result=JSON.readTree(new ExecutionOrderQueries().analyze(db,"p.CallbackOwner","create",null,Set.of("persist"),Set.of("dispatch")));
        assertEquals(0,result.path("ordering_analysis").path("before_event_count").asInt());
        assertEquals("unknown",result.path("ordering_analysis").path("runtime_order_status").asText());
        assertEquals(1,result.path("dynamic_references").size());
        assertEquals("persist",result.path("dynamic_references").get(0).path("method").asText());
        assertFalse(result.path("dynamic_references").get(0).path("execution_proven").asBoolean());
        var direct=JSON.readTree(new ExecutionOrderQueries().analyze(db,"p.CallbackOwner","invoke",null,Set.of("persist"),Set.of("dispatch")));
        assertEquals("proven_on_normal_completion",direct.path("ordering_analysis").path("runtime_order_status").asText());
    }
    @Test void usageAndHierarchyDistinguishCallbackReferencesFromRealInvocationInstructions() throws Exception {
        var usages=JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db,"p.Store","persist","method",null,"all",10,0));
        assertEquals(2,usages.path("total").asInt());
        var callback=usages.path("usages").valueStream().filter(u -> u.path("invocation_kind").asText().equals("dynamic")).findFirst().orElseThrow();
        assertEquals("callback_reference",callback.path("usage_kind").asText());
        assertEquals("callback_creation",callback.path("execution_semantics").asText());
        assertEquals("lambda_metafactory",callback.path("reference_basis").asText());
        var hierarchy=JSON.readTree(new CallHierarchyQueries().getCallHierarchy(db,"p.CallbackOwner","create",null,"outbound",false,1,10,0));
        assertEquals(1,hierarchy.path("calls").valueStream().filter(u -> u.path("edge_kind").asText().equals("callback_reference")).count());
        db.useHandle(h -> h.execute("DELETE FROM metadata WHERE key='invokedynamic_evidence_protocol'"));
        var legacy=JSON.readTree(new SymbolUsageQueries().findSymbolUsages(db,"p.Store","persist","method",null,"all",10,0));
        assertEquals("legacy_unclassified",legacy.path("usages").valueStream().filter(u -> u.path("invocation_kind").asText().equals("dynamic"))
                .findFirst().orElseThrow().path("reference_basis").asText());
    }
    @Test void unclassifiedHandlesCannotTurnPrivateMembersIntoDeadCodeCandidates() throws Exception {
        var methods=new UnusedMethodQueries(); var fields=new UnusedFieldQueries();
        assertEquals(1,JSON.readTree(methods.findUnusedMethods(db,null,false,true,10,0)).path("total").asInt());
        assertEquals(1,JSON.readTree(fields.findUnusedFields(db,null,false,true,true,10,0)).path("total").asInt());
        db.useHandle(h -> {
            h.execute("INSERT INTO dependencies(from_class_id,to_class_id,kind,occurrence_count) SELECT id,id,'METHOD_HANDLE_REFERENCE',1 FROM classes WHERE class_name='p.Store'");
            h.execute("INSERT INTO dependencies(from_class_id,to_class_id,kind,occurrence_count) SELECT id,id,'FIELD_HANDLE_REFERENCE',1 FROM classes WHERE class_name='p.Store'");
        });
        var methodResult=JSON.readTree(methods.findUnusedMethods(db,null,false,true,10,0));
        var fieldResult=JSON.readTree(fields.findUnusedFields(db,null,false,true,true,10,0));
        assertEquals(0,methodResult.path("total").asInt()); assertEquals(0,fieldResult.path("total").asInt());
        assertEquals(1,methodResult.path("excluded_reason_counts").path("unclassified_method_handle_reference").asInt());
        assertEquals(1,fieldResult.path("excluded_reason_counts").path("unclassified_field_handle_reference").asInt());
    }
}
