package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseCompactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FRESH = "\"_meta\":{\"index_id\":\"i\",\"indexed_at\":\"t\",\"indexed_commit\":\"c\","
            + "\"worktree_dirty\":true,\"worktree_changed_files\":3,\"stale_warning\":false,\"stale_scope\":\"current\","
            + "\"answer_confidence\":\"high\"}";

    private static JsonNode compact(String tool, String json, Map<String, Object> arguments) throws Exception {
        return JSON.readTree(ResponseCompaction.apply(json, tool, arguments));
    }

    @Test
    void freshMetaKeepsTheVerdictAndIdentityOnly() throws Exception {
        JsonNode meta = compact("list_beans", "{" + FRESH + "}", Map.of()).path("_meta");
        assertEquals("i", meta.path("index_id").asText());
        assertFalse(meta.path("stale_warning").asBoolean());
        assertEquals("current", meta.path("stale_scope").asText());
        assertEquals("high", meta.path("answer_confidence").asText());
        assertFalse(meta.has("indexed_at") || meta.has("indexed_commit") || meta.has("worktree_dirty"));
    }

    @Test
    void staleMetaKeepsEveryExplanation() throws Exception {
        String stale = "{\"_meta\":{\"indexed_commit\":\"c\",\"stale_warning\":true,\"worktree_dirty\":true}}";
        assertEquals(JSON.readTree(stale), compact("list_beans", stale, Map.of()));
    }

    @Test
    void symbolIdsGoExceptWhereAnAgentNeedsThemToContinue() throws Exception {
        JsonNode result = compact("get_call_hierarchy", """
                {"symbol_id":"a","member_symbol_id_format":"f","calls":[{"caller":{"symbol_id":"b","class":"p.C"}}],
                 "candidates":[{"symbol_id":"keep"}],"next_page_request":{"arguments":{"symbol_id":"keep"}}}""", Map.of());
        assertFalse(result.has("symbol_id") || result.has("member_symbol_id_format"));
        assertFalse(result.path("calls").get(0).path("caller").has("symbol_id"));
        assertEquals("p.C", result.path("calls").get(0).path("caller").path("class").asText());
        assertEquals("keep", result.path("candidates").get(0).path("symbol_id").asText());
        assertEquals("keep", result.path("next_page_request").path("arguments").path("symbol_id").asText());
    }

    @Test
    void descriptorGoesOnlyBesideASignatureInTheToolsThatRepeatIt() throws Exception {
        String json = "{\"members\":[{\"signature\":\"m()\",\"descriptor\":\"()V\"},{\"descriptor\":\"()I\"}]}";
        JsonNode details = compact("get_symbol_details", json, Map.of()).path("members");
        assertFalse(details.get(0).has("descriptor"));
        assertEquals("m()", details.get(0).path("signature").asText());
        assertEquals("()I", details.get(1).path("descriptor").asText());
        assertEquals(JSON.readTree(json), compact("get_call_hierarchy", json, Map.of()));
    }

    @Test
    void perToolProvenanceGoesWithoutTouchingOtherTools() throws Exception {
        JsonNode context = compact("get_context", """
                {"impacted_tests":{"tests":[{"class":"T","dependency_path":["a"]}]},"contexts":[{"risk":{"score":3,"signals":["s"]}}]}""", Map.of());
        assertFalse(context.path("impacted_tests").path("tests").get(0).has("dependency_path"));
        assertEquals(3, context.path("contexts").get(0).path("risk").path("score").asInt());
        assertFalse(context.path("contexts").get(0).path("risk").has("signals"));
        JsonNode config = compact("find_configuration_references", """
                {"references":[{"declared_source":{"source_sha256":"h","declared_value":"true"},"field_reads":{"basis":"b","sites":[]}}]}""", Map.of());
        assertEquals("true", config.path("references").get(0).path("declared_source").path("declared_value").asText());
        assertFalse(config.path("references").get(0).path("declared_source").has("source_sha256"));
        assertFalse(config.path("references").get(0).path("field_reads").has("basis"));
        JsonNode points = compact("list_injection_points", """
                {"injection_points":[{"provider_evidence":{"declarations":[{"declaring_class":"D","artifact":"a"}]}}]}""", Map.of());
        JsonNode declaration = points.path("injection_points").get(0).path("provider_evidence").path("declarations").get(0);
        assertEquals("D", declaration.path("declaring_class").asText());
        assertFalse(declaration.has("artifact"));
    }

    @Test
    void limitationsAndCoverageAreNeverRemoved() throws Exception {
        JsonNode result = compact("get_context", """
                {"limitations":["static only"],"coverage":{"scan_complete":false,"details_request":{"tool":"get_context"}}}""", Map.of());
        assertEquals("static only", result.path("limitations").get(0).asText());
        assertFalse(result.path("coverage").path("scan_complete").asBoolean());
        assertEquals("get_context", result.path("coverage").path("details_request").path("tool").asText());
    }

    @Test
    void fullDetailStatusToolsErrorsAndPlainTextStayUntouched() throws Exception {
        String json = "{\"symbol_id\":\"a\"," + FRESH + "}";
        assertEquals(JSON.readTree(json), compact("get_context", json, Map.of("evidence_detail", "full")));
        assertEquals(JSON.readTree(json), compact("find_entry_points", json, Map.of("detail", "full")));
        assertEquals(JSON.readTree(json), compact("search_symbols", json, Map.of()));
        assertEquals(JSON.readTree(json), compact("get_overview", json, Map.of()));
        String error = "{\"error_code\":\"AMBIGUOUS_METHOD\",\"symbol_id\":\"a\"}";
        assertEquals(JSON.readTree(error), compact("get_call_hierarchy", error, Map.of()));
        assertEquals("not json", ResponseCompaction.apply("not json", "list_beans", Map.of()));
        assertTrue(ResponseCompaction.apply("{}", "list_beans", Map.of()).contains("{"));
    }
}
