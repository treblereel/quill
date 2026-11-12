package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** V41: Codex asked find_usages, list_beans and get_type_hierarchy about dependency classes 15 times and got nothing back. */
class DependencyClassLookupTest {
    @TempDir Path root;
    Jdbi db;

    @BeforeEach void setup() throws Exception {
        db = JavaEvidenceFixture.index(root, Map.of(
                "src/main/java/p/Codec.java", "package p; public class Codec { Object base64; void secured() {} }",
                "src/main/java/p/Store.java", "package p; public class Store { Object base64; }"));
        db.useHandle(handle -> {
            int codec = handle.createQuery("SELECT id FROM classes WHERE class_name = 'p.Codec'").mapTo(Integer.class).one();
            int store = handle.createQuery("SELECT id FROM classes WHERE class_name = 'p.Store'").mapTo(Integer.class).one();
            handle.execute("INSERT INTO class_external_deps(class_id, external_type, usage_kind) VALUES (?, 'org.lib.Base64', 'FIELD'), "
                    + "(?, 'org.lib.Base64', 'METHOD'), (?, 'org.lib.Base64', 'FIELD')", codec, codec, store);
            handle.execute("UPDATE class_members SET annotations = '[\"org.lib.Secured\"]' WHERE class_id = ? AND name = 'secured'", codec);
        });
    }

    private JsonNode lookup(String target) throws Exception {
        String json = TestReads.read(db, reader ->
                ToolResponseSupport.classLookupError(reader, ClassTargetResolver.resolve(reader, target), target));
        return new ObjectMapper().readTree(json);
    }

    @Test void aDependencyClassListsTheApplicationClassesReferencingIt() throws Exception {
        JsonNode result = lookup("org.lib.Base64");
        assertEquals(List.of("p.Codec [FIELD, METHOD]", "p.Store [FIELD]"),
                result.path("application_references").valueStream().map(JsonNode::asText).toList(), result.toString());
        assertEquals("get_external_symbol_details", result.path("next_step").path("tool").asText());
    }

    @Test void aDependencyAnnotationListsTheApplicationMembersCarryingIt() throws Exception {
        JsonNode result = lookup("org.lib.Secured");
        assertEquals(List.of("p.Codec#secured [ANNOTATION]"),
                result.path("application_references").valueStream().map(JsonNode::asText).toList(), result.toString());
    }

    @Test void anUnreferencedDependencyClassStaysAPlainMiss() throws Exception {
        assertFalse(lookup("org.lib.Unused").has("application_references"));
    }
}
