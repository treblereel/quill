package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

class ClassPrefetchRelationsTest {

    @TempDir Path root;

    @Test
    void classFactListsWhatItInjectsWhereItIsInjectedAndItsTests() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.Service", "INTERFACE", null, List.of(), "Service.java", 1, false, 10),
                        new ClassRecord(0, "x.Impl", "CLASS", "java.lang.Object", List.of("x.Service"), "Impl.java", 1, true, 10),
                        new ClassRecord(0, "x.Consumer", "CLASS", "java.lang.Object", List.of(), "Consumer.java", 1, true, 10),
                        new ClassRecord(0, "x.Clock", "CLASS", "java.lang.Object", List.of(), "Clock.java", 1, true, 10),
                        new ClassRecord(0, "x.ServiceTest", "CLASS", "java.lang.Object", List.of(),
                                "src/test/java/x/ServiceTest.java", 1, false, 10)))
                .beans(List.of(
                        new BeanRecord(0, 2, "CLASS", "@ApplicationScoped", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Impl", "x.Service")),
                        new BeanRecord(0, 3, "CLASS", "@Singleton", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Consumer")),
                        new BeanRecord(0, 4, "CLASS", "@Dependent", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Clock"))))
                .injectionPoints(List.of(
                        InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "x.Clock", List.of("@Default"), "clock", 3, false, InjectionPointRecord.STATIC_CDI),
                        InjectionPointRecord.staticAnalysis(0, 2, "FIELD", "x.Service", List.of("@Default"), "service", 1, false, InjectionPointRecord.STATIC_CDI),
                        InjectionPointRecord.staticAnalysis(0, 2, "FIELD", "jakarta.enterprise.inject.Instance<x.Service>", List.of(), "services", null, false, InjectionPointRecord.STATIC_CDI)))
                .dependencies(List.of(new DependencyRecord(5, 1, "TYPE_REFERENCE", null, 1, List.of())))
                .metadata(Map.of("index_id", "relations")).snapshot());
        db.useHandle(handle -> handle.execute("UPDATE classes SET source_set = CASE WHEN class_name = 'x.ServiceTest' THEN 'test' ELSE 'main' END"));

        ObjectNode service = relations(db, "x.Service");
        ObjectNode impl = relations(db, "x.Impl");

        assertEquals(List.of("x.Consumer#service (FIELD x.Service) [RESOLVED]",
                        "x.Consumer#services (FIELD jakarta.enterprise.inject.Instance<x.Service>) [UNKNOWN]"),
                strings(service, "injected_into"));
        assertEquals(List.of("src/test/java/x/ServiceTest.java"), strings(service, "test_classes"));
        assertEquals(List.of("FIELD clock: x.Clock [\"@Default\"] -> x.Clock (CLASS) [RESOLVED]"), strings(impl, "injects"));
        assertEquals(List.of("x.Consumer#service (FIELD x.Service) [RESOLVED]"), strings(impl, "injected_into"));
        assertFalse(service.has("injects"));
    }

    private static ObjectNode relations(Jdbi db, String name) throws Exception {
        return TestReads.read(db, reader -> {
            ClassRecord cls = reader.symbols.findClassesByName(name).getFirst();
            ObjectNode fact = new ObjectMapper().createObjectNode();
            ClassPrefetchRelations.append(reader, fact, cls);
            return fact;
        });
    }

    private static List<String> strings(ObjectNode fact, String field) {
        return fact.path(field).valueStream().map(node -> node.asText()).toList();
    }
}
