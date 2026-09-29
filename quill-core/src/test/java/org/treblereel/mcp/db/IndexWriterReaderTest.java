package org.treblereel.mcp.db;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.model.*;

class IndexWriterReaderTest {

    @TempDir Path tempDir;

    @Test
    void incrementallyUpdatesConfigurationReferencesWithoutReplacingStableRows() {
        Path dbPath = tempDir.resolve("configuration.db");
        Jdbi database = QuillDatabase.create(dbPath);
        List<ClassRecord> classes = List.of(new ClassRecord(
                0, "example.Service", "CLASS", "java.lang.Object", List.of(),
                "src/main/java/example/Service.java", 1, false, 10));
        ConfigurationScanner.Usage usage = new ConfigurationScanner.Usage(
                "app.name", "config_key", 1, "example.Service", "name", null,
                "org.springframework.beans.factory.annotation.Value",
                "src/main/java/example/Service.java", ".", "main");
        ResourceUsageRecord resourceUsage =
                new ResourceUsageRecord(
                        "templates/order.html", "resource", 1, "example.Service", "render",
                        "java.lang.Class#getResource", "src/main/java/example/Service.java",
                        ".", "main");
        ConfigurationScanner.Result initial = new ConfigurationScanner.Result(List.of(
                new ConfigurationScanner.Definition("app.name", "property",
                        "src/main/resources/application.properties", 1, ".", "main"),
                new ConfigurationScanner.Definition("old.key", "property",
                        "src/main/resources/application.properties", 2, ".", "main")),
                List.of(usage));

        IndexWriter.writeFreshWithConfiguration(database, classes, List.of(), List.of(),
                List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), initial,
                List.of(resourceUsage));
        int stableId = database.withHandle(handle -> handle.createQuery(
                        "SELECT id FROM configuration_definitions WHERE key = 'app.name'")
                .mapTo(Integer.class).one());

