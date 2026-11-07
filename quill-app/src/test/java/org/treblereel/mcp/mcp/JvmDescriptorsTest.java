package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassMemberRecord;

class JvmDescriptorsTest {
    private ClassMemberRecord method(String descriptor) {
        return new ClassMemberRecord(1, "METHOD", "pick", "pick:indexed", descriptor,
                "void", List.of(), "public", List.of());
    }

    @Test void sourceSelectorsPreserveTypesArityAndExactDescriptors() {
        var member = method("(I[Ljava/lang/String;)V");
        for (String selector : List.of("(I[Ljava/lang/String;)V", "pick:indexed",
                "pick(int, java.lang.String[])", "(int, String...)", "(int,String[])")) {
            assertTrue(JvmDescriptors.matchesSignature(member, selector), selector);
        }
        for (String selector : List.of("(J[Ljava/lang/String;)V", "pick(int,String[]):int",
                "wrong(int,String[])", "(long,String[])", "(int)", "(int,)", "(int,other.String[])")) {
            assertFalse(JvmDescriptors.matchesSignature(member, selector), selector);
        }
        assertTrue(JvmDescriptors.matchesSignature(method("()V"), "pick()"));
        assertFalse(JvmDescriptors.matchesSignature(method("()V"), "(String)"));
        var legacy = new ClassMemberRecord(1, "METHOD", "pick", "pick(int):void",
                "void", List.of("int"), "public", List.of());
        assertTrue(JvmDescriptors.matchesSignature(legacy, "(I)V"));
    }

    private ClassMemberRecord named(String name, String descriptor) {
        return new ClassMemberRecord(1, "METHOD", name, name + ":indexed", descriptor,
                "void", List.of(), "public", List.of());
    }

    @Test void selectorsWithReturnTypeMatchParametersAndErasedReturn() {
        var member = named("reportFor", "(Lio/cryostat/recordings/ActiveRecording;)Lio/smallrye/mutiny/Uni;");
        for (String selector : List.of(
                "(io.cryostat.recordings.ActiveRecording):io.smallrye.mutiny.Uni",
                "reportFor(io.cryostat.recordings.ActiveRecording):io.smallrye.mutiny.Uni",
                "reportFor(ActiveRecording):Uni<java.util.Map<java.lang.String,java.lang.String>>",
                "reportFor(ActiveRecording) : Uni")) {
            assertTrue(JvmDescriptors.matchesSignature(member, selector), selector);
        }
        for (String selector : List.of("reportFor(ActiveRecording):java.lang.String",
                "reportFor(ActiveRecording)Uni", "reportFor(ActiveRecording):", "(ActiveRecording):other.Uni")) {
            assertFalse(JvmDescriptors.matchesSignature(member, selector), selector);
        }
    }

    @Test void genericParametersAreErasedBeforeCountingArity() {
        var member = named("put", "(Ljava/util/Map;)V");
        assertTrue(JvmDescriptors.matchesSignature(member, "(java.util.Map<java.lang.String,java.lang.Integer>)"));
        assertTrue(JvmDescriptors.matchesSignature(member, "put(Map<String,List<Integer>>):void"));
        assertFalse(JvmDescriptors.matchesSignature(member, "(java.util.Map<String,Integer>,int)"));
    }

    @Test void varargsWithReturnTypeMatch() {
        var member = named("log", "([Ljava/lang/String;)V");
        assertTrue(JvmDescriptors.matchesSignature(member, "(java.lang.String...):void"));
        assertTrue(JvmDescriptors.matchesSignature(member, "log(String[]):void"));
    }

    @Test void bridgeMethodsNeedReturnTypeToSelectOne() {
        var bridge = named("value", "()Ljava/lang/Object;");
        var real = named("value", "()Ljava/lang/String;");
        assertTrue(JvmDescriptors.matchesSignature(bridge, "value()"));
        assertTrue(JvmDescriptors.matchesSignature(real, "value()"));
        assertTrue(JvmDescriptors.matchesSignature(bridge, "value():java.lang.Object"));
        assertFalse(JvmDescriptors.matchesSignature(real, "value():java.lang.Object"));
    }

