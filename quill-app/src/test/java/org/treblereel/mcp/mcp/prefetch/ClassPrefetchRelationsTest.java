package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.BeanRepository.ClassInjection;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.query.StructureToolQueries;
import org.treblereel.mcp.mcp.query.SymbolToolQueries;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalBeanRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

class ClassPrefetchRelationsTest {

    private static ClassInjection point(String kind, String fieldName) {
        return new ClassInjection(
                kind, fieldName, null, null, null, null, null, null, null, false, null, null, null);
    }

    /**
     * V62 CQ-009: Claude read both superclasses in all three sessions to tell inherited
     * dependencies from declared ones.
     */
    @Test
    void injectionOriginSeparatesDeclaredFromInheritedPoints() {
        assertEquals(
                "declared here: none; inherited: EntityNotificationObserver (bus, logger)",
                ClassPrefetchRelations.injectionOrigin(
                        List.of(
                                point("FIELD", "EntityNotificationObserver.bus"),
                                point("FIELD", "EntityNotificationObserver.logger"))));
        assertEquals(
                "declared here: mapper, <init>; inherited: Base (bus)",
                ClassPrefetchRelations.injectionOrigin(
                        List.of(
                                point("FIELD", "mapper"),
                                point("CONSTRUCTOR_PARAM", "<init>"),
                                point("FIELD", "Base.bus"))));
        assertEquals(
                null, ClassPrefetchRelations.injectionOrigin(List.of(point("FIELD", "mapper"))));
    }

    @TempDir Path root;

