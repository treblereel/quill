package org.treblereel.mcp.mcp.change;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

class ChangeSessionMemberCallersTest {
    @TempDir Path root;
    private static final String SOURCE =
            """
        package p;
        class Mapper {
          Object resolve(String v){ return v.isEmpty() ? parse("x") : parse(v); }
          private Object parse(String v){ return v; }
          Object unrelated(){ return null; }
        }
        """;

    /**
     * V24 CQ-049: two of three answers named the call sites by line only and missed resolve, which
     * Quill never stated.
     */
    @Test
    void aChangeNamingAMethodOfItsTargetStatesWhereThatMethodIsCalled() throws Exception {
        var db = JavaEvidenceFixture.index(root, SOURCE);
        String answer =
                TestReads.read(
                                db,
                                scope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        scope,
                                                        root,
                                                        List.of("p.Mapper"),
                                                        "Rename Mapper#parse(String) to parseMappingValue and update callers",
                                                        20,
                                                        "summary",
                                                        "plan"))
                        .toString();
        JsonNode session = new ObjectMapper().readTree(answer);

        JsonNode callers = session.path("member_callers");
        assertEquals(1, callers.size(), answer);
        assertEquals("parse", callers.path(0).path("method").asText(), answer);
        assertEquals("p.Mapper", callers.path(0).path("class").asText());
        assertEquals(
                "resolve", callers.path(0).path("callers").path(0).path("method").asText(), answer);
        assertFalse(callers.toString().contains("unrelated"));

