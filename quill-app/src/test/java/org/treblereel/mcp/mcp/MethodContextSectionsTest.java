package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodContextSectionsTest {

    @TempDir Path root;
    @AfterEach void stop() { JavaSemanticQueries.close(); }

    /** V21: each embedded section of a method context restated the method's identity and its tool's bookkeeping. */
    @Test
    void embeddedSectionsKeepTheirAnswersAndDropTheMethodsIdentity() throws Exception {
        String runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db = JavaEvidenceFixture.index(root, java.util.Map.of(
                "src/main/java/jakarta/inject/Inject.java", "package jakarta.inject; " + runtime + "public @interface Inject {}",
                "src/main/java/jakarta/enterprise/context/ApplicationScoped.java",
                "package jakarta.enterprise.context; " + runtime + "public @interface ApplicationScoped {}",
                "src/main/java/p/Helper.java", "package p; @jakarta.enterprise.context.ApplicationScoped public class Helper { public int help() { return 1; } }",
                "src/main/java/p/Fixture.java", """
                        package p;
                        @jakarta.enterprise.context.ApplicationScoped
                        class Fixture {
                            @jakarta.inject.Inject Helper helper;
                            int run() { return helper.help(); }
                        }
                        """,
                "src/test/java/p/FixtureTest.java", "package p; class FixtureTest { void test() { new Fixture().run(); } }"));
        String text = TestReads.<String>read(db, reader -> new ContextQueries().getContext(reader, java.util.List.of("p.Fixture#run"), false, 10));
        JsonNode method = new ObjectMapper().readTree(text).path("contexts").get(0).path("method_context");

        for (String section : java.util.List.of("outgoing_calls", "injection_points", "test_impact")) {
            JsonNode node = method.path(section);
            if (node.has("error_code")) continue;
            for (String restated : java.util.List.of("target", "name", "method", "descriptor", "signature", "jvm_name", "jvm_descriptor",
                    "source_name", "language", "member_kind", "location", "evidence_defaults", "limitations"))
                assertFalse(node.has(restated), section + "." + restated + " in " + node);
        }
        assertTrue(method.path("outgoing_calls").path("calls").toString().contains("p.Helper"), method.toString());
        for (JsonNode point : method.path("injection_points").path("injection_points"))
            assertFalse(point.has("resolution_trace"), method.toString());
        assertTrue(method.path("test_impact").toString().contains("FixtureTest"), method.toString());
        assertEquals("run", method.path("name").asText(), "The method context itself still names the method");

        JsonNode helper = new ObjectMapper().readTree(TestReads.<String>read(db, reader -> new ContextQueries()
                .getContext(reader, java.util.List.of("p.Helper"), false, 10))).path("contexts").get(0);
        for (String restated : java.util.List.of("language", "source_name", "jvm_name", "origin", "lifecycle", "module", "source_set", "meta_annotations"))
            assertFalse(helper.path("symbol").has(restated), "symbol." + restated + " in " + helper.path("symbol"));
        assertFalse(helper.path("resolution").has("lifecycle"), helper.path("resolution").toString());
        assertTrue(helper.path("usages").path("coverage").path("scan_complete").asBoolean(), helper.toString());
        assertFalse(helper.path("usages").path("coverage").has("schema_version"), helper.toString());
    }

    @Test
    void aUsageRowStatesItsFileAndKindOnce() throws Exception {
        var context = (com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree("""
                {"usages":{"usages":[{"class":"p.A","usage_kind":"constructor_call","indexed_kind":"CONSTRUCTS","source":"A.java",
                 "evidence_file":"A.java","evidence_lines":[3]},{"class":"p.B","usage_kind":"field_type","source":"B.java","evidence_file":"Gen.java"}],
                 "coverage":{"schema_version":2,"scan_complete":true,"page_complete":false,"incomplete_reasons":["page_window_incomplete"],
                 "source_snapshot":{"state":"matching"},"indexed_scope_count":2}}}
                """);
        ContextQueries.classRestatements(context);
        JsonNode rows = context.path("usages").path("usages");
        assertFalse(rows.get(0).has("evidence_file") || rows.get(0).has("indexed_kind"), rows.toString());
        assertEquals("Gen.java", rows.get(1).path("evidence_file").asText(), "A different evidence file is a fact");
        assertEquals("{\"scan_complete\":true,\"page_complete\":false,\"incomplete_reasons\":[\"page_window_incomplete\"]}",
                context.path("usages").path("coverage").toString());
    }
}
