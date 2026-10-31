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

    @Test void kotlinSourceTypesRoundTripAndInvalidDataRemainsUnknown() {
        var database = QuillDatabase.create(tempDir.resolve("types.db"));
        IndexWriter.writeIncremental(database, new IndexFixture()
                .classes(List.of(new ClassRecord(0, "acme.Owner", "CLASS", null,
                List.of(), "Owner.kt", 1, false, 0))).snapshot());
        var type = new KotlinDeclarationTypes.Type("class", "kotlin/String", true, false, false,
                List.of(), null, null, null, null);
        var types = new KotlinDeclarationTypes(type, null, List.of(new KotlinDeclarationTypes.Parameter("p", type, null)),
                List.of(new KotlinDeclarationTypes.TypeParameter(0, "T", "INVARIANT", false, List.of(type))), List.of());
        var declaration = new KotlinDeclarationRecord(1, "FUNCTION", "f", "f", "()V", "kotlin_metadata",
                false, false, false, false, false, false, false, List.of(), types);
        IndexFixture.writeKotlinDeclarations(database, List.of(declaration));
        assertEquals(declaration, database.withHandle(repositoryHandle1 -> new SymbolRepository(repositoryHandle1).findKotlinDeclarations(1)).getFirst());
        for (String invalid : List.of("", "invalid", "[]", "{}", "{\"result\":{}}")) assertNull(SymbolRepository.kotlinTypesFromJson(invalid));
        assertNull(SymbolRepository.kotlinTypesFromJson(null));
        database.useHandle(h -> h.execute("UPDATE kotlin_declarations SET declaration_types='{}'"));
        assertNull(database.withHandle(repositoryHandle2 -> new SymbolRepository(repositoryHandle2).findKotlinDeclarations(1)).getFirst().declarationTypes());
    }

    @Test
    void storesAndReadsKotlinSemanticDeclarations() {
        Jdbi database = QuillDatabase.create(tempDir.resolve("kotlin.db"));
        IndexWriter.writeIncremental(database, new IndexFixture()
                .classes(List.of(new ClassRecord(
                        0, "org.acme.Order", "CLASS", "java.lang.Object", List.of(),
                        "src/main/kotlin/org/acme/Order.kt", 1, false, 20))).snapshot());
        database.useHandle(handle -> handle.execute("""
                INSERT INTO class_members
                  (class_id, kind, name, signature, descriptor, type_name,
                   parameter_types, modifiers, annotations)
                VALUES (1, 'METHOD', 'loadJvm', 'loadJvm(int):java.lang.Object',
                        '(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;',
                        'java.lang.Object', '["int"]', 'public', '[]')"""));
        IndexFixture.writeKotlinDeclarations(database, List.of(
                new KotlinDeclarationRecord(1, "DATA_CLASS", "org.acme.Order",
                        "org.acme.Order", "", "kotlin_metadata", false, false,
                        false, false, false, false, false),
                new KotlinDeclarationRecord(1, "FUNCTION", "load", "loadJvm",
                        "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                        "kotlin_metadata", true, true, true,
                        false, false, false, false),
                new KotlinDeclarationRecord(1, "PROPERTY", "state", "getState", "",
                        "kotlin_metadata", false, false, false,
                        true, true, false, false)));

        var declarations = database.withHandle(repositoryHandle3 -> new SymbolRepository(repositoryHandle3).findKotlinDeclarations(1));

        assertEquals(3, declarations.size());
        assertEquals("DATA_CLASS", declarations.get(0).kind());
        assertTrue(declarations.get(1).isSuspend());
        assertTrue(declarations.get(1).extension());
        assertTrue(declarations.get(1).hasDefaultParameters());
        assertTrue(declarations.get(2).mutable());
        assertTrue(declarations.get(2).lateinit());
        var matches = database.withHandle(repositoryHandle4 -> new SymbolRepository(repositoryHandle4).searchSymbols("load", "METHOD", 10, 0));
        assertEquals(1, matches.size());
        assertEquals(1, database.withHandle(repositoryHandle5 -> new SymbolRepository(repositoryHandle5).countSymbols("load", "METHOD")).intValue());
        assertEquals("loadJvm", matches.getFirst().symbolName());
        assertEquals("load", matches.getFirst().semanticName());
        assertTrue(matches.getFirst().isSuspend());
        assertTrue(matches.getFirst().hasDefaultParameters());
    }

    @Test
    void roundTripsKotlinParametersAndDistinguishesUnknownFromZeroArguments() {
        Jdbi database = QuillDatabase.create(tempDir.resolve("parameters.db"));
        IndexWriter.writeIncremental(database, new IndexFixture()
                .classes(List.of(new ClassRecord(0, "acme.Owner", "CLASS", null,
                List.of(), "Owner.kt", 1, false, 0))).snapshot());
        var parameters = List.of(new KotlinValueParameter("required", false, false),
                new KotlinValueParameter("optional", true, false), new KotlinValueParameter("values", false, true));
        var known = new KotlinDeclarationRecord(1, "FUNCTION", "f", "f", "(II[I)V", "kotlin_metadata",
                false, false, true, false, false, false, false, parameters);
        var empty = new KotlinDeclarationRecord(1, "CONSTRUCTOR", "<init>", "<init>", "()V", "kotlin_metadata",
                false, false, false, false, false, false, false, List.of());
        var unknown = new KotlinDeclarationRecord(1, "FUNCTION", "g", "g", "()V", "kotlin_metadata",
                false, false, false, false, false, false, false);
        IndexFixture.writeKotlinDeclarations(database, List.of(known, empty, unknown));
        var values = database.withHandle(repositoryHandle6 -> new SymbolRepository(repositoryHandle6).findKotlinDeclarations(1));
        assertEquals(known, values.stream().filter(v -> v.name().equals("f")).findFirst().orElseThrow());
        assertEquals(empty, values.stream().filter(v -> v.kind().equals("CONSTRUCTOR")).findFirst().orElseThrow());
        assertEquals(unknown, values.stream().filter(v -> v.name().equals("g")).findFirst().orElseThrow());
    }

    @Test
    void invalidParameterMetadataDegradesToUnknown() {
        for (String invalid : List.of("broken", "{}", "null", "[null]", "[{}]",
                "[{\"name\":\"x\",\"hasDefault\":false,\"vararg\":false},{\"name\":\"x\",\"hasDefault\":true,\"vararg\":false}]")) {
            assertNull(SymbolRepository.kotlinParametersFromJson(invalid), invalid);
        }
        assertNull(SymbolRepository.kotlinParametersFromJson(null));
        assertEquals(List.of(), SymbolRepository.kotlinParametersFromJson("[]"));
    }

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

        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .configuration(initial)
                .resourceUsages(List.of(resourceUsage)).snapshot());
        int stableId = database.withHandle(handle -> handle.createQuery(
                        "SELECT id FROM configuration_definitions WHERE key = 'app.name'")
                .mapTo(Integer.class).one());

        ConfigurationScanner.Result updated = new ConfigurationScanner.Result(List.of(
                initial.definitions().getFirst(),
                new ConfigurationScanner.Definition("new.key", "property",
                        "src/main/resources/application.properties", 2, ".", "main")),
                List.of(usage));
        IndexWriter.IncrementalWriteTimings timings =
                IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), new IndexFixture()
                .classes(classes)
                .configuration(updated)
                .resourceUsages(List.of(resourceUsage)).snapshot());

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
    void persistsReferencesWhoseCompiledClassHasNoSourceContext() {
        Jdbi database = QuillDatabase.create(tempDir.resolve("orphan-references.db"));
        List<ClassRecord> classes = List.of(new ClassRecord(
                0, "example.GeneratedConsumer", "CLASS", "java.lang.Object", List.of(),
                null, 0, false, 0, null, "orphan_output", "current", null, null));
        ConfigurationScanner.Usage usage = new ConfigurationScanner.Usage(
                "app.name", "config_key", 1, "example.GeneratedConsumer", "read", null,
                "java.lang.System#getProperty", null, null, null);
        ResourceUsageRecord resource = new ResourceUsageRecord(
                "templates/order.html", "resource", 1, "example.GeneratedConsumer", "read",
                "java.lang.Class#getResource", null, null, null);

        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .configuration(new ConfigurationScanner.Result(List.of(), List.of(usage)))
                .resourceUsages(List.of(resource)).snapshot());

        assertNull(database.withHandle(handle -> handle.createQuery(
                        "SELECT module FROM configuration_usages")
                .mapTo(String.class).findOne().orElse(null)));
        assertNull(database.withHandle(handle -> handle.createQuery(
                        "SELECT source_set FROM resource_usages")
                .mapTo(String.class).findOne().orElse(null)));
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

        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .classOccurrences(occurrences).snapshot());

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
        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .classAnnotations(initial).snapshot());

        assertEquals(List.of("example.Root"),
                database.withHandle(repositoryHandle7 -> new SymbolRepository(repositoryHandle7).findAnnotationNames("Root")));
        assertEquals(1, database.withHandle(repositoryHandle8 -> new SymbolRepository(repositoryHandle8).countAnnotatedClasses("example.Root", true)).intValue());
        assertEquals(0, database.withHandle(repositoryHandle9 -> new SymbolRepository(repositoryHandle9).countAnnotatedClasses("example.Root", false)).intValue());

        List<ClassAnnotationRecord> updated = List.of(
                new ClassAnnotationRecord(1, "example.Specialized", true, null),
                new ClassAnnotationRecord(1, "example.OtherRoot", false,
                        "example.Specialized"));
        IndexWriter.IncrementalWriteTimings timings = IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), new IndexFixture()
                .classes(classes)
                .classAnnotations(updated).snapshot());

        assertTrue(timings.rowsInserted() >= 1);
        assertTrue(timings.rowsDeleted() >= 1);
        assertEquals(List.of(), database.withHandle(repositoryHandle10 -> new SymbolRepository(repositoryHandle10).findAnnotationNames("Root")));
        assertEquals(List.of("example.OtherRoot"),
                database.withHandle(repositoryHandle11 -> new SymbolRepository(repositoryHandle11).findAnnotationNames("OtherRoot")));
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
        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .classAnnotations(List.of(new ClassAnnotationRecord(
                        1, "example.Tracked", true, null)))
                .classMembers(initial).snapshot());

        assertEquals("run(java.lang.String):void",
                database.withHandle(repositoryHandle12 -> new SymbolRepository(repositoryHandle12).findClassMembers(1)).getFirst().signature());
        assertEquals("run", database.withHandle(repositoryHandle13 -> new SymbolRepository(repositoryHandle13).findClassMembers(List.of(1)))
                .get(1).getFirst().name());
        assertEquals(List.of("example.Tracked"),
                database.withHandle(repositoryHandle14 -> new SymbolRepository(repositoryHandle14).findSymbolAnnotationNames("Tracked")));
        assertEquals(2, database.withHandle(repositoryHandle15 -> new SymbolRepository(repositoryHandle15).countAnnotatedSymbols("example.Tracked", "ALL", true)).intValue());
        var annotatedMethods = database.withHandle(repositoryHandle16 -> new SymbolRepository(repositoryHandle16).findAnnotatedSymbols("example.Tracked", "METHOD", true, 10, 0));
        assertEquals(0, annotatedMethods.size());
        var annotatedParameters = database.withHandle(repositoryHandle17 -> new SymbolRepository(repositoryHandle17).findAnnotatedSymbols("example.Tracked", "PARAMETER", true, 10, 0));
        assertEquals(1, annotatedParameters.size());
        assertEquals(0, annotatedParameters.getFirst().parameterIndex());
        assertEquals("input", annotatedParameters.getFirst().parameterName());
        assertEquals("java.lang.String", annotatedParameters.getFirst().parameterType());

        List<ClassMemberRecord> updated = List.of(new ClassMemberRecord(
                1, "METHOD", "execute", "execute():boolean", "boolean",
                List.of(), "public final", List.of()));
        IndexWriter.IncrementalWriteTimings timings = IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), new IndexFixture()
                .classes(classes)
                .classMembers(updated).snapshot());

        assertTrue(timings.rowsInserted() >= 1);
        assertTrue(timings.rowsDeleted() >= 1);
        assertEquals("execute", database.withHandle(repositoryHandle18 -> new SymbolRepository(repositoryHandle18).findClassMembers(1)).getFirst().name());
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
        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .dependencies(dependencies)
                .classAnnotations(annotations)
                .classMembers(members).snapshot());

        Map<String, UnusedClassCandidate> evidence = database.withHandle(repositoryHandle19 -> new UsageRepository(repositoryHandle19).findUnusedClassCandidates()).stream()
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
        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .classMembers(members).snapshot());

        var all = database.withHandle(repositoryHandle20 -> new SymbolRepository(repositoryHandle20).searchSymbols("Payment", null, 10, 0));
        var methods = database.withHandle(repositoryHandle21 -> new SymbolRepository(repositoryHandle21).searchSymbols("Payment", "METHOD", 10, 0));

        assertEquals(3, all.size());
        assertEquals(3, database.withHandle(repositoryHandle22 -> new SymbolRepository(repositoryHandle22).countSymbols("Payment", null)).intValue());
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
                List.of("1>2", "2>4", "2>3", "3>4"),
                List.of("java.util.concurrent.Executor.execute"),
                List.of("6|14|java.util.concurrent.Executor|execute|(Ljava/lang/Runnable;)V|scheduling")));
        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .methodCalls(calls).snapshot());

        var inbound = database.withHandle(repositoryHandle23 -> new UsageRepository(repositoryHandle23).findMethodCalls(2, "execute", "inbound", 10, 0));
        assertEquals(1, inbound.size());
        assertEquals("example.Caller", inbound.getFirst().fromClass());
        assertEquals(List.of(12, 18), inbound.getFirst().evidenceLines());
        assertEquals(List.of(4, 9), inbound.getFirst().instructionOrdinals());
        assertEquals(2, inbound.getFirst().callerBranchCount());
        assertEquals(1, inbound.getFirst().callerExceptionHandlerCount());
        assertEquals(List.of("1>2", "2>4", "2>3", "3>4"),
                inbound.getFirst().callerControlFlowEdges());
        assertEquals(List.of("java.util.concurrent.Executor.execute"),
                inbound.getFirst().callerAsyncBoundaries());
        assertEquals(1, inbound.getFirst().callerExternalCalls().size());
        assertEquals(1, database.withHandle(repositoryHandle24 -> new UsageRepository(repositoryHandle24).countMethodCalls(1, "run", "outbound")).intValue());
        assertEquals(1, database.withHandle(repositoryHandle25 -> new UsageRepository(repositoryHandle25).findMethodCalls(2, "execute", "(Ljava/lang/String;)Z", "inbound", 10, 0, ReadQueryContext.readWithGraph(database, context -> context.methods().symbolicOwners(2, "execute", "(Ljava/lang/String;)Z", "inbound")))).size());
        assertEquals(0, database.withHandle(repositoryHandle26 -> new UsageRepository(repositoryHandle26).countMethodCalls(2, "execute", "()Z", "inbound", ReadQueryContext.readWithGraph(database, context -> context.methods().symbolicOwners(2, "execute", "()Z", "inbound")))).intValue());
        var inboundUsages = database.withHandle(repositoryHandle27 -> new UsageRepository(repositoryHandle27).findMethodInboundUsages());
        assertEquals(1, inboundUsages.size());
        assertEquals("(Ljava/lang/String;)Z", inboundUsages.getFirst().descriptor());
        assertEquals(1, inboundUsages.getFirst().callerClassCount());
        assertEquals(2, inboundUsages.getFirst().occurrenceCount());
        var exact = database.withHandle(repositoryHandle28 -> new UsageRepository(repositoryHandle28).findExactMethodUsages(ReadQueryContext.readWithGraph(database, context -> context.methods().symbolicOwners(2, "execute", "(Ljava/lang/String;)Z", "inbound")), "execute", "(Ljava/lang/String;)Z", 10, 0));
        assertEquals(1, exact.size());
        assertEquals(1, database.withHandle(repositoryHandle29 -> new UsageRepository(repositoryHandle29).countExactMethodUsages(ReadQueryContext.readWithGraph(database, context -> context.methods().symbolicOwners(2, "execute", "(Ljava/lang/String;)Z", "inbound")), "execute", "(Ljava/lang/String;)Z")).intValue());
        assertEquals(0, database.withHandle(repositoryHandle30 -> new UsageRepository(repositoryHandle30).countExactMethodUsages(ReadQueryContext.readWithGraph(database, context -> context.methods().symbolicOwners(2, "execute", "()Z", "inbound")), "execute", "()Z")).intValue());

        IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), new IndexFixture()
                .classes(classes).snapshot());
        assertEquals(0, database.withHandle(repositoryHandle31 -> new UsageRepository(repositoryHandle31).countMethodCalls(2, null, "inbound")).intValue());
        assertTrue(database.withHandle(repositoryHandle32 -> new UsageRepository(repositoryHandle32).findMethodInboundUsages()).isEmpty());
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
        IndexWriter.writeFresh(database, new IndexFixture()
                .classes(classes)
                .fieldAccesses(accesses).snapshot());

        var usages = database.withHandle(repositoryHandle33 -> new UsageRepository(repositoryHandle33).findFieldUsages());
        assertEquals(1, usages.size());
        assertEquals("value", usages.getFirst().fieldName());
        assertEquals("I", usages.getFirst().descriptor());
        assertEquals(1, usages.getFirst().readerClassCount());
        assertEquals(2, usages.getFirst().readOccurrences());
        assertEquals(1, usages.getFirst().writerClassCount());
        assertEquals(1, usages.getFirst().writeOccurrences());
        var reads = database.withHandle(repositoryHandle34 -> new UsageRepository(repositoryHandle34).findExactFieldUsages(2, "value", "I", "read", 10, 0));
        assertEquals(1, reads.size());
        assertEquals("read_instance", reads.getFirst().accessKind());
        assertEquals(List.of(11, 14), reads.getFirst().evidenceLines());
        assertEquals(1, database.withHandle(repositoryHandle35 -> new UsageRepository(repositoryHandle35).countExactFieldUsages(2, "value", "I", "write")).intValue());

        IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), new IndexFixture()
                .classes(classes).snapshot());
        assertTrue(database.withHandle(repositoryHandle36 -> new UsageRepository(repositoryHandle36).findFieldUsages()).isEmpty());
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

        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes)
                .dependencies(dependencies).snapshot());

        assertEquals(1, jdbi.withHandle(repositoryHandle37 -> new UsageRepository(repositoryHandle37).countDependents(2)).intValue());
        assertEquals(3, jdbi.withHandle(repositoryHandle38 -> new UsageRepository(repositoryHandle38).countDependencyEdges(2, true)).intValue());
        assertEquals(1, jdbi.withHandle(repositoryHandle39 -> new UsageRepository(repositoryHandle39).dependencyBreakdown(2, true)).get(0).classes());
        assertEquals(3, jdbi.withHandle(repositoryHandle40 -> new UsageRepository(repositoryHandle40).dependencyBreakdown(2, true)).get(0).edges());
        assertEquals(List.of(7, 11), jdbi.withHandle(repositoryHandle41 -> new UsageRepository(repositoryHandle41).findDependencies(2, "inbound")).stream()
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

        IndexWriter.writeIncremental(database, new IndexFixture()
                .classes(classes)
                .dependencies(dependencies).snapshot());

        UsageRepository.ArchitectureHub hub = database.withHandle(repositoryHandle42 -> new UsageRepository(repositoryHandle42).findArchitectureHubs()).getFirst();
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
        IndexWriter.writeFresh(writable, new IndexFixture()
                .classes(initialClasses)
                .beans(beans)
                .dependencies(dependencies)
                .metadata(metadata)
                .files(files).snapshot());

        IndexWriter.IncrementalWriteTimings unchanged = IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), new IndexFixture()
                .classes(initialClasses)
                .beans(beans)
                .dependencies(dependencies)
                .metadata(metadata)
                .files(files).snapshot());

        assertEquals(0, unchanged.rowsInserted());
        assertEquals(0, unchanged.rowsDeleted());
        assertEquals(7, unchanged.rowsUnchanged());

        List<ClassRecord> changedClasses = List.of(
                new ClassRecord(0, "example.A", "CLASS", null, List.of(), "src/A.java", 1,
                        true, 11, 1, "source", "current"),
                initialClasses.get(1));
        IndexWriter.IncrementalWriteTimings changed = IndexWriter.writeIncremental(QuillDatabase.openWritable(dbPath), new IndexFixture()
                .classes(changedClasses)
                .beans(beans)
                .metadata(metadata)
                .files(files).snapshot());

        assertEquals(1, changed.rowsInserted(), "Only the changed class row should be inserted");
        assertEquals(2, changed.rowsDeleted(),
                "The old class row and removed dependency should be deleted");
        assertEquals(11, QuillDatabase.open(dbPath).withHandle(repositoryHandle43 -> new SymbolRepository(repositoryHandle43).findClassByName("example.A"))
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
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes)
                .metadata(Map.of("indexed_at", "2026-08-26")).snapshot());

        var result = jdbi.withHandle(repositoryHandle44 -> new SymbolRepository(repositoryHandle44).findAllClasses());
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
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes)
                .beans(beans).snapshot());

        var result = jdbi.withHandle(repositoryHandle45 -> new BeanRepository(repositoryHandle45).findBeans(null));
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

        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes)
                .beans(beans).snapshot());

        assertEquals(List.of("org.acme.Current"), jdbi.withHandle(repositoryHandle46 -> new SymbolRepository(repositoryHandle46).findAllClasses()).stream()
                .map(ClassRecord::className).toList());
        assertEquals(1, jdbi.withHandle(repositoryHandle47 -> new SymbolRepository(repositoryHandle47).countClasses()).intValue());
        assertEquals(10, jdbi.withHandle(repositoryHandle48 -> new SymbolRepository(repositoryHandle48).sumSourceTokens()).intValue());
        assertEquals(1, jdbi.withHandle(repositoryHandle49 -> new BeanRepository(repositoryHandle49).countBeans()).intValue());
        assertEquals(1, jdbi.withHandle(repositoryHandle50 -> new BeanRepository(repositoryHandle50).findBeans(null)).size());
        assertTrue(jdbi.withHandle(repositoryHandle51 -> new SymbolRepository(repositoryHandle51).searchClasses("Stale", 10)).isEmpty());
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
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes)
                .beans(beans).snapshot());

        assertEquals("org.acme.Second", jdbi.withHandle(repositoryHandle52 -> new SymbolRepository(repositoryHandle52).findClassesByIds(List.of(2))).get(2).className());
        assertEquals("@Singleton", jdbi.withHandle(repositoryHandle53 -> new BeanRepository(repositoryHandle53).findBeansByIds(List.of(1))).get(1).scope());
        assertEquals("@Dependent", jdbi.withHandle(repositoryHandle54 -> new BeanRepository(repositoryHandle54).findBeansByClassIds(List.of(2))).get(2).scope());
        assertTrue(jdbi.withHandle(repositoryHandle55 -> new SymbolRepository(repositoryHandle55).findClassesByIds(List.of())).isEmpty());
    }

    @Test
    void findHotspotsWithSinceRecomputesCounts() {
        try (var repositoryHandle = jdbi.open()) {
        GitRepository gitRepository = new GitRepository(repositoryHandle);
        var classes = List.of(
            new ClassRecord(0, "org.acme.Foo", "CLASS", null,
                    List.of(), "src/Foo.java", 1, false, 100)
        );
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes).snapshot());

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
        IndexFixture.writeGitData(jdbi, stats, commits, commitFiles);

        var allTime = gitRepository.findHotspots(10, null);
        assertEquals(1, allTime.size());
        assertEquals(2, allTime.getFirst().commitCount(), "All-time should return 2 commits");

        var filtered = gitRepository.findHotspots(10, "2026-08-15");
        assertEquals(1, filtered.size());
        assertEquals(1, filtered.getFirst().commitCount(),
                "Since 2026-08-15 should return 1 commit, recomputed from join");
    }
    }

    @Test
    void findHotspotsWithSinceExcludesOldFiles() {
        try (var repositoryHandle = jdbi.open()) {
        GitRepository gitRepository = new GitRepository(repositoryHandle);
        var classes = List.of(
            new ClassRecord(0, "org.acme.Old", "CLASS", null, List.of(), "Old.java", 1, false, 50),
            new ClassRecord(0, "org.acme.New", "CLASS", null, List.of(), "New.java", 1, false, 50)
        );
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes).snapshot());

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
        IndexFixture.writeGitData(jdbi, stats, commits, commitFiles);

        var filtered = gitRepository.findHotspots(10, "2026-06-01");
        assertEquals(1, filtered.size());
        assertEquals("New.java", filtered.getFirst().filePath());
    }
    }

    @Test
    void readMetadata() {
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .metadata(Map.of("indexed_at", "2026-08-26", "last_commit", "abc123")).snapshot());

        var meta = jdbi.withHandle(repositoryHandle56 -> new IndexRepository(repositoryHandle56).getMetadata());
        assertEquals("2026-08-26", meta.get("indexed_at"));
        assertEquals("abc123", meta.get("last_commit"));
    }

    @Test
    void storesAndReadsApplicationModuleClasspath() {
        IndexFixture.writeModuleClasspath(jdbi, List.of(
                new ModuleClasspathRecord("application", "application", 0, "self"),
                new ModuleClasspathRecord("application", "service", 1, "project_dependency"),
                new ModuleClasspathRecord("application", "common", 2, "project_dependency")));

        assertEquals(Set.of("application", "service", "common"),
                jdbi.withHandle(repositoryHandle57 -> new IndexRepository(repositoryHandle57).findVisibleModules("application")));
        assertEquals(List.of(0, 1, 2), jdbi.withHandle(repositoryHandle58 -> new IndexRepository(repositoryHandle58).findModuleClasspath("application"))
                .stream().map(ModuleClasspathRecord::distance).toList());
        assertEquals(3, jdbi.withHandle(repositoryHandle59 -> new IndexRepository(repositoryHandle59).findAllModuleClasspath()).size());
        assertTrue(jdbi.withHandle(repositoryHandle60 -> new IndexRepository(repositoryHandle60).findVisibleModules("missing")).isEmpty());
    }

    @Test
    void profileFilterExactMatch() {
        assertTrue(BeanRepository.matchesProfile(List.of("dev"), "dev"));
        assertTrue(BeanRepository.matchesProfile(List.of("dev", "local"), "dev"));
    }

    @Test
    void profileFilterRejectsNegation() {
        assertFalse(BeanRepository.matchesProfile(List.of("!dev"), "dev"));
    }

    @Test
    void profileFilterRejectsSubstring() {
        assertFalse(BeanRepository.matchesProfile(List.of("dev-staging"), "dev"));
    }

    @Test
    void profileFilterNullOrEmptyProfiles() {
        assertFalse(BeanRepository.matchesProfile(null, "dev"));
        assertFalse(BeanRepository.matchesProfile(List.of(), "dev"));
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
        IndexWriter.writeIncremental(jdbi, new IndexFixture()
                .classes(classes)
                .beans(beans).snapshot());

        var result = jdbi.withHandle(repositoryHandle61 -> new BeanRepository(repositoryHandle61).findBeans(Map.of("profile", "dev")));
        assertEquals(1, result.size(), "Only exact 'dev' profile should match, not '!dev' or 'dev-staging'");
        assertEquals(1, result.getFirst().classId());
    }
    @Test void kotlinSourceDebugRoundTripsAndRemovesStaleEvidence() {
        var db = QuillDatabase.create(tempDir.resolve("source-debug.db"));
        db.useHandle(h -> h.execute("INSERT INTO classes(id,class_name,kind,interfaces) VALUES (1,'acme.Owner','CLASS','[]')"));
        var info = new org.treblereel.mcp.core.KotlinSourceDebug.Info("acme.Owner", List.of(
                new org.treblereel.mcp.core.KotlinSourceDebug.Mapping("Kotlin", "Owner.kt", "acme/Owner", 5, 1, 100, 1)), false,
                new org.treblereel.mcp.core.KotlinSourceDebug.Coroutine("acme.Owner", "run", "Owner.kt", 2, List.of(5), List.of(6)));
        IndexFixture.writeKotlinSourceDebug(db, java.util.Map.of(1, info));
        assertEquals(info, db.withHandle(repositoryHandle62 -> new SymbolRepository(repositoryHandle62).findKotlinSourceDebug(1)).orElseThrow());
        db.useHandle(h -> h.execute("UPDATE kotlin_source_debug SET data='invalid'"));
        assertTrue(db.withHandle(repositoryHandle63 -> new SymbolRepository(repositoryHandle63).findKotlinSourceDebug(1)).isEmpty());
        IndexFixture.writeKotlinSourceDebug(db, java.util.Map.of());
        assertTrue(db.withHandle(repositoryHandle64 -> new SymbolRepository(repositoryHandle64).findKotlinSourceDebug(1)).isEmpty());
    }

}
