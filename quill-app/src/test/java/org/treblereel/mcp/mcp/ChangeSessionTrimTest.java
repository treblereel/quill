package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/** What V28 CQ-049 answers restated that no agent read, cut without touching what they do read. */
class ChangeSessionTrimTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Agents ran -Dtest only for tests ranked 1-3 of the 20 a snapshot carried (V24-V28, 39 runs). */
    @Test void aSnapshotStatesTheTopRankedTestsAndHowManyMore() throws Exception {
        ObjectNode primary = (ObjectNode) JSON.readTree("{\"action\":\"inspect_test_evidence\",\"evidence_snapshot\":{\"answer_complete\":false,\"tests\":["
                + "{\"class\":\"T1\"},{\"class\":\"T2\"},{\"class\":\"T3\"},{\"class\":\"T4\"},{\"class\":\"T5\"},{\"class\":\"T6\"},{\"class\":\"T7\"}]}}");
        ChangeSessionQueries.trimSnapshotTests(primary);
        JsonNode snapshot = primary.path("evidence_snapshot");
        assertEquals(5, snapshot.path("tests").size(), snapshot.toString());
        assertEquals("T1", snapshot.at("/tests/0/class").asText());
        assertEquals(7, snapshot.path("tests_total").asInt(), snapshot.toString());
        assertEquals("find_impacted_tests", snapshot.path("more_tests_from").asText(), snapshot.toString());
        assertFalse(snapshot.path("answer_complete").asBoolean());
    }

    /** Codex prints the checklist; required items keep their label, advisory ones their evidence, clear ones a count. */
    @Test void aChecklistStatesWhatIsOpenAndCountsWhatIsClear() throws Exception {
        ObjectNode checklist = (ObjectNode) JSON.readTree("""
            {"schema_version":1,"acknowledgement_model":"evidence_only","status":"required","required_count":1,"advisory_count":1,"items":[
             {"id":"test_coverage","label":"Review incomplete affected-test evidence","status":"required","evidence":"verification.test_evidence","evidence_count":2,"reason_code":"INCOMPLETE_TEST_COVERAGE"},
             {"id":"dependency_usages","label":"Review callers and dependency fallout","status":"advisory","evidence":"plan.dependency_review","evidence_count":11,"reason_code":null},
             {"id":"target_resolution","label":"Resolve every requested target","status":"clear","evidence":"verification.blockers","evidence_count":0,"reason_code":null},
             {"id":"module_selection","label":"Resolve build-system module mappings","status":"clear","evidence":"x","evidence_count":0,"reason_code":null}]}
            """);
        JsonNode compact = ChangeSessionQueries.compactChecklist(checklist);
        assertEquals("required", compact.path("status").asText());
        assertEquals(2, compact.path("items").size(), compact.toString());
        assertEquals("Review incomplete affected-test evidence", compact.at("/items/0/label").asText());
        assertEquals("INCOMPLETE_TEST_COVERAGE", compact.at("/items/0/reason_code").asText());
        assertFalse(compact.at("/items/1").has("label"), compact.toString());
        assertEquals(11, compact.at("/items/1/evidence_count").asInt());
        assertEquals(2, compact.path("clear_count").asInt(), compact.toString());
        assertEquals(4, checklist.path("items").size(), "The phase gate reads the whole checklist");
    }

    @Test void aReceiptDropsTheCommandTheDirectiveAlreadyCarries() throws Exception {
        ObjectNode receipt = (ObjectNode) JSON.readTree("{\"verified\":false,\"recommendation\":{\"scope\":\"quick_compile\",\"argv\":[\"mvnw\",\"test-compile\"]}}");
        ObjectNode primary = (ObjectNode) JSON.readTree("{\"action\":\"inspect_test_evidence\",\"preparation_command\":{\"scope\":\"quick_compile\",\"argv\":[\"mvnw\",\"test-compile\"]}}");
        ChangeSessionQueries.dropRestatedRecommendation(receipt, primary);
        assertFalse(receipt.has("recommendation"), receipt.toString());

        ObjectNode other = (ObjectNode) JSON.readTree("{\"verified\":false,\"recommendation\":{\"scope\":\"quick_compile\",\"argv\":[\"mvnw\",\"test-compile\"]}}");
        ChangeSessionQueries.dropRestatedRecommendation(other, (ObjectNode) JSON.readTree("{\"action\":\"inspect_primary\"}"));
        assertTrue(other.has("recommendation"), "Nothing else names the command here");
    }

    /** _meta repeated commits, times and stale reasons verification.build.freshness states. */
    @Test void metaKeepsWhatJudgesTheAnswerAndDropsWhatVerificationStates() throws Exception {
        JsonNode meta = JSON.readTree("""
            {"index_id":"i","freshness_basis":"prepared_input_observation","input_observed_at":"t","indexed_at":"t",
             "indexed_commit":"c","current_commit":"c","commit_stale":false,"worktree_dirty":true,"worktree_changed_files":10,
             "structural_changed_files":2,"structure_stale":true,"stale_reasons":["a"],"stale_warning":true,
             "stale_scope":"repository","answer_confidence":"low","recommended_action":"Compile"}
            """);
        JsonNode stated = ChangeSessionQueries.compactMeta(meta, true);
        for (String kept : java.util.List.of("index_id", "structure_stale", "stale_warning", "stale_scope", "answer_confidence", "recommended_action"))
            assertTrue(stated.has(kept), kept + " " + stated);
        for (String dropped : java.util.List.of("indexed_at", "input_observed_at", "current_commit", "indexed_commit", "stale_reasons", "worktree_changed_files"))
            assertFalse(stated.has(dropped), dropped + " " + stated);
        assertTrue(ChangeSessionQueries.compactMeta(meta, false).has("stale_reasons"), "Without verification nothing else states them");
    }
}
