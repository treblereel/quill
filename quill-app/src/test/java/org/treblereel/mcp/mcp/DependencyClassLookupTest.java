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
            // p.Codec implements the library's Encoder; p.Store injects Base64 (from a producer on p.Codec) and Event<p.Codec> (built in).
            handle.execute("UPDATE classes SET interfaces = '[\"org.lib.Encoder\"]' WHERE id = ?", codec);
            handle.execute("INSERT INTO class_external_deps(class_id, external_type, usage_kind) VALUES (?, 'org.lib.Encoder', 'IMPLEMENTS'), "
                    + "(?, 'org.lib.Event', 'FIELD')", codec, store);
            handle.execute("INSERT INTO beans(id, class_id, kind, scope) VALUES (1, ?, 'CLASS', '@Singleton'), (2, ?, 'CLASS', '@Singleton')", store, codec);
            handle.execute("INSERT INTO beans(id, class_id, kind, scope, declaring_class_id, member_name, bean_types) VALUES "
                    + "(3, ?, 'PRODUCER_METHOD', '@ApplicationScoped', ?, 'produceBase64', '[\"org.lib.Base64\"]')", codec, codec);
            handle.execute("INSERT INTO injection_points(bean_id, kind, target_type, field_name, resolved_bean_id, resolution_status, "
                    + "resolution_strategy, resolution_reason, resolution_confidence) VALUES "
                    + "(1, 'FIELD', 'org.lib.Base64', 'base64', 3, 'RESOLVED', 'TYPE', 'SINGLE_CANDIDATE', 'HIGH'), "
                    + "(1, 'FIELD', 'org.lib.Event<p.Codec>', 'codecEvent', NULL, 'RESOLVED', 'BUILTIN', 'BUILTIN_BEAN', 'HIGH'), "
                    + "(1, 'FIELD', 'org.lib.Bus', 'bus', NULL, 'UNKNOWN', 'TYPE', 'NO_CONTEXT_CANDIDATE', 'LOW')");
            handle.execute("INSERT INTO external_beans(class_name, kind, scope, qualifiers, stereotypes, profiles, member_name, bean_types, "
                    + "framework, artifact, injection_points) VALUES ('org.lib.BusProducer', 'PRODUCER_METHOD', '@Singleton', '[]', '[]', '[]', "
                    + "'bus', '[\"org.lib.Bus\"]', 'cdi', 'org.lib:lib:1', '[]')");
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

    private JsonNode answer(java.util.function.Function<org.treblereel.mcp.db.ReadQueryContext, String> query) throws Exception {
        return new ObjectMapper().readTree(TestReads.read(db, query::apply));
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asText).toList();
    }

    // V47 CQ-011/CQ-042: find_usages(Base64, injection) was a CLASS_NOT_FOUND although the index holds its injection points.
    @Test void usagesOfADependencyClassAreItsInjectionPointsAndReferences() throws Exception {
        JsonNode injection = answer(reader -> new UsageToolQueries().findUsages(reader, "org.lib.Base64", "injection", null, 50, 0));
        assertFalse(injection.has("error_code"), injection.toString());
        assertEquals("dependency", injection.path("origin").asText());
        assertEquals(List.of("p.Store.base64 -> p.Codec#produceBase64 (PRODUCER_METHOD @ApplicationScoped)"),
                texts(injection.path("injection_points")), injection.toString());
        JsonNode all = answer(reader -> new UsageToolQueries().findUsages(reader, "org.lib.Base64", null, null, 50, 0));
        assertEquals(List.of("p.Codec [FIELD, METHOD]", "p.Store [FIELD]"), texts(all.path("application_references")), all.toString());
    }

    // V47 CQ-010: find_implementations / get_type_hierarchy(jakarta Event) failed; the application's subtypes of a library type are indexed.
    @Test void implementationsAndDescendantsOfADependencyTypeAreTheApplicationSubtypes() throws Exception {
        JsonNode implementations = answer(reader -> new StructureToolQueries().findImplementations(reader, "org.lib.Encoder", true, null, null, 50, 0));
        assertEquals("dependency", implementations.path("origin").asText(), implementations.toString());
        assertEquals(List.of("p.Codec"), implementations.path("implementations").valueStream().map(node -> node.path("class").asText()).toList());
        JsonNode none = answer(reader -> new StructureToolQueries().findImplementations(reader, "org.lib.Event", true, null, null, 50, 0));
        assertEquals(0, none.path("implementation_class_count").asInt(-1), none.toString());
        JsonNode hierarchy = answer(reader -> new TypeHierarchyQueries().getTypeHierarchy(reader, "org.lib.Encoder", "descendants", 5, 50, 0));
        assertFalse(hierarchy.has("error_code"), hierarchy.toString());
        assertEquals(true, hierarchy.toString().contains("p.Codec"), hierarchy.toString());
    }

    // V47 CQ-010: list_beans(jakarta Event, origin=application) failed; no application bean has the type and its injection points are built in.
    @Test void beansOfADependencyTypeStateWhatItsInjectionPointsResolveTo() throws Exception {
        JsonNode event = answer(reader -> new StructureToolQueries().getBeans(reader, "org.lib.Event", null, null, null, null, null, null, "application", 50, 0));
        assertFalse(event.has("error_code"), event.toString());
        assertEquals(0, event.path("beans").size(), event.toString());
        assertEquals(List.of("p.Store.codecEvent (org.lib.Event<p.Codec>) -> built-in bean"), texts(event.path("injection_points")), event.toString());
        JsonNode points = answer(reader -> new StructureToolQueries().getInjectionPoints(reader, "org.lib.Base64"));
        assertEquals(List.of("p.Store.base64 -> p.Codec#produceBase64 (PRODUCER_METHOD @ApplicationScoped)"), texts(points.path("injection_points")), points.toString());
        assertEquals(List.of("p.Codec#produceBase64 (PRODUCER_METHOD @ApplicationScoped)"), texts(points.path("beans")), points.toString());
        assertEquals(true, points.path("answer").asText().startsWith("1 application bean has dependency type org.lib.Base64"), points.toString());
    }

    // V47 CQ-004: the 28 EventBus injection points had no application bean; the dependency's producer is indexed.
    @Test void anInjectionPointWithoutAnApplicationBeanNamesTheDependencyProducer() throws Exception {
        JsonNode bus = answer(reader -> new UsageToolQueries().findUsages(reader, "org.lib.Bus", "injection", null, 50, 0));
        assertEquals(List.of("p.Store.bus -> no application bean"), texts(bus.path("injection_points")), bus.toString());
        assertEquals(List.of("org.lib.BusProducer#bus (PRODUCER_METHOD @Singleton, org.lib:lib:1)"), texts(bus.path("dependency_beans")), bus.toString());
    }

    @Test void anUnreferencedDependencyClassStaysAPlainMiss() throws Exception {
        assertFalse(lookup("org.lib.Unused").has("application_references"));
    }
}
