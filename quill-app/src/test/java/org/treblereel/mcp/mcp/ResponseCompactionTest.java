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

    private static final String RULE = "a long detection rule explanation repeated for every item";

    private static String entries(int count) {
        var items = new java.util.ArrayList<String>();
        for (int i = 0; i < count; i++) {
            items.add("{\"class\":\"C" + i + "\",\"symbol_id\":\"s" + i + "\",\"detection_rule\":\"" + RULE + "\"}");
        }
        return "{\"entry_points\":[" + String.join(",", items) + "]}";
    }

    @Test
    void repeatedItemFieldIsStatedOnceBesideTheList() throws Exception {
        JsonNode root = compact("find_entry_points", entries(3), Map.of());
        assertEquals(RULE, root.path("entry_points_common").path("detection_rule").asText());
        assertFalse(root.path("entry_points").get(0).has("detection_rule"));
        assertEquals("C2", root.path("entry_points").get(2).path("class").asText());
    }

    @Test
    void fullDetailStatesRepeatedFieldsOnceAndKeepsEverythingElse() throws Exception {
        JsonNode root = compact("find_entry_points", entries(3), Map.of("detail", "full"));
        assertEquals(RULE, root.path("entry_points_common").path("detection_rule").asText());
        assertEquals("s1", root.path("entry_points").get(1).path("symbol_id").asText());
    }

    @Test
    void fieldsThatDifferOrCostNothingStayOnEachItem() throws Exception {
        JsonNode two = compact("find_entry_points", entries(2), Map.of());
        assertFalse(two.has("entry_points_common"));
        assertTrue(two.path("entry_points").get(0).has("detection_rule"));
        JsonNode cheap = compact("list_beans", "{\"beans\":[{\"c\":\"a\",\"k\":1},{\"c\":\"b\",\"k\":1},{\"c\":\"d\",\"k\":1}]}", Map.of());
        assertFalse(cheap.has("beans_common"), cheap.toString());
        assertEquals(1, cheap.path("beans").get(0).path("k").asInt());
        String identical = "{\"a\":[{\"x\":\"" + RULE + "\"},{\"x\":\"" + RULE + "\"},{\"x\":\"" + RULE + "\"}]}";
        assertEquals(JSON.readTree(identical), compact("list_beans", identical, Map.of()));
    }

    @Test
    void callGraphsKeepTheirOwnMetadataSharing() throws Exception {
        JsonNode root = compact("get_call_hierarchy", entries(3).replace("entry_points", "calls"), Map.of());
        assertFalse(root.has("calls_common"));
        assertTrue(root.path("calls").get(0).has("detection_rule"));
    }

    @Test
    void followUpRequestsKeepTheirShape() throws Exception {
        String repeated = entries(3);
        JsonNode root = compact("list_beans", "{\"next_step\":" + repeated + ",\"candidates\":[{\"k\":\"" + RULE + "\",\"c\":1},{\"k\":\"" + RULE + "\",\"c\":2},{\"k\":\"" + RULE + "\",\"c\":3}]}", Map.of());
        assertFalse(root.has("candidates_common"));
        assertFalse(root.path("next_step").has("entry_points_common"));
    }

    @Test
    void toolsReportingStateAndErrorsStayUntouched() throws Exception {
        assertEquals(JSON.readTree(entries(3)), compact("change_session", entries(3), Map.of()));
        String error = entries(3).replaceFirst("\\{", "{\"error_code\":\"X\",");
        assertEquals(JSON.readTree(error), compact("list_beans", error, Map.of()));
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

    private static final String COVERAGE = "{\"scope\":\"indexed_application_bytecode\",\"not_covered\":"
            + "[\"reflection\",\"external_consumers\",\"runtime_dispatch_exhaustiveness\"],\"scan_complete\":true}";

    @Test
    void repeatedSubtreeIsStatedOnceAndLaterPointsToIt() throws Exception {
        String json = "{\"contexts\":[{\"target\":\"A\",\"coverage\":" + COVERAGE + "},{\"target\":\"B\",\"coverage\":" + COVERAGE + "}]}";
        for (String tool : java.util.List.of("get_context", "change_session")) {
            for (Map<String, Object> arguments : java.util.List.<Map<String, Object>>of(Map.of(), Map.of("detail", "full"))) {
                JsonNode root = compact(tool, json, arguments);
                assertEquals(JSON.readTree(COVERAGE), root.at("/contexts/0/coverage"));
                assertEquals("/contexts/0/coverage", root.at("/contexts/1/coverage/same_as").asText(), tool + arguments);
            }
        }
    }

    @Test
    void followUpRequestsStayCallableWhenRepeated() throws Exception {
        String request = "{\"tool\":\"get_context\",\"arguments\":{\"targets\":[\"a.very.long.package.name.ClassName\"],"
                + "\"limit\":20,\"evidence_detail\":\"full\",\"evidence_offset\":0}}";
        JsonNode root = compact("get_context", "{\"a\":{\"x\":1,\"details_request\":" + request + "},\"b\":{\"x\":2,\"details_request\":"
                + request + "}}", Map.of());
        assertEquals("get_context", root.at("/b/details_request/tool").asText());
    }

    @Test
    void nestedMetaThatRepeatsTheRootIsDropped() throws Exception {
        String other = FRESH.replace("\"i\"", "\"other\"");
        JsonNode root = compact("get_context", "{\"a\":{" + FRESH + "},\"b\":{" + other + "}," + FRESH + "}",
                Map.of("evidence_detail", "full"));
        assertFalse(root.path("a").has("_meta"));
        assertEquals("other", root.at("/b/_meta/index_id").asText());
        assertEquals("i", root.at("/_meta/index_id").asText());
    }

    @Test
    void pagingFieldsThatRestateTheListAreDropped() throws Exception {
        JsonNode root = compact("change_session", """
                {"v":{"x":[1,2],"x_count":2,"x_has_more":false,"y":[1],"y_count":5,"y_has_more":true}}""", Map.of());
        assertFalse(root.path("v").has("x_count"));
        assertFalse(root.path("v").has("x_has_more"));
        assertEquals(5, root.at("/v/y_count").asInt());
        assertTrue(root.at("/v/y_has_more").asBoolean());
    }

    @Test
    void emptyListsStayBecauseTheyStateThatNothingIsMissing() throws Exception {
        JsonNode root = compact("get_call_hierarchy", """
                {"calls":[],"coverage":{"incomplete_reasons":[],"scan_complete":true}}""", Map.of());
        assertTrue(root.at("/coverage/incomplete_reasons").isArray());
        assertTrue(root.path("calls").isArray());
    }

}
