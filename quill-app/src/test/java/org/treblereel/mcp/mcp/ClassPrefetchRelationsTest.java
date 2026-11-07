package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.treblereel.mcp.model.ExternalBeanRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

class ClassPrefetchRelationsTest {

    @TempDir Path root;

    @Test
    void contractCallersIncludeImplementationSelfCallsButNotUnrelatedMethods() throws Exception {
        var db = JavaEvidenceFixture.index(root, """
                package x;
                interface Service { void run(); }
                class Impl implements Service {
                    public void run() {}
                    void repeat() { run(); }
                    void unrelated() {}
                }
                class Consumer { void call(Service s) { s.run(); } }
                class Other { void call(Impl i) { i.unrelated(); } }
                """);
        ObjectNode fact = relations(db, "x.Service");
        assertEquals(2, fact.path("contract_caller_classes").asInt(), fact.toString());
        assertEquals(2, strings(fact, "contract_callers").size());
        assertTrue(strings(fact, "contract_callers").stream().allMatch(line -> line.endsWith(" calls run()")), fact.toString());
        assertEquals(0, fact.path("contract_application_producer_count").asInt(-1));
        assertTrue(strings(fact, "contract_callers").stream().anyMatch(line -> line.startsWith("x.Impl ")));
        assertTrue(strings(fact, "contract_callers").stream().noneMatch(line -> line.startsWith("x.Other ")));
    }

    @Test
    void contractCallShowsShortParameterTypesWithoutReturnTypes() {
        assertEquals("reportFor(ActiveRecording,String),key(int[]),run(),entry(Entry)",
                ClassPrefetchRelations.compactCalls("reportFor(Lio/x/ActiveRecording;Ljava/lang/String;)Lio/smallrye/Uni;,"
                        + "key([I)Z,run()V,run()Ljava/lang/Object;,entry(Ljava/util/Map$Entry;)V"));
    }

    @Test
    void unreadableContractCallStaysAsWritten() {
        assertEquals("null", ClassPrefetchRelations.compactCalls("null"));
        assertEquals("a,b(int)", ClassPrefetchRelations.compactCalls("a,b(I)V"));
        assertEquals("m(bad", ClassPrefetchRelations.compactCalls("m(bad"));
    }

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
        db.useHandle(handle -> {
            handle.execute("UPDATE classes SET source_set = CASE WHEN class_name = 'x.ServiceTest' THEN 'test' ELSE 'main' END");
            handle.execute("INSERT INTO method_calls(from_class_id, from_method, from_descriptor, to_class_id, to_method, to_descriptor, "
                    + "invocation_kind) VALUES (4, 'tick', '()V', 1, 'key', '()Ljava/lang/String;', 'static')");
        });

        ObjectNode service = relations(db, "x.Service");
        ObjectNode impl = relations(db, "x.Impl");

