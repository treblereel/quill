package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChangeSessionMemberCallersTest {
    @TempDir Path root;
    private static final String SOURCE = """
        package p;
        class Mapper {
          Object resolve(String v){ return v.isEmpty() ? parse("x") : parse(v); }
          private Object parse(String v){ return v; }
          Object unrelated(){ return null; }
        }
        """;

    /** V24 CQ-049: two of three answers named the call sites by line only and missed resolve, which Quill never stated. */
    @Test void aChangeNamingAMethodOfItsTargetStatesWhereThatMethodIsCalled() throws Exception {
        var db = JavaEvidenceFixture.index(root, SOURCE);
        String answer = TestReads.<String>read(db, scope -> new ChangeSessionQueries().snapshot(scope, root,
                List.of("p.Mapper"), "Rename Mapper#parse(String) to parseMappingValue and update callers", 20, "summary", "plan"));
        JsonNode session = new ObjectMapper().readTree(answer);

        JsonNode callers = session.path("member_callers");
        assertEquals(1, callers.size(), answer);
        assertEquals("parse", callers.path(0).path("method").asText(), answer);
        assertEquals("p.Mapper", callers.path(0).path("class").asText());
        assertEquals("resolve", callers.path(0).path("callers").path(0).path("method").asText(), answer);
        assertFalse(callers.toString().contains("unrelated"));

        String plain = TestReads.<String>read(db, scope -> new ChangeSessionQueries().snapshot(scope, root,
                List.of("p.Mapper"), "Add logging", 20, "summary", "plan"));
        assertTrue(new ObjectMapper().readTree(plain).path("member_callers").isMissingNode(), plain);
    }

    @Test void eachOverloadOfANamedMethodGetsItsOwnCallers() throws Exception {
        var db = JavaEvidenceFixture.index(root, """
            package p;
            class Mapper {
              Object resolve(String v){ return parse(v); }
              Object count(){ return parse(1); }
              private Object parse(String v){ return v; }
              private Object parse(int v){ return v; }
            }
            """);
        String answer = TestReads.<String>read(db, scope -> new ChangeSessionQueries().snapshot(scope, root,
                List.of("p.Mapper"), "Rename parse", 20, "summary", "plan"));
        JsonNode callers = new ObjectMapper().readTree(answer).path("member_callers");
        assertEquals(2, callers.size(), answer);
        assertTrue(callers.toString().contains("resolve") && callers.toString().contains("count"), answer);
    }

    /** V25 CQ-049: Claude asked after editing, the auto view was verification, and callers inside plan never showed. */
    @Test void theCallersShowInTheVerificationViewToo() throws Exception {
        var db = JavaEvidenceFixture.index(root, SOURCE);
        String answer = TestReads.<String>read(db, scope -> new ChangeSessionQueries().snapshot(scope, root,
                List.of("p.Mapper"), "Renamed parse to parseMappingValue", 20, "summary", "verification"));
        JsonNode session = new ObjectMapper().readTree(answer);
        assertFalse(session.has("plan"), answer);
        assertEquals("resolve", session.path("member_callers").path(0).path("callers").path(0).path("method").asText(), answer);
    }

    /** V28 CQ-049: the change said "call sites in resolve()", and the 15 test methods calling resolve took 1.5 KB. */
    @Test void aNamedMethodThatCallsAnotherNamedMethodIsContextNotATarget() throws Exception {
        var db = JavaEvidenceFixture.index(root, """
            package p;
            class Mapper {
              Object resolve(String v){ return v.isEmpty() ? parse("x") : parse(v); }
              private Object parse(String v){ return v; }
            }
            class Client { Object a(){ return new Mapper().resolve("a"); } Object b(){ return new Mapper().resolve("b"); } }
            """);
        String answer = TestReads.<String>read(db, scope -> new ChangeSessionQueries().snapshot(scope, root,
                List.of("p.Mapper"), "Rename parse and update its call sites in resolve()", 20, "summary", "plan"));
        JsonNode callers = new ObjectMapper().readTree(answer).path("member_callers");
        assertEquals("parse", callers.path(0).path("method").asText(), answer);
        assertEquals(1, callers.path(0).path("callers").size(), "One row per calling method: " + answer);
        assertEquals("resolve", callers.path(0).path("callers").path(0).path("method").asText(), answer);
        JsonNode context = callers.path(1);
        assertEquals("resolve", context.path("method").asText(), answer);
        assertFalse(context.has("callers"), "Its own callers are not the change: " + answer);
        assertEquals(2, context.path("callers_total").asInt(), answer);
        assertTrue(context.path("role").asText().contains("parse"), answer);
    }
}
