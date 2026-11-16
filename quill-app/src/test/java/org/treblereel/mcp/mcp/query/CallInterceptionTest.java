package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-027 (Codex): asked where the transaction starts and ends on ReleaseController#publish,
 * Codex read six services one by one for their @Transactional; the call hierarchy named the callees
 * but not their proxy semantics.
 */
class CallInterceptionTest {
    @TempDir Path root;

    private static final Map<String, String> SOURCES =
            Map.of(
                    "src/main/java/org/springframework/transaction/annotation/Propagation.java",
                    """
            package org.springframework.transaction.annotation;
            public enum Propagation { REQUIRED, REQUIRES_NEW }
            """,
                    "src/main/java/org/springframework/transaction/annotation/Transactional.java",
                    """
            package org.springframework.transaction.annotation;
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.METHOD, ElementType.TYPE})
            public @interface Transactional { Propagation propagation() default Propagation.REQUIRED; boolean readOnly() default false; }
            """,
                    "src/main/java/p/Controller.java",
                    """
            package p;
            import org.springframework.transaction.annotation.*;
            class Controller {
              Service service = new Service();
              Audit audit = new Audit();
              Sender sender = new DbSender();
              @Transactional public void publish() { service.save(); audit.log(); helper(); sender.send(); }
              @Transactional(propagation = Propagation.REQUIRES_NEW) void helper() {}
            }
            """,
                    "src/main/java/p/Service.java",
                    """
            package p;
            import org.springframework.transaction.annotation.Transactional;
            class Service { @Transactional(readOnly = true) public void save() {} }
            """,
                    "src/main/java/p/Sender.java",
                    """
            package p;
            interface Sender { void send(); }
            """,
                    "src/main/java/p/DbSender.java",
                    """
            package p;
            import org.springframework.transaction.annotation.Transactional;
            class DbSender implements Sender { @Transactional public void send() {} }
            """,
                    "src/main/java/p/Audit.java",
                    """
            package p;
            import org.springframework.transaction.annotation.Transactional;
            @Transactional class Audit { public void log() {} }
            """);

    private JsonNode hierarchy() throws Exception {
        var db = JavaEvidenceFixture.index(root, SOURCES);
        String text =
                TestReads.read(
                                db,
                                reader ->
                                        new CallHierarchyQueries()
                                                .getCallHierarchy(
                                                        reader,
                                                        "p.Controller",
                                                        "publish",
                                                        null,
                                                        "outbound",
                                                        false,
                                                        1,
                                                        20,
                                                        0))
                        .toString();
        return new ObjectMapper().readTree(text);
    }

    @Test
    void callEndpointsStateTheirTransactionalAttributes() throws Exception {
        JsonNode answer = hierarchy();
        JsonNode interception = answer.path("interception");
        assertEquals(
                "@Transactional(propagation=REQUIRED)",
                interception.path("p.Controller#publish").get(0).asText(),
                answer.toString());
        assertEquals(
                "@Transactional(propagation=REQUIRED, readOnly=true)",
                interception.path("p.Service#save").get(0).asText(),
                answer.toString());
        assertEquals(
                "@Transactional(propagation=REQUIRED) on class",
                interception.path("p.Audit#log").get(0).asText(),
                answer.toString());
        assertTrue(answer.path("interception_basis").asText().contains("proxy"), answer.toString());
    }

    @Test
    void aSelfInvocationIsMarkedAsBypassingTheProxy() throws Exception {
        JsonNode interception = hierarchy().path("interception");
        String helper = interception.path("p.Controller#helper").toString();
        assertTrue(helper.contains("REQUIRES_NEW"), helper);
        assertTrue(helper.contains("self-invocation"), helper);
        assertFalse(interception.path("p.Service#save").toString().contains("self-invocation"));
    }

    /**
     * Apollo MessageSender#sendMessage: the interface is called, DatabaseMessageSender is
     * annotated.
     */
    @Test
    void anInterfaceCalleeStatesItsImplementationsAnnotations() throws Exception {
        JsonNode interception = hierarchy().path("interception");
        assertEquals(
                "via p.DbSender: @Transactional(propagation=REQUIRED)",
                interception.path("p.Sender#send").get(0).asText(),
                interception.toString());
    }
}
