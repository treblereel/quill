package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ChangeVerificationCompactionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** V26 CQ-049: a supported build protocol, matching source sets and untracked config files took 3 KB of verify_change. */
    @Test void settledBuildFactsAndNonStructuralFilesAreCounted() throws Exception {
        String answer = """
            {"verdict":"needs_build",
             "worktree":{"dirty":true,"changes":[
               {"path":".claude/settings.json","status":"untracked","structural":false},
               {"path":"AGENTS.md","status":"untracked","structural":false},
               {"path":"src/main/java/p/Mapper.java","status":"modified","structural":true}]},
             "build":{"integration":{"state":"installed","path":".mvn/extensions.xml","detail":"current",
                        "protocol":{"status":"supported","features":{"build_events":"available"}}},
                      "compiled_outputs":{"count":2,"status":"fresh","source_sets":[
                        {"source_set":"main","status":"matching","output":"/p/target/classes"},
                        {"source_set":"test","status":"matching","output":"/p/target/test-classes"}]}}}
            """;
        JsonNode result = JSON.readTree(ChangeVerificationQueries.compactAnswer(answer));

        assertEquals(1, result.at("/worktree/changes").size(), result.toString());
        assertEquals("src/main/java/p/Mapper.java", result.at("/worktree/changes/0/path").asText());
        assertEquals(2, result.at("/worktree/non_structural_changes").asInt(), result.toString());
        assertFalse(result.at("/build/integration").has("protocol"), result.toString());
        assertEquals("installed", result.at("/build/integration/state").asText());
        assertFalse(result.at("/build/compiled_outputs").has("source_sets"), result.toString());
        assertEquals(2, result.at("/build/compiled_outputs/source_sets_matching").asInt(), result.toString());
    }

    @Test void anUnsupportedProtocolOrAStaleSourceSetStaysWhole() throws Exception {
        String answer = """
            {"build":{"integration":{"state":"installed","protocol":{"status":"unsupported"}},
                      "compiled_outputs":{"source_sets":[{"source_set":"main","status":"stale"}]}}}
            """;
        JsonNode result = JSON.readTree(ChangeVerificationQueries.compactAnswer(answer));
        assertTrue(result.at("/build/integration").has("protocol"), result.toString());
        assertTrue(result.at("/build/compiled_outputs").has("source_sets"), result.toString());
    }
}
