package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphMetadataTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static ObjectNode graph() {
        ObjectNode root = JSON.createObjectNode();
        root.put("total", 3).put("truncated", true);
        root.putArray("limitations").add("Not runtime coverage");
        var calls = root.putArray("calls");
        for (int i = 0; i < 3; i++) {
            var edge = calls.addObject();
            edge.putObject("caller").put("class", "different.C" + i);
            edge.putObject("callee").put("class", "unrelated.example.Owner")
                    .put("symbol_id", "α漢字".repeat(100)).put("descriptor", "(Ljava/lang/String;)V");
            edge.putObject("source_evidence").put("status", "compiler_arguments_unsupported")
                    .put("bytecode_source_identity_proven", false).put("reference_coverage_complete", false);
            edge.put("occurrence_count", i + 1).putArray("evidence_lines").add(i + 10);
        }
        return root;
    }
    static JsonNode expand(String text) throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(text);
        JsonNode shared = root.remove("shared_metadata");
        root.remove("reference_format");
        if (shared != null) for (JsonNode page:root.findValues("calls")) for (JsonNode row : page) {
            ObjectNode edge = (ObjectNode) row;
            for (String field : List.of("caller", "callee", "dispatch", "source_evidence")) {
                JsonNode id = edge.remove(field + "_ref");
                if (id != null) edge.set(field, shared.path(id.asText()).deepCopy());
            }
        }
        return root;
    }
    @Test void composedContextsShareMetadataAcrossGraphsWithoutLosingAnyFact() throws Exception {
        ObjectNode context=JSON.createObjectNode();var contexts=context.putArray("contexts");
        contexts.addObject().putObject("method_context").set("outgoing_calls",graph());
        contexts.addObject().putObject("method_context").set("outgoing_calls",graph());
        var compact=GraphMetadata.compact(context.toString());
        assertTrue(JSON.readTree(compact).has("shared_metadata"));assertEquals(context,expand(compact));
    }
    @Test void exactRoundTripPreservesUnicodeCountsLimitationsAndUnknownEvidence() throws Exception {
        ObjectNode graph = graph(); String original = graph.toString();
        String compact = GraphMetadata.compact(original);
        assertTrue(compact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < original.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(graph, expand(compact));
        assertEquals(original, graph.toString());
        assertEquals(compact, GraphMetadata.compact(compact));
    }
    @Test void unequalMetadataDoesNotAliasAndKeysCanHaveDifferentOrder() throws Exception {
        ObjectNode graph = graph();
        ((ObjectNode) graph.path("calls").get(1).path("callee")).put("descriptor", "()V");
        var original = graph.path("calls").get(2).path("callee");
        var reordered = JSON.createObjectNode().put("descriptor", original.path("descriptor").asText())
                .put("symbol_id", original.path("symbol_id").asText()).put("class", original.path("class").asText());
        ((ObjectNode) graph.path("calls").get(2)).set("callee", reordered);
        String compact = GraphMetadata.compact(graph.toString());
        assertEquals(graph, expand(compact));
        JsonNode c = JSON.readTree(compact).path("calls");
        assertTrue(c.get(1).has("callee")); assertFalse(c.get(1).has("callee_ref"));
        assertTrue(c.get(0).has("callee_ref"));
    }
    @Test void dispatchTargetReferencesDoNotBlockSharingCallersAndCallees() throws Exception {
        ObjectNode graph = graph();
        graph.putObject("dispatch_targets").putObject("d1").put("owner", "unrelated.example.Owner");
        graph.path("calls").forEach(edge -> ((ObjectNode) edge).put("dispatch_ref", "d1"));
        JsonNode compact = JSON.readTree(GraphMetadata.compact(graph.toString()));
        assertTrue(compact.has("shared_metadata"));
        assertEquals(graph.path("dispatch_targets"), compact.path("dispatch_targets"));
        compact.path("calls").forEach(edge -> {
            assertEquals("d1", edge.path("dispatch_ref").asText());
            assertTrue(edge.has("callee_ref"));
        });
    }
    @Test void noSavingsErrorsMalformedAndReservedNamesRemainExactlyUnchanged() {
        for (String text : List.of("not JSON", "null", "[]", "{\"calls\":[null]}",
                "{\"calls\":[{\"callee\":{},\"caller\":{}}]}",
                "{\"calls\":[{\"caller_ref\":\"existing\"}]}")) assertEquals(text, GraphMetadata.compact(text));
        for (String field : List.of("error", "error_code", "shared_metadata", "reference_format")) {
            ObjectNode g = graph();g.put(field, "existing");
            assertEquals(g.toString(), GraphMetadata.compact(g.toString()));
        }
    }
}