        assertEquals(List.of("x.Consumer#service (FIELD x.Service) [\"@Default\"] [RESOLVED]",
                        "x.Consumer#services (FIELD jakarta.enterprise.inject.Instance<x.Service>) [UNKNOWN]"),
                strings(service, "injected_into"));
        assertEquals(List.of("src/test/java/x/ServiceTest.java"), strings(service, "test_classes"));
        assertEquals(List.of("Clock.java", "Consumer.java", "Impl.java"), strings(service, "related_source_files"));
        assertEquals(List.of("FIELD clock: x.Clock [\"@Default\"] -> x.Clock (CLASS) [RESOLVED]"), strings(impl, "injects"));
        assertEquals(List.of("x.Consumer#service (FIELD x.Service) [\"@Default\"] [RESOLVED]"), strings(impl, "injected_into"));
        assertFalse(service.has("injects"));
    }

    @Test
    void eventAndInstanceInjectionsNameTheirObserversAndConditionalCandidates() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("events.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.Publisher", "CLASS", "java.lang.Object", List.of(), "Publisher.java", 1, true, 10),
                        new ClassRecord(0, "x.Events$Created", "CLASS", "java.lang.Object", List.of(), "Events.java", 1, false, 10),
                        new ClassRecord(0, "x.Listener", "CLASS", "java.lang.Object", List.of(), "Listener.java", 1, true, 10),
                        new ClassRecord(0, "x.BucketStore", "CLASS", "java.lang.Object", List.of("x.Store"), "BucketStore.java", 1, true, 10)))
                .beans(List.of(
                        new BeanRecord(0, 1, "CLASS", "@Singleton", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Publisher")),
                        new BeanRecord(0, 3, "CLASS", "@Singleton", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Listener")),
                        new BeanRecord(0, 4, "CLASS", "@ApplicationScoped", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.BucketStore", "x.Store"))))
                .injectionPoints(List.of(
                        InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "jakarta.enterprise.event.Event<x.Events$Created>", List.of("@Default"), "created", null, false, InjectionPointRecord.STATIC_CDI),
                        InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "jakarta.enterprise.inject.Instance<x.Store>", List.of("@Default"), "stores", null, false, InjectionPointRecord.STATIC_CDI)))
                .dependencies(List.of()).metadata(Map.of("index_id", "events")).snapshot());
        db.useHandle(handle -> {
            handle.execute("INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations,annotation_details) VALUES (3,'METHOD','onCreated','onCreated(x.Events$Created):void','(Lx/Events$Created;)V','void','[\"x.Events$Created\"]','void','[]',?)",
                    "[{\"annotationName\":\"jakarta.enterprise.event.Observes\",\"targetKind\":\"METHOD_PARAMETER\",\"parameterIndex\":0,\"parameterName\":\"e\",\"parameterType\":\"x.Events$Created\",\"values\":{\"$defaults\":[\"notifyObserver\"],\"during\":\"AFTER_SUCCESS\"}}]");
            handle.execute("INSERT INTO class_annotations(class_id,annotation_name,direct,annotation_values) VALUES (4,'io.quarkus.arc.lookup.LookupIfProperty',1,?)",
                    "{\"$defaults\":[\"lookupIfMissing\"],\"name\":\"store.mode\",\"stringValue\":\"bucket\"}");
        });

        ObjectNode publisher = relations(db, "x.Publisher");

        assertEquals(List.of("x.Listener#onCreated(@Observes x.Events.Created during=AFTER_SUCCESS)"),
                strings(publisher, "observers_of_injected_events"));
        assertEquals(List.of("CLASS x.BucketStore @ApplicationScoped qualifiers [\"@Default\"] "
                        + "@io.quarkus.arc.lookup.LookupIfProperty(name=store.mode, stringValue=bucket)"),
                strings(publisher, "instance_candidates"));
        assertEquals("FIELD created: jakarta.enterprise.event.Event<x.Events.Created> [\"@Default\"] -> not resolved by the application index [UNKNOWN]",
                strings(publisher, "injects").get(0));
        assertEquals("java.util.List<x.Outer.Inner$1>", ClassPrefetchRelations.source("java.util.List<x.Outer$Inner$1>"));
    }

    @Test
    void decoratorsAreNamedWithPriorityAndUnresolvedPointsListQualifiedDependencyProviders() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("decorators.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.Service", "INTERFACE", null, List.of(), "Service.java", 1, false, 10),
                        new ClassRecord(0, "x.Impl", "CLASS", "java.lang.Object", List.of("x.Service"), "Impl.java", 1, true, 10),
                        new ClassRecord(0, "x.Caching", "CLASS", "java.lang.Object", List.of("x.Service"), "Caching.java", 1, true, 10),
                        new ClassRecord(0, "x.Audit", "CLASS", "java.lang.Object", List.of("x.Service"), "Audit.java", 1, true, 10)))
                .beans(List.of(
                        new BeanRecord(0, 2, "CLASS", "@ApplicationScoped", List.of("@Default"), List.of(), false, null, null, null, null, List.of("x.Impl", "x.Service")),
                        new BeanRecord(0, 3, "DECORATOR", "@Dependent", List.of("@Default"), List.of(), false, 20, null, null, null, List.of("x.Caching", "x.Service")),
                        new BeanRecord(0, 4, "DECORATOR", "@Dependent", List.of("@Default"), List.of(), false, 10, null, null, null, List.of("x.Audit", "x.Service"))))
                .injectionPoints(List.of(
                        InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "org.Log", List.of("@Default"), "log", null, false, InjectionPointRecord.STATIC_CDI),
                        InjectionPointRecord.staticAnalysis(0, 2, "FIELD", "x.Service", List.of("@Any"), "delegate", null, false, InjectionPointRecord.STATIC_CDI)))
                .dependencies(List.of()).metadata(Map.of("index_id", "decorators")).snapshot());
        IndexFixture.writeExternalBeans(db, List.of(
                new ExternalBeanRecord(1, "dep.LogProducer", "PRODUCER_METHOD", "@Dependent", List.of("@Default", "@Any"), List.of(),
                        false, false, null, List.of(), "simple", List.of("org.Log"), "cdi", "dep:log:1", "/tmp/log.jar", List.of()),
                new ExternalBeanRecord(2, "dep.LogProducer", "PRODUCER_METHOD", "@Dependent", List.of("@dep.LogName", "@Any"), List.of(),
                        false, false, null, List.of(), "named", List.of("org.Log"), "cdi", "dep:log:1", "/tmp/log.jar", List.of())));

        ObjectNode service = relations(db, "x.Service");
        ObjectNode impl = relations(db, "x.Impl");

        assertEquals(List.of("x.Audit @Priority(10)", "x.Caching @Priority(20)"), strings(service, "decorators"));
        assertEquals(List.of("x.Caching#delegate (FIELD x.Service) [\"@Any\"] [UNKNOWN] in a decorator: a delegate point resolves to the decorated bean"),
                strings(service, "injected_into"));
        assertEquals(List.of("FIELD log: org.Log [\"@Default\"] -> not resolved by the application index; "
                        + "dependency providers: dep.LogProducer#simple from dep:log:1 [UNKNOWN]"),
                strings(impl, "injects"));
        assertFalse(impl.has("decorators"));
    }

    @Test
    void producerCandidateShowsDefaultBean() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(db, new IndexFixture()
                .classes(List.of(
                        new ClassRecord(0, "x.Consumer", "CLASS", "java.lang.Object", List.of(), "Consumer.java", 1, true, 10),
                        new ClassRecord(0, "x.Producers", "CLASS", "java.lang.Object", List.of(), "Producers.java", 1, true, 10),
                        new ClassRecord(0, "x.FileSystem", "INTERFACE", null, List.of(), "FileSystem.java", 1, false, 10)))
                .beans(List.of(
                        new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"), List.of(), false, false,
                                null, null, null, null, List.of("x.Consumer")),
                        new BeanRecord(0, 2, "PRODUCER_METHOD", "@ApplicationScoped", List.of("@Default"), List.of(), false, true,
                                null, null, 2, "produce", List.of("x.FileSystem"))))
                .injectionPoints(List.of(InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "x.FileSystem",
                        List.of("@Default"), "fs", 2, false, InjectionPointRecord.STATIC_CDI)))
                .metadata(Map.of("index_id", "default-bean")).snapshot());

        assertEquals(List.of("FIELD fs: x.FileSystem [\"@Default\"] -> x.Producers#produce (PRODUCER_METHOD) @DefaultBean [RESOLVED]"),
                strings(relations(db, "x.Consumer"), "injects"));
        assertTrue(TestReads.read(db, reader -> ClassPrefetchRelations.typeCandidates(reader, "x.FileSystem"))
                .getFirst().endsWith(" @DefaultBean"));
        String points = TestReads.read(db, reader -> new StructureToolQueries().getInjectionPoints(reader, "x.Consumer"));
        var point = new ObjectMapper().readTree(points).path("injection_points").get(0);
        assertTrue(point.path("resolved_default_bean").asBoolean(), point.toString());
    }

    @Test
    void defaultBeanSurvivesPrefetchTruncation() throws Exception {
        var json = new ObjectMapper();
        ObjectNode packet = json.createObjectNode();
        ObjectNode point = packet.putArray("facts").addObject().put("kind", "injection_point")
                .putObject("injection_point").put("resolved_default_bean", true);
        var limitations = point.putArray("limitations");
        for (int i = 0; i < 400; i++) limitations.add("limitation number " + i + " with some padding text");

        PrefetchContext.boundContext(packet);

        assertTrue(packet.path("context_truncated").asBoolean());
        assertTrue(packet.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES);
        assertTrue(packet.path("facts").get(0).path("injection_point").path("resolved_default_bean").asBoolean());
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
