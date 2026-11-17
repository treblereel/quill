package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.config.ConfigurationReadSnippets;
import org.treblereel.mcp.mcp.semantic.JavaSemanticQueries;

class ConfigurationMethodSourceTest {

    /** These packets are sized for this budget; the default limit may differ. */
    private static final int LEGACY_BUDGET = 5000;
    @TempDir Path root;
    Jdbi db;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; class Fixture {\nboolean enabled;\nboolean read(int n){\n if (!enabled) return false;\n String braces=\"} /* not a brace */\";\n return n>0;\n}\nboolean read(String n){return enabled && !n.isEmpty();}\n}\n");
        var p = new Properties();
        p.setProperty("compilerSettingsProtocol", "1");
        p.setProperty("evidence", "successful_mojo");
        p.setProperty("sourceSet", "main");
        p.setProperty("release", "17");
        p.setProperty("jdkHome", System.getProperty("java.home"));
        var jdk = new Properties();
        try (var r = Files.newBufferedReader(Path.of(System.getProperty("java.home"), "release"))) {
            jdk.load(r);
        }
        p.setProperty("jdkVersion", jdk.getProperty("JAVA_VERSION").replace("\"", ""));
        p.setProperty("sourceRoot.count", "1");
        p.setProperty("sourceRoot.0", root.resolve("src/main/java").toString());
        p.setProperty("compileClasspath.count", "0");
        p.setProperty("argument.count", "0");
        p.setProperty("buildInput.count", "1");
        p.setProperty("buildInput.path.0", root.resolve("pom.xml").toString());
        p.setProperty("buildInput.sha256.0", SourceFileHashes.hash(root.resolve("pom.xml")));
        try (var w = Files.newBufferedWriter(root.resolve("target/quill-java-main.properties"))) {
            p.store(w, "fixture");
        }
    }

    @Test
    void budgetKeepsAllConstructorDependenciesBeforeNavigation() {
        var data = json.createObjectNode();
        var facts = data.putArray("facts");
        var fact = facts.addObject();
        fact.putArray("injects").add("Logger").add("Config").add("Factory");
        fact.putArray("related_source_files").add("x".repeat(11000));
        PrefetchContext.boundContext(data);
        assertEquals(3, fact.path("injects").size());
        assertEquals(1, facts.size());
        assertFalse(fact.has("related_source_files"));
    }

    @AfterEach
    void close() {
        JavaSemanticQueries.close();
    }

    ObjectNode evidence() throws Exception {
        var e = json.createObjectNode();
        var r = e.putArray("references").addObject().put("className", "p.Fixture");
        var sites = r.putObject("field_reads").put("truncated", false).putArray("sites");
        sites.addObject().put("method", "read").put("descriptor", "(I)Z").putArray("lines").add(4);
        sites.addObject()
                .put("method", "read")
                .put("descriptor", "(Ljava/lang/String;)Z")
                .putArray("lines")
                .add(8);
        TestReads.read(
                db,
                readScope -> {
                    ConfigurationReadSnippets.append(
                            readScope,
                            e,
                            Map.of("src/main/java/p/Fixture.java", new TreeSet<>(List.of(4, 8))));
                    return null;
                });
        return e;
    }

    @Test
    void returnsWholeExactOverloadsNotBraceHeuristics() throws Exception {
        var e = evidence();
        TestReads.read(
                db,
                readScope -> {
                    ConfigurationMethodSource.append(readScope, e);
                    return null;
                });
        var s = e.path("field_read_source");
        assertTrue(s.path("method_complete").asBoolean(), e.toString());
        assertFalse(s.path("source_bytecode_identity_proven").asBoolean());
        assertFalse(s.has("character_limit"));
        assertFalse(s.path("truncated").asBoolean());
        var w = s.path("files").get(0).path("windows");
        assertEquals(2, w.size());
        assertTrue(w.get(0).path("source").asText().contains("return n>0;"));
        assertEquals(3, w.get(0).path("start_line").asInt());
        assertEquals(7, w.get(0).path("end_line").asInt());
        assertTrue(w.get(1).path("source").asText().startsWith("boolean read(String n)"));
    }

    @Test
    void missingCompilerCaptureReadsWholeMethodsFromSyntaxAndSaysSo() throws Exception {
        Files.delete(root.resolve("target/quill-java-main.properties"));
        var e = evidence();
        TestReads.read(
                db,
                readScope -> {
                    ConfigurationMethodSource.append(readScope, e);
                    return null;
                });
        var s = e.path("field_read_source");
        assertTrue(s.path("method_complete").asBoolean(), e.toString());
        assertEquals("java_syntax_method_declaration", s.path("range_kind").asText());
        var w = s.path("files").get(0).path("windows");
        assertEquals(2, w.size());
        assertEquals(3, w.get(0).path("start_line").asInt());
        assertEquals(7, w.get(0).path("end_line").asInt());
        assertTrue(
                w.get(0)
                        .path("source")
                        .asText()
                        .contains("String braces=\"} /* not a brace */\";"));
    }

