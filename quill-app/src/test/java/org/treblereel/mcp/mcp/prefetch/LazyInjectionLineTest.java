package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-030: asked whether the cycles through a service make creation impossible, Claude read
 * the injection points of five services one by one; the injection lines never said which edges
 * were @Lazy, and every cycle there runs through one.
 */
class LazyInjectionLineTest {
    @TempDir Path root;

    @Test
    void injectionLinesMarkLazyEdgesInBothDirections() throws Exception {
        Jdbi db =
                JavaEvidenceFixture.index(
                        root,
                        Map.of(
                                "src/main/java/org/springframework/context/annotation/Lazy.java",
                                "package org.springframework.context.annotation; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) "
                                        + "public @interface Lazy { boolean value() default true; }",
                                "src/main/java/p/Fixture.java",
                                "package p; class A { A(@org.springframework.context.annotation.Lazy B b, C c) {} } "
                                        + "class B { B(A a) {} } class C {}"));
        db.useHandle(
                h -> {
                    h.execute("UPDATE classes SET source_set = 'main', module = 'app'");
                    for (String bean : new String[] {"p.A", "p.B", "p.C"})
                        h.execute(
                                "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', 'singleton', "
                                        + "'[\"@Default\"]', json_array(class_name), 'spring' FROM classes WHERE class_name = ?",
                                bean);
                    String[][] points = {{"p.A", "p.B"}, {"p.A", "p.C"}, {"p.B", "p.A"}};
                    for (String[] p : points)
                        h.execute(
                                "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, resolution_status, "
                                        + "resolution_strategy, resolution_reason, resolution_confidence) SELECT o.id, 'CONSTRUCTOR_PARAM', ?, '[]', '<init>', t.id, "
                                        + "'RESOLVED', 'STATIC_SPRING', 'UNIQUE_ELIGIBLE_CANDIDATE', 'HIGH' FROM beans o JOIN classes oc ON oc.id = o.class_id, "
                                        + "beans t JOIN classes tc ON tc.id = t.class_id WHERE oc.class_name = ? AND tc.class_name = ?",
                                p[1],
                                p[0],
                                p[1]);
                });
        ObjectNode a = fact(db, "p.A");
        String injects = a.path("injects").toString();
        assertTrue(injects.contains("p.B -> p.B (CLASS singleton) [RESOLVED] @Lazy"), injects);
        assertTrue(!injects.contains("p.C -> p.C (CLASS singleton) [RESOLVED] @Lazy"), injects);
        ObjectNode b = fact(db, "p.B");
        assertTrue(b.path("injected_into").toString().contains("@Lazy"), b.toString());
    }

    private static ObjectNode fact(Jdbi db, String className) throws Exception {
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(
                db,
                reader -> {
                    ClassPrefetchRelations.append(
                            reader, fact, reader.symbols.findClassByName(className).orElseThrow());
                    return null;
                });
        return fact;
    }
}
