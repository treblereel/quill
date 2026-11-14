package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ExternalBeanRecord;

class NameTargetPrefetchTest {

    @TempDir Path root;

    @Test
    void packageListsOnlyBeansDeclaredDirectlyInIt() throws Exception {
        Jdbi db = index();
        var fact = TestReads.read(db, reader -> NameTargetPrefetch.packageFact(reader, "x.rbac"));

        assertEquals(2, fact.path("classes_in_package_by_source_set").path("main").asInt(), fact.toString());
        assertEquals(List.of("main CLASS x.rbac.Mapper @Singleton qualifiers [\"@Default\"]",
                        "main PRODUCER_METHOD x.rbac.Mapper#clock @ApplicationScoped qualifiers [\"@Default\"] types [\"x.Clock\"]"),
                fact.path("beans").valueStream().map(node -> node.asText()).toList());
        assertNull(TestReads.read(db, reader -> NameTargetPrefetch.packageFact(reader, "x.missing")));
    }

    /** V62 CQ-002: rbac has no producers, and Claude read eight classes' members one call each to confirm it. */
    @Test
    void packageCountsBeanKindsIncludingAbsentProducers() throws Exception {
        Jdbi db = index();
        var counts = TestReads.read(db, reader -> NameTargetPrefetch.packageFact(reader, "x.rbac")).path("bean_kind_counts_main");
        assertEquals(1, counts.path("CLASS").asInt(), counts.toString());
        assertEquals(1, counts.path("PRODUCER_METHOD").asInt(), counts.toString());
        assertEquals(0, counts.path("PRODUCER_FIELD").asInt(), counts.toString());
        assertEquals(true, counts.has("PRODUCER_FIELD"), counts.toString());
    }

    @Test
    void typeProvidersNameApplicationAndDependencyBeansWithTheirArtifact() throws Exception {
        Jdbi db = index();
        var fact = TestReads.read(db, reader -> NameTargetPrefetch.typeProviders(reader, "x.Clock"));

        assertEquals(List.of("PRODUCER_METHOD x.rbac.Mapper#clock @ApplicationScoped qualifiers [\"@Default\"]"),
                fact.path("application_providers").valueStream().map(node -> node.asText()).toList());
        assertEquals(List.of("PRODUCER_METHOD dep.ClockProducer#clock @Singleton qualifiers [\"@Default\"] from dep:clock:1 (cdi)"),
                fact.path("dependency_providers").valueStream().map(node -> node.asText()).toList());
        assertEquals("get_external_symbol_details", fact.path("dependency_provider_evidence").get(0).path("inspect").path("tool").asText());
        assertNull(TestReads.read(db, reader -> NameTargetPrefetch.typeProviders(reader, "x.Nobody")));
    }

