package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

class LambdaCallHierarchyTest {

    @TempDir Path root;

    /**
     * V21 CQ-027: agents asked for lambda$onStart$0, which call evidence names, and got
     * METHOD_NOT_FOUND.
     */
    @Test
    void aLambdaNamedByCallEvidenceHasItsOwnCalls() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
                package p;
                class Fixture {
                    void start(java.util.List<String> paths) { paths.forEach(path -> persist(path.trim())); }
                    void persist(String path) {}
                }
                """);
        String text =
                TestReads.read(
                                db,
                                reader ->
                                        new CallHierarchyQueries()
                                                .getCallHierarchy(
                                                        reader,
                                                        "p.Fixture",
                                                        "lambda$start$0",
                                                        null,
                                                        "outbound",
                                                        false,
                                                        1,
                                                        20,
                                                        0))
                        .toString();
        var answer = new ObjectMapper().readTree(text);

        assertTrue(!answer.has("error_code"), text);
        assertEquals("start", answer.path("lambda_declared_by").asText(), text);
        assertTrue(answer.path("calls").toString().contains("persist"), text);
    }

    /**
     * V41 CQ-014: Codex asked for getTemplate, the class declares getTemplates, and the error
     * listed no candidates.
     */
    @Test
    void anUnknownMethodNamesTheClosestDeclaredMethods() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
                package p;
                class Fixture {
                    java.util.List<String> getTemplates() { return null; }
                    void addTemplate(String t) {}
                    void close() {}
                }
                """);
        String text =
                TestReads.read(
                                db,
                                reader ->
                                        new CallHierarchyQueries()
                                                .getCallHierarchy(
                                                        reader,
                                                        "p.Fixture",
                                                        "getTemplate",
                                                        null,
                                                        "outbound",
                                                        false,
                                                        1,
                                                        20,
                                                        0))
                        .toString();
        var answer = new ObjectMapper().readTree(text);

        assertEquals("METHOD_NOT_FOUND", answer.path("error_code").asText(), text);
        assertEquals("getTemplates", answer.path("similar_methods").get(0).asText(), text);
    }
}
