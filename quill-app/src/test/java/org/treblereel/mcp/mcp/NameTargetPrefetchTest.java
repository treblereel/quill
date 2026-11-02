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

        assertEquals(2, fact.path("classes_in_package").asInt(), fact.toString());
        assertEquals(List.of("main CLASS x.rbac.Mapper @Singleton qualifiers [\"@Default\"]",
                        "main PRODUCER_METHOD x.rbac.Mapper#clock @ApplicationScoped qualifiers [\"@Default\"] types [\"x.Clock\"]"),
                fact.path("beans").valueStream().map(node -> node.asText()).toList());
        assertNull(TestReads.read(db, reader -> NameTargetPrefetch.packageFact(reader, "x.missing")));
    }

    @Test
    void typeProvidersNameApplicationAndDependencyBeansWithTheirArtifact() throws Exception {
        Jdbi db = index();
        var fact = TestReads.read(db, reader -> NameTargetPrefetch.typeProviders(reader, "x.Clock"));

        assertEquals(List.of("PRODUCER_METHOD x.rbac.Mapper#clock @ApplicationScoped qualifiers [\"@Default\"]"),
                fact.path("application_providers").valueStream().map(node -> node.asText()).toList());
        assertEquals(List.of("PRODUCER_METHOD dep.ClockProducer#clock @Singleton qualifiers [\"@Default\"] from dep:clock:1 (cdi)"),
                fact.path("dependency_providers").valueStream().map(node -> node.asText()).toList());
        assertNull(TestReads.read(db, reader -> NameTargetPrefetch.typeProviders(reader, "x.Nobody")));
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
