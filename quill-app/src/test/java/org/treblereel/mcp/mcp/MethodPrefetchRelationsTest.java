package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodPrefetchRelationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void methodFactListsAnnotationValuesAndCallsTwoLevelsDownThroughLambdas() throws Exception {
        var db = JavaEvidenceFixture.index(root, """
                package p;
                class Fixture {
                    @Deprecated(since = "9")
                    void start() { helper(); Runnable task = () -> other(); task.run(); }
                    void helper() { deep(); }
                    void deep() { leaf(); }
                    void leaf() {}
                    void other() { System.out.println("x"); }
                }
                """);
        ObjectNode fact = JSON.createObjectNode().put("class", "p.Fixture").put("name", "start").put("descriptor", "()V");
        TestReads.read(db, reader -> { MethodPrefetchRelations.append(reader, fact); return null; });

        assertEquals("@java.lang.Deprecated(since=9)", fact.path("annotations").get(0).asText(), fact.toString());
        List<String> calls = fact.path("calls").valueStream().map(node -> node.asText()).toList();
        assertTrue(calls.contains("1 p.Fixture#helper [virtual]"), calls.toString());
        assertTrue(calls.contains("1 p.Fixture#other [virtual]"), calls.toString());
        assertTrue(calls.contains("2 p.Fixture#deep [virtual]"), calls.toString());
        assertTrue(calls.contains("1 java.lang.Runnable#run [external]"), calls.toString());
        assertFalse(calls.contains("3 p.Fixture#leaf [virtual]"), calls.toString());
        assertTrue(calls.stream().noneMatch(call -> call.contains("lambda$")), calls.toString());
    }

    @Test
    void impactCallersBecomeOneLinePerClassAndCoverageKeepsItsVerdict() {
        ObjectNode fact = JSON.createObjectNode();
        ArrayNode tests = fact.putArray("test_callers");
        ObjectNode caller = tests.addObject().put("class", "p.FixtureTest").put("source", "src/test/java/p/FixtureTest.java");
        caller.putArray("methods").add(JSON.createObjectNode().put("method", "a").put("evidence_lines", "[1]"))
                .add(JSON.createObjectNode().put("method", "b"));
        fact.putObject("coverage").put("scan_complete", true).put("snapshot_freshness", "current")
                .putArray("incomplete_reasons").add("compiled_output_coverage_unverified");
        fact.putObject("reference_page_counts").put("tests", 1);

        MethodPrefetchRelations.compactImpact(fact);

        assertEquals("p.FixtureTest src/test/java/p/FixtureTest.java via a, b", fact.path("test_callers").get(0).asText());
        assertTrue(fact.path("coverage").path("scan_complete").asBoolean());
        assertEquals(1, fact.path("coverage").path("incomplete_reasons").size());
        assertFalse(fact.path("coverage").has("snapshot_freshness"));
        assertFalse(fact.has("reference_page_counts"));
    }
}
