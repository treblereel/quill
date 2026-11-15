package org.treblereel.mcp.mcp.change;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.query.CallHierarchyQueries;

class DirectCalleeComparisonTest {
    @TempDir Path root;

    @Test
    void exactSetFactsRequireCompletePagesAndStableIdentities() throws Exception {
        var contexts = new ObjectMapper().createArrayNode();
        for (String target : List.of("left", "right")) {
            var calls =
                    contexts.addObject()
                            .put("requested_target", target)
                            .putObject("method_context")
                            .putObject("outgoing_calls");
            var edges = calls.putArray("calls");
            edges.addObject().putObject("callee").put("symbol_id", "shared");
            edges.addObject().putObject("callee").put("symbol_id", target);
        }
        var facts = DirectCalleeComparison.compare(contexts);
        assertTrue(facts.path("answer_complete").asBoolean());
        assertEquals(1, facts.path("common_callees_across_all").size());
        assertEquals(
                "left",
                facts.path("exclusive_callees_by_target")
                        .path("left")
                        .get(0)
                        .path("symbol_id")
                        .asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                        contexts.get(1).path("method_context").path("outgoing_calls"))
                .put("has_more", true);
        facts = DirectCalleeComparison.compare(contexts);
        assertFalse(facts.path("answer_complete").asBoolean());
        assertFalse(facts.has("exclusive_callees_by_target"));
    }

    @Test
    void actualGraphUsesStableCalleeIdsAndDoesNotCountCallbacksAsCalls() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Fixture {void shared(){} void own(){} void left(){shared();own();} void right(){shared();} }");
        var mapper = new ObjectMapper();
        var contexts = mapper.createArrayNode();
        for (String method : List.of("left", "right")) {
            var calls =
                    TestReads.read(
                            db,
                            readScope ->
                                    new CallHierarchyQueries()
                                            .getCallHierarchy(
                                                    readScope,
                                                    "p.Fixture",
                                                    method,
                                                    "()V",
                                                    "outbound",
                                                    false,
                                                    1,
                                                    25,
                                                    0));
            contexts.addObject()
                    .put("requested_target", method)
                    .putObject("method_context")
                    .set("outgoing_calls", calls);
        }
        var facts = DirectCalleeComparison.compare(contexts);
        assertTrue(facts.path("answer_complete").asBoolean(), facts.toString());
        assertEquals(
                "shared", facts.path("common_callees_across_all").get(0).path("method").asText());
        assertEquals(
                "own",
                facts.path("exclusive_callees_by_target")
                        .path("left")
                        .get(0)
                        .path("method")
                        .asText());
    }

    @Test
    void bridgeParameterSelectorKeepsRequestedJvmIdentity() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface Port<T>{T accept(T x);} class Fixture implements Port<String>{public String accept(String x){return x;}}");
        var json =
                TestReads.read(
                        db,
                        readScope ->
                                new TestImpactQueries()
                                        .findImpactedTests(
                                                readScope,
                                                List.of("p.Fixture#accept(java.lang.Object)"),
                                                false,
                                                1,
                                                10,
                                                0));
        assertEquals(
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                json.path("descriptor").asText(),
                json.toString());
        assertFalse(json.path("runtime_coverage_measured").asBoolean());
    }
}
