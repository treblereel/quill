package org.treblereel.mcp.mcp.support;

import org.treblereel.mcp.db.ReadQueryContext;

import org.treblereel.mcp.db.IndexFixture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.mcp.semantic.SymbolContract;

class ClassTargetResolverTest {

    @TempDir Path tempDir;

    @Test
    void resolvesSingleExactShortNameAndIgnoresHistoricalMatches() {
        Jdbi jdbi = database(List.of(
                current("io.crysknife.client.BeanManager"),
                historical("legacy.BeanManager")));

        ClassTargetResolver.Lookup lookup = ReadQueryContext.readWithGraph(jdbi, readScope -> ClassTargetResolver.resolve(readScope, "BeanManager"));

        assertTrue(lookup.found());
        assertEquals("io.crysknife.client.BeanManager", lookup.cls().className());
    }

    @Test
    void resolvesSourceSpellingOfNestedClassesToTheirBinaryName() {
        Jdbi jdbi = database(List.of(
                current("io.acme.Credential"),
                current("io.acme.Credential$Listener"),
                current("io.acme.Outer$Middle$Inner")));

        var listener = ReadQueryContext.readWithGraph(jdbi,
                readScope -> ClassTargetResolver.resolve(readScope, "io.acme.Credential.Listener"));
        var inner = ReadQueryContext.readWithGraph(jdbi,
                readScope -> ClassTargetResolver.resolve(readScope, "io.acme.Outer.Middle.Inner"));
        var missing = ReadQueryContext.readWithGraph(jdbi,
                readScope -> ClassTargetResolver.resolve(readScope, "io.acme.Credential.Missing"));

        assertEquals("io.acme.Credential$Listener", listener.cls().className());
        assertEquals("io.acme.Outer$Middle$Inner", inner.cls().className());
        assertEquals("CLASS_NOT_FOUND", missing.errorCode());
        // V41 CQ-027: Codex asked get_symbol_details for Credential.Listener#postPersist.
        var member = ReadQueryContext.readWithGraph(jdbi,
                readScope -> ClassTargetResolver.resolve(readScope, "io.acme.Credential.Listener#postPersist"));
        assertEquals("io.acme.Credential$Listener", member.cls() == null ? member.errorCode() : member.cls().className());
    }

    @Test
    void reportsAmbiguousTargetWhenMultipleCurrentClassesHaveSameShortName() {
        Jdbi jdbi = database(List.of(
                current("first.BeanManager"),
                current("second.BeanManager")));

        ClassTargetResolver.Lookup lookup = ReadQueryContext.readWithGraph(jdbi, readScope -> ClassTargetResolver.resolve(readScope, "BeanManager"));

        assertFalse(lookup.found());
        assertEquals("AMBIGUOUS_CLASS", lookup.errorCode());
        assertEquals("Ambiguous class name", lookup.error());
        assertEquals(List.of("first.BeanManager", "second.BeanManager"),
                lookup.candidates().stream().map(ClassRecord::className).toList());
    }

    @Test
    void exactFqcnWinsBeforeShortNameResolution() {
        Jdbi jdbi = database(List.of(
                current("first.BeanManager"),
                current("second.BeanManager")));

        ClassTargetResolver.Lookup lookup =
                ReadQueryContext.readWithGraph(jdbi, readScope -> ClassTargetResolver.resolve(readScope, "second.BeanManager"));

        assertTrue(lookup.found());
        assertEquals("second.BeanManager", lookup.cls().className());
    }

    @Test
    void duplicateFqcnRequiresAConcreteModuleContext() {
        Jdbi jdbi = database(List.of(
                current("example.GeneratedRegistry", "applications/one"),
                current("example.GeneratedRegistry", "applications/two")));

        ClassTargetResolver.Lookup lookup =
                ReadQueryContext.readWithGraph(jdbi, readScope -> ClassTargetResolver.resolve(readScope, "example.GeneratedRegistry"));

        assertFalse(lookup.found());
        assertEquals("AMBIGUOUS_CLASS", lookup.errorCode());
        assertEquals("Ambiguous class context", lookup.error());
        assertEquals(List.of("applications/one", "applications/two"),
                lookup.candidates().stream().map(ClassRecord::module).toList());
    }

    @Test
    void reportsUnsupportedSymbolIdVersionsWithoutTreatingThemAsClassNames() {
        ClassTargetResolver.Lookup lookup = ReadQueryContext.readWithGraph(database(List.of(current("example.Type"))), readScope -> ClassTargetResolver.resolve(readScope, "quill:symbol:v4:anything"));

        assertFalse(lookup.found());
        assertEquals("UNSUPPORTED_SYMBOL_ID_VERSION", lookup.errorCode());
        assertEquals("Unsupported symbol_id version", lookup.error());
        assertTrue(lookup.candidates().isEmpty());
    }

    @Test
    void reportsMalformedRecognizedSymbolIds() {
        ClassTargetResolver.Lookup lookup = ReadQueryContext.readWithGraph(database(List.of(current("example.Type"))), readScope -> ClassTargetResolver.resolve(readScope, "quill:symbol:v2:broken"));

        assertFalse(lookup.found());
        assertEquals("MALFORMED_SYMBOL_ID", lookup.errorCode());
        assertEquals("Malformed symbol_id", lookup.error());
    }

    @Test
    void scopedIdsDisambiguateDuplicateFqcnsAcrossModules() {
        Jdbi jdbi = database(List.of(
                current("example.GeneratedRegistry", "applications/one"),
                current("example.GeneratedRegistry", "applications/two")));
        List<ClassRecord> duplicates = ReadQueryContext.readWithGraph(jdbi, readScope -> ClassTargetResolver.resolve(readScope, "example.GeneratedRegistry")).candidates();
        String firstId = SymbolContract.id(
                duplicates.get(0), "CLASS", "example.GeneratedRegistry", "");
        String secondId = SymbolContract.id(
                duplicates.get(1), "CLASS", "example.GeneratedRegistry", "");

        assertNotEquals(firstId, secondId);
        assertEquals("applications/one",
                ReadQueryContext.readWithGraph(jdbi, readScope -> ClassTargetResolver.resolve(readScope, firstId)).cls().module());
        assertEquals("applications/two",
                ReadQueryContext.readWithGraph(jdbi, readScope -> ClassTargetResolver.resolve(readScope, secondId)).cls().module());
    }

    private Jdbi database(List<ClassRecord> classes) {
        Jdbi jdbi = QuillDatabase.create(tempDir.resolve("index-" + System.nanoTime() + ".db"));
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes)
                .metadata(Map.of("indexed_at", "2026-09-16T00:00:00Z")).snapshot());
        return jdbi;
    }

    private static ClassRecord current(String name) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                "src/main/java/" + name.replace('.', '/') + ".java", 1, false, 10);
    }

    private static ClassRecord current(String name, String module) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                module + "/src/main/java/" + name.replace('.', '/') + ".java",
                1, false, 10, null, "source", "current", module, "main");
    }

    private static ClassRecord historical(String name) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                "src/main/java/" + name.replace('.', '/') + ".java", 1, false, 10,
                null, "source", "historical");
    }
}
