package org.treblereel.mcp.mcp;

import org.treblereel.mcp.db.ReadQueryContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class ExternalSymbolQueriesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;
    private Jdbi jdbi;

    @BeforeEach
    void createClasspath() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>");
        Path jar = temp.resolve("repository/org/demo/fixture/1.0/fixture-1.0.jar");
        Files.createDirectories(jar.getParent());
        String entryName = Fixture.class.getName().replace('.', '/') + ".class";
        try (InputStream bytecode = Fixture.class.getClassLoader().getResourceAsStream(entryName);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            bytecode.transferTo(output);
            output.closeEntry();
        }
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.createDirectories(classpath.getParent());
        Files.writeString(classpath, jar.toString());
        jdbi = QuillDatabase.create(temp.resolve("index.db"));
        jdbi.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle,
                                          module, source_set)
                        VALUES ('src/main/java/App.java', 'src/main/java/App.java', 'java',
                                'source', 'current', '.', 'main')
                        """).execute());
    }

    @Test
    void producerEvidenceSelectsOnlyRequestedMemberAndReportsMissingJar() {
        Path jar = temp.resolve("repository/org/demo/fixture/1.0/fixture-1.0.jar");
        var evidence = ExternalSymbolQueries.producerEvidence(jar, Fixture.class.getName(), "greet");
        assertEquals(1, evidence.path("declarations").size());
        assertEquals(1, evidence.path("method_calls").size());
        assertTrue(evidence.path("method_calls").toString().contains("java.lang.String#trim()"));
        assertFalse(evidence.path("method_calls").toString().contains("<init>"));
        assertEquals("unavailable", ExternalSymbolQueries.producerEvidence(temp.resolve("missing.jar"),
                Fixture.class.getName(), "greet").path("status").asText());
    }

    @Test
    void producerBudgetIsNotConsumedByOtherMethods() throws Exception {
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(61, 1, "demo/LargeProducer", null, "java/lang/Object", null);
        for (String name : new String[]{"unrelated", "produce"}) {
            var method = writer.visitMethod(9, name, "()V", null, null);
            method.visitCode();
            for (int i = 0; i < (name.equals("unrelated") ? 220 : 1); i++)
                method.visitMethodInsn(184, "demo/Factory", "create", "()V", false);
            method.visitInsn(177);
            method.visitMaxs(0, 0);
            method.visitEnd();
        }
        writer.visitEnd();
        Path jar = temp.resolve("large.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("demo/LargeProducer.class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }
        var evidence = ExternalSymbolQueries.producerEvidence(jar, "demo.LargeProducer", "produce");
        assertTrue(evidence.path("method_calls").toString().contains("demo.Factory#create"));
        assertFalse(evidence.path("method_calls").has("truncated"));
    }

    @Test
    void companionSourceIsBoundedAndReportsTruncation() throws Exception {
        Path sourceJar = temp.resolve("repository/org/demo/fixture/1.0/fixture-1.0-sources.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(sourceJar))) {
            output.putNextEntry(new JarEntry("org/treblereel/mcp/mcp/ExternalSymbolQueriesTest.java"));
            output.write("x".repeat(13000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, scope ->
                new ExternalSymbolQueries().details(scope, temp, "Fixture", 20, 0)));
        assertTrue(result.path("source_available").asBoolean());
        assertTrue(result.path("source_excerpt").path("truncated").asBoolean());
        assertEquals(12000, result.path("source_excerpt").path("text").asText().length());
        var summary = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, scope ->
                new ExternalSymbolQueries().details(scope, temp, "Fixture", 20, 0, "summary")));
        assertTrue(summary.path("source_available").asBoolean());
        assertFalse(summary.has("source_excerpt"));
        assertFalse(summary.has("bytecode_method_calls"));
        assertEquals("source", summary.path("source_request").path("detail").asText());
        var source = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, scope ->
                new ExternalSymbolQueries().details(scope, temp, "Fixture", 20, 0, "source")));
        assertEquals(result.path("source_excerpt"), source.path("source_excerpt"));
        assertFalse(source.has("members"));
        assertFalse(source.has("bytecode_method_calls"));
    }

    @Test
    void searchesResolvedDependencyJarClasses() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .search(readScope, temp, "Fixture", "class", null, "fixture", "contains", false, 20, 0)));

        assertEquals(1, result.path("total").asInt());
        assertTrue(result.path("symbols").get(0).path("class_name").asText()
                .endsWith("ExternalSymbolQueriesTest$Fixture"));
        assertEquals(1, result.path("discovery").path("jar_count").asInt());
    }

    @Test
    void searchesMembersWithinAnExplicitExternalClass() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .search(readScope, temp, "greet", "method", "Fixture", null, "exact", false, 20, 0)));

        assertEquals(1, result.path("total").asInt());
        assertEquals("method", result.path("symbols").get(0).path("kind").asText());
        assertEquals("greet", result.path("symbols").get(0).path("name").asText());
        assertEquals(1, result.path("discovery").path("classes_inspected").asInt());
    }

    @Test
    void rejectsUnboundedMemberSearch() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .search(readScope, temp, "greet", "method", null, null, "contains", false, 20, 0)));

        assertTrue(result.path("error").asText().contains("requires class_name"));
    }

    @Test
    void exactClassNameDoesNotInspectNestedClasses() throws Exception {
        appendJar("2.0", Fixture.Inner.class);

        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries().search(readScope, temp, "nestedOnly", "method", Fixture.class.getName(), null, "contains", false, 20, 0)));

        assertEquals(0, result.path("total").asInt());
        assertEquals(1, result.path("discovery").path("classes_inspected").asInt());
    }

    @Test
    void groupsTheSameClassAcrossDependencyVersions() throws Exception {
        appendJar("2.0", Fixture.class);

        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .search(readScope, temp, "Fixture", "class", null, "fixture", "contains", true, 20, 0)));

        assertEquals(1, result.path("total").asInt());
        assertEquals(2, result.path("symbols").get(0).path("version_count").asInt());
        assertEquals(2, result.path("symbols").get(0).path("occurrences").size());
    }

    @Test
    void compactClassSearchOmitsJarPaths() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries().search(readScope, temp, "Fixture", "class", null, null, "exact", false, 20, 0)));

        var symbol = result.path("symbols").get(0);
        assertEquals("exact", result.path("match_mode").asText());
        assertEquals(1, symbol.path("artifacts").size());
        assertTrue(symbol.path("occurrences").isMissingNode());
        assertFalse(result.toString().contains(temp.toString()));
    }

    @Test
    void validatesMatchMode() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries().search(readScope, temp, "Fixture", "class", null, null, "fuzzy", false, 20, 0)));

        assertTrue(result.path("error").asText().contains("match_mode"));
    }

    @Test
    void missingClassHasDedicatedErrorCode() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "io.quarkus.Absent", 20, 0)));

        assertEquals("EXTERNAL_CLASS_NOT_FOUND", result.path("error_code").asText());
        assertTrue(result.path("reason").asText().contains("classpaths"));
        assertEquals("search_external_symbols", result.path("next_step").path("tool").asText());
    }

    @Test
    void shortNameAmbiguityReturnsCandidatesInsteadOfChoosingArbitrarily() throws Exception {
        appendJar("2.0", Other.Fixture.class);

        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture", 20, 0)));

        assertTrue(result.path("error").asText().contains("ambiguous"));
        assertEquals(2, result.path("candidates").size());
    }

    @Test
    void fullDetailLeavesBytecodeCallsToTheirOwnRequest() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture", 20, 0, "full")));

        assertTrue(result.path("members").toString().contains("greet"));
        assertFalse(result.has("bytecode_method_calls"));
        assertEquals("bytecode", result.path("bytecode_request").path("detail").asText());
    }

    @Test
    void readsMembersOnlyForSelectedExternalClass() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture", 20, 0, "bytecode")));

        assertTrue(result.path("class_name").asText().endsWith("$Fixture"));
        assertTrue(result.path("members").toString().contains("greet"));
        assertFalse(result.path("source_available").asBoolean());
        assertTrue(result.path("bytecode_method_calls").toString().contains("java.lang.String#trim"));
        assertTrue(result.path("annotations").toString().contains("Deprecated"));
        assertTrue(result.path("members").toString().contains("producer-evidence"));
        assertFalse(result.path("runtime_resolution_proven").asBoolean());
        assertTrue(result.path("bytecode_method_calls").path("disabledValue()Ljava/lang/String;")
                .path("string_constants").toString().contains("disabled"));
        assertTrue(result.path("members").toString().contains("ExternalSymbolQueriesTest$Produces"));
        assertTrue(result.path("members").toString().contains("ExternalSymbolQueriesTest$Singleton"));
        assertFalse(result.path("members").toString().contains("parameter-only"));
    }

    @interface Produces {}
    @interface Singleton {}
    @interface Named { String value(); }

    @Deprecated
    static final class Fixture {
        @Deprecated(since = "producer-evidence")
        @Produces
        @Singleton
        String disabledValue() { return "disabled"; }

        String greet(@Named("parameter-only") String name) {
            return "hello " + name.trim();
        }

        static final class Inner {
            void nestedOnly() {}
        }
    }

    static final class Other {
        static final class Fixture {}
    }

    private void appendJar(String version, Class<?> type) throws Exception {
        Path jar = temp.resolve("repository/org/demo/fixture/" + version
                + "/fixture-" + version + ".jar");
        Files.createDirectories(jar.getParent());
        String entryName = type.getName().replace('.', '/') + ".class";
        try (InputStream bytecode = type.getClassLoader().getResourceAsStream(entryName);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            bytecode.transferTo(output);
            output.closeEntry();
        }
        Path classpath = temp.resolve("target/quill-classpath.txt");
        Files.writeString(classpath, java.io.File.pathSeparator + jar,
                java.nio.file.StandardOpenOption.APPEND);
    }
}