    @Test
    void truncationOrWrongReadRangeNeverClaimsCompleteCoverage() throws Exception {
        var e37 = evidence();
        ((ObjectNode) e37.path("references").get(0).path("field_reads")).put("truncated", true);
        TestReads.read(
                db,
                readScope -> {
                    ConfigurationMethodSource.append(readScope, e37);
                    return null;
                });
        assertFalse(e37.path("field_read_source").path("method_complete").asBoolean());
        ObjectNode e38 = evidence();
        ((ArrayNode)
                        e38.path("references")
                                .get(0)
                                .path("field_reads")
                                .path("sites")
                                .get(0)
                                .path("lines"))
                .add(99);
        TestReads.read(
                db,
                readScope -> {
                    ConfigurationMethodSource.append(readScope, e38);
                    return null;
                });
        assertFalse(e38.path("field_read_source").path("method_complete").asBoolean());
    }

    @Test
    void hookBudgetOmitsWholeFactsAndNamesMissingCoverage() throws Exception {
        var data = json.createObjectNode().put("status", "ready");
        var facts = data.putArray("facts");
        var small =
                facts.addObject()
                        .put("kind", "configuration")
                        .put("tool", "find_configuration_references");
        small.putObject("arguments").put("key", "warehouse.enabled");
        small.putObject("evidence").put("source", "complete small source");
        var large =
                facts.addObject()
                        .put("kind", "configuration")
                        .put("tool", "find_configuration_references");
        large.putObject("arguments").put("key", "warehouse.large");
        large.putObject("evidence").put("source", "λ".repeat(6000));
        var original = small.deepCopy();
        PrefetchContext.boundContext(data);
        assertEquals(1, facts.size());
        assertEquals(original, facts.get(0));
        assertEquals(
                "warehouse.large",
                data.path("unavailable").get(0).path("arguments").path("key").asText());
        assertEquals("partial", data.path("status").asText());
        assertTrue(data.path("context_truncated").asBoolean());
        assertTrue(
                data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        <= PrefetchContext.CONTEXT_LIMIT_BYTES);
        var before = data.deepCopy();
        PrefetchContext.boundContext(data);
        assertEquals(before, data);
    }

    @Test
    void hookBudgetHalvesLongListsAndStatesTheCutBeforeOmittingFacts() throws Exception {
        var data = json.createObjectNode().put("status", "ready");
        var facts = data.putArray("facts");
        var fact = facts.addObject().put("kind", "method_impact");
        var callers = fact.putArray("callers");
        for (int i = 0; i < 200; i++)
            callers.addObject().put("caller", "p.Caller" + i + "#call" + "x".repeat(60));
        fact.putArray("tests").add("p.ATest").add("p.BTest");
        PrefetchContext.boundContext(data);
        assertEquals(1, facts.size());
        assertEquals(2, fact.path("tests").size());
        assertEquals(200, fact.path("callers_truncated").path("total").asInt());
        assertEquals(
                fact.path("callers").size(), fact.path("callers_truncated").path("shown").asInt());
        assertEquals(
                "p.Caller0#call" + "x".repeat(60),
                fact.path("callers").get(0).path("caller").asText());
        assertEquals("partial", data.path("status").asText());
        assertTrue(
                data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        <= PrefetchContext.CONTEXT_LIMIT_BYTES);
    }

    @Test
    void hookBudgetDropsOptionalFieldsAtAnyDepthAndStatesThem() {
        var data = json.createObjectNode().put("status", "ready");
        var facts = data.putArray("facts");
        var fact = facts.addObject().put("kind", "configuration");
        fact.putArray("limitations").add("static only");
        var evidence = fact.putObject("evidence");
        evidence.put("key", "warehouse.enabled");
        evidence.putObject("field_read_source").put("source", "x".repeat(6000));
        PrefetchContext.boundContext(data, null, LEGACY_BUDGET);
        assertEquals(1, facts.size());
        assertEquals("warehouse.enabled", fact.path("evidence").path("key").asText());
        assertFalse(fact.path("evidence").has("field_read_source"));
        assertEquals("static only", fact.path("limitations").get(0).asText());
        assertEquals("field_read_source", data.path("context_omitted_fields").get(0).asText());
        // V68 CQ-039: "partial" for an omitted optional field made Claude repeat the lookup the
        // prefetch had done.
        assertEquals("ready", data.path("status").asText());
        assertTrue(data.path("context_truncated").asBoolean());
    }

    @Test
    void hookBudgetLeavesSmallPacketsUntouched() {
        var data = json.createObjectNode().put("status", "ready");
        data.putArray("facts").addObject().put("symbol_id", "kept").putArray("methods").add("m");
        var before = data.deepCopy();
        PrefetchContext.boundContext(data);
        assertEquals(before, data);
    }

    @Test
    void changedSourceCannotLeaveOldSnippetInFallback() throws Exception {
        var e = evidence();
        Files.writeString(
                root.resolve("src/main/java/p/Fixture.java"), "// CHANGED_SOURCE_SENTINEL");
        TestReads.read(
                db,
                readScope -> {
                    ConfigurationMethodSource.append(readScope, e);
                    return null;
                });
        assertTrue(e.path("field_read_source").path("files").isEmpty());
        assertFalse(e.toString().contains("CHANGED_SOURCE_SENTINEL"));
    }
}
