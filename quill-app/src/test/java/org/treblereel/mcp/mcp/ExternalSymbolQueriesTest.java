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

    /** V21: Codex guessed io.quarkus.scheduler.runtime.util.SchedulerUtils; the class lives in another package. */
    @Test
    void aWrongPackageNamesTheClassesWithThatSimpleName() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "org.wrong.ExternalSymbolQueriesTest$Fixture", 20, 0)));

        assertEquals("EXTERNAL_CLASS_NOT_FOUND", result.path("error_code").asText());
        assertEquals(Fixture.class.getName(), result.path("candidates").get(0).asText(), result.toString());
        var absent = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "io.quarkus.quartz.runtime.QuartzScheduler", 20, 0)));
        assertTrue(absent.path("error").asText().contains("no class named QuartzScheduler"), absent.toString());
    }

    /** V41 CQ-039/CQ-047/CQ-050: Codex guessed config classes this Quarkus version does not have; the package's own names answer. */
    @Test
    void aClassMissingFromItsPackageNamesTheClosestClassesThere() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "org.treblereel.mcp.mcp.ExternalSymbolQueriesTest$Fixtures", 20, 0)));

        assertEquals("EXTERNAL_CLASS_NOT_FOUND", result.path("error_code").asText());
        assertEquals(Fixture.class.getName(), result.path("similar_classes").get(0).asText(), result.toString());
        assertTrue(result.path("similar_classes_basis").asText().contains("org.treblereel.mcp.mcp"), result.toString());
    }

    /** V21: Codex read 285 KB of these answers; members restated their signature and single-version jars twice. */
    @Test
    void bytecodeAnswerStatesEachFactOnce() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture", 20, 0, "bytecode")));

        for (var member : result.path("members")) {
            assertFalse(member.has("type"), member.toString());
            // V41: Codex read 723 KB of these; name and kind restate the signature.
            assertFalse(member.has("name"), member.toString());
            assertFalse(member.has("kind"), member.toString());
            assertFalse(member.path("annotations").isArray() && member.path("annotations").isEmpty(), member.toString());
            assertFalse(member.path("signature").asText().contains(Fixture.class.getName() + "."), member.toString());
        }
        assertFalse(result.has("occurrences"), "One version: artifact and jar already say it");
        assertFalse(result.has("superclass"));
        assertFalse(result.has("interfaces"));
        assertEquals(1, result.path("limitations").size());
        assertTrue(result.path("member_format").asText().contains("<init>"), result.toString());
        assertTrue(result.toString().length() < 1500, result.toString().length() + " " + result);
    }

    /** V21: Codex opened framework classes one call at a time, up to 13 calls in one session. */
    @Test
    void severalClassesAreAnsweredInOneCall() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture, io.quarkus.Absent", 20, 0, "bytecode")));

        assertEquals(2, result.path("classes").size(), result.toString());
        assertTrue(result.path("classes").get(0).path("bytecode_method_calls").has("greet(String)"), result.toString());
        assertEquals("EXTERNAL_CLASS_NOT_FOUND", result.path("classes").get(1).path("error_code").asText());
    }

    @Test
    void sourceExcerptSkipsTheLicenseHeader() throws Exception {
        Path sourceJar = temp.resolve("repository/org/demo/fixture/1.0/fixture-1.0-sources.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(sourceJar))) {
            output.putNextEntry(new JarEntry("org/treblereel/mcp/mcp/ExternalSymbolQueriesTest.java"));
            output.write(("/*\n * Licensed under the Apache License\n" + " * text\n".repeat(30) + " */\n"
                    + "package org.treblereel.mcp.mcp;\n/** The contract. */\nclass ExternalSymbolQueriesTest {}\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, scope ->
                new ExternalSymbolQueries().details(scope, temp, "Fixture", 20, 0, "source")));
        String text = result.path("source_excerpt").path("text").asText();
        assertTrue(text.startsWith("package org.treblereel.mcp.mcp;"), text);
        assertTrue(text.contains("/** The contract. */"), text);
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
        assertTrue(result.path("bytecode_method_calls").path("disabledValue()")
                .path("string_constants").toString().contains("disabled"));
        assertTrue(result.path("bytecode_method_calls").path("greet(String)").path("calls").toString()
                .contains("\"java.lang.String#trim()\""));
        assertFalse(result.path("bytecode_method_calls").toString().contains("Ljava/lang/"),
                "JVM descriptors cost tokens and say nothing a Java signature does not");
        assertTrue(result.path("members").toString().contains("ExternalSymbolQueriesTest$Produces"));
        assertTrue(result.path("members").toString().contains("ExternalSymbolQueriesTest$Singleton"));
        assertFalse(result.path("members").toString().contains("parameter-only"));
    }

    @Test
    void methodSelectsOneMethodsMembersAndBytecode() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture", 20, 0, "bytecode", "greet")));

        assertEquals(1, result.path("members").size());
        assertTrue(result.path("members").get(0).path("signature").asText().contains(" greet("), result.toString());
        assertEquals(1, result.path("total_members").asInt());
        var keys = new java.util.ArrayList<String>();
        result.path("bytecode_method_calls").fieldNames().forEachRemaining(keys::add);
        assertEquals(java.util.List.of("greet(String)"), keys);
        assertEquals("greet", result.path("method").asText());
    }

    @Test
    void unknownMethodListsTheClassMethodNames() throws Exception {
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture", 20, 0, "bytecode", "missing")));

        assertEquals("EXTERNAL_MEMBER_NOT_FOUND", result.path("error_code").asText());
        assertTrue(result.path("candidates").toString().contains("greet"));
        assertTrue(result.path("candidates").toString().contains("disabledValue"));
        // V41: Codex guessed method names; the closest declared one comes first, not the alphabetically first.
        var close = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "Fixture", 20, 0, "bytecode", "greeting")));
        assertEquals("greet", close.path("candidates").get(0).asText(), close.toString());
    }

    @Test
    void sameJavaParametersKeepTheirDescriptorsApart() {
        assertEquals("run(String, int[])", ExternalSymbolQueries.javaMethod("run", "(Ljava/lang/String;[I)V"));
        assertEquals("a.B#<init>(Map$Entry)", ExternalSymbolQueries.javaCall("a/B", "<init>", "(Ljava/util/Map$Entry;)V"));
    }

    @interface Produces {}
    @interface Singleton {}
    @interface Named { String value(); }

    /** V33: Codex asked for Quarkus *-deployment processors 10 times and got only a not-found error. */
    @Test
    void aBuildTimeClassIsReadFromTheExtensionsDeploymentArtifactInTheLocalRepository() throws Exception {
        Path runtime = temp.resolve("repository/org/demo/ext/1.0/ext-1.0.jar");
        Files.createDirectories(runtime.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(runtime))) {
            output.putNextEntry(new JarEntry("META-INF/quarkus-extension.properties"));
            output.write("deployment-artifact=org.demo\\:ext-deployment\\:1.0\n".getBytes());
            output.closeEntry();
        }
        jar("repository/org/demo/ext-deployment/1.0/ext-deployment-1.0.jar", BuildStep.class);
        Files.writeString(temp.resolve("repository/org/demo/ext-deployment/1.0/ext-deployment-1.0.pom"), """
                <project><dependencies>
                  <dependency><groupId>org.demo.arc</groupId><artifactId>processor</artifactId></dependency>
                  <dependency><groupId>org.demo</groupId><artifactId>helper</artifactId><scope>test</scope></dependency>
                </dependencies></project>""");
        jar("repository/org/demo/arc/processor/1.0/processor-1.0.jar", Processor.class);
        Files.writeString(temp.resolve("target/quill-classpath.txt"), java.io.File.pathSeparator + runtime,
                java.nio.file.StandardOpenOption.APPEND);

        var step = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, BuildStep.class.getName(), 20, 0)));
        assertEquals(BuildStep.class.getName(), step.path("class_name").asText(), step.toString());
        assertEquals("org.demo:ext-deployment:1.0", step.path("artifact").asText());
        assertTrue(step.path("classpath_scope").asText().contains("build time"), step.toString());
        var processor = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, Processor.class.getName(), 20, 0)));
        assertEquals(Processor.class.getName(), processor.path("class_name").asText(), processor.toString());
    }

    /** V33: the test classpath was never captured, yet the error said test classpaths were indexed. */
    @Test
    void aMissingClassSaysWhichTestClasspathsWereNotCaptured() throws Exception {
        jdbi.useHandle(handle -> handle.execute("INSERT INTO metadata(key, value) VALUES ('missing_test_classpath_modules', '[\".\"]')"));
        var result = JSON.readTree(ReadQueryContext.<String>readWithGraph(jdbi, readScope -> new ExternalSymbolQueries()
                .details(readScope, temp, "io.quarkus.test.junit.QuarkusTestProfile", 20, 0)));
        assertTrue(result.path("reason").asText().contains("test classpath not captured for modules [.]"), result.toString());
    }

    static final class BuildStep { void build() {} }

    static final class Processor { void process() {} }

    private void jar(String relative, Class<?> type) throws Exception {
        Path jar = temp.resolve(relative);
        Files.createDirectories(jar.getParent());
        String entryName = type.getName().replace('.', '/') + ".class";
        try (InputStream bytecode = type.getClassLoader().getResourceAsStream(entryName);
                JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            bytecode.transferTo(output);
            output.closeEntry();
        }
    }

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
