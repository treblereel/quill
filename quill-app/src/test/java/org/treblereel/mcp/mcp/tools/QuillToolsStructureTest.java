package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.*;

/**
 * Unused code, entry points, module and package graphs, architecture rules, cycles and index
 * comparison.
 */
class QuillToolsStructureTest extends QuillToolsTestBase {

    @Test
    void findUnusedClassesExcludesRuntimeRootsAndSupportsScopeOptions() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.UnusedHelper', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/UnusedHelper.java', 1,
                            0, 80, 'source', 'current', '.', 'main'),
                           ('org.acme.UnusedContract', 'INTERFACE', NULL, '[]',
                            'src/main/java/org/acme/UnusedContract.java', 1,
                            0, 20, 'source', 'current', '.', 'main'),
                           ('org.acme.FrameworkHook', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/FrameworkHook.java', 1,
                            0, 50, 'source', 'current', '.', 'main'),
                           ('org.acme.MainApp', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/MainApp.java', 1,
                            0, 60, 'source', 'current', '.', 'main'),
                           ('org.acme.FirstProcessor', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/FirstProcessor.java', 1,
                            0, 70, 'source', 'current', '.', 'main'),
                           ('org.acme.GeneratedHelper', 'CLASS', 'java.lang.Object', '[]',
                            'target/generated-sources/org/acme/GeneratedHelper.java', 1,
                            0, 30, 'generated', 'current', '.', 'main'),
                           ('org.acme.TestHelper', 'CLASS', 'java.lang.Object', '[]',
                            'src/test/java/org/acme/TestHelper.java', 1,
                            0, 40, 'source', 'current', '.', 'test')""");
                    handle.execute(
                            """
                    INSERT INTO class_annotations(class_id, annotation_name, direct, via_annotation)
                    SELECT id, 'org.acme.RuntimeHook', 1, NULL FROM classes
                    WHERE class_name = 'org.acme.FrameworkHook'""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    SELECT id, 'METHOD', 'main', 'main(java.lang.String[]):void', 'void',
                           '["java.lang.String[]"]', 'public static', '[]'
                    FROM classes WHERE class_name = 'org.acme.MainApp'""");
                });

        JsonNode defaults =
                JSON.readTree(JDBI_READS.findUnusedClasses(jdbi, null, false, false, 20, 0));
        JsonNode expanded =
                JSON.readTree(JDBI_READS.findUnusedClasses(jdbi, null, true, true, 20, 0));

        assertEquals("candidates_not_proven_dead_code", defaults.path("classification").asText());
        assertEquals(2, defaults.path("total").asInt());
        assertEquals(
                "org.acme.UnusedHelper", defaults.path("candidates").get(0).path("class").asText());
        assertEquals("medium", defaults.path("candidates").get(0).path("confidence").asText());
        assertEquals("low", defaults.path("candidates").get(1).path("confidence").asText());
        assertEquals(4, expanded.path("total").asInt());
        assertTrue(
                defaults.path("excluded_reason_counts").path("hierarchy_reference").asInt() >= 1);
        assertEquals(1, defaults.path("excluded_reason_counts").path("service_provider").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts").path("main_entry_point").asInt());
        assertTrue(defaults.path("service_descriptor_evidence_available").asBoolean());
    }

    @Test
    void findUnusedMethodsMatchesOverloadsByDescriptorAndExcludesCallbacks() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.PlainUtility', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/PlainUtility.java', 1,
                            0, 35, 'source', 'current', '.', 'main')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'METHOD', 'helper', 'helper(java.lang.String):void', 'void',
                            '["java.lang.String"]', 'private', '[]'),
                           (1, 'METHOD', 'helper', 'helper(int):void', 'void',
                            '["int"]', 'private', '[]'),
                           (1, 'METHOD', 'callback', 'callback():void', 'void',
                            '[]', 'private', '["org.acme.RuntimeHook"]'),
                           (1, 'METHOD', 'readObject',
                            'readObject(java.io.ObjectInputStream):void', 'void',
                            '["java.io.ObjectInputStream"]', 'private', '[]'),
                           (1, 'METHOD', 'nativeHook', 'nativeHook():void', 'void',
                            '[]', 'private native', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    SELECT id, 'METHOD', 'abandoned', 'abandoned():int', 'int',
                           '[]', 'private static', '[]'
                    FROM classes WHERE class_name = 'org.acme.PlainUtility'""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (1, 'createOrder', '()V', 1, 'helper',
                            '(Ljava/lang/String;)V', 'special', 2, '[18,21]')""");
                });

        JsonNode result =
                JSON.readTree(JDBI_READS.findUnusedMethods(jdbi, null, false, false, 20, 0));

        assertEquals(
                "private_method_candidates_not_proven_dead_code",
                result.path("classification").asText());
        assertEquals(2, result.path("total").asInt());
        assertEquals(
                "org.acme.PlainUtility", result.path("candidates").get(0).path("class").asText());
        assertEquals("medium", result.path("candidates").get(0).path("confidence").asText());
        JsonNode overloaded =
                result.path("candidates")
                        .valueStream()
                        .filter(candidate -> candidate.path("method").asText().equals("helper"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("helper(int):void", overloaded.path("signature").asText());
        assertEquals("(I)V", overloaded.path("descriptor").asText());
        assertEquals("low", overloaded.path("confidence").asText());
        assertEquals(
                1, result.path("excluded_reason_counts").path("inbound_bytecode_call").asInt());
        assertEquals(1, result.path("excluded_reason_counts").path("annotated_method").asInt());
        assertEquals(
                1,
                result.path("excluded_reason_counts")
                        .path("conventional_runtime_callback")
                        .asInt());
        assertEquals(1, result.path("excluded_reason_counts").path("native_method").asInt());
    }

    @Test
    void findUnusedFieldsUsesExactDescriptorsAndConservativeExclusions() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.PlainState', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/PlainState.java', 1,
                            0, 30, 'source', 'current', '.', 'main'),
                           ('org.acme.SerializableState', 'CLASS', 'java.lang.Object',
                            '["java.io.Serializable"]',
                            'src/main/java/org/acme/SerializableState.java', 1,
                            0, 20, 'source', 'current', '.', 'main')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    SELECT id, 'FIELD', 'unused', 'unused:int', 'int', '[]', 'private', '[]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'
                    UNION ALL
                    SELECT id, 'FIELD', 'writeOnly', 'writeOnly:java.lang.String',
                           'java.lang.String', '[]', 'private', '[]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'
                    UNION ALL
                    SELECT id, 'FIELD', 'used', 'used:java.lang.String',
                           'java.lang.String', '[]', 'private', '[]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'
                    UNION ALL
                    SELECT id, 'FIELD', 'injected', 'injected:org.acme.Service',
                           'org.acme.Service', '[]', 'private', '["jakarta.inject.Inject"]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'
                    UNION ALL
                    SELECT id, 'FIELD', 'CONSTANT', 'CONSTANT:int', 'int', '[]',
                           'private static final', '[]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'
                    UNION ALL
                    SELECT id, 'FIELD', 'serialized', 'serialized:int', 'int', '[]',
                           'private', '[]'
                    FROM classes WHERE class_name = 'org.acme.SerializableState'""");
                    handle.execute(
                            """
                    INSERT INTO field_accesses
                      (from_class_id, from_method, from_descriptor, to_class_id,
                       field_name, field_descriptor, access_kind,
                       occurrence_count, evidence_lines)
                    SELECT id, 'initialize', '()V', id, 'writeOnly',
                           'Ljava/lang/String;', 'write_instance', 1, '[12]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'
                    UNION ALL
                    SELECT id, 'initialize', '()V', id, 'used',
                           'Ljava/lang/String;', 'write_instance', 1, '[13]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'
                    UNION ALL
                    SELECT id, 'value', '()Ljava/lang/String;', id, 'used',
                           'Ljava/lang/String;', 'read_instance', 2, '[17,20]'
                    FROM classes WHERE class_name = 'org.acme.PlainState'""");
                });

        JsonNode defaults =
                JSON.readTree(JDBI_READS.findUnusedFields(jdbi, null, false, false, false, 20, 0));
        JsonNode expanded =
                JSON.readTree(JDBI_READS.findUnusedFields(jdbi, null, false, false, true, 20, 0));

        assertEquals(
                "private_field_candidates_not_proven_dead_code",
                defaults.path("classification").asText());
        assertEquals(1, defaults.path("total").asInt());
        assertEquals("unused", defaults.path("candidates").get(0).path("field").asText());
        assertEquals("I", defaults.path("candidates").get(0).path("descriptor").asText());
        assertEquals(
                "never_accessed",
                defaults.path("candidates").get(0).path("candidate_kind").asText());
        assertEquals(2, expanded.path("total").asInt());
        JsonNode writeOnly =
                expanded.path("candidates")
                        .valueStream()
                        .filter(candidate -> candidate.path("field").asText().equals("writeOnly"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("write_only", writeOnly.path("candidate_kind").asText());
        assertEquals(1, writeOnly.path("write_occurrences").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts").path("annotated_field").asInt());
        assertEquals(
                1, defaults.path("excluded_reason_counts").path("static_final_constant").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts").path("serializable_state").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts").path("indexed_read").asInt());
        assertEquals(
                1,
                defaults.path("excluded_reason_counts").path("write_only_not_requested").asInt());
    }

    @Test
    void findEntryPointsClassifiesFrameworkMethodsAndServiceProviders() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.MainApp', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/MainApp.java', 1,
                            0, 20, 'source', 'current', '.', 'main'),
                           ('org.acme.GreetingResource', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/GreetingResource.java', 1,
                            1, 35, 'source', 'current', '.', 'main'),
                           ('org.acme.EventHandlers', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/EventHandlers.java', 1,
                            1, 40, 'source', 'current', '.', 'main'),
                           ('org.acme.FirstProcessor', 'CLASS',
                            'javax.annotation.processing.AbstractProcessor',
                            '["javax.annotation.processing.Processor"]',
                            'src/main/java/org/acme/FirstProcessor.java', 1,
                            0, 25, 'source', 'current', '.', 'main'),
                           ('org.acme.PluginProvider', 'CLASS', 'java.lang.Object',
                            '["java.lang.Runnable"]',
                            'src/main/java/org/acme/PluginProvider.java', 1,
                            0, 15, 'source', 'current', '.', 'main')""");
                    handle.execute(
                            """
                    INSERT INTO class_annotations(class_id, annotation_name, direct, via_annotation)
                    SELECT id, 'jakarta.ws.rs.Path', 1, NULL FROM classes
                    WHERE class_name = 'org.acme.GreetingResource'""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    SELECT id, 'METHOD', 'main', 'main(java.lang.String[]):void', 'void',
                           '["java.lang.String[]"]', 'public static', '[]'
                    FROM classes WHERE class_name = 'org.acme.MainApp'
                    UNION ALL
                    SELECT id, 'METHOD', 'hello', 'hello():java.lang.String',
                           'java.lang.String', '[]', 'public', '["jakarta.ws.rs.GET"]'
                    FROM classes WHERE class_name = 'org.acme.GreetingResource'
                    UNION ALL
                    SELECT id, 'METHOD', 'observe', 'observe(java.lang.String):void', 'void',
                           '["java.lang.String"]', '', '["jakarta.enterprise.event.Observes"]'
                    FROM classes WHERE class_name = 'org.acme.EventHandlers'
                    UNION ALL
                    SELECT id, 'METHOD', 'tick', 'tick():void', 'void', '[]', '',
                           '["io.quarkus.scheduler.Scheduled"]'
                    FROM classes WHERE class_name = 'org.acme.EventHandlers'
                    UNION ALL
                    SELECT id, 'METHOD', 'consume', 'consume(java.lang.String):void', 'void',
                           '["java.lang.String"]', '',
                           '["org.eclipse.microprofile.reactive.messaging.Incoming"]'
                    FROM classes WHERE class_name = 'org.acme.EventHandlers'""");
                    handle.execute(
                            "UPDATE metadata SET value = ? WHERE key = ?",
                            """
                    [{"serviceType":"javax.annotation.processing.Processor",
                      "providerType":"org.acme.FirstProcessor",
                      "descriptorPath":"processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor","line":1},
                     {"serviceType":"java.lang.Runnable",
                      "providerType":"org.acme.PluginProvider",
                      "descriptorPath":"app/src/main/resources/META-INF/services/java.lang.Runnable","line":1}]
                    """,
                            "service_registrations_detail");
                });

        JsonNode result =
                JSON.readTree(JDBI_READS.findEntryPoints(jdbi, null, null, false, false, 20, 0));
        JsonNode processors =
                JSON.readTree(
                        JDBI_READS.findEntryPoints(
                                jdbi, "annotation_processor", null, false, false, 20, 0));

        assertEquals(8, result.path("total").asInt());
        assertEquals(1, result.path("counts_by_kind").path("main").asInt());
        assertEquals(1, result.path("counts_by_kind").path("rest_resource").asInt());
        assertEquals(1, result.path("counts_by_kind").path("rest_endpoint").asInt());
        assertEquals(1, result.path("counts_by_kind").path("observer").asInt());
        assertEquals(1, result.path("counts_by_kind").path("scheduled").asInt());
        assertEquals(1, result.path("counts_by_kind").path("message_consumer").asInt());
        assertEquals(1, result.path("counts_by_kind").path("annotation_processor").asInt());
        assertEquals(1, result.path("counts_by_kind").path("service_provider").asInt());
        assertEquals(1, processors.path("total").asInt());
        assertEquals(
                "service_descriptor_registration",
                processors.path("entry_points").get(0).path("detection_rule").asText());
        assertEquals(
                "javax.annotation.processing.Processor",
                processors.path("entry_points").get(0).path("service").path("type").asText());
    }

    @Test
    void getModuleGraphSeparatesDirectDependenciesFromTransitiveVisibility() throws Exception {
        IndexFixture.writeModuleClasspath(
                jdbi,
                List.of(
                        new ModuleClasspathRecord("app", "app", 0, "self"),
                        new ModuleClasspathRecord("app", "service", 1, "project_dependency"),
                        new ModuleClasspathRecord("app", "common", 2, "project_dependency"),
                        new ModuleClasspathRecord("service", "service", 0, "self"),
                        new ModuleClasspathRecord("service", "common", 1, "project_dependency"),
                        new ModuleClasspathRecord("common", "common", 0, "self")));
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO classes
                  (class_name, kind, superclass, interfaces, source_file, source_line,
                   is_bean, source_tokens, origin, lifecycle, module, source_set)
                VALUES ('org.acme.app.Application', 'CLASS', 'java.lang.Object', '[]',
                        'app/src/main/java/org/acme/app/Application.java', 1,
                        1, 30, 'source', 'current', 'app', 'main'),
                       ('org.acme.service.Service', 'CLASS', 'java.lang.Object', '[]',
                        'service/src/main/java/org/acme/service/Service.java', 1,
                        1, 20, 'source', 'current', 'service', 'main'),
                       ('org.acme.common.GeneratedModel', 'CLASS', 'java.lang.Object', '[]',
                        'common/target/generated-sources/org/acme/common/GeneratedModel.java', 1,
                        0, 10, 'generated', 'current', 'common', 'main')
                """));

        JsonNode direct = JSON.readTree(JDBI_READS.getModuleGraph(jdbi, null, "both", 5, 20, 0));
        JsonNode outbound =
                JSON.readTree(JDBI_READS.getModuleGraph(jdbi, "app", "outbound", 2, 20, 0));
        JsonNode inbound =
                JSON.readTree(JDBI_READS.getModuleGraph(jdbi, "common", "inbound", 2, 20, 0));
        JsonNode missing =
                JSON.readTree(JDBI_READS.getModuleGraph(jdbi, "missing", "both", 2, 20, 0));

        assertEquals(2, direct.path("total").asInt());
        assertEquals(2, direct.path("direct_relations").asInt());
        assertEquals(0, direct.path("transitive_relations").asInt());
        assertEquals(2, outbound.path("total").asInt());
        assertEquals(
                "direct_project_dependency",
                outbound.path("relations").get(0).path("kind").asText());
        assertEquals(
                "transitive_classpath_visibility",
                outbound.path("relations").get(1).path("kind").asText());
        assertEquals(2, inbound.path("total").asInt());
        assertEquals("service", inbound.path("relations").get(0).path("from").asText());
        JsonNode common =
                inbound.path("nodes")
                        .valueStream()
                        .filter(node -> node.path("module").asText().equals("common"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(1, common.path("generated_classes").asInt());
        assertEquals("Module not found: missing", missing.path("error").asText());
        assertEquals(3, missing.path("available_modules").size());
    }

    @Test
    void getPackageGraphAggregatesCouplingAndSupportsDirections() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    UPDATE classes
                    SET class_name = 'org.audit.AuditService',
                        source_file = 'src/main/java/org/audit/AuditService.java'
                    WHERE id = 4""");
                    handle.execute(
                            """
                    INSERT INTO dependencies
                      (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (4, 1, 'TYPE_REFERENCE', 2, '[8,12]')""");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode graph =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.getPackageGraph(
                                                readScope, null, "both", null, false, false, 1,
                                                0)));
        assertEquals(2, graph.path("package_count").asInt());
        assertEquals(2, graph.path("total").asInt());
        assertEquals(1, graph.path("showing").asInt());
        assertTrue(graph.path("has_more").asBoolean());
        assertTrue(graph.path("relations").get(0).path("occurrence_count").asInt() >= 1);

        JsonNode inbound =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.getPackageGraph(
                                                readScope, "audit", "inbound", null, false, false,
                                                10, 0)));
        assertEquals("org.audit", inbound.path("package").asText());
        assertEquals(1, inbound.path("total").asInt());
        assertEquals("org.acme", inbound.path("relations").get(0).path("from").asText());
        assertEquals("org.audit", inbound.path("relations").get(0).path("to").asText());
        assertEquals(
                1,
                inbound.path("relations")
                        .get(0)
                        .path("occurrences_by_kind")
                        .path("CONSTRUCTS")
                        .asInt());

        JsonNode outbound =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.getPackageGraph(
                                                readScope,
                                                "org.audit",
                                                "outbound",
                                                null,
                                                false,
                                                false,
                                                10,
                                                0)));
        assertEquals(1, outbound.path("total").asInt());
        assertEquals(
                2,
                outbound.path("relations")
                        .get(0)
                        .path("occurrences_by_kind")
                        .path("TYPE_REFERENCE")
                        .asInt());

        JsonNode missing =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.getPackageGraph(
                                                readScope, "missing", "both", null, false, false,
                                                10, 0)));
        assertEquals("Package not found: missing", missing.path("error").asText());
        assertTrue(missing.path("_meta").isObject());
    }

    @Test
    void findArchitectureViolationsEvaluatesPackageAndModuleRules() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    UPDATE classes
                    SET class_name = 'org.persistence.AuditRepository',
                        source_file = 'src/main/java/org/persistence/AuditRepository.java',
                        module = 'persistence', source_set = 'main'
                    WHERE id = 4""");
                    handle.execute(
                            "UPDATE classes SET module = 'web', source_set = 'main' WHERE id = 1");
                    handle.execute(
                            """
                    INSERT INTO dependencies
                      (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (1, 4, 'CALLS', 3, '[21,27]')""");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode packages =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findArchitectureViolations(
                                                readScope,
                                                "package",
                                                "org.acme..",
                                                List.of("org.persistence.."),
                                                List.of("CALLS"),
                                                false,
                                                false,
                                                10,
                                                0)));
        assertFalse(packages.path("compliant").asBoolean());
        assertEquals(1, packages.path("total").asInt());
        assertEquals(3, packages.path("occurrence_count").asInt());
        assertEquals(3, packages.path("occurrences_by_kind").path("CALLS").asInt());
        assertEquals(
                "org.acme.OrderService",
                packages.path("violations").get(0).path("from_class").asText());
        assertEquals(
                "org.persistence.AuditRepository",
                packages.path("violations").get(0).path("to_class").asText());
        assertEquals(
                List.of(21, 27),
                packages.path("violations")
                        .get(0)
                        .path("evidence_lines")
                        .valueStream()
                        .map(JsonNode::asInt)
                        .toList());
        assertTrue(packages.path("_meta").isObject());

        JsonNode modules =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findArchitectureViolations(
                                                readScope,
                                                "module",
                                                "web",
                                                List.of("persistence"),
                                                List.of(),
                                                false,
                                                false,
                                                10,
                                                0)));
        assertEquals(1, modules.path("total").asInt());
        assertEquals("web", modules.path("violations").get(0).path("from_boundary").asText());

        JsonNode missingSource =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findArchitectureViolations(
                                                readScope,
                                                "package",
                                                "org.missing..",
                                                List.of("org.persistence.."),
                                                List.of(),
                                                false,
                                                false,
                                                10,
                                                0)));
        assertEquals(
                "Source boundary pattern matched no indexed classes",
                missingSource.path("error").asText());
        assertTrue(missingSource.path("available_boundaries").isArray());

        JsonNode compliant =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findArchitectureViolations(
                                                readScope,
                                                "package",
                                                "org.acme..",
                                                List.of("org.absent.."),
                                                List.of(),
                                                false,
                                                false,
                                                10,
                                                0)));
        assertTrue(compliant.path("compliant").asBoolean());
        assertEquals(0, compliant.path("matched_forbidden_classes").asInt());
        assertEquals(0, compliant.path("total").asInt());
    }

    @Test
    void findArchitectureViolationsValidatesRules() throws Exception {
        QuillToolQueries queries = new QuillToolQueries();
        assertEquals(
                "Invalid scope: expected package or module",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findArchitectureViolations(
                                                        readScope,
                                                        "class",
                                                        "org.acme",
                                                        List.of("org.persistence"),
                                                        List.of(),
                                                        false,
                                                        false,
                                                        10,
                                                        0)))
                        .path("message")
                        .asText());
        assertEquals(
                "At least one forbidden pattern is required",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findArchitectureViolations(
                                                        readScope,
                                                        "package",
                                                        "org.acme",
                                                        List.of(),
                                                        List.of(),
                                                        false,
                                                        false,
                                                        10,
                                                        0)))
                        .path("message")
                        .asText());
    }

    @Test
    void findCyclesReturnsClassComponentsAndRepresentativePath() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.Container', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/Container.java', 1,
                            0, 10, 'source', 'current', 'app', 'main'),
                           ('org.acme.Container$Worker', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/Container.java', 3,
                            0, 5, 'source', 'current', 'app', 'main')
                    """);
                    handle.execute(
                            """
                    INSERT INTO dependencies(from_class_id, to_class_id, kind,
                                             occurrence_count, evidence_lines)
                    VALUES (4, 1, 'CLASS_REFERENCE', 1, '[7]'),
                           (1, 4, 'METHOD_CALL', 2, '[18,21]'),
                           (5, 6, 'CONSTRUCTS', 1, '[]'),
                           (6, 5, 'TYPE_USE', 1, '[]')
                    """);
                });

        JsonNode result =
                JSON.readTree(JDBI_READS.findCycles(jdbi, "class", null, false, false, 20, 0));

        assertEquals("class", result.path("scope").asText());
        assertEquals(1, result.path("total").asInt());
        assertEquals(
                "collapsed_into_top_level_owner", result.path("nested_class_handling").asText());
        JsonNode cycle = result.path("cycles").get(0);
        assertEquals(3, cycle.path("member_count").asInt());
        assertEquals("org.acme.AuditService", cycle.path("representative_path").get(0).asText());
        assertEquals(
                cycle.path("representative_path").get(0).asText(),
                cycle.path("representative_path")
                        .get(cycle.path("representative_path").size() - 1)
                        .asText());
        assertTrue(
                cycle.path("edges")
                        .valueStream()
                        .anyMatch(edge -> edge.path("kinds").toString().contains("CDI_INJECT")));
        JsonNode through =
                JSON.readTree(
                        JDBI_READS.findCycles(
                                jdbi, "org.acme.AuditService", null, false, false, 20, 0));
        assertEquals(
                "org.acme.AuditService", through.path("class_filter").asText(), through.toString());
        assertEquals(result.path("cycles"), through.path("cycles"));
        JsonNode outside =
                JSON.readTree(
                        JDBI_READS.findCycles(
                                jdbi, "org.acme.Container", null, false, false, 20, 0));
        assertEquals(0, outside.path("total").asInt(), outside.toString());
        assertFalse(outside.path("class_in_cycle").asBoolean());
    }

    @Test
    void findCyclesKeepsNestedClassesWhoseOwnerHasNoDependencies() throws Exception {
        // The owner is indexed only when it takes part in a dependency row; a nested class
        // with an edge-free owner stays its own node instead of failing the whole query.
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (id, class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES (100, 'org.acme.Outer$Inner', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/Outer.java', 3, 0, 5, 'source', 'current', 'app', 'main'),
                           (101, 'org.acme.Peer', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/Peer.java', 1, 0, 5, 'source', 'current', 'app', 'main')
                    """);
                    handle.execute(
                            """
                    INSERT INTO dependencies(from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (100, 101, 'METHOD_CALL', 1, '[]'), (101, 100, 'CONSTRUCTS', 1, '[]')
                    """);
                });

        JsonNode result =
                JSON.readTree(JDBI_READS.findCycles(jdbi, "class", null, false, true, 20, 0));

        assertFalse(result.has("error_code"), result.toString());
        assertTrue(
                result.path("cycles")
                        .valueStream()
                        .anyMatch(
                                cycle ->
                                        cycle.path("members")
                                                        .toString()
                                                        .contains("org.acme.Outer$Inner")
                                                && cycle.path("members")
                                                        .toString()
                                                        .contains("org.acme.Peer")),
                result.toString());
    }

    @Test
    void findCyclesUsesOnlyDirectModuleDependencies() throws Exception {
        IndexFixture.writeModuleClasspath(
                jdbi,
                List.of(
                        new ModuleClasspathRecord("app", "app", 0, "self"),
                        new ModuleClasspathRecord("app", "service", 1, "project_dependency"),
                        new ModuleClasspathRecord("app", "common", 2, "project_dependency"),
                        new ModuleClasspathRecord("service", "service", 0, "self"),
                        new ModuleClasspathRecord("service", "app", 1, "project_dependency"),
                        new ModuleClasspathRecord("common", "common", 0, "self")));

        JsonNode result =
                JSON.readTree(JDBI_READS.findCycles(jdbi, "module", null, false, false, 20, 0));

        assertEquals(1, result.path("total").asInt());
        assertEquals(2, result.path("cycles").get(0).path("member_count").asInt());
        assertEquals(3, result.path("cycles").get(0).path("representative_path").size());
        assertFalse(result.path("cycles").get(0).path("members").toString().contains("common"));
    }

    /**
     * V62: Codex sent these spellings and repeated the identical call after each INVALID_ARGUMENT.
     */
    @Test
    void graphToolsAcceptTheDirectionSpellingsAgentsSend() throws Exception {
        assertEquals(
                JSON.readTree(JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 1))
                        .path("dependencies"),
                JSON.readTree(JDBI_READS.getDependencies(jdbi, "OrderService", "outgoing", 1))
                        .path("dependencies"));
        assertEquals(
                JSON.readTree(JDBI_READS.getDependencies(jdbi, "AuditService", "inbound", 1))
                        .path("dependents"),
                JSON.readTree(JDBI_READS.getDependencies(jdbi, "AuditService", "incoming", 1))
                        .path("dependents"));
        assertFalse(
                JSON.readTree(JDBI_READS.getDependencies(jdbi, "OrderService", "outgoing", 1))
                        .has("error_code"));
        assertFalse(
                JSON.readTree(JDBI_READS.getDependencies(jdbi, "AuditService", "incoming", 1))
                        .has("error_code"));
        assertEquals(
                "descendants",
                JSON.readTree(
                                JDBI_READS.getTypeHierarchy(
                                        jdbi, "PaymentService", "subtypes", 5, 10, 0))
                        .path("direction")
                        .asText());
        assertEquals(
                "ancestors",
                JSON.readTree(
                                JDBI_READS.getTypeHierarchy(
                                        jdbi, "PaymentService", "supertypes", 5, 10, 0))
                        .path("direction")
                        .asText());
    }

    @Test
    void findCyclesRejectsUnknownScope() throws Exception {
        JsonNode result =
                JSON.readTree(JDBI_READS.findCycles(jdbi, "package", null, false, false, 20, 0));
        assertEquals("Invalid scope: expected class or module", result.path("message").asText());
    }

    @Test
    void compareIndexReportsStructuralAndResolutionDeltas() throws Exception {
        Path quillDir = Files.createDirectories(tempDir.resolve(".quill"));
        Jdbi baseline = QuillDatabase.create(quillDir.resolve("baseline.db"));
        IndexWriter.writeIncremental(
                baseline,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "org.acme.OrderService",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "src/main/java/org/acme/OrderService.java",
                                                10,
                                                true,
                                                500),
                                        new ClassRecord(
                                                0,
                                                "org.acme.PaymentService",
                                                "INTERFACE",
                                                null,
                                                List.of(),
                                                "src/main/java/org/acme/PaymentService.java",
                                                5,
                                                false,
                                                100),
                                        new ClassRecord(
                                                0,
                                                "org.acme.StripePaymentService",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of("org.acme.PaymentService"),
                                                "src/main/java/org/acme/StripePaymentService.java",
                                                8,
                                                true,
                                                300)))
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
                                                List.of("OrderService", "Object")),
                                        new BeanRecord(
                                                0,
                                                3,
                                                "CLASS",
                                                "@ApplicationScoped",
                                                List.of("@Default", "@Premium"),
                                                List.of(),
                                                false,
                                                null,
                                                List.of("prod"),
                                                null,
                                                null,
                                                List.of(
                                                        "PaymentService",
                                                        "StripePaymentService",
                                                        "Object"))))
                        .injectionPoints(
                                List.of(
                                        InjectionPointRecord.staticAnalysis(
                                                        0,
                                                        1,
                                                        "FIELD",
                                                        "PaymentService",
                                                        List.of("@Default"),
                                                        "paymentService",
                                                        2,
                                                        false,
                                                        InjectionPointRecord.STATIC_CDI)
                                                .withResolution(
                                                        2,
                                                        false,
                                                        new ResolutionTrace(
                                                                List.of(),
                                                                List.of(
                                                                        ResolutionRule
                                                                                .TYPE_ASSIGNABILITY),
                                                                List.of()))))
                        .dependencies(List.of(new DependencyRecord(1, 3, "CDI_INJECT", 1, 2)))
                        .metadata(
                                Map.of(
                                        "index_id",
                                        "baseline",
                                        "indexed_at",
                                        "2026-09-16T10:00:00Z",
                                        "last_commit",
                                        "old-commit"))
                        .snapshot());
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO metadata(key, value) VALUES ('index_id', 'current')");
                    handle.execute(
                            "UPDATE metadata SET value = '2026-09-17T10:00:00Z' "
                                    + "WHERE key = 'indexed_at'");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'checkout', 'checkout():void', 'void',
                            '[]', '[public]', '[]')
                    """);
                });

        JsonNode result = JSON.readTree(JDBI_READS.compareIndex(jdbi, tempDir, null, 20));

        assertEquals("baseline", result.path("baseline").path("index_id").asText());
        assertTrue(result.path("api_compatibility").isObject());
        assertFalse(result.path("api_compatibility").path("compatibility_proven").asBoolean());
        assertEquals("current", result.path("current").path("index_id").asText());
        assertEquals(1, result.path("classes").path("added_count").asInt());
        assertEquals("org.acme.AuditService", result.path("classes").path("added").get(0).asText());
        assertEquals(1, result.path("classes").path("modified_count").asInt());
        assertEquals(1, result.path("dependencies").path("added_count").asInt());
        assertEquals(1, result.path("dependencies").path("occurrence_count_changed_count").asInt());
        assertEquals(1, result.path("beans").path("added_count").asInt());
        assertEquals(1, result.path("injections").path("added_count").asInt());
    }

    @Test
    void compareIndexListsAvailableBaselinesWhenRequestedOneIsMissing() throws Exception {
        Files.createDirectories(tempDir.resolve(".quill"));
        Jdbi baseline = QuillDatabase.create(tempDir.resolve(".quill/baseline.db"));
        IndexWriter.writeIncremental(
                baseline,
                new IndexFixture()
                        .metadata(
                                Map.of(
                                        "index_id",
                                        "baseline",
                                        "indexed_at",
                                        "2026-09-16T10:00:00Z",
                                        "last_commit",
                                        "old-commit"))
                        .snapshot());
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                "INSERT INTO metadata(key, value) VALUES ('index_id', 'current')"));

        JsonNode result = JSON.readTree(JDBI_READS.compareIndex(jdbi, tempDir, "missing", 20));

        assertEquals("Baseline index not found: missing", result.path("error").asText());
        assertEquals("baseline", result.path("available_baselines").get(0).asText());
    }
}
