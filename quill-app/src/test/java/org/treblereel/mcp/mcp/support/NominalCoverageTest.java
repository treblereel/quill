package org.treblereel.mcp.mcp.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Apollo (Codex, Claude): every find_usages and get_call_hierarchy answer carried a 1.1-1.4 KB
 * coverage block, nearly always saying that everything indexed was scanned and fresh; 7-11 calls
 * per case, each re-read by every later request.
 */
class NominalCoverageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String NOMINAL =
            """
            {"target":"p.A","usages":[],"coverage":{"schema_version":2,"scope":"indexed_application_bytecode",
             "not_covered":["repository_usage_completeness","external_consumers","reflection","source_only_references","runtime_dispatch_exhaustiveness","bytecode_source_identity"],
             "source_snapshot":{"state":"matching","basis":"sha256","language_scope":"jvm"},"snapshot_freshness":"current_observed_worktree",
             "incomplete_reasons":[],"compiled_outputs":{"state":"fresh","basis":"prepared_build_observation","bytecode_identity_proven":false,
             "changed_after_index":false,"content_matches_index":true,"stale_modules":[]},"module_discovery":"complete",
             "indexed_scopes":[{"module":"a","source_set":"main","class_count":3}],"scope_inventory_truncated":false,"scan_complete":true,
             "page_complete":true,"unclassified_handle_reference_groups":0,"legacy_dynamic_reference_groups":0,"indexed_scope_verified":true,
             "missing_test_output_modules":[]}}
            """;

    private ObjectNode present(String json) throws Exception {
        ObjectNode root = (ObjectNode) JSON.readTree(json);
        ToolResponseSupport.queryEvidence(root, "find_usages", Map.of("target", "p.A"), false, 0);
        return root;
    }

    @Test
    void nominalCoverageIsOneShortStatement() throws Exception {
        var coverage = present(NOMINAL).path("coverage");
        assertEquals("complete", coverage.path("state").asText(), coverage.toString());
        assertTrue(coverage.path("not_covered").isArray(), coverage.toString());
        assertTrue(coverage.has("details_request"), coverage.toString());
        assertFalse(coverage.has("source_snapshot"), coverage.toString());
        assertFalse(coverage.has("compiled_outputs"), coverage.toString());
        assertTrue(
                coverage.toString().length() < 500, coverage.toString().length() + " " + coverage);
    }

    @Test
    void coverageWithAnIncompleteReasonStaysDetailed() throws Exception {
        var coverage =
                present(
                                NOMINAL.replace(
                                        "\"incomplete_reasons\":[]",
                                        "\"incomplete_reasons\":[\"compiled_outputs_changed_after_index\"]"))
                        .path("coverage");
        assertFalse(coverage.has("state"), coverage.toString());
        assertTrue(coverage.has("compiled_outputs"), coverage.toString());
    }

    /** A page window left open is what has_more already says; it is the only reason here. */
    @Test
    void anOpenPageWindowAloneStillCompactsAndKeepsItsReason() throws Exception {
        var coverage =
                present(
                                NOMINAL.replace(
                                                "\"incomplete_reasons\":[]",
                                                "\"incomplete_reasons\":[\"page_window_incomplete\"]")
                                        .replace(
                                                "\"page_complete\":true", "\"page_complete\":false")
                                        // An open page also leaves the scope unverified (Apollo).
                                        .replace(
                                                "\"indexed_scope_verified\":true",
                                                "\"indexed_scope_verified\":false"))
                        .path("coverage");
        assertEquals("complete", coverage.path("state").asText(), coverage.toString());
        assertEquals(
                "[\"page_window_incomplete\"]", coverage.path("incomplete_reasons").toString());
    }
}
