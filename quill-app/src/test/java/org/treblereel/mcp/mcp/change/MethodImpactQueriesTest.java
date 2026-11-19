package org.treblereel.mcp.mcp.change;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.prefetch.MethodPrefetchRelations;
import org.treblereel.mcp.mcp.prefetch.PrefetchContext;

class MethodImpactQueriesTest {
    @TempDir Path root;
    private static final String SOURCE =
            """
        package p;
        class Fixture { int map(String x){return x.length();} int map(int x){return x;} }
        class Consumer {int use(){var f=new Fixture();return f.map("a")+f.map("b");}}
        class ConsumerTest {int verify(){return new Fixture().map("c");}}
        """;

    @Test
    void exactImpactCountsPagesAndOverloadsWithoutRepeatedGraphs() throws Exception {
        var db = JavaEvidenceFixture.index(root, SOURCE);
        try {
            db.useHandle(
                    h ->
                            h.execute(
                                    "UPDATE classes SET source_set='test' WHERE class_name='p.ConsumerTest'"));
            var data =
                    TestReads.read(
                            db,
                            readScope ->
                                    new MethodImpactQueries()
                                            .get(
                                                    readScope,
                                                    "p.Fixture",
                                                    "map(java.lang.String)",
                                                    200));
            assertEquals(1, data.path("production_callers").size(), data.toString());
            assertEquals(1, data.path("test_callers").size());
            assertEquals(2, data.path("reference_page_counts").path("caller_methods").asInt());
            assertEquals(
                    3, data.path("reference_page_counts").path("invocation_occurrences").asInt());
            assertEquals(1, data.path("counts").path("tests").path("methods").asInt());
            assertEquals(1, data.path("counts").path("tests").path("call_sites").asInt());
            assertEquals(2, data.path("counts").path("production").path("call_sites").asInt());
            assertEquals(
                    data.path("counts"),
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(
                                                            readScope,
                                                            "p.Fixture",
                                                            "map(String)",
                                                            200))
                            .path("counts"));
            assertTrue(data.path("answer_complete").asBoolean(), data.toString());
            assertFalse(data.toString().contains("source_evidence"));
            assertEquals(
                    0,
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(readScope, "p.Fixture", "map(int)", 200))
                            .path("production_callers")
                            .size());
            assertFalse(
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(
                                                            readScope,
                                                            "p.Fixture",
                                                            "map(java.lang.String)",
                                                            1))
                            .path("answer_complete")
                            .asBoolean());
            assertTrue(
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(readScope, "p.Fixture", "map", 200))
                            .has("error_code"));
            var projection =
                    TestReads.read(
                            db,
                            readScope ->
                                    new ContextQueries()
                                            .getContext(
                                                    readScope,
                                                    java.util.List.of(
                                                            "p.Fixture#map(java.lang.String)"),
                                                    false,
                                                    25,
                                                    "impact"));
            assertTrue(projection.path("answer_complete").asBoolean(), projection.toString());
            assertFalse(projection.has("impacted_tests"));
        } finally {
        }
    }

    /**
     * V24-V27 CQ-049: parse was private, test_callers empty, and Claude globbed for
     * PermissionMapper tests 24 times in 14 sessions.
     */
    @Test
    void aMethodImpactNamesTheTestsOfItsDeclaringClass() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
            package p;
            class Mapper { Object resolve(String v){return parse(v);} private Object parse(String v){return v;} }
            class MapperTest { Object check(){return new Mapper().resolve("a");} }
            class Unrelated { int x(){return 1;} }
            """);
        try {
            db.useHandle(
                    h ->
                            h.execute(
                                    "UPDATE classes SET source_set='test', source_file='src/test/java/p/MapperTest.java' WHERE class_name='p.MapperTest'"));
            var data =
                    TestReads.read(
                            db,
                            readScope ->
                                    new MethodImpactQueries()
                                            .get(
                                                    readScope,
                                                    "p.Mapper",
                                                    "parse(java.lang.String)",
                                                    200));
            assertEquals(0, data.path("test_callers").size(), data.toString());
            assertEquals(
                    "p.MapperTest",
                    data.path("declaring_class_tests").path(0).asText(),
                    data.toString());
            assertEquals(1, data.path("declaring_class_tests").size(), data.toString());
        } finally {
        }
    }

    /**
     * V62 CQ-041: Claude listed callers of resolve but never said they consume its Optional; the
     * controls read it from source.
     */
    @Test
    void aProductionCallerShowsHowItUsesTheReturnedValue() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
            package p;
            import java.util.Optional;
            class Mapper { record Verb(String resource, String verb) {} Optional<Verb> resolve(String v) { return Optional.empty(); } }
            class Caller {
                Mapper mapper = new Mapper();
                boolean check(String name) {
                    var mapping = mapper.resolve(name);
                    if (mapping.isEmpty()) {
                        return false;
                    }
                    var k8s = mapping.get();
                    return k8s.resource().equals(k8s.verb());
                }
                void ignore() { mapper.resolve("x"); }
            }
            """);
        try {
            var data =
                    TestReads.read(
                            db,
                            readScope ->
                                    new MethodImpactQueries()
                                            .get(
                                                    readScope,
                                                    "p.Mapper",
                                                    "resolve(java.lang.String)",
                                                    200));
            var methods = data.path("production_callers").path(0).path("methods");
            var check =
                    methods.valueStream()
                            .filter(m -> m.path("method").asText().equals("check"))
                            .findFirst()
                            .orElseThrow();
            String use = check.path("result_use").asText();
            assertTrue(
                    use.startsWith("line 7: var mapping = mapper.resolve(name);"), data.toString());
            // Cryostat full-20261118 CQ-041: Claude r2 and r3 retold this line, "calls
            // mapping.isEmpty(),
            // mapping.get()", and never said the callers consume an Optional.
            assertTrue(
                    use.contains(
                            "var mapping = mapper.resolve(name); mapping is Optional<Verb>; then"),
                    use);
            assertTrue(use.contains("mapping.isEmpty() (line 8)"), use);
            assertTrue(use.contains("mapping.get() (line 11) as k8s"), use);
            assertTrue(use.contains("k8s.resource() (line 12)"), use);
            assertTrue(use.contains("k8s.verb() (line 12)"), use);
            var ignore =
                    methods.valueStream()
                            .filter(m -> m.path("method").asText().equals("ignore"))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    "line 14: void ignore() { mapper.resolve(\"x\"); }",
                    ignore.path("result_use").asText(),
                    data.toString());
            MethodPrefetchRelations.compactImpact(data);
            assertFalse(
                    data.path("production_callers").path(0).asText().contains("uses result"),
                    data.toString());
            assertTrue(
                    data.path("caller_result_use")
                            .path(0)
                            .asText()
                            .startsWith(
                                    "p.Caller#check line 7: var mapping = mapper.resolve(name); mapping is Optional<Verb>; then mapping.isEmpty() (line 8)"),
                    data.toString());
        } finally {
        }
    }

    @Test
    void aCallRepeatedOnTheResultListsItsLinesOnce() {
        var lines =
                java.util.List.of(
                        "var m = mapper.resolve(n);",
                        "log(m.get());",
                        "{ log(m.get()); }",
                        "}",
                        "m.size();");
        assertEquals(
                "line 1: var m = mapper.resolve(n); then m.get() (lines 2, 3)",
                CallerResultUse.describe(lines, 1, "resolve"));
    }

    @Test
    void literalSelectorsNotQuestionPhrasesDrivePrefetchAndSourceChangesBlockIt() throws Exception {
        var db = JavaEvidenceFixture.index(root, SOURCE);
        try {
            var data =
                    TestReads.read(
                            db,
                            readScope ->
                                    PrefetchContext.collect(
                                            readScope,
                                            root,
                                            "N'importe quoi: p.Fixture#map(java.lang.String)?"));
            assertEquals("exact indexed method impact", data.path("basis").asText());
            assertEquals(1, data.path("facts").size(), data.toString());
            assertEquals("direct_method_impact", data.path("facts").get(0).path("kind").asText());
            Files.writeString(
                    root.resolve("src/main/java/p/Fixture.java"),
                    SOURCE.replace("x.length()", "0"));
            assertEquals(
                    0,
                    TestReads.read(
                                    db,
                                    readScope ->
                                            PrefetchContext.collect(
                                                    readScope,
                                                    root,
                                                    "p.Fixture#map(java.lang.String)"))
                            .path("facts")
                            .size());
        } finally {
        }
    }

    @Test
    void inheritedContractsAndOverridesRemainSeparateFromDirectReferences() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface Contract {int map(String x);} class Fixture implements Contract {public int map(String x){return 1;}} class Child extends Fixture {public int map(String x){return 2;}}");
        try {
            var data =
                    TestReads.read(
                            db,
                            readScope ->
                                    new MethodImpactQueries()
                                            .get(
                                                    readScope,
                                                    "p.Fixture",
                                                    "map(java.lang.String)",
                                                    200));
            assertEquals(
                    1,
                    data.path("override_evidence").path("selected_declarations").size(),
                    data.toString());
            assertEquals(1, data.path("override_evidence").path("overrides").size());
            assertEquals(
                    "p.Contract", data.path("interface_contracts").get(0).path("class").asText());
            assertEquals(0, data.path("production_callers").size());
        } finally {
        }
    }

    @Test
    void shortTypesKeepOverloadAmbiguityAndArrayDimensions() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Left {static class Item{}} class Right {static class Item{}} class Fixture {void map(Left.Item x){} void map(Right.Item x){} void array(String[] values){}}");
        try {
            assertTrue(
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(readScope, "p.Fixture", "map(Item)", 200))
                            .has("error_code"));
            assertFalse(
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(
                                                            readScope,
                                                            "p.Fixture",
                                                            "map(p.Left$Item)",
                                                            200))
                            .has("error_code"));
            assertFalse(
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(
                                                            readScope,
                                                            "p.Fixture",
                                                            "array(String[])",
                                                            200))
                            .has("error_code"));
            assertTrue(
                    TestReads.read(
                                    db,
                                    readScope ->
                                            new MethodImpactQueries()
                                                    .get(
                                                            readScope,
                                                            "p.Fixture",
                                                            "array(String)",
                                                            200))
                            .has("error_code"));
        } finally {
        }
    }

    @Test
    void indirectApplicationCallersAreBoundedAndNeverClaimRuntimeClosure() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Fixture {int map(String x){return 1;}} class First {int call(){return new Fixture().map(\"x\");}} class Second {int entry(){return new First().call();}} class Third {int start(){return new Second().entry();}}");
        try {
            var data =
                    TestReads.read(
                            db,
                            readScope ->
                                    new MethodImpactQueries()
                                            .get(readScope, "p.Fixture", "map(String)", 200));
            assertEquals(1, data.path("counts").path("production").path("methods").asInt());
            assertEquals(2, data.path("indirect_production").size(), data.toString());
            assertEquals(
                    2,
                    java.util.stream.StreamSupport.stream(
                                    data.path("indirect_production").spliterator(), false)
                            .mapToInt(g -> g.path("counts").path("call_sites").asInt())
                            .sum());
            assertFalse(data.path("indirect_scope").path("transitive_closure_proven").asBoolean());
            assertEquals(2, data.path("indirect_counts").path("call_sites").asInt());
            assertEquals(
                    2, data.path("indirect_counts").path("reference_instruction_sites").asInt());
            assertEquals(0, data.path("indirect_counts").path("callback_reference_sites").asInt());
            assertTrue(data.path("answer_complete").asBoolean());
        } finally {
        }
    }
}