    @Test
    void contractCallersIncludeImplementationSelfCallsButNotUnrelatedMethods() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
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
        // Grouped by package and called methods: the self-calling implementation is a caller,
        // the class calling an unrelated implementation method is not.
        assertEquals(List.of("x: Consumer, Impl call run()"), strings(fact, "contract_callers"), fact.toString());
        assertEquals(0, fact.path("contract_application_producer_count").asInt(-1));
    }

    @Test
    void contractCallShowsShortParameterTypesWithoutReturnTypes() {
        assertEquals(
                "reportFor(ActiveRecording,String),key(int[]),run(),entry(Entry)",
                ClassPrefetchRelations.compactCalls(
                        "reportFor(Lio/x/ActiveRecording;Ljava/lang/String;)Lio/smallrye/Uni;,"
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
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Service",
                                                "INTERFACE",
                                                null,
                                                List.of(),
                                                "Service.java",
                                                1,
                                                false,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Impl",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of("x.Service"),
                                                "Impl.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Consumer",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Consumer.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Clock",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Clock.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.ServiceTest",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "src/test/java/x/ServiceTest.java",
                                                1,
                                                false,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Producers",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Producers.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                2,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Impl", "x.Service")),
                                        new BeanRecord(
                                                0,
                                                3,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Consumer")),
                                        new BeanRecord(
                                                0,
                                                4,
                                                "CLASS",
                                                "@Dependent",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Clock")),
                                        new BeanRecord(
                                                0,
                                                6,
                                                "PRODUCER_METHOD",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                false,
                                                null,
                                                List.of(),
                                                6,
                                                "client",
                                                List.of("x.Client")),
                                        new BeanRecord(
                                                0,
                                                6,
                                                "PRODUCER_FIELD",
                                                "@Dependent",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                false,
                                                null,
                                                List.of(),
                                                6,
                                                "zone",
                                                List.of("x.Zone"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "x.Clock",
                                                List.of("@Default"),
                                                "clock",
                                                3,
                                                false,
                                                InjectionPointRecord.STATIC_CDI),
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                2,
                                                "FIELD",
                                                "x.Service",
                                                List.of("@Default"),
                                                "service",
                                                1,
                                                false,
                                                InjectionPointRecord.STATIC_CDI),
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                2,
                                                "FIELD",
                                                "jakarta.enterprise.inject.Instance<x.Service>",
                                                List.of(),
                                                "services",
                                                null,
                                                false,
                                                InjectionPointRecord.STATIC_CDI)))
                        .dependencies(
                                List.of(
                                        new DependencyRecord(
                                                5, 1, "TYPE_REFERENCE", null, 1, List.of())))
                        .metadata(Map.of("index_id", "relations"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    handle.execute(
                            "UPDATE classes SET source_set = CASE WHEN class_name = 'x.ServiceTest' THEN 'test' ELSE 'main' END");
                    handle.execute(
                            "INSERT INTO method_calls(from_class_id, from_method, from_descriptor, to_class_id, to_method, to_descriptor, "
                                    + "invocation_kind) VALUES (4, 'tick', '()V', 1, 'key', '()Ljava/lang/String;', 'static')");
                    // V24-V27 CQ-045: Claude read the consumer's source for its scope and the
                    // methods using the bean.
                    handle.execute(
                            "INSERT INTO method_calls(from_class_id, from_method, from_descriptor, to_class_id, to_method, to_descriptor, "
                                    + "invocation_kind) VALUES (3, 'lambda$handle$0', '()V', 1, 'run', '()V', 'interface'), "
                                    + "(3, 'audit', '()V', 1, 'run', '()V', 'interface')");
                });

        ObjectNode service = relations(db, "x.Service");
        ObjectNode impl = relations(db, "x.Impl");

        assertEquals(
                List.of(
                        "x.Consumer#service (FIELD x.Service) [\"@Default\"] [RESOLVED] owner @Singleton, uses it in audit, handle",
                        "x.Consumer#services (FIELD jakarta.enterprise.inject.Instance<x.Service>) [UNKNOWN] owner @Singleton, uses it in audit, handle"),
                strings(service, "injected_into"));
        assertEquals(List.of("src/test/java/x/ServiceTest.java"), strings(service, "test_classes"));
        assertEquals(
                List.of("Clock.java", "Consumer.java", "Impl.java"),
                strings(service, "related_source_files"));
        assertEquals(
                List.of(
                        "FIELD clock: x.Clock [\"@Default\"] -> x.Clock (CLASS @Dependent) [RESOLVED]"),
                strings(impl, "injects"));
        assertEquals(
                List.of(
                        "x.Consumer#service (FIELD x.Service) [\"@Default\"] [RESOLVED] owner @Singleton"),
                strings(impl, "injected_into"));
        assertFalse(service.has("injects"));
        // V32/V33 CQ-044: asked to turn a class bean into a producer, no Quill session found the
        // application's producer host.
        assertEquals(
                List.of("x.Producers (2)"),
                strings(impl, "application_producer_hosts"),
                impl.toString());
        assertFalse(
                service.has("application_producer_hosts"),
                "only a class bean gets them: " + service);
    }

    @Test
    void eventAndInstanceInjectionsNameTheirObserversAndConditionalCandidates() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("events.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Publisher",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Publisher.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Events$Created",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Events.java",
                                                1,
                                                false,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Listener",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Listener.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.BucketStore",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of("x.Store"),
                                                "BucketStore.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Publisher")),
                                        new BeanRecord(
                                                0,
                                                3,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Listener")),
                                        new BeanRecord(
                                                0,
                                                4,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.BucketStore", "x.Store"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "jakarta.enterprise.event.Event<x.Events$Created>",
                                                List.of("@Default"),
                                                "created",
                                                null,
                                                false,
                                                InjectionPointRecord.STATIC_CDI),
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "jakarta.enterprise.inject.Instance<x.Store>",
                                                List.of("@Default"),
                                                "stores",
                                                null,
                                                false,
                                                InjectionPointRecord.STATIC_CDI)))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "events"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations,annotation_details) VALUES (3,'METHOD','onCreated','onCreated(x.Events$Created):void','(Lx/Events$Created;)V','void','[\"x.Events$Created\"]','void','[]',?)",
                            "[{\"annotationName\":\"jakarta.enterprise.event.Observes\",\"targetKind\":\"METHOD_PARAMETER\",\"parameterIndex\":0,\"parameterName\":\"e\",\"parameterType\":\"x.Events$Created\",\"values\":{\"$defaults\":[\"notifyObserver\"],\"during\":\"AFTER_SUCCESS\"}}]");
                    handle.execute(
                            "INSERT INTO class_annotations(class_id,annotation_name,direct,annotation_values) VALUES (4,'io.quarkus.arc.lookup.LookupIfProperty',1,?)",
                            "{\"$defaults\":[\"lookupIfMissing\"],\"name\":\"store.mode\",\"stringValue\":\"bucket\"}");
                });

        ObjectNode publisher = relations(db, "x.Publisher");

        assertEquals(
                List.of("x.Listener#onCreated(@Observes x.Events.Created during=AFTER_SUCCESS)"),
                strings(publisher, "observers_of_injected_events"));
        assertEquals(
                List.of(
                        "CLASS x.BucketStore @ApplicationScoped qualifiers [\"@Default\"] "
                                + "@io.quarkus.arc.lookup.LookupIfProperty(name=store.mode, stringValue=bucket)"),
                strings(publisher, "instance_candidates"));
        // V62 CQ-014: the condition alone sent Codex into ArC bytecode to learn what Instance.get()
        // does without the bean.
        assertTrue(
                publisher
                        .path("candidate_conditions")
                        .path("@LookupIfProperty")
                        .asText()
                        .contains("UnsatisfiedResolutionException"),
                publisher.toString());
        assertEquals(
                "FIELD created: jakarta.enterprise.event.Event<x.Events.Created> [\"@Default\"] -> not resolved by the application index [UNKNOWN]",
                strings(publisher, "injects").get(0));
        assertEquals(
                "java.util.List<x.Outer.Inner$1>",
                ClassPrefetchRelations.source("java.util.List<x.Outer$Inner$1>"));
    }

    /**
     * V62 CQ-014: get_context named @LookupIfProperty without its values, so Codex read ArC
     * bytecode to learn what get() does.
     */
    @Test
    void symbolDetailsStateABeansLookupConditionAndWhatGetDoesWithoutIt() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("lookup.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.BucketStore",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "BucketStore.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.BucketStore"))))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "lookup"))
                        .snapshot());
        db.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO class_annotations(class_id,annotation_name,direct,annotation_values) VALUES (1,'io.quarkus.arc.lookup.LookupIfProperty',1,?)",
                                "{\"$defaults\":[\"lookupIfMissing\"],\"name\":\"store.mode\",\"stringValue\":\"bucket\"}"));

        var bean =
                TestReads.read(
                                db,
                                reader ->
                                        new SymbolToolQueries()
                                                .getSymbolDetails(
                                                        reader,
                                                        "x.BucketStore",
                                                        false,
                                                        null,
                                                        10,
                                                        0))
                        .path("bean");

        var condition = bean.path("registration_conditions").path(0);
        assertEquals(
                "bucket", condition.path("values").path("stringValue").asText(), bean.toString());
        assertTrue(
                condition.path("meaning").asText().contains("UnsatisfiedResolutionException"),
                condition.toString());
    }

    /**
     * V62 CQ-003: every Codex session grepped beans.xml and quarkus.arc.* to explain why a class is
     * a bean.
     */
    @Test
    void symbolDetailsStateWhyAClassIsDiscoveredAsABean() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("discovery.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Mapper",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Mapper.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default", "@Any"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Mapper"))))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "discovery"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO class_annotations(class_id,annotation_name,direct,annotation_values) VALUES (1,'jakarta.enterprise.context.ApplicationScoped',1,'{}')");
                    handle.execute(
                            "INSERT INTO files(project_path,repository_path,kind,origin,lifecycle,module,source_set) VALUES ('src/main/resources/META-INF/beans.xml','src/main/resources/META-INF/beans.xml','resource','source','current','.','main')");
                });

        String discovery =
                TestReads.read(
                                db,
                                reader ->
                                        new SymbolToolQueries()
                                                .getSymbolDetails(
                                                        reader, "x.Mapper", false, null, 10, 0))
                        .path("bean")
                        .path("discovery")
                        .asText();

        assertTrue(
                discovery.contains("bean-defining annotation @ApplicationScoped on the class"),
                discovery);
        assertTrue(discovery.contains("stereotypes: none"), discovery);
        assertTrue(discovery.contains("src/main/resources/META-INF/beans.xml"), discovery);
        assertTrue(discovery.contains("no quarkus.arc.exclude-types"), discovery);
    }

    /**
     * V66 CQ-005: asked whether Quarkus may remove a bean, Codex read HttpSecurityProcessor
     * bytecode and searched the web for the rules.
     */
    @Test
    void symbolDetailsStateWhetherArcMayRemoveAClassBean() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("removal.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Mapper",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Mapper.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Auth",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Auth.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Idle",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Idle.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default", "@Any"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Mapper")),
                                        new BeanRecord(
                                                0,
                                                2,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default", "@Any"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Auth")),
                                        new BeanRecord(
                                                0,
                                                3,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default", "@Any"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Idle"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                2,
                                                "FIELD",
                                                "x.Mapper",
                                                List.of("@Default"),
                                                "mapper",
                                                1,
                                                false,
                                                InjectionPointRecord.STATIC_CDI)))
                        .dependencies(List.of())
                        .metadata(
                                Map.of(
                                        "index_id",
                                        "removal",
                                        org.treblereel.mcp.core.GeneratedBytecodeScanner
                                                .METADATA_KEY,
                                        "{\"classes\":[{\"class\":\"x.Mapper_Bean\",\"source_class\":\"x.Mapper\",\"jar\":\"target/quarkus-app/quarkus/generated-bytecode.jar\",\"entry\":\"x/Mapper_Bean.class\",\"references\":[]}],\"jars\":[]}"))
                        .snapshot());

        String kept =
                TestReads.read(
                                db,
                                reader ->
                                        new SymbolToolQueries()
                                                .getSymbolDetails(
                                                        reader, "x.Mapper", false, null, 10, 0))
                        .path("bean")
                        .path("arc_removal")
                        .asText();
        assertTrue(kept.contains("kept: injected into x.Auth#mapper"), kept);
        assertTrue(
                kept.contains("quarkus.arc.remove-unused-beans not declared (default all)"), kept);
        assertTrue(kept.contains("generated x.Mapper_Bean"), kept);

        String idle =
                TestReads.read(
                                db,
                                reader ->
                                        new SymbolToolQueries()
                                                .getSymbolDetails(
                                                        reader, "x.Idle", false, null, 10, 0))
                        .path("bean")
                        .path("arc_removal")
                        .asText();
        assertTrue(idle.contains("not ruled out"), idle);
        assertTrue(idle.contains("no generated x.Idle_Bean"), idle);

        // V66 probe of CQ-009: an observer bean read as removable although its @Observes method
        // keeps it.
        db.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO class_members(class_id,kind,name,descriptor,signature,type_name,parameter_types,modifiers,annotations) "
                                        + "VALUES (3,'METHOD','onEvent','(Lx/Event;)V','onEvent(x.Event):void','void','[]','','[\"jakarta.enterprise.event.Observes\"]')"));
        String observer =
                TestReads.read(
                                db,
                                reader ->
                                        new SymbolToolQueries()
                                                .getSymbolDetails(
                                                        reader, "x.Idle", false, null, 10, 0))
                        .path("bean")
                        .path("arc_removal")
                        .asText();
        assertTrue(observer.contains("kept: observer method onEvent"), observer);
    }

    /**
     * V62 CQ-045: asked about a scope change, Claude read the class for its state and @PreDestroy
     * and opened each dependency for its scope.
     */
    @Test
    void aClassStatesItsInstanceStateAndLifecycleCallbacks() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("state.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Cache",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Cache.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Cache"))))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "state"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    String insert =
                            "INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations,annotation_details) VALUES (1,?,?,?,?,?,'[]',?,?,'[]')";
                    handle.execute(
                            insert,
                            "FIELD",
                            "entries",
                            "entries",
                            "Ljava/util/Map;",
                            "java.util.Map<java.lang.String, x.Client>",
                            "private final",
                            "[]");
                    handle.execute(insert, "FIELD", "hits", "hits", "J", "long", "private", "[]");
                    handle.execute(
                            insert,
                            "FIELD",
                            "LIMIT",
                            "LIMIT",
                            "I",
                            "int",
                            "private static final",
                            "[]");
                    handle.execute(
                            insert,
                            "METHOD",
                            "shutdown",
                            "shutdown():void",
                            "()V",
                            "void",
                            "public",
                            "[\"jakarta.annotation.PreDestroy\"]");
                    handle.execute(
                            insert, "METHOD", "read", "read():void", "()V", "void", "public", "[]");
                });

        ObjectNode cache = relations(db, "x.Cache");

        assertEquals(
                List.of("entries java.util.Map<java.lang.String, x.Client> final", "hits long"),
                strings(cache, "instance_state"),
                cache.toString());
        assertEquals(
                List.of("shutdown() @PreDestroy"),
                strings(cache, "lifecycle_callbacks"),
                cache.toString());
    }

    @Test
    void decoratorsAreNamedWithPriorityAndUnresolvedPointsListQualifiedDependencyProviders()
            throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("decorators.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Service",
                                                "INTERFACE",
                                                null,
                                                List.of(),
                                                "Service.java",
                                                1,
                                                false,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Impl",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of("x.Service"),
                                                "Impl.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Caching",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of("x.Service"),
                                                "Caching.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Audit",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of("x.Service"),
                                                "Audit.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                2,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Impl", "x.Service")),
                                        new BeanRecord(
                                                0,
                                                3,
                                                "DECORATOR",
                                                "@Dependent",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                20,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Caching", "x.Service")),
                                        new BeanRecord(
                                                0,
                                                4,
                                                "DECORATOR",
                                                "@Dependent",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                10,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Audit", "x.Service"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "org.Log",
                                                List.of("@Default"),
                                                "log",
                                                null,
                                                false,
                                                InjectionPointRecord.STATIC_CDI),
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                2,
                                                "FIELD",
                                                "x.Service",
                                                List.of("@Any"),
                                                "delegate",
                                                null,
                                                false,
                                                InjectionPointRecord.STATIC_CDI)))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "decorators"))
                        .snapshot());
        IndexFixture.writeExternalBeans(
                db,
                List.of(
                        new ExternalBeanRecord(
                                1,
                                "dep.LogProducer",
                                "PRODUCER_METHOD",
                                "@Dependent",
                                List.of("@Default", "@Any"),
                                List.of(),
                                false,
                                false,
                                null,
                                List.of(),
                                "simple",
                                List.of("org.Log"),
                                "cdi",
                                "dep:log:1",
                                "/tmp/log.jar",
                                List.of()),
                        new ExternalBeanRecord(
                                2,
                                "dep.LogProducer",
                                "PRODUCER_METHOD",
                                "@Dependent",
                                List.of("@dep.LogName", "@Any"),
                                List.of(),
                                false,
                                false,
                                null,
                                List.of(),
                                "named",
                                List.of("org.Log"),
                                "cdi",
                                "dep:log:1",
                                "/tmp/log.jar",
                                List.of())));

        ObjectNode service = relations(db, "x.Service");
        ObjectNode impl = relations(db, "x.Impl");

        assertEquals(
                List.of("x.Audit @Priority(10)", "x.Caching @Priority(20)"),
                strings(service, "decorators"));
        assertEquals(
                List.of(
                        "x.Caching#delegate (FIELD x.Service) [\"@Any\"] [UNKNOWN] in a decorator: a delegate point resolves to the decorated bean"),
                strings(service, "injected_into"));
        assertEquals(
                List.of(
                        "FIELD log: org.Log [\"@Default\"] -> not resolved by the application index; "
                                + "dependency providers: dep.LogProducer#simple from dep:log:1 [UNKNOWN]"),
                strings(impl, "injects"));
        assertFalse(impl.has("decorators"));
    }

    @Test
    void producerCandidateShowsDefaultBean() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Consumer",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Consumer.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Producers",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Producers.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.FileSystem",
                                                "INTERFACE",
                                                null,
                                                List.of(),
                                                "FileSystem.java",
                                                1,
                                                false,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                false,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("x.Consumer")),
                                        new BeanRecord(
                                                0,
                                                2,
                                                "PRODUCER_METHOD",
                                                "@ApplicationScoped",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                true,
                                                null,
                                                null,
                                                2,
                                                "produce",
                                                List.of("x.FileSystem"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                0,
                                                1,
                                                "FIELD",
                                                "x.FileSystem",
                                                List.of("@Default"),
                                                "fs",
                                                2,
                                                false,
                                                InjectionPointRecord.STATIC_CDI)))
                        .metadata(Map.of("index_id", "default-bean"))
                        .snapshot());

        assertEquals(
                List.of(
                        "FIELD fs: x.FileSystem [\"@Default\"] -> x.Producers#produce (PRODUCER_METHOD @ApplicationScoped) @DefaultBean [RESOLVED]"),
                strings(relations(db, "x.Consumer"), "injects"));
        assertTrue(
                TestReads.read(
                                db,
                                reader ->
                                        ClassPrefetchRelations.typeCandidates(
                                                reader, "x.FileSystem"))
                        .getFirst()
                        .endsWith(" @DefaultBean"));
        String points =
                TestReads.read(
                                db,
                                reader ->
                                        new StructureToolQueries()
                                                .getInjectionPoints(reader, "x.Consumer"))
                        .toString();
        var point = new ObjectMapper().readTree(points).path("injection_points").get(0);
        assertEquals(
                "@DefaultBean: used unless an eligible non-default bean exists",
                point.path("resolved_fallback").asText(),
                point.toString());
    }

    // V41 CQ-024: asked for a config mapping's synthetic bean dependencies, Claude listed its
    // consumers.
    @Test
    void aSyntheticConfigMappingBeanStatesItsInjectionPointDependency() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("index.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.AppConfig",
                                                "INTERFACE",
                                                null,
                                                List.of(),
                                                "AppConfig.java",
                                                1,
                                                false,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "SYNTHETIC",
                                                "@Dependent",
                                                List.of("@Default", "@Any"),
                                                List.of(),
                                                false,
                                                false,
                                                null,
                                                null,
                                                null,
                                                "synthetic:ConfigMappingCreator",
                                                List.of("x.AppConfig"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                        0,
                                                        1,
                                                        "SYNTHETIC",
                                                        "jakarta.enterprise.inject.spi.InjectionPoint",
                                                        List.of("@Default"),
                                                        "ConfigMappingCreator",
                                                        null,
                                                        false,
                                                        InjectionPointRecord.STATIC_CDI)
                                                .asCdiBuiltin()))
                        .metadata(Map.of("index_id", "synthetic"))
                        .snapshot());

        assertEquals(
                List.of(
                        "SYNTHETIC ConfigMappingCreator: jakarta.enterprise.inject.spi.InjectionPoint [\"@Default\"] -> built-in bean [RESOLVED]"),
                strings(relations(db, "x.AppConfig"), "injects"));
        // Within the class fact's bean, where the prefetch budget never drops it.
        ObjectNode fact =
                TestReads.read(
                        db,
                        reader -> {
                            ObjectNode node = new ObjectMapper().createObjectNode();
                            node.putObject("bean").put("kind", "SYNTHETIC");
                            ClassPrefetchRelations.append(
                                    reader,
                                    node,
                                    reader.symbols.findClassesByName("x.AppConfig").getFirst());
                            return node;
                        });
        assertEquals(
                List.of(
                        "SYNTHETIC ConfigMappingCreator: jakarta.enterprise.inject.spi.InjectionPoint [\"@Default\"] -> built-in bean [RESOLVED]"),
                strings((ObjectNode) fact.path("bean"), "injects"),
                fact.toString());
        assertFalse(fact.has("injects"), fact.toString());
    }

    @Test
    void defaultBeanSurvivesPrefetchTruncation() throws Exception {
        var json = new ObjectMapper();
        ObjectNode packet = json.createObjectNode();
        ObjectNode point =
                packet.putArray("facts")
                        .addObject()
                        .put("kind", "injection_point")
                        .putObject("injection_point")
                        .put("resolved_fallback", "@DefaultBean");
        var limitations = point.putArray("limitations");
        for (int i = 0; i < 400; i++)
            limitations.add("limitation number " + i + " with some padding text");

        PrefetchContext.boundContext(packet);

        assertTrue(packet.path("context_truncated").asBoolean());
        assertTrue(packet.toString().length() <= PrefetchContext.CONTEXT_LIMIT_BYTES);
        assertEquals(
                "@DefaultBean",
                packet.path("facts")
                        .get(0)
                        .path("injection_point")
                        .path("resolved_fallback")
                        .asText());
    }

    private static ObjectNode relations(Jdbi db, String name) throws Exception {
        return TestReads.read(
                db,
                reader -> {
                    ClassRecord cls = reader.symbols.findClassesByName(name).getFirst();
                    ObjectNode fact = new ObjectMapper().createObjectNode();
                    ClassPrefetchRelations.append(reader, fact, cls);
                    return fact;
                });
    }

    /**
     * V32 CQ-009: asked what a subclass inherits and overrides, agents walked the hierarchy with
     * three detail calls.
     */
    @Test
    void classFactStatesWhatItOverridesAndInheritsFromIndexedSuperclasses() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        """
                package x;
                abstract class Base {
                    protected abstract Object created(Object e);
                    protected abstract Object deleted(Object e);
                    protected Object updated(Object e) { return null; }
                    protected void publish() {}
                    private void hidden() {}
                    static void util() {}
                }
                abstract class Simple extends Base {
                    protected Object created(Object e) { return payload(e); }
                    protected Object deleted(Object e) { return payload(e); }
                    protected abstract Object payload(Object e);
                }
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @interface Obs {}
                class Observer extends Simple {
                    protected Object payload(Object e) { return e; }
                    protected Object deleted(Object e) { return null; }
                    void own() {}
                    void onEvent(@Obs String event, int count) { helper(event); }
                    private static Object helper(String raw) { return raw; }
                }
                class Plain { void run() {} }
                class Keeper extends Base {
                    protected Object created(Object e) { return e; }
                    protected Object deleted(Object e) { return e; }
                }
                class Heir extends Keeper { void extra() {} }
                """);
        ObjectNode fact = relations(db, "x.Observer");

        assertEquals(
                List.of(
                        "deleted(Object) overrides Simple, Base abstract",
                        "payload(Object) overrides Simple abstract"),
                strings(fact, "overrides"),
                fact.toString());
        assertEquals(
                "created(Object)",
                fact.path("inherited_methods").path("Simple").asText(),
                fact.toString());
        assertEquals(
                "publish(), updated(Object)",
                fact.path("inherited_methods").path("Base").asText(),
                fact.toString());
        assertEquals(List.of("x.Simple", "x.Base"), strings(fact, "superclass_chain"));
        assertTrue(fact.path("overrides_basis").asText().contains("superclass"));
        // V34 CQ-009: with only the overrides stated, every session fetched the class's members to
        // find its own methods.
        assertEquals(
                List.of("helper(String) private static", "onEvent(@Obs String,int)", "own()"),
                strings(fact, "declared_methods"),
                fact.toString());
        assertEquals(
                "handle(Event<E, S>)",
                ClassPrefetchRelations.shortSignature("handle(io.x.Event<E, S>):void"));
        assertEquals(
                "send(String,Object)",
                ClassPrefetchRelations.shortSignature(
                        "send(java.lang.String,java.lang.Object):void"));

        ObjectNode plain = relations(db, "x.Plain");
        assertFalse(plain.has("overrides"), plain.toString());
        assertFalse(plain.has("inherited_methods"), plain.toString());
        assertFalse(plain.has("overrides_basis"), plain.toString());
        assertFalse(plain.has("superclass_chain"), plain.toString());
        ObjectNode heir = relations(db, "x.Heir");
        assertFalse(
                heir.has("inherited_methods"),
                "a class overriding nothing gets no hierarchy section: " + heir);
        assertFalse(heir.has("declared_methods"), heir.toString());
    }

    private static List<String> strings(ObjectNode fact, String field) {
        return fact.path(field).valueStream().map(node -> node.asText()).toList();
    }

    /**
     * Apollo with_quill v2 AQ-005: a Spring bean got ArC removal rules and quarkus.arc settings;
     * Claude's cost tripled.
     */
    @Test
    void aSpringBeanGetsNoArcDiscoveryOrRemovalVerdict() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("spring.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Holder",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Holder.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Default"),
                                                List.of(),
                                                false,
                                                false,
                                                null,
                                                List.of(),
                                                null,
                                                null,
                                                List.of("x.Holder"),
                                                BeanRecord.SPRING)))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "spring"))
                        .snapshot());
        var bean =
                TestReads.read(
                                db,
                                reader ->
                                        new SymbolToolQueries()
                                                .getSymbolDetails(
                                                        reader, "x.Holder", false, null, 10, 0))
                        .path("bean");
        assertFalse(bean.has("arc_removal"), bean.toString());
        assertFalse(bean.has("discovery"), bean.toString());
    }

    /**
     * Apollo v1-v3 AQ-044: asked how to keep a component's bean name, Claude never named
     * the @PreAuthorize SpEL calls that use it.
     */
    @Test
    void aSpringBeanStatesTheAnnotationExpressionsCallingItByName() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("spel.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.Validator",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Validator.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.Controller",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Controller.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        new BeanRecord(
                                                0,
                                                1,
                                                "CLASS",
                                                "@Singleton",
                                                List.of("@Qualifier(\"validator\")"),
                                                List.of(),
                                                false,
                                                false,
                                                null,
                                                List.of(),
                                                null,
                                                null,
                                                List.of("x.Validator"),
                                                BeanRecord.SPRING)))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "spel"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    String insert =
                            "INSERT INTO class_members(class_id,kind,name,signature,descriptor,type_name,parameter_types,modifiers,annotations,annotation_details) "
                                    + "VALUES (2,'METHOD',?,?,'()V','void','[]','public','[\"org.springframework.security.access.prepost.PreAuthorize\"]',?)";
                    handle.execute(
                            insert,
                            "create",
                            "create():void",
                            "[{\"annotationName\":\"org.springframework.security.access.prepost.PreAuthorize\",\"values\":{\"value\":\"@validator.canRelease(#app)\"}}]");
                    handle.execute(
                            insert,
                            "merge",
                            "merge():void",
                            "[{\"annotationName\":\"org.springframework.security.access.prepost.PreAuthorize\",\"values\":{\"value\":\"@validator.canRead(#app) and @validator.canRelease(#app)\"}}]");
                });

        ObjectNode validator = relations(db, "x.Validator");

        String references = validator.path("bean_name_expressions").toString();
        assertTrue(
                references.contains(
                        "bean name validator: 2 annotation expressions (@PreAuthorize)"),
                validator.toString());
        assertTrue(references.contains("canRelease x2"), references);
        assertTrue(references.contains("x.Controller#create"), references);
    }

    /**
     * Apollo v2/v3 AQ-024: asked who registers a @ConfigurationProperties class and when, Codex
     * missed the @ConditionalOnProperty.
     */
    @Test
    void aConfigurationPropertiesClassStatesWhoRegistersItAndWhen() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("properties.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.DiscoveryProperties",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "DiscoveryProperties.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.DiscoveryAutoConfiguration",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "DiscoveryAutoConfiguration.java",
                                                1,
                                                true,
                                                10)))
                        .dependencies(List.of())
                        .metadata(Map.of("index_id", "properties"))
                        .snapshot());
        db.useHandle(
                handle -> {
                    String insert =
                            "INSERT INTO class_annotations(class_id,annotation_name,direct,annotation_values) VALUES (?,?,1,?)";
                    handle.execute(
                            insert,
                            1,
                            "org.springframework.boot.context.properties.ConfigurationProperties",
                            "{\"value\":\"apollo.service.discovery\"}");
                    handle.execute(
                            insert,
                            2,
                            "org.springframework.boot.context.properties.EnableConfigurationProperties",
                            "{\"value\":[\"x.DiscoveryProperties\"]}");
                    handle.execute(
                            insert,
                            2,
                            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                            "{\"$defaults\":[\"havingValue\",\"matchIfMissing\",\"name\"],\"havingValue\":\"\",\"matchIfMissing\":false,\"name\":[],\"prefix\":\"apollo.service.discovery\",\"value\":[\"enabled\"]}");
                });

        ObjectNode properties = relations(db, "x.DiscoveryProperties");

        assertEquals(
                List.of(
                        "x.DiscoveryAutoConfiguration via @EnableConfigurationProperties; @ConditionalOnProperty(prefix=apollo.service.discovery, value=[enabled], matchIfMissing=false)"),
                strings(properties, "registered_by"),
                properties.toString());
        assertEquals(
                "apollo.service.discovery-x.DiscoveryProperties",
                properties.path("properties_bean_name").asText(),
                properties.toString());
    }

    /**
     * Apollo AQ-033 (Codex): asked what starts ReleaseMessageScanner, its Spring dependencies and
     * the settings driving its schedule, Codex read four files; the class fact named
     * InitializingBean but not afterPropertiesSet's scheduling, the interval key it reads through
     * BizConfig, or the @Bean method creating it.
     */
    @Test
    void aLifecycleCallbackStatesItsSchedulingConfigReadsAndCreator(@TempDir Path dir)
            throws Exception {
        Jdbi db =
                JavaEvidenceFixture.index(
                        dir,
                        Map.of(
                                "src/main/java/org/springframework/beans/factory/InitializingBean.java",
                                "package org.springframework.beans.factory; public interface InitializingBean { void afterPropertiesSet(); }",
                                "src/main/java/p/Fixture.java",
                                "package p; class Cfg { int interval() { return 1; } } "
                                        + "class Scanner implements org.springframework.beans.factory.InitializingBean { Cfg cfg = new Cfg(); "
                                        + "public void afterPropertiesSet() { cfg.interval(); } } "
                                        + "class Auto { Scanner scanner() { return new Scanner(); } }"));
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', '@Singleton', '[]', '[\"p.Auto\"]', 'spring' FROM classes WHERE class_name = 'p.Auto'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, framework) "
                                    + "SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Scanner\"]', id, 'scanner', 'spring' FROM classes WHERE class_name = 'p.Auto'");
                    handle.execute(
                            "INSERT INTO configuration_usages(key, kind, class_id, class_name, member, annotation) "
                                    + "SELECT 'scan.interval', 'config_key', id, class_name, 'interval', 'p.Base#getInt -> org.springframework.core.env.Environment#getProperty' FROM classes WHERE class_name = 'p.Cfg'");
                    handle.execute(
                            "INSERT OR REPLACE INTO method_evidence(from_class_id, from_method, from_descriptor, caller_external_calls) "
                                    + "SELECT id, 'afterPropertiesSet', '()V', ? FROM classes WHERE class_name = 'p.Scanner'",
                            "[\"47|79|java.util.concurrent.ScheduledExecutorService|scheduleWithFixedDelay|(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;|\"]");
                });
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(
                db,
                reader -> {
                    ClassPrefetchRelations.append(
                            reader,
                            fact,
                            reader.symbols.findClassByName("p.Scanner").orElseThrow());
                    return null;
                });
        String lifecycle = fact.path("lifecycle_callbacks").toString();
        assertTrue(lifecycle.contains("afterPropertiesSet() InitializingBean"), fact.toString());
        assertTrue(
                lifecycle.contains("ScheduledExecutorService#scheduleWithFixedDelay line 79"),
                fact.toString());
        assertTrue(lifecycle.contains("reads scan.interval via p.Cfg#interval"), fact.toString());
        assertTrue(
                fact.path("created_by_bean_methods").toString().contains("p.Auto#scanner"),
                fact.toString());
    }
}
