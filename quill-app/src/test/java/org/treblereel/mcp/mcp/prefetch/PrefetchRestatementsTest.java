package org.treblereel.mcp.mcp.prefetch;

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

    private static ObjectNode recorded(String name) throws Exception {
        try (var in = PrefetchRestatementsTest.class.getResourceAsStream("/prefetch/" + name)) {
            return (ObjectNode) JSON.readTree(in);
        }
    }

    /** V29 CQ-049: the packet rides in every turn; counts, descriptor and JDK callees restate the lists. */
    @Test
    void aCompleteMethodFactDropsWhatItsListsAlreadySay() throws Exception {
        ObjectNode fact = recorded("v29-cq049-method-fact.json");
        int before = fact.toString().length();

        PrefetchRestatements.strip(fact);

        for (String gone : java.util.List.of("descriptor", "counts", "indirect_counts", "calls", "calls_basis"))
            assertFalse(fact.has(gone), gone + " in " + fact);
        assertFalse(fact.path("indirect_scope").has("has_more"), fact.path("indirect_scope").toString());
        assertFalse(fact.path("indirect_scope").has("traversal_truncated"));
        assertFalse(fact.path("name_in_source").has("basis"), "No comment or string match to explain");
        assertEquals("parse(java.lang.String):io.cryostat.security.rbac.PermissionMapper$K8sResourceVerb",
                fact.path("signature").asText());
        assertTrue(fact.path("production_callers").get(0).asText().contains("lines [80,83]"));
        assertEquals(3, fact.path("declaring_class_tests").size());
        assertTrue(fact.path("selected_method_source").path("source").asText().startsWith("private K8sResourceVerb parse"));
        assertTrue(fact.path("answer_complete").asBoolean());
        assertTrue(fact.toString().length() < before * 3 / 4, before + " -> " + fact.toString().length());
    }

    @Test
    void applicationCalleesAndIncompleteCountsStay() throws Exception {
        ObjectNode fact = (ObjectNode) JSON.readTree("""
                {"kind":"direct_method_impact","answer_complete":false,"counts":{"production":{"methods":2}},
                 "calls":["1 p.B#run():void","1 java.lang.String#trim():java.lang.String [external]"],"calls_basis":"depth, callee",
                 "name_in_source":{"file":"src/A.java","code_lines":[3],"other":["1 comment"],"basis":"word matches"}}
                """);
        PrefetchRestatements.strip(fact);
        assertEquals(2, fact.path("counts").path("production").path("methods").asInt(), fact.toString());
        assertEquals("[\"1 p.B#run():void\"]", fact.path("calls").toString());
        assertTrue(fact.has("calls_basis"));
        assertEquals("word matches", fact.path("name_in_source").path("basis").asText());
    }

    /** V41 CQ-027: every with-Quill answer missed ObjectMapper#readValue, the library call the transaction runs per file. */
    @Test
    void libraryCalleesStayWhileJdkCalleesGo() throws Exception {
        ObjectNode fact = (ObjectNode) JSON.readTree("""
                {"kind":"direct_method_impact","calls":["1 p.B#run():void",
                 "1 com.fasterxml.jackson.databind.ObjectMapper#readValue(java.io.InputStream, java.lang.Class) [external]",
                 "1 java.nio.file.Files#newInputStream(java.nio.file.Path, java.nio.file.OpenOption[]) [external]",
                 "1 jdk.internal.misc.Unsafe#park(boolean, long) [external]",
                 "1 org.jboss.logging.Logger#tracev(java.lang.String, java.lang.Object):void [external]",
                 "2 org.slf4j.Logger#info(java.lang.String):void [external]"],"calls_basis":"depth, callee"}
                """);
        PrefetchRestatements.strip(fact);
        assertEquals("[\"1 p.B#run():void\",\"1 com.fasterxml.jackson.databind.ObjectMapper#readValue(java.io.InputStream, java.lang.Class) [external]\"]",
                fact.path("calls").toString());
    }

    /** V29 CQ-045: annotation plumbing, a lone source occurrence and dependency tallies were a third of the fact. */
    @Test
    void aClassFactDropsWhatEveryClassSays() throws Exception {
        ObjectNode fact = recorded("v29-cq045-class-fact.json");
        int before = fact.toString().length();

        PrefetchRestatements.strip(fact);

        for (String gone : java.util.List.of("superclass", "interfaces", "class_occurrences", "location",
                "external_dependency_breakdown", "external_dependency_reference_count", "external_dependency_type_count",
                "external_dependencies_truncated", "direct_implementation_count", "direct_implementations",
                "direct_implementations_truncated"))
            assertFalse(fact.has(gone), gone + " in " + fact);
        assertEquals("[\"jakarta.enterprise.context.NormalScope\"]", fact.path("meta_annotations").toString());
        assertEquals("[\"io.cryostat.security.rbac.SsarClientCache\"]", fact.path("bean").path("bean_types").toString());
        for (String gone : java.util.List.of("stereotypes", "profiles", "alternative"))
            assertFalse(fact.path("bean").has(gone), gone + " in " + fact.path("bean"));
        assertFalse(fact.path("generated_creation_evidence").get(0).has("jar_modified"));
        assertEquals("@ApplicationScoped", fact.path("bean").path("scope").asText());
        assertEquals(4, fact.path("test_classes").size());
        assertEquals(1, fact.path("injected_into").size());
        assertEquals(5, fact.path("external_dependencies").size());
        assertTrue(fact.toString().length() < before * 4 / 5, before + " -> " + fact.toString().length());
    }

    @Test
    void packetInstructionsNameFrameworkContractsOnlyWhenAFactCarriesOne() throws Exception {
        var plain = JSON.readTree("[{\"kind\":\"CLASS\"}]");
        var framework = JSON.readTree("[{\"kind\":\"direct_method_impact\",\"framework_contract\":{\"x\":1}}]");
        assertFalse(PrefetchRestatements.instructions(plain).contains("framework_contract"));
        assertTrue(PrefetchRestatements.instructions(framework).contains("framework_contract"));
        assertTrue(PrefetchRestatements.instructions(plain).contains("repeating them returns the same facts"));
        // Facts with a pointer to the rest, not orders: a cut that does not say where the rest is
        // sends the agent searching, and imperative hook text can read as an injected command.
        assertTrue(PrefetchRestatements.instructions(plain).contains("get_context or find_usages on the same target returns them"));
        assertFalse(PrefetchRestatements.instructions(plain).matches(".*\\b(must|never|always|do not|only)\\b.*"));
        assertFalse(PrefetchRestatements.instructions(plain).contains("deployed values"));
        assertTrue(PrefetchRestatements.instructions(JSON.readTree("[{\"kind\":\"configuration\"}]")).contains("deployed values"));
    }
}
