package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

/**
 * V41 CQ-030: find_cycles and get_dependencies answers were about 30 KB, much of it paths the class
 * names already give.
 */
class GraphCompactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void cycleMembersAtTheirConventionalPathAreNamesAndEdgesAreOneLine() throws Exception {
        String json =
                """
                {"cycles":[{"member_count":3,"members":[
                  {"class":"p.A","source_file":"src/main/java/p/A.java","module":"."},
                  {"class":"p.B$Inner","source_file":"src/main/java/p/B.java","module":"."},
                  {"class":"p.C","source_file":"gen/p/C.java","module":"."}],
                 "edges":[{"from":"p.A","to":"p.B$Inner","kinds":["CONSTRUCTS"]},{"from":"p.B$Inner","to":"p.A","kinds":["CLASS_REFERENCE","TYPE_USE"]}]}]}
                """;
        JsonNode cycle =
                GraphCompaction.cycles((ObjectNode) JSON.readTree(json)).path("cycles").get(0);
        assertEquals(
                "[\"p.A\",\"p.B$Inner\",{\"class\":\"p.C\",\"source_file\":\"gen/p/C.java\"}]",
                cycle.path("members").toString());
        assertEquals(
                "[\"p.A -> p.B$Inner [CONSTRUCTS]\",\"p.B$Inner -> p.A [CLASS_REFERENCE, TYPE_USE]\"]",
                cycle.path("edges").toString());
        assertTrue(
                GraphCompaction.cycles((ObjectNode) JSON.readTree(json))
                        .path("graph_format")
                        .asText()
                        .contains("source_file"));
        assertEquals(".", cycle.path("members_common").path("module").asText(), cycle.toString());
    }

    @Test
    void dependencyEvidenceInTheConventionalFileOfItsHolderAndMainSourceSetAreNotRepeated()
            throws Exception {
        String json =
                """
                {"target":"p.A","graph":[
                  {"depth":1,"parent":"p.A","relation_direction":"outbound","class":"p.B","kind":"CONSTRUCTS","evidence_file":"src/main/java/p/A.java","evidence_lines":[9],"source_set":"main"},
                  {"depth":1,"parent":"p.A","relation_direction":"inbound","class":"p.C$X","kind":"CALLS","evidence_file":"src/test/java/p/C.java","evidence_lines":[3],"source_set":"test"},
                  {"depth":1,"parent":"p.A","relation_direction":"inbound","class":"p.D","kind":"CALLS","evidence_file":"src/main/java/p/Other.java","evidence_lines":[4],"source_set":"main"}]}
                """;
        JsonNode result = GraphCompaction.dependencies((ObjectNode) JSON.readTree(json));
        JsonNode graph = result.path("graph");
        assertFalse(graph.get(0).has("evidence_file"), graph.toString());
        assertFalse(graph.get(0).has("source_set"));
        assertFalse(graph.get(1).has("evidence_file"));
        assertEquals("test", graph.get(1).path("source_set").asText());
        assertEquals("src/main/java/p/Other.java", graph.get(2).path("evidence_file").asText());
        assertEquals(
                "main",
                result.path("node_defaults").path("source_set").asText(),
                result.toString());
        assertTrue(
                result.path("node_defaults").path("evidence_file").asText().contains("outbound"));
    }
}