        String plain =
                TestReads.read(
                                db,
                                scope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        scope,
                                                        root,
                                                        List.of("p.Mapper"),
                                                        "Add logging",
                                                        20,
                                                        "summary",
                                                        "plan"))
                        .toString();
        assertTrue(
                new ObjectMapper().readTree(plain).path("member_callers").isMissingNode(), plain);
    }

    @Test
    void eachOverloadOfANamedMethodGetsItsOwnCallers() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
            package p;
            class Mapper {
              Object resolve(String v){ return parse(v); }
              Object count(){ return parse(1); }
              private Object parse(String v){ return v; }
              private Object parse(int v){ return v; }
            }
            """);
        String answer =
                TestReads.read(
                                db,
                                scope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        scope,
                                                        root,
                                                        List.of("p.Mapper"),
                                                        "Rename parse",
                                                        20,
                                                        "summary",
                                                        "plan"))
                        .toString();
        JsonNode callers = new ObjectMapper().readTree(answer).path("member_callers");
        assertEquals(2, callers.size(), answer);
        assertTrue(
                callers.toString().contains("resolve") && callers.toString().contains("count"),
                answer);
    }

    /**
     * V25 CQ-049: Claude asked after editing, the auto view was verification, and callers inside
     * plan never showed.
     */
    @Test
    void theCallersShowInTheVerificationViewToo() throws Exception {
        var db = JavaEvidenceFixture.index(root, SOURCE);
        String answer =
                TestReads.read(
                                db,
                                scope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        scope,
                                                        root,
                                                        List.of("p.Mapper"),
                                                        "Renamed parse to parseMappingValue",
                                                        20,
                                                        "summary",
                                                        "verification"))
                        .toString();
        JsonNode session = new ObjectMapper().readTree(answer);
        assertFalse(session.has("plan"), answer);
        assertEquals(
                "resolve",
                session.path("member_callers")
                        .path(0)
                        .path("callers")
                        .path(0)
                        .path("method")
                        .asText(),
                answer);
    }

    /**
     * V28 CQ-049: the change said "call sites in resolve()", and the 15 test methods calling
     * resolve took 1.5 KB.
     */
    @Test
    void aNamedMethodThatCallsAnotherNamedMethodIsContextNotATarget() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
            package p;
            class Mapper {
              Object resolve(String v){ return v.isEmpty() ? parse("x") : parse(v); }
              private Object parse(String v){ return v; }
            }
            class Client { Object a(){ return new Mapper().resolve("a"); } Object b(){ return new Mapper().resolve("b"); } }
            """);
        String answer =
                TestReads.read(
                                db,
                                scope ->
                                        new ChangeSessionQueries()
                                                .snapshot(
                                                        scope,
                                                        root,
                                                        List.of("p.Mapper"),
                                                        "Rename parse and update its call sites in resolve()",
                                                        20,
                                                        "summary",
                                                        "plan"))
                        .toString();
        JsonNode callers = new ObjectMapper().readTree(answer).path("member_callers");
        assertEquals("parse", callers.path(0).path("method").asText(), answer);
        assertEquals(
                1, callers.path(0).path("callers").size(), "One row per calling method: " + answer);
        assertEquals(
                "resolve", callers.path(0).path("callers").path(0).path("method").asText(), answer);
        JsonNode context = callers.path(1);
        assertEquals("resolve", context.path("method").asText(), answer);
        assertFalse(context.has("callers"), "Its own callers are not the change: " + answer);
        assertEquals(2, context.path("callers_total").asInt(), answer);
        assertTrue(context.path("role").asText().contains("parse"), answer);
    }

    /** Apollo AQ-049 (Codex): only private methods named means no class outside can be affected. */
    @Test
    void aChangeNamingOnlyPrivateMethodsIsConfinedToItsClass() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
            package p;
            class Mapper {
              public Object resolve(String v){ return parse(v); }
              Mapper(){}
              private Object parse(String v){ return v; }
            }
            """);
        assertEquals(
                List.of("p.Mapper"),
                TestReads.read(
                        db,
                        scope ->
                                ChangeSessionQueries.privateOnlyClasses(
                                        scope,
                                        List.of("p.Mapper"),
                                        "Rename private parse to parseValue")));
        // Apollo stores the constructor under the class name, which the change names too.
        assertEquals(
                List.of("p.Mapper"),
                TestReads.read(
                        db,
                        scope ->
                                ChangeSessionQueries.privateOnlyClasses(
                                        scope,
                                        List.of("p.Mapper"),
                                        "Rename Mapper private parse() to parseValue")));
        assertEquals(
                List.of(),
                TestReads.read(
                        db,
                        scope ->
                                ChangeSessionQueries.privateOnlyClasses(
                                        scope, List.of("p.Mapper"), "Rename parse and resolve")));
        assertEquals(
                List.of(),
                TestReads.read(
                        db,
                        scope ->
                                ChangeSessionQueries.privateOnlyClasses(
                                        scope, List.of("p.Mapper"), "Add logging")));
    }

    /**
     * Apollo AQ-049 (Codex): renaming a private method got 48 dependent classes and 20 controller
     * tests (23 KB), and the focused command ran all of them.
     */
    @Test
    void aPrivateOnlyChangeDropsDependentsAndKeepsTheClassOwnTests() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var plan =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(
                                """
            {"dependency_review":[{"class":"p.Client"},{"class":"p.Other"}],
             "test_plan":{"tests":[{"class":"p.ClientTest"},{"class":"p.MapperTest"}],
                          "showing":2,"total":2}}
            """);
        var verification =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(
                                """
            {"commands":[{"scope":"quick_compile","argv":["mvn","test-compile"]},
              {"scope":"focused","argv":["mvn","-pl","m","-Dtest=p.ClientTest,p.MapperTest","test"]}]}
            """);
        ChangeSessionQueries.confineToClasses(plan, verification, List.of("p.Mapper"));

        assertEquals(0, plan.path("dependency_review").size(), plan.toString());
        assertEquals(2, plan.path("dependency_scope").path("omitted").asInt(), plan.toString());
        assertTrue(
                plan.path("dependency_scope").path("reason").asText().contains("private"),
                plan.toString());
        assertEquals(
                "[{\"class\":\"p.MapperTest\"}]", plan.path("test_plan").path("tests").toString());
        assertEquals(1, plan.path("test_plan").path("total").asInt(), plan.toString());
        assertEquals(
                "-Dtest=p.MapperTest",
                verification.path("commands").path(1).path("argv").path(3).asText(),
                verification.toString());
    }

    /** Without an own test, the ranked tests stay: running nothing would prove nothing. */
    @Test
    void aPrivateOnlyChangeWithoutAnOwnTestKeepsTheRankedTests() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var plan =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(
                                """
            {"dependency_review":[{"class":"p.Client"}],
             "test_plan":{"tests":[{"class":"p.ClientTest"}],"showing":1,"total":1}}
            """);
        var verification =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(
                                """
            {"commands":[{"scope":"focused","argv":["mvn","-Dtest=p.ClientTest","test"]}]}
            """);
        ChangeSessionQueries.confineToClasses(plan, verification, List.of("p.Mapper"));
        assertEquals(0, plan.path("dependency_review").size(), plan.toString());
        assertTrue(plan.path("test_plan").path("tests").toString().contains("ClientTest"));
        assertEquals(
                "-Dtest=p.ClientTest",
                verification.path("commands").path(0).path("argv").path(1).asText());
    }
}