        ConfigurationScanner.Result updated = new ConfigurationScanner.Result(List.of(
                initial.definitions().getFirst(),
                new ConfigurationScanner.Definition("new.key", "property",
                        "src/main/resources/application.properties", 2, ".", "main")),
                List.of(usage));
        IndexWriter.IncrementalWriteTimings timings =
                IndexWriter.writeIncrementalWithConfiguration(
                        QuillDatabase.openWritable(dbPath), classes, List.of(), List.of(),
                        List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), updated,
                        List.of(resourceUsage));

        assertTrue(timings.rowsInserted() >= 1);
        assertTrue(timings.rowsDeleted() >= 1);
        int updatedStableId = database.withHandle(handle -> handle.createQuery(
                        "SELECT id FROM configuration_definitions WHERE key = 'app.name'")
                .mapTo(Integer.class).one());
        assertEquals(stableId, updatedStableId);
        assertEquals(List.of("app.name", "new.key"), database.withHandle(handle ->
                handle.createQuery("SELECT key FROM configuration_definitions ORDER BY key")
                        .mapTo(String.class).list()));
        int usageCount = database.withHandle(handle -> handle.createQuery(
                        "SELECT count(*) FROM configuration_usages")
                .mapTo(Integer.class).one());
        assertEquals(1, usageCount);
        int resourceUsageCount = database.withHandle(handle -> handle.createQuery(
                        "SELECT count(*) FROM resource_usages")
                .mapTo(Integer.class).one());
        assertEquals(1, resourceUsageCount);
    }

    @Test
    void persistsMultiplePhysicalOccurrencesForOneLogicalClass() {
        Jdbi database = QuillDatabase.create(tempDir.resolve("occurrences.db"));
        List<ClassRecord> classes = List.of(
                new ClassRecord(0, "example.Registry", "CLASS", null, List.of(),
                        "app-one/src/main/java/example/Registry.java", 1, false, 10));
        List<ClassOccurrenceRecord> occurrences = List.of(
                new ClassOccurrenceRecord(1, 1, "example.Registry", "app-one", "main",
                        "app-one/target/classes", "app-one/target/classes/example/Registry.class",
                        "app-one/src/main/java/example/Registry.java", "source"),
                new ClassOccurrenceRecord(2, 1, "example.Registry", "app-two", "main",
                        "app-two/target/classes", "app-two/target/classes/example/Registry.class",
                        "app-two/target/generated-sources/annotations/example/Registry.java",
                        "generated"));

        IndexWriter.writeFresh(database, classes, List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), occurrences);

        var rows = database.withHandle(handle -> handle.createQuery(
                        "SELECT module, origin FROM class_occurrences ORDER BY module")
                .map((row, context) -> row.getString("module") + ":" + row.getString("origin"))
                .list());
        assertEquals(List.of("app-one:source", "app-two:generated"), rows);
    }

    @Test
    void persistsAndIncrementallyUpdatesClassAnnotations() {
        Path dbPath = tempDir.resolve("annotations.db");
        Jdbi database = QuillDatabase.create(dbPath);
        List<ClassRecord> classes = List.of(new ClassRecord(
                0, "example.Service", "CLASS", "java.lang.Object", List.of(),
                "src/main/java/example/Service.java", 1, false, 10));
        List<ClassAnnotationRecord> initial = List.of(
                new ClassAnnotationRecord(1, "example.Specialized", true, null),
                new ClassAnnotationRecord(1, "example.Root", false, "example.Specialized"));
        IndexWriter.writeFresh(database, classes, List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                initial);

        assertEquals(List.of("example.Root"),
                IndexReader.findAnnotationNames(database, "Root"));
        assertEquals(1, IndexReader.countAnnotatedClasses(database, "example.Root", true));
        assertEquals(0, IndexReader.countAnnotatedClasses(database, "example.Root", false));

        List<ClassAnnotationRecord> updated = List.of(
                new ClassAnnotationRecord(1, "example.Specialized", true, null),
                new ClassAnnotationRecord(1, "example.OtherRoot", false,
                        "example.Specialized"));
        IndexWriter.IncrementalWriteTimings timings = IndexWriter.writeIncremental(
                QuillDatabase.openWritable(dbPath), classes, List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), updated);

        assertTrue(timings.rowsInserted() >= 1);
        assertTrue(timings.rowsDeleted() >= 1);
        assertEquals(List.of(), IndexReader.findAnnotationNames(database, "Root"));
        assertEquals(List.of("example.OtherRoot"),
                IndexReader.findAnnotationNames(database, "OtherRoot"));
    }

    @Test
    void persistsAndIncrementallyUpdatesClassMembers() {
        Path dbPath = tempDir.resolve("members.db");
        Jdbi database = QuillDatabase.create(dbPath);
        List<ClassRecord> classes = List.of(new ClassRecord(
                0, "example.Service", "CLASS", "java.lang.Object", List.of(),
                "src/main/java/example/Service.java", 1, false, 10));
        List<ClassMemberRecord> initial = List.of(new ClassMemberRecord(
                1, "METHOD", "run", "run(java.lang.String):void", "", "void",
                List.of("java.lang.String"), "public", List.of("example.Tracked"),
                List.of(new MemberAnnotationRecord("example.Tracked", "METHOD_PARAMETER",
                        0, "input", "java.lang.String"))));
        IndexWriter.writeFresh(database, classes, List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ClassAnnotationRecord(
                        1, "example.Tracked", true, null)), initial);

        assertEquals("run(java.lang.String):void",
                IndexReader.findClassMembers(database, 1).getFirst().signature());
        assertEquals("run", IndexReader.findClassMembers(database, List.of(1))
                .get(1).getFirst().name());
        assertEquals(List.of("example.Tracked"),
                IndexReader.findSymbolAnnotationNames(database, "Tracked"));
        assertEquals(2, IndexReader.countAnnotatedSymbols(
                database, "example.Tracked", "ALL", true));
        var annotatedMethods = IndexReader.findAnnotatedSymbols(
                database, "example.Tracked", "METHOD", true, 10, 0);
        assertEquals(0, annotatedMethods.size());
        var annotatedParameters = IndexReader.findAnnotatedSymbols(
                database, "example.Tracked", "PARAMETER", true, 10, 0);
        assertEquals(1, annotatedParameters.size());
        assertEquals(0, annotatedParameters.getFirst().parameterIndex());
        assertEquals("input", annotatedParameters.getFirst().parameterName());
        assertEquals("java.lang.String", annotatedParameters.getFirst().parameterType());

        List<ClassMemberRecord> updated = List.of(new ClassMemberRecord(
                1, "METHOD", "execute", "execute():boolean", "boolean",
                List.of(), "public final", List.of()));
        IndexWriter.IncrementalWriteTimings timings = IndexWriter.writeIncremental(
                QuillDatabase.openWritable(dbPath), classes, List.of(), List.of(), List.of(),
                Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), updated);

        assertTrue(timings.rowsInserted() >= 1);
        assertTrue(timings.rowsDeleted() >= 1);
        assertEquals("execute", IndexReader.findClassMembers(database, 1).getFirst().name());
    }

    @Test
    void reportsEvidenceForUnusedClassClassification() {
        Jdbi database = QuillDatabase.create(tempDir.resolve("unused-classes.db"));
        List<ClassRecord> classes = List.of(
                new ClassRecord(0, "example.Root", "INTERFACE", null, List.of(),
                        "src/main/java/example/Root.java", 1, false, 10),
                new ClassRecord(0, "example.Child", "CLASS", "java.lang.Object",
                        List.of("example.Root"), "src/main/java/example/Child.java", 1, false, 20),
                new ClassRecord(0, "example.Entry", "CLASS", "java.lang.Object", List.of(),
                        "src/main/java/example/Entry.java", 1, false, 30),
                new ClassRecord(0, "example.Candidate", "CLASS", "java.lang.Object", List.of(),
                        "src/main/java/example/Candidate.java", 1, false, 40));
        List<DependencyRecord> dependencies = List.of(
                new DependencyRecord(2, 1, "TYPE_REFERENCE", null, 2, List.of(7, 9)));
        List<ClassAnnotationRecord> annotations = List.of(
                new ClassAnnotationRecord(3, "example.FrameworkHook", true, null));
        List<ClassMemberRecord> members = List.of(
                new ClassMemberRecord(3, "METHOD", "main",
                        "main(java.lang.String[]):void", "void",
                        List.of("java.lang.String[]"), "public static", List.of()));
        IndexWriter.writeFresh(database, classes, List.of(), List.of(), dependencies, Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                annotations, members);

        Map<String, UnusedClassCandidate> evidence = IndexReader
                .findUnusedClassCandidates(database).stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.classRecord().className(), item -> item));

        assertEquals(1, evidence.get("example.Root").inboundClassCount());
        assertEquals(2, evidence.get("example.Root").inboundOccurrenceCount());
        assertEquals(1, evidence.get("example.Root").hierarchyUserCount());
        assertEquals(1, evidence.get("example.Entry").directAnnotationCount());
        assertTrue(evidence.get("example.Entry").hasMainMethod());
        assertEquals(0, evidence.get("example.Candidate").inboundClassCount());
        assertEquals(0, evidence.get("example.Candidate").hierarchyUserCount());
    }

    @Test
    void searchesClassAndMemberSymbolsWithKindFiltering() {
        Jdbi database = QuillDatabase.create(tempDir.resolve("symbol-search.db"));
        List<ClassRecord> classes = List.of(new ClassRecord(
                0, "example.PaymentService", "CLASS", "java.lang.Object", List.of(),
                "src/main/java/example/PaymentService.java", 1, false, 25));
        List<ClassMemberRecord> members = List.of(
                new ClassMemberRecord(1, "METHOD", "processPayment",
                        "processPayment(example.Order):boolean", "boolean",
                        List.of("example.Order"), "public", List.of("example.Audited")),
                new ClassMemberRecord(1, "FIELD", "paymentGateway", "paymentGateway:Gateway",
                        "example.Gateway", List.of(), "private", List.of()));
        IndexWriter.writeFresh(database, classes, List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), members);

        var all = IndexReader.searchSymbols(database, "Payment", null, 10, 0);
        var methods = IndexReader.searchSymbols(database, "Payment", "METHOD", 10, 0);

        assertEquals(3, all.size());
        assertEquals(3, IndexReader.countSymbols(database, "Payment", null));
        assertEquals(1, methods.size());
        assertEquals("processPayment", methods.getFirst().symbolName());
        assertEquals(List.of("example.Order"), methods.getFirst().parameterTypes());
    }

    @Test
    void persistsQueriesAndIncrementallyRemovesMethodCalls() {
        Path dbPath = tempDir.resolve("method-calls.db");
        Jdbi database = QuillDatabase.create(dbPath);
        List<ClassRecord> classes = List.of(
                new ClassRecord(0, "example.Caller", "CLASS", "java.lang.Object", List.of(),
                        "src/main/java/example/Caller.java", 1, false, 20),
                new ClassRecord(0, "example.Target", "CLASS", "java.lang.Object", List.of(),
                        "src/main/java/example/Target.java", 1, false, 20));
        List<MethodCallRecord> calls = List.of(new MethodCallRecord(
                1, "run", "()V", 2, "execute", "(Ljava/lang/String;)Z",
                "virtual", 2, List.of(12, 18), List.of(4, 9), 2, 1,
                List.of("1>2", "2>4", "2>3", "3>4")));
        IndexWriter.writeFresh(database, classes, List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), calls);

        var inbound = IndexReader.findMethodCalls(database, 2, "execute", "inbound", 10, 0);
        assertEquals(1, inbound.size());
        assertEquals("example.Caller", inbound.getFirst().fromClass());
        assertEquals(List.of(12, 18), inbound.getFirst().evidenceLines());
        assertEquals(List.of(4, 9), inbound.getFirst().instructionOrdinals());
        assertEquals(2, inbound.getFirst().callerBranchCount());
        assertEquals(1, inbound.getFirst().callerExceptionHandlerCount());
        assertEquals(List.of("1>2", "2>4", "2>3", "3>4"),
                inbound.getFirst().callerControlFlowEdges());
        assertEquals(1, IndexReader.countMethodCalls(database, 1, "run", "outbound"));
        assertEquals(1, IndexReader.findMethodCalls(database, 2, "execute",
                "(Ljava/lang/String;)Z", "inbound", 10, 0).size());
        assertEquals(0, IndexReader.countMethodCalls(
                database, 2, "execute", "()Z", "inbound"));
        var inboundUsages = IndexReader.findMethodInboundUsages(database);
        assertEquals(1, inboundUsages.size());
        assertEquals("(Ljava/lang/String;)Z", inboundUsages.getFirst().descriptor());
        assertEquals(1, inboundUsages.getFirst().callerClassCount());
        assertEquals(2, inboundUsages.getFirst().occurrenceCount());
        var exact = IndexReader.findExactMethodUsages(
                database, 2, "execute", "(Ljava/lang/String;)Z", 10, 0);
        assertEquals(1, exact.size());
        assertEquals(1, IndexReader.countExactMethodUsages(
                database, 2, "execute", "(Ljava/lang/String;)Z"));
        assertEquals(0, IndexReader.countExactMethodUsages(
                database, 2, "execute", "()Z"));

        IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), classes,
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals(0, IndexReader.countMethodCalls(database, 2, null, "inbound"));
        assertTrue(IndexReader.findMethodInboundUsages(database).isEmpty());
    }

    @Test
    void persistsAggregatesAndIncrementallyRemovesFieldAccesses() {
        Path dbPath = tempDir.resolve("field-accesses.db");
        Jdbi database = QuillDatabase.create(dbPath);
        List<ClassRecord> classes = List.of(
                new ClassRecord(0, "example.Caller", "CLASS", "java.lang.Object", List.of(),
                        "src/main/java/example/Caller.java", 1, false, 20),
                new ClassRecord(0, "example.Target", "CLASS", "java.lang.Object", List.of(),
                        "src/main/java/example/Target.java", 1, false, 20));
        List<FieldAccessRecord> accesses = List.of(
                new FieldAccessRecord(1, "read", "()V", 2, "value", "I",
                        "read_instance", 2, List.of(11, 14)),
                new FieldAccessRecord(1, "write", "()V", 2, "value", "I",
                        "write_instance", 1, List.of(19)));
        IndexWriter.writeFresh(database, classes, List.of(), List.of(), List.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), accesses);

        var usages = IndexReader.findFieldUsages(database);
        assertEquals(1, usages.size());
        assertEquals("value", usages.getFirst().fieldName());
        assertEquals("I", usages.getFirst().descriptor());
        assertEquals(1, usages.getFirst().readerClassCount());
        assertEquals(2, usages.getFirst().readOccurrences());
        assertEquals(1, usages.getFirst().writerClassCount());
        assertEquals(1, usages.getFirst().writeOccurrences());
        var reads = IndexReader.findExactFieldUsages(
                database, 2, "value", "I", "read", 10, 0);
        assertEquals(1, reads.size());
        assertEquals("read_instance", reads.getFirst().accessKind());
        assertEquals(List.of(11, 14), reads.getFirst().evidenceLines());
        assertEquals(1, IndexReader.countExactFieldUsages(
                database, 2, "value", "I", "write"));

        IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), classes,
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
        assertTrue(IndexReader.findFieldUsages(database).isEmpty());
    }

    @Test
    void dependencyMetricsSeparateUniqueClassesFromEdgeOccurrences() {
        Jdbi jdbi = QuillDatabase.create(tempDir.resolve("dependency-metrics.db"));
        List<ClassRecord> classes = List.of(
                new ClassRecord(0, "example.Consumer", "CLASS", null, List.of(),
                        "src/main/java/example/Consumer.java", 1, false, 10),
                new ClassRecord(0, "example.Target", "CLASS", null, List.of(),
                        "src/main/java/example/Target.java", 1, false, 10));
        List<DependencyRecord> dependencies = List.of(
                new DependencyRecord(1, 2, "CALLS", null, 2, List.of(7, 11)),
                new DependencyRecord(1, 2, "TYPE_USE", null, 1));

        IndexWriter.write(jdbi, classes, List.of(), List.of(), dependencies, Map.of());

        assertEquals(1, IndexReader.countDependents(jdbi, 2));
        assertEquals(3, IndexReader.countDependencyEdges(jdbi, 2, true));
        assertEquals(1, IndexReader.dependencyBreakdown(jdbi, 2, true).get(0).classes());
        assertEquals(3, IndexReader.dependencyBreakdown(jdbi, 2, true).get(0).edges());
        assertEquals(List.of(7, 11), IndexReader.findDependencies(jdbi, 2, "inbound").stream()
                .filter(dependency -> dependency.kind().equals("CALLS"))
                .findFirst().orElseThrow().evidenceLines());
    }

    @Test
    void architectureHubsSeparateOriginAndSourceSetDimensions() {
        Jdbi database = QuillDatabase.create(tempDir.resolve("hub-breakdown.db"));
        List<ClassRecord> classes = List.of(
                contextualClass("example.Target", "source", "main"),
                contextualClass("example.HandwrittenConsumer", "source", "main"),
                contextualClass("example.GeneratedConsumer", "generated", "main"),
                contextualClass("example.TestConsumer", "source", "test"));
        List<DependencyRecord> dependencies = List.of(
                new DependencyRecord(2, 1, "TYPE_USE", null),
                new DependencyRecord(3, 1, "TYPE_USE", null),
                new DependencyRecord(4, 1, "TYPE_USE", null));

        IndexWriter.write(database, classes, List.of(), List.of(), dependencies, Map.of());

        IndexReader.ArchitectureHub hub = IndexReader.findArchitectureHubs(database).getFirst();
        assertEquals(1, hub.classId());
        assertEquals(3, hub.totalDependents());
        assertEquals(2, hub.sourceDependents());
        assertEquals(1, hub.generatedDependents());
        assertEquals(2, hub.productionDependents());
        assertEquals(1, hub.testDependents());
    }

    private static ClassRecord contextualClass(String name, String origin, String sourceSet) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                "src/" + sourceSet + "/java/" + name.replace('.', '/') + ".java",
                1, false, 10, null, origin, "current", ".", sourceSet);
    }

    @Test
    void incrementalWriteMutatesOnlyThePersistentDelta() {
        Path dbPath = tempDir.resolve("incremental.db");
        Jdbi writable = QuillDatabase.create(dbPath);
        List<FileRecord> files = List.of(
                new FileRecord(1, "src/A.java", "src/A.java", "java", "source",
                        "current", null),
                new FileRecord(2, "src/B.java", "src/B.java", "java", "source",
                        "current", null));
        List<ClassRecord> initialClasses = List.of(
                new ClassRecord(0, "example.A", "CLASS", null, List.of(), "src/A.java", 1,
                        true, 10, 1, "source", "current"),
                new ClassRecord(0, "example.B", "CLASS", null, List.of(), "src/B.java", 1,
                        false, 20, 2, "source", "current"));
        List<BeanRecord> beans = List.of(new BeanRecord(1, 1, "CLASS", "@Dependent",
                List.of(), List.of(), false, null, List.of(), null, null, List.of("example.A")));
        List<DependencyRecord> dependencies = List.of(
                new DependencyRecord(1, 2, "TYPE_USE", null));
        Map<String, String> metadata = Map.of("database_write_mode", "incremental");
        IndexWriter.writeFresh(writable, initialClasses, beans, List.of(), dependencies, metadata,
                List.of(), List.of(), List.of(), List.of(), List.of(), files);

        IndexWriter.IncrementalWriteTimings unchanged = IndexWriter.writeIncremental(
                QuillDatabase.openWritable(dbPath), initialClasses, beans, List.of(), dependencies,
                metadata, List.of(), List.of(), List.of(), List.of(), List.of(), files);

        assertEquals(0, unchanged.rowsInserted());
        assertEquals(0, unchanged.rowsDeleted());
        assertEquals(7, unchanged.rowsUnchanged());

        List<ClassRecord> changedClasses = List.of(
                new ClassRecord(0, "example.A", "CLASS", null, List.of(), "src/A.java", 1,
                        true, 11, 1, "source", "current"),
                initialClasses.get(1));
        IndexWriter.IncrementalWriteTimings changed = IndexWriter.writeIncremental(
                QuillDatabase.openWritable(dbPath), changedClasses, beans, List.of(), List.of(),
                metadata, List.of(), List.of(), List.of(), List.of(), List.of(), files);

        assertEquals(1, changed.rowsInserted(), "Only the changed class row should be inserted");
        assertEquals(2, changed.rowsDeleted(),
                "The old class row and removed dependency should be deleted");
        assertEquals(11, IndexReader.findClassByName(QuillDatabase.open(dbPath), "example.A")
                .orElseThrow().sourceTokens());
        int dependencyCount = QuillDatabase.open(dbPath).withHandle(h ->
                h.createQuery("SELECT count(*) FROM dependencies").mapTo(Integer.class).one());
        assertEquals(0, dependencyCount);
        int classNameIndexCount = QuillDatabase.open(dbPath).withHandle(h -> h.createQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='index' AND name='idx_classes_name'")
                .mapTo(Integer.class).one());
        assertTrue(classNameIndexCount > 0, "Secondary indexes must be retained");
        boolean foreignKeysValid = QuillDatabase.openWritable(dbPath).withHandle(h ->
                h.createQuery("PRAGMA foreign_key_check").mapToMap().list().isEmpty());
        assertTrue(foreignKeysValid);
    }
    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = QuillDatabase.create(tempDir.resolve("test.db"));
    }

    @Test
    void writeAndReadClasses() {
        var classes = List.of(
            new ClassRecord(0, "org.acme.OrderService", "CLASS", "java.lang.Object",
                    List.of(), "src/main/java/org/acme/OrderService.java", 10, true, 500),
            new ClassRecord(0, "org.acme.OrderDTO", "CLASS", "java.lang.Object",
                    List.of("Serializable"), "src/main/java/org/acme/OrderDTO.java", 5, false, 200)
        );
        IndexWriter.write(jdbi, classes, List.of(), List.of(), List.of(), Map.of("indexed_at", "2026-08-26"));

        var result = IndexReader.findAllClasses(jdbi);
        assertEquals(2, result.size());
        assertEquals("org.acme.OrderService", result.get(0).className());
        assertTrue(result.get(0).isBean());
        assertFalse(result.get(1).isBean());
    }

    @Test
    void writeAndReadBeans() {
        var classes = List.of(
            new ClassRecord(0, "org.acme.OrderService", "CLASS", "java.lang.Object",
                    List.of(), "src/main/java/org/acme/OrderService.java", 10, true, 500)
        );
        var beans = List.of(
            new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                    List.of(), false, null, null, null, null, List.of("OrderService", "Object"))
        );
        IndexWriter.write(jdbi, classes, beans, List.of(), List.of(), Map.of());

        var result = IndexReader.findBeans(jdbi, null);
        assertEquals(1, result.size());
        assertEquals("@ApplicationScoped", result.getFirst().scope());
    }

    @Test
    void currentQueriesExcludeHistoricalAndOrphanClassOutputs() {
        var classes = List.of(
                new ClassRecord(0, "org.acme.Current", "CLASS", null, List.of(),
                        "Current.java", 1, true, 10, null, "source", "current"),
                new ClassRecord(0, "org.acme.Deleted", "CLASS", null, List.of(),
                        "Deleted.java", 1, true, 10, null, "source", "historical"),
                new ClassRecord(0, "org.acme.StaleOutput", "CLASS", null, List.of(),
                        null, 1, true, 10, null, "orphan_output", "current"));
        var beans = List.of(
                bean(1), bean(2), bean(3));

        IndexWriter.write(jdbi, classes, beans, List.of(), List.of(), Map.of());

        assertEquals(List.of("org.acme.Current"), IndexReader.findAllClasses(jdbi).stream()
                .map(ClassRecord::className).toList());
        assertEquals(1, IndexReader.countClasses(jdbi));
        assertEquals(10, IndexReader.sumSourceTokens(jdbi));
        assertEquals(1, IndexReader.countBeans(jdbi));
        assertEquals(1, IndexReader.findBeans(jdbi, null).size());
        assertTrue(IndexReader.searchClasses(jdbi, "Stale", 10).isEmpty());
    }

    private static BeanRecord bean(int classId) {
        return new BeanRecord(0, classId, "CLASS", "@Dependent", List.of(), List.of(),
                false, null, List.of(), null, null, List.of());
    }

    @Test
    void batchReadersReturnRequestedRecords() {
        var classes = List.of(
                new ClassRecord(0, "org.acme.First", "CLASS", null,
                        List.of(), "First.java", 1, true, 10),
                new ClassRecord(0, "org.acme.Second", "CLASS", null,
                        List.of(), "Second.java", 1, true, 20));
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@Singleton", List.of(), List.of(),
                        false, null, List.of(), null, null, List.of()),
                new BeanRecord(0, 2, "CLASS", "@Dependent", List.of(), List.of(),
                        false, null, List.of(), null, null, List.of()));
        IndexWriter.write(jdbi, classes, beans, List.of(), List.of(), Map.of());

        assertEquals("org.acme.Second", IndexReader.findClassesByIds(jdbi, List.of(2)).get(2).className());
        assertEquals("@Singleton", IndexReader.findBeansByIds(jdbi, List.of(1)).get(1).scope());
        assertEquals("@Dependent", IndexReader.findBeansByClassIds(jdbi, List.of(2)).get(2).scope());
        assertTrue(IndexReader.findClassesByIds(jdbi, List.of()).isEmpty());
    }

    @Test
    void findHotspotsWithSinceRecomputesCounts() {
        var classes = List.of(
            new ClassRecord(0, "org.acme.Foo", "CLASS", null,
                    List.of(), "src/Foo.java", 1, false, 100)
        );
        IndexWriter.write(jdbi, classes, List.of(), List.of(), List.of(), Map.of());

        var commits = List.of(
            new org.treblereel.mcp.model.GitCommitRecord(1, "aaa", "aaa", "dev1", "d@t.c", "2026-08-01T10:00:00Z", "old"),
            new org.treblereel.mcp.model.GitCommitRecord(2, "bbb", "bbb", "dev2", "d@t.c", "2026-08-20T10:00:00Z", "new")
        );
        var commitFiles = List.of(
            new org.treblereel.mcp.model.GitCommitFile(1, 1, "src/Foo.java", "MODIFY"),
            new org.treblereel.mcp.model.GitCommitFile(2, 1, "src/Foo.java", "MODIFY")
        );
        var stats = List.of(
            new org.treblereel.mcp.model.GitFileStats(1, "src/Foo.java", 1, 2,
                    "2026-08-20T10:00:00Z", "dev2", "2026-08-01T10:00:00Z", 2)
        );
        IndexWriter.writeGitData(jdbi, stats, commits, commitFiles);

        var allTime = IndexReader.findHotspots(jdbi, 10, null);
        assertEquals(1, allTime.size());
        assertEquals(2, allTime.getFirst().commitCount(), "All-time should return 2 commits");

        var filtered = IndexReader.findHotspots(jdbi, 10, "2026-08-15");
        assertEquals(1, filtered.size());
        assertEquals(1, filtered.getFirst().commitCount(),
                "Since 2026-08-15 should return 1 commit, recomputed from join");
    }

    @Test
    void findHotspotsWithSinceExcludesOldFiles() {
        var classes = List.of(
            new ClassRecord(0, "org.acme.Old", "CLASS", null, List.of(), "Old.java", 1, false, 50),
            new ClassRecord(0, "org.acme.New", "CLASS", null, List.of(), "New.java", 1, false, 50)
        );
        IndexWriter.write(jdbi, classes, List.of(), List.of(), List.of(), Map.of());

        var commits = List.of(
            new org.treblereel.mcp.model.GitCommitRecord(1, "aaa", "aaa", "dev", "d@t", "2026-01-01T00:00:00Z", "old"),
            new org.treblereel.mcp.model.GitCommitRecord(2, "bbb", "bbb", "dev", "d@t", "2026-08-01T00:00:00Z", "new")
        );
        var commitFiles = List.of(
            new org.treblereel.mcp.model.GitCommitFile(1, 1, "Old.java", "ADD"),
            new org.treblereel.mcp.model.GitCommitFile(2, 2, "New.java", "ADD")
        );
        var stats = List.of(
            new org.treblereel.mcp.model.GitFileStats(1, "Old.java", 1, 1, "2026-01-01T00:00:00Z", "dev", "2026-01-01T00:00:00Z", 1),
            new org.treblereel.mcp.model.GitFileStats(2, "New.java", 2, 1, "2026-08-01T00:00:00Z", "dev", "2026-08-01T00:00:00Z", 1)
        );
        IndexWriter.writeGitData(jdbi, stats, commits, commitFiles);

        var filtered = IndexReader.findHotspots(jdbi, 10, "2026-06-01");
        assertEquals(1, filtered.size());
        assertEquals("New.java", filtered.getFirst().filePath());
    }

    @Test
    void readMetadata() {
        IndexWriter.write(jdbi, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26", "last_commit", "abc123"));

        var meta = IndexReader.getMetadata(jdbi);
        assertEquals("2026-08-26", meta.get("indexed_at"));
        assertEquals("abc123", meta.get("last_commit"));
    }

    @Test
    void storesAndReadsApplicationModuleClasspath() {
        IndexWriter.writeModuleClasspath(jdbi, List.of(
                new ModuleClasspathRecord("application", "application", 0, "self"),
                new ModuleClasspathRecord("application", "service", 1, "project_dependency"),
                new ModuleClasspathRecord("application", "common", 2, "project_dependency")));

        assertEquals(Set.of("application", "service", "common"),
                IndexReader.findVisibleModules(jdbi, "application"));
        assertEquals(List.of(0, 1, 2), IndexReader.findModuleClasspath(jdbi, "application")
                .stream().map(ModuleClasspathRecord::distance).toList());
        assertEquals(3, IndexReader.findAllModuleClasspath(jdbi).size());
        assertTrue(IndexReader.findVisibleModules(jdbi, "missing").isEmpty());
    }

    @Test
    void profileFilterExactMatch() {
        assertTrue(IndexReader.matchesProfile(List.of("dev"), "dev"));
        assertTrue(IndexReader.matchesProfile(List.of("dev", "local"), "dev"));
    }

    @Test
    void profileFilterRejectsNegation() {
        assertFalse(IndexReader.matchesProfile(List.of("!dev"), "dev"));
    }

    @Test
    void profileFilterRejectsSubstring() {
        assertFalse(IndexReader.matchesProfile(List.of("dev-staging"), "dev"));
    }

    @Test
    void profileFilterNullOrEmptyProfiles() {
        assertFalse(IndexReader.matchesProfile(null, "dev"));
        assertFalse(IndexReader.matchesProfile(List.of(), "dev"));
    }

    @Test
    void findBeansFiltersByProfileExactly() {
        var classes = List.of(
            new ClassRecord(0, "org.acme.DevService", "CLASS", "java.lang.Object",
                    List.of(), "DevService.java", 1, true, 100),
            new ClassRecord(0, "org.acme.NotDevService", "CLASS", "java.lang.Object",
                    List.of(), "NotDevService.java", 1, true, 100),
            new ClassRecord(0, "org.acme.DevStagingService", "CLASS", "java.lang.Object",
                    List.of(), "DevStagingService.java", 1, true, 100)
        );
        var beans = List.of(
            new BeanRecord(0, 1, "CLASS", "@Singleton", List.of("@Default"),
                    List.of(), false, null, List.of("dev"), null, null, List.of()),
            new BeanRecord(0, 2, "CLASS", "@Singleton", List.of("@Default"),
                    List.of(), false, null, List.of("!dev"), null, null, List.of()),
            new BeanRecord(0, 3, "CLASS", "@Singleton", List.of("@Default"),
                    List.of(), false, null, List.of("dev-staging"), null, null, List.of())
        );
        IndexWriter.write(jdbi, classes, beans, List.of(), List.of(), Map.of());

        var result = IndexReader.findBeans(jdbi, Map.of("profile", "dev"));
        assertEquals(1, result.size(), "Only exact 'dev' profile should match, not '!dev' or 'dev-staging'");
        assertEquals(1, result.getFirst().classId());
    }
}