    @Test void simpleNameCollisionsRemainMultipleCandidates() {
        var methods = List.of(method("(Ljava/util/Date;)V"), method("(Ljava/sql/Date;)V"));
        assertEquals(2, methods.stream().filter(m -> JvmDescriptors.matchesSignature(m, "(Date)")).count());
        assertEquals(1, methods.stream().filter(m -> JvmDescriptors.matchesSignature(m, "pick(java.sql.Date)")).count());
    }

    @Test void allBoxedTypesMatchWithoutBoxingOrPrimitiveWidening() {
        String[][] types = {
                {"Boolean", "boolean", "Z"}, {"Byte", "byte", "B"},
                {"Character", "char", "C"}, {"Short", "short", "S"},
                {"Integer", "int", "I"}, {"Long", "long", "J"},
                {"Float", "float", "F"}, {"Double", "double", "D"}
        };
        for (var type : types) {
            var boxed = method("(Ljava/lang/" + type[0] + ";)V");
            var primitive = method("(" + type[2] + ")V");
            for (String selector : List.of("(" + type[0] + ")", "pick(java.lang." + type[0] + ")")) {
                assertTrue(JvmDescriptors.matchesSignature(boxed, selector), selector);
                assertFalse(JvmDescriptors.matchesSignature(primitive, selector), selector);
            }
            for (var other : types) {
                String selector = "(" + other[1] + ")";
                assertFalse(JvmDescriptors.matchesSignature(boxed, selector), selector);
                assertEquals(type[1].equals(other[1]),
                        JvmDescriptors.matchesSignature(primitive, selector), selector);
            }
            assertTrue(JvmDescriptors.matchesSignature(method("([Ljava/lang/" + type[0] + ";)V"),
                    "(" + type[0] + "[])"));
            assertTrue(JvmDescriptors.matchesSignature(method("([" + type[2] + ")V"),
                    "(" + type[1] + "...)"));
            assertFalse(JvmDescriptors.matchesSignature(boxed, "(other." + type[0] + ")"));
        }
        var collisions = List.of(method("(Ljava/lang/Integer;)V"), method("(Lother/Integer;)V"));
        assertEquals(2, collisions.stream().filter(m -> JvmDescriptors.matchesSignature(m, "(Integer)")).count());
        assertEquals(1, collisions.stream().filter(m -> JvmDescriptors.matchesSignature(m, "(java.lang.Integer)")).count());
    }

    @Test void realQueriesSelectBoxedAndPrimitiveOverloadsSeparately(@TempDir Path root) throws Exception {
        var db = JavaEvidenceFixture.index(root, """
                package p;
                class Target {
                    void pick(java.lang.Integer value) {}
                    void pick(java.lang.Double value) {}
                    void pick(int value) {}
                    void pick(double value) {}
                }
                class Caller {
                    void run(Target t) {
                        t.pick(Integer.valueOf(1)); t.pick(Double.valueOf(1.0));
                        t.pick(1); t.pick(1.0);
                    }
                }
                """);
        var json = new ObjectMapper();
        for (var type : List.of("Integer", "Double", "int", "double")) {
            String descriptor = switch (type) {
                case "Integer", "Double" -> "(Ljava/lang/" + type + ";)V";
                case "int" -> "(I)V";
                default -> "(D)V";
            };
            for (var selector : List.of("(" + type + ")", "pick(" +
                    (Character.isUpperCase(type.charAt(0)) ? "java.lang." : "") + type + ")")) {
                var hierarchy = json.readTree(ReadQueryContext.<String>readWithGraph(db,
                        context -> new CallHierarchyQueries().getCallHierarchy(context,
                                "p.Target", "pick", selector, "inbound", false, 1, 10, 0)));
                var usages = json.readTree(ReadQueryContext.<String>readWithGraph(db,
                        context -> new SymbolUsageQueries().findSymbolUsages(context,
                                "p.Target", "pick", "method", selector, "all", 10, 0)));
                assertEquals(descriptor, hierarchy.path("descriptor").asText(), selector);
                assertEquals(descriptor, usages.path("descriptor").asText(), selector);
                assertEquals(1, hierarchy.path("total").asInt(), hierarchy.toString());
                assertEquals(1, usages.path("total").asInt(), usages.toString());
            }
        }
    }
}
