package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class PrefetchRestatementsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** V21 CQ-033: 43% of the packet was constant caveats, zero counts and empty sections. */
    @Test
    void aMethodFactKeepsItsAnswerAndLosesItsRestatements() throws Exception {
        ObjectNode fact = (ObjectNode) JSON.readTree("""
                {"kind":"direct_method_impact","scope":"direct selected-member consumers; bounded indirect paths reported separately",
                 "runtime_execution_proven":false,"target":"p.A","name":"ping","descriptor":"()V","signature":"ping():void",
                 "symbol_id":"quill:symbol:v3:p.A~method~ping","location":{"path":"src/A.java","line":null,"basis":"unknown"},
                 "coverage":{"scan_complete":true,"incomplete_reasons":[],"not_covered":["reflection","external_consumers"]},
                 "total":0,"showing":0,"limit":200,"offset":0,"has_more":false,"truncated":false,
                 "limitations":["Only exact usages present in indexed application bytecode are reported"],
                 "class":"p.A","declaration_file":"src/A.java","production_callers":[],"test_callers":[],
                 "override_evidence":{"overrides":[],"showing":0,"total":0,"has_more":false,"truncated":false,"limitations":["x"]},
                 "interface_contracts":[],"unindexed_supertypes":[],"interface_contracts_complete":true,
                 "counts":{"production":{"methods":0,"call_sites":0,"files":0},"total":{"caller_methods":0,"call_sites":0,"consumer_files":0,"files_including_declaration":1},"call_site_definition":"indexed invocation instructions"},
                 "indirect_production":[],"indirect_counts":{"methods":0,"call_sites":0,"files":0},
                 "indirect_scope":{"max_depth":4,"transitive_closure_proven":false,"has_more":false},
                 "answer_scope":"direct consumers and contracts, not an exhaustive runtime or transitive blast radius",
                 "answer_complete":true,"test_scope":"direct static references; not measured runtime test coverage",
                 "calls":["1 java.util.Set#forEach"],"calls_basis":"depth, callee, invocation",
                 "selected_method_source":{"source_file":"src/A.java","source_line":12,"source_end_line":14,"source":"void ping() {}"},
                 "source_sha256":"abc"}
                """);
        fact.put("lambda_evidence_gap", true);
        int before = fact.toString().length();

        PrefetchRestatements.strip(fact);

        for (String gone : java.util.List.of("symbol_id", "source_sha256", "limitations", "coverage", "counts", "override_evidence",
                "interface_contracts", "unindexed_supertypes", "interface_contracts_complete", "indirect_counts", "indirect_scope",
                "answer_scope", "test_scope", "runtime_execution_proven", "declaration_file", "limit", "offset", "has_more", "truncated"))
            assertFalse(fact.has(gone), gone + " in " + fact);
        assertFalse(fact.has("lambda_evidence_gap"), "The body shows its lambdas");
        assertFalse(fact.has("class"), "class restates target");
        assertEquals("src/A.java", fact.path("location").path("path").asText());
        assertEquals(12, fact.path("location").path("line").asInt(), "The body's first line places the declaration");
        assertTrue(fact.path("production_callers").isArray(), "No callers is an answer");
        assertTrue(fact.path("answer_complete").asBoolean());
        assertTrue(fact.path("scope").asText().contains("not proven"), fact.toString());
        assertEquals("void ping() {}", fact.path("selected_method_source").path("source").asText());
        assertTrue(fact.toString().length() < before / 2, before + " -> " + fact.toString().length() + " " + fact);
    }

    @Test
    void gapsAndNonZeroCountsStay() throws Exception {
        ObjectNode fact = (ObjectNode) JSON.readTree("""
                {"kind":"direct_method_impact","coverage":{"scan_complete":false,"incomplete_reasons":["x"],"not_covered":["reflection"]},
                 "counts":{"production":{"methods":2,"call_sites":3,"files":1}},"has_more":true,
                 "override_evidence":{"overrides":[{"class":"p.B"}]},"indirect_production":["p.C via run"],"indirect_counts":{"methods":1}}
                """);
        PrefetchRestatements.strip(fact);
        assertEquals("x", fact.path("coverage").path("incomplete_reasons").get(0).asText(), fact.toString());
        assertFalse(fact.path("coverage").has("not_covered"));
        assertEquals(2, fact.path("counts").path("production").path("methods").asInt());
        assertTrue(fact.path("has_more").asBoolean());
        assertEquals(1, fact.path("override_evidence").path("overrides").size());
        assertEquals(1, fact.path("indirect_counts").path("methods").asInt());
    }

    @Test
    void aClassFactDropsIndexBookkeeping() throws Exception {
        ObjectNode fact = (ObjectNode) JSON.readTree("""
                {"class":"p.A","jvm_name":"p.A","kind":"CLASS","lifecycle":"current","origin":"source","language":"java",
                 "source_set":"main","module":".","source_tokens":120,"members_included":false,"source_name":"A.java",
                 "_meta":{"index_id":"i"},"symbol_id":"s","source":"src/A.java","bean":{"scope":"@ApplicationScoped"}}
                """);
        PrefetchRestatements.strip(fact);
        assertEquals("{\"class\":\"p.A\",\"kind\":\"CLASS\",\"source\":\"src/A.java\",\"bean\":{\"scope\":\"@ApplicationScoped\"}}",
                fact.toString());
    }
}
