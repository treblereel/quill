package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class CallHierarchyCompactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String RESPONSE =
            """
            {"target":"p.Mapper","calls":[
             {"caller":{"class":"p.Mapper","method":"parse","descriptor":"(Ljava/lang/String;)V","source":"src/Mapper.java","origin":"source","module":"."},
              "callee":{"class":"p.Verb","method":"<init>","descriptor":"()V","source":"src/Mapper.java","origin":"source","module":"."},
              "invocation_kind":"special","dispatch_ref":"d1","occurrence_count":1,"evidence_lines":[109],"depth":1,"traversal_direction":"outbound",
              "path":["p.Mapper#parse(Ljava/lang/String;)V","p.Verb#<init>()V"]},
             {"caller":{"class":"p.Mapper","method":"resolve","descriptor":"()V","source":"src/Mapper.java","origin":"source","module":"."},
              "callee":{"class":"p.Config","method":"permissions","descriptor":"()Ljava/util/Map;","source":"src/Config.java","origin":"source","module":"."},
              "invocation_kind":"interface","dispatch_ref":"d2","occurrence_count":1,"evidence_lines":[67],"depth":1,"traversal_direction":"outbound",
              "path":["p.Mapper#resolve()V","p.Config#permissions()Ljava/util/Map;"]},
             {"caller":{"class":"p.Web","method":"handle","descriptor":"()V","source":"src/Web.java","origin":"source","module":"web"},
              "callee":{"class":"p.Auth","method":"check","descriptor":"()V","source":"src/Auth.java","origin":"dependency","module":"."},
              "invocation_kind":"virtual","dispatch_ref":"d3","occurrence_count":2,"evidence_lines":[5,9],"depth":2,"traversal_direction":"inbound",
              "path":["p.Mapper#toPermission()V","p.Auth#check()V","p.Web#handle()V"]}],
             "dispatch_targets":{
              "d1":{"target_basis":"bytecode_symbolic_reference","declared_target":{"class":"p.Verb","name":"<init>","descriptor":"()V"},
                    "declaration_resolution":"declared","resolved_declaration":{"class":"p.Verb","name":"<init>","descriptor":"()V","lookup_path":["p.Verb"]},
                    "status":"fixed_symbolic_target","possible_target_count":0},
              "d2":{"target_basis":"bytecode_symbolic_reference","declared_target":{"class":"p.Config","name":"permissions","descriptor":"()Ljava/util/Map;"},
                    "declaration_resolution":"declared","resolved_declaration":{"class":"p.Config","name":"permissions","descriptor":"()Ljava/util/Map;","lookup_path":["p.Config"]},
                    "status":"hierarchy_candidates","possible_target_count":0,"limit":20},
              "d3":{"target_basis":"bytecode_symbolic_reference","declared_target":{"class":"p.Auth","name":"check","descriptor":"()V"},
                    "declaration_resolution":"ancestor_not_indexed","resolved_declaration":{"class":"p.Base","name":"check","descriptor":"()V","lookup_path":["p.Auth","p.Base"]},
                    "status":"hierarchy_candidates","possible_target_count":2,"limit":20,"candidates":[{"class":"p.AuthA"},{"class":"p.AuthB"}]}},
             "has_more":false}
            """;

    /**
     * V32 CQ-030: a 25K transitive answer restated its caller and callee in every path and fixed
     * dispatch target.
     */
    @Test
    void directEdgesAndFixedTargetsStopRestatingTheirEndpoints() throws Exception {
        JsonNode out = CallHierarchyCompaction.compactAnswer((ObjectNode) JSON.readTree(RESPONSE));

        JsonNode direct = out.path("calls").get(0);
        assertFalse(direct.has("path"), direct.toString());
        assertFalse(direct.has("dispatch_ref"), "a fixed target is the callee itself");
        assertFalse(out.path("dispatch_targets").has("d1"));
        assertEquals(109, direct.path("evidence_lines").get(0).asInt());
        assertEquals("special", direct.path("invocation_kind").asText());
        assertFalse(direct.path("caller").has("origin"));
        assertFalse(direct.path("caller").has("module"));
        assertEquals("(Ljava/lang/String;)V", direct.path("caller").path("descriptor").asText());
        assertEquals("source", out.path("endpoint_defaults").path("origin").asText());
        assertEquals(".", out.path("endpoint_defaults").path("module").asText());
        assertTrue(out.path("dispatch_basis").asText().contains("without dispatch_ref"));

        JsonNode open = out.path("dispatch_targets").path("d2");
        assertEquals("hierarchy_candidates", open.path("status").asText());
        assertEquals(0, open.path("possible_target_count").asInt());
        assertFalse(open.has("declared_target"));
        assertFalse(open.has("resolved_declaration"));
        assertFalse(open.has("target_basis"));
        assertFalse(open.has("limit"));
        assertEquals("d2", out.path("calls").get(1).path("dispatch_ref").asText());
    }

    /**
     * What the endpoints do not already say stays: a deeper route, another declaration, candidates,
     * non-default origins.
     */
    @Test
    void routesResolvedAncestorsCandidatesAndOtherOriginsStay() throws Exception {
        JsonNode out = CallHierarchyCompaction.compactAnswer((ObjectNode) JSON.readTree(RESPONSE));

        JsonNode deep = out.path("calls").get(2);
        assertEquals(3, deep.path("path").size());
        assertEquals("web", deep.path("caller").path("module").asText());
        assertEquals("dependency", deep.path("callee").path("origin").asText());
        JsonNode target = out.path("dispatch_targets").path(deep.path("dispatch_ref").asText());
        assertEquals("p.Base", target.path("resolved_declaration").path("class").asText());
        assertEquals(2, target.path("candidates").size());
        assertEquals(20, target.path("limit").asInt());
        assertEquals("ancestor_not_indexed", target.path("declaration_resolution").asText());
    }

    @Test
    void errorsPassThrough() throws Exception {
        assertEquals(
                "{\"error_code\":\"CLASS_NOT_FOUND\"}",
                CallHierarchyCompaction.compactAnswer(
                                (ObjectNode) JSON.readTree("{\"error_code\":\"CLASS_NOT_FOUND\"}"))
                        .toString());
    }

    /**
     * V41 CQ-045: a 40 KB transitive inbound answer spent 20 KB on routes that restate earlier
     * edges.
     */
    @Test
    void aDeeperEdgeReachedThroughAnEdgeOnThePagePointsAtItInsteadOfRestatingTheRoute()
            throws Exception {
        String edge =
                "{\"caller\":{\"class\":\"p.%s\",\"method\":\"%s\",\"descriptor\":\"()V\"},\"callee\":{\"class\":\"p.%s\",\"method\":\"%s\",\"descriptor\":\"()V\"},"
                        + "\"depth\":%d,\"traversal_direction\":\"inbound\",\"path\":%s}";
        String json =
                "{\"target\":\"p.T\",\"calls\":["
                        + edge.formatted("X", "x", "T", "t", 1, "[\"p.T#t()V\",\"p.X#x()V\"]")
                        + ","
                        + edge.formatted(
                                "Y", "y", "X", "x", 2, "[\"p.T#t()V\",\"p.X#x()V\",\"p.Y#y()V\"]")
                        + ","
                        + edge.formatted(
                                "Z",
                                "z",
                                "Y",
                                "y",
                                3,
                                "[\"p.T#t()V\",\"p.X#x()V\",\"p.Y#y()V\",\"p.Z#z()V\"]")
                        + ","
                        + edge.formatted(
                                "W",
                                "w",
                                "Q",
                                "q",
                                3,
                                "[\"p.T#t()V\",\"p.R#r()V\",\"p.Q#q()V\",\"p.W#w()V\"]")
                        + "]}";
        JsonNode out = CallHierarchyCompaction.compactAnswer((ObjectNode) JSON.readTree(json));
        JsonNode calls = out.path("calls");
        assertEquals(0, calls.get(1).path("via").asInt(-1), calls.toString());
        assertFalse(calls.get(1).has("path"));
        assertEquals(1, calls.get(2).path("via").asInt(-1), calls.toString());
        // Its route passes through edges not on this page, so it stays written out.
        assertEquals(4, calls.get(3).path("path").size(), calls.toString());
        assertFalse(calls.get(3).has("via"));
        assertTrue(out.path("route_basis").asText().contains("via"), out.toString());
    }

    private static final String SOURCED =
            """
            {"target":"a.ReleaseController","calls":[
             {"caller":{"class":"a.ReleaseController","method":"publish","descriptor":"()V","source":"admin/src/main/java/a/ReleaseController.java","module":"admin"},
              "callee":{"class":"b.Namespace","method":"getClusterName","descriptor":"()Ljava/lang/String;","source":"biz/src/main/java/b/Namespace.java","module":"biz"},
              "invocation_kind":"virtual","occurrence_count":1,"evidence_lines":[134],"depth":1,"traversal_direction":"outbound",
              "source_evidence":{"status":"corroborated","generation":1,"locations":[{"file":"admin/src/main/java/a/ReleaseController.java",
                 "usage_kind":"call","range_kind":"reference_expression","line":134,"column":24,"end_line":134,"end_column":54,
                 "excerpt":"messageCluster = parentNamespace.getClusterName();"}]}},
             {"caller":{"class":"b.Namespace$Inner","method":"run","descriptor":"()V","source":"biz/src/main/java/b/Namespace.java","module":"biz"},
              "callee":{"class":"c.Odd","method":"go","descriptor":"()V","source":"elsewhere/Odd.java","module":"c"},
              "invocation_kind":"virtual","occurrence_count":2,"evidence_lines":[5,9],"depth":2,"traversal_direction":"outbound",
              "source_evidence":{"status":"corroborated","generation":1,"locations":[
                 {"file":"biz/src/main/java/b/Namespace.java","usage_kind":"call","range_kind":"reference_expression","line":5,"column":3,"end_line":6,"end_column":2,"excerpt":"go("},
                 {"file":"biz/src/main/java/b/Namespace.java","usage_kind":"call","range_kind":"reference_expression","line":9,"column":3,"end_line":9,"end_column":7,"excerpt":"go()"}]}}],
             "source_evidence_defaults":{"basis":"compiler"},
             "has_more":false}
            """;

    /**
     * Apollo AQ-027 (Codex): a 25-edge transitive answer was 28 KB; each edge restated its caller's
     * file, its conventional source path, the call kind and the line it already listed.
     */
    @Test
    void edgesStopRestatingConventionalPathsDirectionAndTheirOwnLines() throws Exception {
        JsonNode out = CallHierarchyCompaction.compactAnswer((ObjectNode) JSON.readTree(SOURCED));
        JsonNode first = out.path("calls").get(0);
        assertFalse(first.path("caller").has("source"), first.toString());
        assertFalse(first.path("callee").has("source"), first.toString());
        assertTrue(out.path("endpoint_defaults").path("source").asText().contains("src/main/java"));
        assertFalse(first.has("traversal_direction"), first.toString());
        assertEquals("outbound", out.path("edge_defaults").path("traversal_direction").asText());
        JsonNode evidence = first.path("source_evidence");
        assertFalse(evidence.has("status"), evidence.toString());
        assertFalse(evidence.has("generation"), evidence.toString());
        assertEquals("corroborated", out.path("source_evidence_defaults").path("status").asText());
        assertEquals(1, out.path("source_evidence_defaults").path("generation").asInt());
        assertEquals("compiler", out.path("source_evidence_defaults").path("basis").asText());
        JsonNode location = evidence.path("locations").get(0);
        assertEquals(
                "{\"column\":24,\"end_column\":54,\"excerpt\":\"messageCluster = parentNamespace.getClusterName();\"}",
                location.toString());
        assertEquals(134, first.path("evidence_lines").get(0).asInt());

        JsonNode second = out.path("calls").get(1);
        assertFalse(second.path("caller").has("source"), "a nested class lives in its outer file");
        assertEquals("elsewhere/Odd.java", second.path("callee").path("source").asText());
        JsonNode multi = second.path("source_evidence").path("locations");
        assertEquals(5, multi.get(0).path("line").asInt(), multi.toString());
        assertEquals(6, multi.get(0).path("end_line").asInt(), multi.toString());
        assertFalse(multi.get(1).has("end_line"), multi.toString());
        assertFalse(multi.get(0).has("file"), multi.toString());
    }
}