    // V48 CQ-004: the fact named quarkus-vertx, and Codex grepped pom.xml for where it is declared.
    @Test
    void dependencyProvidersStateWhereTheirArtifactIsDeclared() throws Exception {
        Jdbi db = index();
        java.nio.file.Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>test</groupId><artifactId>app</artifactId><version>1</version>
                  <dependencies><dependency><groupId>dep</groupId>
                    <artifactId>clock</artifactId><version>1</version>
                  </dependency></dependencies>
                </project>
                """);
        db.useHandle(handle -> handle.execute("INSERT INTO metadata(key, value) VALUES ('project_root', ?)", root.toString()));
        var fact = TestReads.read(db, reader -> NameTargetPrefetch.typeProviders(reader, "x.Clock"));
        assertEquals(List.of("dep:clock direct, declared at pom.xml:4; version 1 at pom.xml:4"),
                fact.path("artifact_declarations").valueStream().map(node -> node.asText()).toList(), fact.toString());
    }

    @Test
    void defaultProvidersPrecedeNamedProvidersInBoundedEvidence() throws Exception {
        Jdbi db = index();
        IndexFixture.writeExternalBeans(db, List.of(
                new ExternalBeanRecord(1, "dep.ANamedProducer", "PRODUCER_METHOD", "@Singleton",
                        List.of("@Named(custom)"), List.of(), false, false, null, List.of(), "named", List.of("x.Clock"), "cdi", "dep:clock:1", "/tmp/clock.jar", List.of()),
                new ExternalBeanRecord(2, "dep.ClockProducer", "PRODUCER_METHOD", "@Singleton",
                        List.of("@Default"), List.of(), false, false, null, List.of(), "clock", List.of("x.Clock"), "cdi", "dep:clock:1", "/tmp/clock.jar", List.of())));
        var fact = TestReads.read(db, reader -> NameTargetPrefetch.typeProviders(reader, "x.Clock"));
        assertEquals(true, fact.path("dependency_providers").get(0).asText().contains("dep.ClockProducer#clock"));
        assertEquals("dep.ClockProducer", fact.path("dependency_provider_evidence").get(0).path("class").asText());
    }

    @Test
    void generatedClassesTraceToTheApplicationSourceToEdit() throws Exception {
        Jdbi db = index();
        String generated = "{\"jars\":[{\"path\":\"target/quarkus-app/quarkus/generated-bytecode.jar\",\"modified\":\"2026-01-01T00:00:00Z\","
                + "\"older_than_compiled_classes\":true}],\"classes\":[{\"class\":\"x.rbac.Mapper_ClientProxy\","
                + "\"jar\":\"target/quarkus-app/quarkus/generated-bytecode.jar\",\"entry\":\"x/rbac/Mapper_ClientProxy.class\","
                + "\"source_class\":\"x.rbac.Mapper\",\"references\":[\"dep.Creator\",\"x.rbac.Mapper\"]}]}";
        db.useHandle(handle -> handle.execute("INSERT INTO metadata(key, value) VALUES ('quarkus_generated_classes', ?)", generated));

        var fact = TestReads.read(db, reader -> NameTargetPrefetch.generatedClassFact(reader, "x.rbac.Mapper_ClientProxy"));
        assertEquals("target/quarkus-app/quarkus/generated-bytecode.jar!/x/rbac/Mapper_ClientProxy.class", fact.path("artifact").asText());
        assertEquals("Mapper.java", fact.path("generated_for_source").asText());
        assertEquals(true, fact.path("jar_older_than_compiled_classes").asBoolean());
        assertNull(TestReads.read(db, reader -> NameTargetPrefetch.generatedClassFact(reader, "x.rbac.Mapper_Bean")));

        var classFact = TestReads.read(db, reader -> {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            NameTargetPrefetch.appendGenerated(reader, node, "x.rbac.Mapper");
            return node;
        });
        assertEquals(List.of("target/quarkus-app/quarkus/generated-bytecode.jar!/x/rbac/Mapper_ClientProxy.class references dep.Creator"),
                classFact.path("generated_classes").valueStream().map(node -> node.asText()).toList());
    }

    @Test
    void generatedCreationIncludesDirectConstructorEvidence() throws Exception {
        Jdbi db = index();
        var writer = new org.objectweb.asm.ClassWriter(0);
        writer.visit(61, 1, "x/rbac/Mapper_Bean", null, "java/lang/Object", null);
        var method = writer.visitMethod(1, "doCreate", "()Lx/rbac/Mapper;", null, null);
        method.visitCode();
        for (int i = 0; i < 20; i++)
            method.visitMethodInsn(184, "x/Helper", "prepare", "()V", false);
        method.visitTypeInsn(187, "x/rbac/Mapper");
        method.visitInsn(89);
        method.visitMethodInsn(183, "x/rbac/Mapper", "<init>", "()V", false);
        method.visitInsn(176);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        var jar = root.resolve("generated.jar");
        try (var output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            output.putNextEntry(new java.util.jar.JarEntry("x/rbac/Mapper_Bean.class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }
        db.useHandle(handle -> handle.execute("INSERT INTO metadata(key, value) VALUES ('quarkus_generated_classes', ?)",
                "{\"classes\":[{\"class\":\"x.rbac.Mapper_Bean\",\"jar\":\"generated.jar\",\"source_class\":\"x.rbac.Mapper\"}]}"));
        var fact = TestReads.read(db, reader -> {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            NameTargetPrefetch.appendGenerated(reader, node, "x.rbac.Mapper", root);
            return node;
        });
        assertEquals(1, fact.path("generated_creation_evidence").size());
        assertEquals(true, fact.path("generated_creation_evidence").toString().contains("x.rbac.Mapper#<init>"));
        var creation = fact.path("generated_creation_evidence").get(0).path("method_calls")
                .path("doCreate()");
        assertEquals("x.rbac.Mapper#<init>()", creation.asText());
        for (int i = 0; i < 20; i++) fact.withArray("navigation").add("helper-" + i + "x".repeat(50));
        var envelope = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        envelope.putArray("facts").add(fact);
        int bytes = envelope.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int generated = fact.path("generated_creation_evidence").toString().length();
        fact.put("other_evidence", "x".repeat(PrefetchContext.CONTEXT_LIMIT_BYTES + generated + 200 - bytes));
        PrefetchContext.boundContext(envelope);
        assertEquals(true, envelope.path("context_truncated").asBoolean());
        assertEquals(1, envelope.path("facts").size());
        assertEquals(false, fact.has("generated_creation_evidence"));
        assertEquals(true, envelope.path("context_omitted_fields").toString().contains("generated_creation_evidence"));
        assertEquals(true, fact.path("navigation").size() < 20);
    }

    private Jdbi index() {
        Jdbi db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.rbac.Mapper", "CLASS", "java.lang.Object", List.of(), "Mapper.java", 1, true, 10),
                        new ClassRecord(0, "x.rbac.Helper", "CLASS", "java.lang.Object", List.of(), "Helper.java", 1, false, 10),
                        new ClassRecord(0, "x.rbac.sub.Deep", "CLASS", "java.lang.Object", List.of(), "Deep.java", 1, true, 10)))
                .beans(List.of(
                        new BeanRecord(0, 1, "CLASS", "@Singleton", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.rbac.Mapper")),
                        new BeanRecord(0, 1, "PRODUCER_METHOD", "@ApplicationScoped", List.of("@Default"), List.of(), false, null, null, 1, "clock", List.of("x.Clock")),
                        new BeanRecord(0, 3, "CLASS", "@Dependent", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.rbac.sub.Deep"))))
                .dependencies(List.of()).metadata(Map.of("index_id", "names")).snapshot());
        db.useHandle(handle -> handle.execute("UPDATE classes SET source_set = 'main'"));
        IndexFixture.writeExternalBeans(db, List.of(new ExternalBeanRecord(1, "dep.ClockProducer", "PRODUCER_METHOD", "@Singleton",
                List.of("@Default"), List.of(), false, false, null, List.of(), "clock", List.of("x.Clock"), "cdi", "dep:clock:1",
                "/tmp/clock.jar", List.of())));
        return db;
    }
}
