package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MethodPrefetchRelationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void methodFactListsAnnotationValuesAndCallsTwoLevelsDownThroughLambdas() throws Exception {
        var db = JavaEvidenceFixture.index(root, """
                package p;
                class Base { void inherited() {} }
                class Fixture extends Base {
                    @Deprecated(since = "9")
                    void start() { helper(); parent(); Runnable task = () -> other(); task.run(); }
                    void helper() { deep(); }
                    void deep() { leaf(); }
                    void leaf() {}
                    void parent() { inherited(); }
                    void other() { System.out.println("x"); }
                }
                """);
        ObjectNode fact = JSON.createObjectNode().put("class", "p.Fixture").put("name", "start").put("descriptor", "()V");
        TestReads.read(db, reader -> { MethodPrefetchRelations.append(reader, fact); return null; });

        assertEquals("@java.lang.Deprecated(since=9)", fact.path("annotations").get(0).asText(), fact.toString());
        List<String> calls = fact.path("calls").valueStream().map(node -> node.asText()).toList();
        assertTrue(calls.contains("1 p.Fixture#helper [virtual]"), calls.toString());
        assertTrue(calls.contains("1 p.Fixture#other [virtual]"), calls.toString());
        assertTrue(calls.contains("2 p.Fixture#deep [virtual]"), calls.toString());
        assertTrue(calls.contains("1 java.lang.Runnable#run():void [external]"), calls.toString());
        assertFalse(calls.contains("3 p.Fixture#leaf [virtual]"), calls.toString());
        assertTrue(calls.contains("2 p.Fixture#inherited [virtual, inherited: declared by p.Base]"), calls.toString());
        assertTrue(calls.stream().noneMatch(call -> call.contains("lambda$")), calls.toString());
        assertEquals("(java.nio.file.Path, java.nio.file.LinkOption[]):boolean",
                MethodPrefetchRelations.javaSignature("(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"));
        assertEquals("", MethodPrefetchRelations.javaSignature("(Lbroken"));
    }

    @Test
    void firedEventObserversAreThoseOfTheEventFieldsTheMethodReads() throws Exception {
        String runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db = JavaEvidenceFixture.index(root, java.util.Map.of(
                "src/main/java/jakarta/inject/Inject.java", "package jakarta.inject; " + runtime + "public @interface Inject {}",
                "src/main/java/jakarta/enterprise/context/ApplicationScoped.java",
                "package jakarta.enterprise.context; " + runtime + "public @interface ApplicationScoped {}",
                "src/main/java/jakarta/enterprise/event/Event.java",
                "package jakarta.enterprise.event; public interface Event<T> { void fire(T event); }",
                "src/main/java/jakarta/enterprise/event/Observes.java",
                "package jakarta.enterprise.event; " + runtime + "public @interface Observes {}",
                "src/main/java/p/Publisher.java", """
                        package p;
                        import jakarta.enterprise.event.Event;
                        @jakarta.enterprise.context.ApplicationScoped
                        public class Publisher {
                            public record Created() {}
                            public record Deleted() {}
                            @jakarta.inject.Inject Event<Created> created;
                            @jakarta.inject.Inject Event<Deleted> deleted;
                            void create() { created.fire(new Created()); }
                            void delete() { deleted.fire(new Deleted()); }
                        }
                        """,
                "src/main/java/p/Listener.java", """
                        package p;
                        import jakarta.enterprise.event.Observes;
                        @jakarta.enterprise.context.ApplicationScoped
                        public class Listener {
                            void onCreated(@Observes Publisher.Created event) {}
                            void onDeleted(@Observes Publisher.Deleted event) {}
                        }
                        """));
        // The fixture indexes bytecode only, so the Publisher bean and its two Event points are added here.
        db.useHandle(handle -> {
            handle.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types) "
                    + "SELECT id, 'CLASS', '@ApplicationScoped', '[\"@Default\"]', '[\"p.Publisher\"]' FROM classes WHERE class_name = 'p.Publisher'");
            for (String field : List.of("created:Created", "deleted:Deleted"))
                handle.execute("INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, "
                        + "resolution_strategy, resolution_reason, resolution_confidence) SELECT b.id, 'FIELD', "
                        + "'jakarta.enterprise.event.Event<p.Publisher$" + field.split(":")[1] + ">', '[\"@Default\"]', '"
                        + field.split(":")[0] + "', 'RESOLVED', 'STATIC_CDI', 'BUILTIN_BEAN', 'high' FROM beans b "
                        + "JOIN classes c ON c.id = b.class_id WHERE c.class_name = 'p.Publisher'");
        });
        ObjectNode fact = JSON.createObjectNode().put("class", "p.Publisher").put("name", "create").put("descriptor", "()V");
        TestReads.read(db, reader -> { MethodPrefetchRelations.append(reader, fact); return null; });

        assertEquals(List.of("created jakarta.enterprise.event.Event<p.Publisher.Created> -> p.Listener#onCreated(@Observes p.Publisher.Created)"),
                fact.path("observers_of_fired_events").valueStream().map(node -> node.asText()).toList(), fact.toString());
        assertEquals("Event fields this method or its walked callees read", fact.path("observers_basis").asText());
    }

    @Test
    void dependencyInterceptorsBoundByTheMethodAnnotationAreListedByPriority() throws Exception {
        String runtime = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db = JavaEvidenceFixture.index(root, java.util.Map.of(
                "src/main/java/tx/Tx.java", "package tx; " + runtime + "public @interface Tx { String value() default \"REQUIRED\"; }",
                "src/main/java/p/Fixture.java", "package p; class Fixture { @tx.Tx(\"NEVER\") void start() {} }"));
        org.treblereel.mcp.db.IndexFixture.writeExternalBeans(db, List.of(
                interceptor(1, "dep.NeverInterceptor", 200, "@tx.Tx(value=NEVER)"),
                interceptor(2, "dep.RequiredInterceptor", 200, "@tx.Tx(value=REQUIRED)"),
                interceptor(3, "dep.AnyTxInterceptor", 100, "@tx.Tx(value=REQUIRED @Nonbinding)")));
        ObjectNode fact = JSON.createObjectNode().put("class", "p.Fixture").put("name", "start").put("descriptor", "()V");
        TestReads.read(db, reader -> { MethodPrefetchRelations.append(reader, fact); return null; });

        assertEquals(List.of("dep.AnyTxInterceptor @Priority(100) via @tx.Tx(value=REQUIRED @Nonbinding) from dep:tx:1",
                        "dep.NeverInterceptor @Priority(200) via @tx.Tx(value=NEVER) from dep:tx:1"),
                fact.path("dependency_interceptors").valueStream().map(node -> node.asText()).toList(), fact.toString());
    }

    private static org.treblereel.mcp.model.ExternalBeanRecord interceptor(int id, String name, int priority, String binding) {
        return new org.treblereel.mcp.model.ExternalBeanRecord(id, name, "INTERCEPTOR", "@Dependent", List.of("@Default", "@Any"),
                List.of(), false, false, priority, List.of(), null, List.of(name), "cdi", "dep:tx:1", "/tmp/tx.jar", List.of(),
                List.of(binding));
    }

    @Test
    void impactCallersBecomeOneLinePerClassAndCoverageKeepsItsVerdict() {
        ObjectNode fact = JSON.createObjectNode();
        ArrayNode tests = fact.putArray("test_callers");
        ObjectNode caller = tests.addObject().put("class", "p.FixtureTest").put("source", "src/test/java/p/FixtureTest.java");
        caller.putArray("methods").add(JSON.createObjectNode().put("method", "a").put("evidence_lines", "[1]"))
                .add(JSON.createObjectNode().put("method", "b"));
        fact.putObject("coverage").put("scan_complete", true).put("snapshot_freshness", "current")
                .putArray("incomplete_reasons").add("compiled_output_coverage_unverified");
        fact.putObject("reference_page_counts").put("tests", 1);

        MethodPrefetchRelations.compactImpact(fact);

        assertEquals("p.FixtureTest src/test/java/p/FixtureTest.java via a, b", fact.path("test_callers").get(0).asText());
        assertTrue(fact.path("coverage").path("scan_complete").asBoolean());
        assertEquals(1, fact.path("coverage").path("incomplete_reasons").size());
        assertFalse(fact.path("coverage").has("snapshot_freshness"));
        assertFalse(fact.has("reference_page_counts"));
    }
}
