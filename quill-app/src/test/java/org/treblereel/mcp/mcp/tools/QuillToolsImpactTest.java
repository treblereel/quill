package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.change.ContextQueries;
import org.treblereel.mcp.mcp.change.TestImpactQueries;
import org.treblereel.mcp.mcp.semantic.SymbolContract;
import org.treblereel.mcp.model.*;

/** Context, related targets, impacted tests, design impact and risk. */
class QuillToolsImpactTest extends QuillToolsTestBase {

    /**
     * V24 CQ-045: Claude asked impact for SsarClientCache in all three sessions and got an error
     * instead of its tests.
     */
    @Test
    void aClassImpactNamesItsDependentsAndTests() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.OrderServiceTest', 'CLASS', 'java.lang.Object', '[]',
                            'src/test/java/org/acme/OrderServiceTest.java', 1, 0, 80,
                            'source', 'current', '.', 'test')""");
                    handle.execute(
                            """
                    INSERT INTO dependencies (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    SELECT t.id, s.id, 'CALLS', 1, '[7]' FROM classes t, classes s
                    WHERE t.class_name = 'org.acme.OrderServiceTest' AND s.class_name = 'org.acme.OrderService'""");
                    handle.execute(
                            """
                    INSERT INTO method_calls (from_class_id, from_method, from_descriptor, to_class_id, to_method, to_descriptor, invocation_kind)
                    SELECT t.id, 'lambda$placesOrder$0', '()V', s.id, 'place', '()V', 'virtual' FROM classes t, classes s
                    WHERE t.class_name = 'org.acme.OrderServiceTest' AND s.class_name = 'org.acme.OrderService'""");
                });
        String answer =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        context ->
                                new ContextQueries()
                                        .getContext(
                                                context,
                                                List.of("OrderService"),
                                                false,
                                                10,
                                                "impact"));
        JsonNode result = JSON.readTree(answer);

        assertEquals(0, result.path("unresolved_target_count").asInt(), result.toString());
        JsonNode impact = result.path("contexts").path(0);
        assertEquals("direct_class_impact", impact.path("kind").asText(), result.toString());
        assertEquals("org.acme.OrderService", impact.path("class").asText());
        assertEquals(
                "org.acme.OrderServiceTest",
                impact.path("test_dependents").path(0).path("class").asText(),
                result.toString());
        assertEquals(
                "placesOrder",
                impact.path("test_dependents").path(0).path("used_in").path(0).asText(),
                result.toString());
        assertTrue(
                impact.path("production_dependents")
                        .valueStream()
                        .noneMatch(row -> row.path("class").asText().endsWith("Test")),
                result.toString());
        assertTrue(
                impact.path("impacted_tests")
                        .path("tests")
                        .toString()
                        .contains("org.acme.OrderServiceTest"),
                result.toString());
    }

    @Test
    void getContextComposesCompactEvidenceAndKeepsPartialResults() throws Exception {

        JsonNode result =
                JSON.readTree(
                        JDBI_READS.getContext(
                                jdbi, List.of("OrderService", "MissingService"), false, 5));

        assertEquals(1, result.path("resolved_target_count").asInt());
        assertEquals(1, result.path("unresolved_target_count").asInt());
        assertFalse(result.path("answer_complete").asBoolean());
        JsonNode context = result.path("contexts").get(0);
        assertEquals("org.acme.OrderService", context.path("resolution").path("class").asText());
        assertEquals("org.acme.OrderService", context.path("symbol").path("class").asText());
        assertFalse(context.path("symbol").path("members_included").asBoolean());
        assertEquals(1, context.path("coupling").path("fan_out").asInt());
        assertTrue(context.path("usages").has("usages"));
        assertTrue(context.path("risk").has("risk_score"));
        assertFalse(context.path("risk").has("_meta"));
        assertTrue(result.has("impacted_tests"));
        JsonNode tool = JSON.readTree(ContextQueries.summarizeImpactedTests(result.toString()));
        assertFalse(
                tool.path("impacted_tests").has("tests"),
                "find_impacted_tests lists them on request");
        assertEquals(
                "find_impacted_tests",
                tool.path("impacted_tests").path("request").path("tool").asText());
        assertEquals(
                result.path("impacted_tests").path("targets"),
                tool.path("impacted_tests").path("request").path("arguments").path("targets"));
        assertEquals(
                result.path("impacted_tests").path("total"),
                tool.path("impacted_tests").path("total"));
        assertEquals(
                result.path("impacted_tests").path("answer_complete"),
                tool.path("impacted_tests").path("answer_complete"));
        JsonNode failed =
                JSON.readTree(
                        ContextQueries.summarizeImpactedTests(
                                "{\"impacted_tests\":{\"error_code\":\"AMBIGUOUS_CLASS\"}}"));
        assertFalse(
                failed.path("impacted_tests").has("request"),
                "No follow-up without resolved targets");
        assertEquals(
                "MissingService", result.path("unresolved_targets").get(0).path("target").asText());
        assertEquals("abc1234", result.path("_meta").path("indexed_commit").asText());
    }

    @Test
    void getContextRequiresTargetsAndReportsEveryUnresolvedTarget() throws Exception {
        JsonNode empty = JSON.readTree(JDBI_READS.getContext(jdbi, List.of(), false, 10));
        JsonNode tooMany =
                JSON.readTree(
                        JDBI_READS.getContext(
                                jdbi,
                                List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K"),
                                false,
                                10));

        assertEquals("At least one target is required", empty.path("message").asText());
        assertEquals(11, tooMany.path("requested_targets").size());
        assertEquals(11, tooMany.path("unresolved_target_count").asInt());
    }

    @Test
    void relatedTargetsShareTheTraversalBudgetAndReportIncompleteEvidence() throws Exception {
        var dependencies =
                java.util.stream.IntStream.rangeClosed(7, 5406)
                        .mapToObj(id -> new DependencyRecord(id, ((id - 7) % 6) + 1, "CALLS", null))
                        .toList();
        var indexed = relatedTargetsFixture(5406, dependencies);
        AtomicInteger inboundReads = new AtomicInteger();
        indexed.setSqlLogger(
                new SqlLogger() {
                    @Override
                    public void logAfterExecution(StatementContext context) {
                        if (context.getRawSql()
                                .equals("SELECT * FROM dependencies WHERE to_class_id = :classId"))
                            inboundReads.incrementAndGet();
                    }
                });
        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                indexed,
                                readScope ->
                                        new TestImpactQueries()
                                                .findImpactedTests(
                                                        readScope,
                                                        List.of(
                                                                "OrderService",
                                                                "PaymentService",
                                                                "StripePaymentService",
                                                                "AuditService",
                                                                "Related5",
                                                                "Related6"),
                                                        true,
                                                        3,
                                                        40,
                                                        0)));
        assertEquals(5000, inboundReads.get());
        assertTrue(result.path("traversal_truncated").asBoolean());
        assertFalse(result.path("answer_complete").asBoolean());
        assertTrue(result.path("limitations").toString().contains("across all targets"));
    }

    @Test
    void relatedTargetsReadEachInboundDependencyListOnce() throws Exception {
        var dependencies = new java.util.ArrayList<DependencyRecord>();
        for (int id = 1; id <= 6; id++)
            dependencies.add(new DependencyRecord(7, id, "CALLS", null));
        dependencies.add(new DependencyRecord(8, 7, "CALLS", null));
        var indexed = relatedTargetsFixture(8, dependencies);
        AtomicInteger inboundReads = new AtomicInteger();
        indexed.setSqlLogger(
                new SqlLogger() {
                    @Override
                    public void logAfterExecution(StatementContext context) {
                        if (context.getRawSql()
                                .equals(
                                        "SELECT * FROM dependencies WHERE to_class_id = :classId")) {
                            inboundReads.incrementAndGet();
                        }
                    }
                });
        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                indexed,
                                readScope ->
                                        new TestImpactQueries()
                                                .findImpactedTests(
                                                        readScope,
                                                        List.of(
                                                                "OrderService",
                                                                "PaymentService",
                                                                "StripePaymentService",
                                                                "AuditService",
                                                                "Related5",
                                                                "Related6"),
                                                        true,
                                                        3,
                                                        40,
                                                        0)));
        assertEquals(1, result.path("total").asInt());
        JsonNode test = result.path("tests").get(0);
        assertEquals(2, test.path("dependency_depth").asInt());
        assertEquals(6, test.path("reasons").size());
        assertEquals(
                8, inboundReads.get(), "Shared traversal nodes must be loaded once per request");
    }

    @Test
    void findImpactedTestsCombinesTransitiveStaticAndGitEvidence() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.OrderHelper', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/OrderHelper.java', 1, 0, 40,
                            'source', 'current', '.', 'main')""");
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.OrderServiceTest', 'CLASS', 'java.lang.Object', '[]',
                            'src/test/java/org/acme/OrderServiceTest.java', 1, 0, 80,
                            'source', 'current', '.', 'test')""");
                    handle.execute(
                            """
                    INSERT INTO dependencies
                      (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (5, 1, 'CALLS', 1, '[12]'),
                           (6, 5, 'CALLS', 2, '[20,24]')""");
                    handle.execute(
                            """
                    INSERT INTO git_commit_files(commit_id, class_id, file_path, change_type)
                    VALUES (1, 6, 'src/test/java/org/acme/OrderServiceTest.java', 'MODIFY')""");
                });

        JsonNode result =
                JSON.readTree(
                        JDBI_READS.findImpactedTests(
                                jdbi, List.of("OrderService"), true, 3, 10, 0));

        assertEquals("class", result.path("granularity").asText());
        assertFalse(result.path("runtime_coverage_measured").asBoolean(true));
        assertTrue(
                result.path("limitations")
                        .valueStream()
                        .map(JsonNode::asText)
                        .anyMatch(
                                value ->
                                        value.contains("different member")
                                                && value.contains(
                                                        "does not establish method execution")));
        assertEquals(1, result.path("indexed_test_class_count").asInt());
        assertTrue(result.path("compiled_test_outputs_indexed").asBoolean());
        assertEquals(1, result.path("total").asInt());
        JsonNode test = result.path("tests").get(0);
        assertEquals("org.acme.OrderServiceTest", test.path("class").asText());
        assertEquals(2, test.path("dependency_depth").asInt());
        assertEquals("medium", test.path("confidence").asText());
        assertEquals(1, test.path("co_change_count").asInt());
        assertEquals(
                List.of(
                        "org.acme.OrderService",
                        "org.acme.OrderHelper",
                        "org.acme.OrderServiceTest"),
                test.path("dependency_path").valueStream().map(JsonNode::asText).toList());
    }

    @Test
    void methodTestImpactSeparatesExactReferencesFromClassRanking() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes(class_name, kind, superclass, interfaces, source_file,
                      source_line, is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.OrderServiceTest', 'CLASS', 'java.lang.Object', '[]',
                      'src/test/java/org/acme/OrderServiceTest.java', 1, 0, 50,
                      'source', 'current', '.', 'test')""");
                    handle.execute(
                            """
                    INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                      type_name, parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'process', 'process(int):void', '(I)V',
                      'void', '["int"]', 'public', '[]'),
                      (1, 'METHOD', 'process', 'process(java.lang.String):void',
                      '(Ljava/lang/String;)V', 'void', '["java.lang.String"]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls(from_class_id, from_method, from_descriptor,
                      to_class_id, to_method, to_descriptor, invocation_kind,
                      occurrence_count, evidence_lines)
                    VALUES (3, 'first', '()V', 1, 'process', '(I)V', 'virtual', 3, '[11,12,13]'),
                      (5, 'testProcess', '()V', 1, 'process', '(I)V', 'virtual', 2, '[20,21]'),
                      (4, 'differentOverload', '()V', 1, 'process', '(Ljava/lang/String;)V',
                      'virtual', 1, '[31]')""");
                    handle.execute(
                            """
                    INSERT INTO dependencies(from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (3, 1, 'CALLS', 3, '[11,12,13]'), (5, 1, 'CALLS', 2, '[20,21]'),
                      (4, 1, 'CALLS', 1, '[31]')""");
                });
        var queries = new QuillToolQueries();
        JsonNode first =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findImpactedTests(
                                                readScope,
                                                List.of(
                                                        SymbolContract.id(
                                                                "org.acme.OrderService",
                                                                "METHOD",
                                                                "process",
                                                                "(I)V")),
                                                true,
                                                3,
                                                1,
                                                0)));
        JsonNode second =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findImpactedTests(
                                                readScope,
                                                List.of(
                                                        SymbolContract.id(
                                                                "org.acme.OrderService",
                                                                "METHOD",
                                                                "process",
                                                                "(I)V")),
                                                true,
                                                3,
                                                1,
                                                1)));
        assertEquals("selected_method_reference_page", first.path("test_ranking_scope").asText());
        assertFalse(first.path("runtime_coverage_measured").asBoolean(true));
        JsonNode refs = first.path("selected_method_references");
        assertEquals("(I)V", refs.path("descriptor").asText());
        assertEquals(2, refs.path("total").asInt());
        assertTrue(refs.path("has_more").asBoolean());
        assertEquals(1, second.path("selected_method_references").path("offset").asInt());
        assertNotEquals(
                refs.path("usages").get(0).path("caller"),
                second.path("selected_method_references").path("usages").get(0).path("caller"));
        JsonNode all =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findImpactedTests(
                                                readScope,
                                                List.of(
                                                        SymbolContract.id(
                                                                "org.acme.OrderService",
                                                                "METHOD",
                                                                "process",
                                                                "(I)V")),
                                                true,
                                                3,
                                                20,
                                                0)));
        assertEquals(
                5,
                all.path("selected_method_references")
                        .path("usages")
                        .valueStream()
                        .mapToInt(value -> value.path("occurrence_count").asInt())
                        .sum());
        assertEquals("org.acme.OrderServiceTest", all.path("tests").get(0).path("class").asText());
        JsonNode old =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findImpactedTests(
                                                readScope,
                                                List.of("OrderService"),
                                                true,
                                                3,
                                                20,
                                                0)));
        assertEquals(old.path("tests"), all.path("class_level_candidates").path("tests"));
        assertEquals(
                2, all.path("tests").get(0).path("invocation_instruction_occurrences").asInt());
        assertEquals(
                "AMBIGUOUS_SYMBOL",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findImpactedTests(
                                                        readScope,
                                                        List.of("OrderService#process"),
                                                        true,
                                                        3,
                                                        20,
                                                        0)))
                        .path("error_code")
                        .asText());
        assertEquals(
                "SYMBOL_NOT_FOUND",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findImpactedTests(
                                                        readScope,
                                                        List.of(
                                                                SymbolContract.id(
                                                                        "org.acme.OrderService",
                                                                        "METHOD",
                                                                        "process",
                                                                        "(J)V")),
                                                        true,
                                                        3,
                                                        20,
                                                        0)))
                        .path("error_code")
                        .asText());
        assertEquals(
                "INVALID_ARGUMENT",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findImpactedTests(
                                                        readScope,
                                                        List.of(
                                                                "OrderService#process",
                                                                "AuditService"),
                                                        true,
                                                        3,
                                                        20,
                                                        0)))
                        .path("error_code")
                        .asText());
        assertEquals(
                "INVALID_ARGUMENT",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findImpactedTests(
                                                        readScope,
                                                        List.of("OrderService#"),
                                                        true,
                                                        3,
                                                        20,
                                                        0)))
                        .path("error_code")
                        .asText());
        assertEquals(
                "UNSUPPORTED_SYMBOL_ID_VERSION",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findImpactedTests(
                                                        readScope,
                                                        List.of("quill:symbol:v0:any"),
                                                        true,
                                                        3,
                                                        20,
                                                        0)))
                        .path("error_code")
                        .asText());
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                  type_name, parameter_types, modifiers, annotations)
                VALUES (1, 'METHOD', 'unique', 'unique():void', '()V', 'void', '[]', 'public', '[]')"""));
        JsonNode unique =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findImpactedTests(
                                                readScope,
                                                List.of("OrderService#unique"),
                                                true,
                                                3,
                                                20,
                                                0)));
        assertEquals("unique", unique.path("selected_method_references").path("name").asText());
        assertEquals(0, unique.path("selected_method_references").path("total").asInt());
        assertEquals(old.path("tests"), unique.path("class_level_candidates").path("tests"));
        assertTrue(unique.path("tests").isEmpty());
        JsonNode typed =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findImpactedTests(
                                                readScope,
                                                List.of("OrderService#process( int )"),
                                                true,
                                                3,
                                                20,
                                                0)));
        assertEquals("(I)V", typed.path("selected_method_references").path("descriptor").asText());
        assertEquals(
                "SYMBOL_NOT_FOUND",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findImpactedTests(
                                                        readScope,
                                                        List.of("OrderService#process(long)"),
                                                        true,
                                                        3,
                                                        20,
                                                        0)))
                        .path("error_code")
                        .asText());
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                  type_name, parameter_types, modifiers, annotations)
                VALUES (1, 'METHOD', 'process', 'process(int):java.lang.String', '(I)Ljava/lang/String;',
                  'java.lang.String', '["int"]', 'public', '[]')"""));
        assertEquals(
                "AMBIGUOUS_SYMBOL",
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                queries.findImpactedTests(
                                                        readScope,
                                                        List.of("OrderService#process(int)"),
                                                        true,
                                                        3,
                                                        20,
                                                        0)))
                        .path("error_code")
                        .asText());
    }

    @Test
    void methodTestImpactPreservesSourceIdentityForDuplicateClasses() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes(class_name, kind, interfaces, source_file, module, source_set)
                    VALUES ('org.acme.OrderService', 'CLASS', '[]',
                      'alt/src/main/java/org/acme/OrderService.java', 'alt', 'main')""");
                    handle.execute(
                            """
                    INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                      type_name, parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'work', 'work():void', '()V', 'void', '[]', 'public', '[]')""");
                });
        var cls =
                org.treblereel.mcp.db.IndexReader.findClassesByName(jdbi, "org.acme.OrderService")
                        .stream()
                        .filter(value -> value.sourceFile().startsWith("src/"))
                        .findFirst()
                        .orElseThrow();
        String id = SymbolContract.id(cls, "METHOD", "work", "()V");
        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findImpactedTests(
                                                        readScope, List.of(id), true, 3, 10, 0)));
        assertFalse(result.has("error_code"), result.toString());
        assertEquals(id, result.path("selected_method_references").path("symbol_id").asText());
        assertFalse(result.path("answer_complete").asBoolean());
        assertTrue(result.path("tests").isEmpty());
    }

    @Test
    void methodTestImpactDistinguishesCallbackReferencesFromInvocations() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes(class_name, kind, interfaces, source_file, source_set)
                    VALUES ('org.acme.CallbackTest', 'CLASS', '[]', 'src/test/java/org/acme/CallbackTest.java', 'test')""");
                    handle.execute(
                            """
                    INSERT INTO class_members(class_id, kind, name, signature, descriptor,
                      type_name, parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'work', 'work():void', '()V', 'void', '[]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls(from_class_id, from_method, from_descriptor,
                      to_class_id, to_method, to_descriptor, invocation_kind, occurrence_count, evidence_lines)
                    VALUES (5, 'callback', '()V', 1, 'work', '()V', 'dynamic', 1, '[11]'),
                      (5, 'invoke', '()V', 1, 'work', '()V', 'virtual', 2, '[20,21]')""");
                    handle.execute(
                            "INSERT INTO metadata(key,value) VALUES ('invokedynamic_evidence_protocol','2')");
                });
        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findImpactedTests(
                                                        readScope,
                                                        List.of("OrderService#work()"),
                                                        true,
                                                        3,
                                                        10,
                                                        0)));
        assertFalse(result.has("error_code"), result.toString());
        assertEquals(1, result.path("tests").size());
        assertEquals(3, result.path("tests").get(0).path("reference_occurrences").asInt());
        assertEquals(
                2, result.path("tests").get(0).path("invocation_instruction_occurrences").asInt());
        assertEquals(2, result.path("tests").get(0).path("reference_indexes").size());
        assertEquals(0, result.path("unclassified_reference_groups").asInt());
    }

    @Test
    void findImpactedTestsReportsMissingCompiledTestCoverage() throws Exception {
        JsonNode result =
                JSON.readTree(
                        JDBI_READS.findImpactedTests(
                                jdbi, List.of("AuditService"), true, 3, 10, 0));
        JsonNode invalid =
                JSON.readTree(JDBI_READS.findImpactedTests(jdbi, List.of(), true, 3, 10, 0));

        assertFalse(result.path("compiled_test_outputs_indexed").asBoolean());
        assertTrue(result.path("limitations").get(0).asText().contains("No compiled test classes"));
        assertEquals("At least one target is required", invalid.path("message").asText());
    }

    @Test
    void compareDesignImpactRanksExistingHostsAndPreservesSemanticCaveat() throws Exception {
        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .compareDesignImpact(
                                                        readScope,
                                                        List.of("OrderService", "AuditService"),
                                                        3)));

        assertEquals(2, result.path("candidates").size());
        assertEquals(1, result.path("candidates").get(0).path("rank").asInt());
        assertTrue(result.path("candidates").get(0).has("comparison_score"));
        assertTrue(result.path("recommendation").asText().contains("lifecycle"));
        assertTrue(
                result.path("limitations")
                        .valueStream()
                        .anyMatch(value -> value.asText().contains("semantic cohesion")));
    }

    @Test
    void getRiskReturnsScoreAndSignals() throws Exception {
        String result = JDBI_READS.getRisk(jdbi, "StripePaymentService");
        JsonNode root = JSON.readTree(result);

        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        assertEquals("class", root.get("target_type").asText());
        assertTrue(root.has("risk_score"));
        assertTrue(root.get("risk_score").asDouble() >= 0);
        assertTrue(root.get("risk_score").asDouble() <= 10);

        String level = root.get("risk_level").asText();
        assertTrue(List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(level));

        JsonNode signals = root.get("signals");
        assertNotNull(signals);
        assertTrue(signals.has("fan_in"));
        assertTrue(signals.has("fan_out"));
        assertTrue(signals.has("git_churn"));
        assertTrue(signals.has("bus_factor"));
        assertTrue(signals.has("coupling"));

        assertTrue(signals.get("git_churn").get("value").asInt() >= 1);

        String recommendation = root.get("recommendation").asText();
        assertNotNull(recommendation);
        assertFalse(recommendation.isEmpty());

        assertTrue(root.has("_meta"));

        JsonNode sourcePath =
                JSON.readTree(
                        JDBI_READS.getRisk(
                                jdbi, "src/main/java/org/acme/StripePaymentService.java"));
        assertEquals("class", sourcePath.path("target_type").asText());
        assertEquals("org.acme.StripePaymentService", sourcePath.path("target").asText());
    }

    @Test
    void getRiskWithoutGitDataExcludesGitSignals() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit-risk.db"));
        var classes =
                List.of(
                        new ClassRecord(
                                0,
                                "org.acme.Bar",
                                "CLASS",
                                null,
                                List.of(),
                                "Bar.java",
                                1,
                                true,
                                100));
        var beans =
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
                                List.of("Bar")));
        IndexWriter.writeIncremental(
                noGitJdbi,
                new IndexFixture()
                        .classes(classes)
                        .beans(beans)
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-08-26T14:30:00",
                                        "last_commit",
                                        "unknown"))
                        .snapshot());

        String result = JDBI_READS.getRisk(noGitJdbi, "Bar");
        JsonNode root = JSON.readTree(result);

        assertEquals("org.acme.Bar", root.get("target").asText());
        assertTrue(root.has("risk_score"));

        JsonNode signals = root.get("signals");
        assertTrue(signals.has("fan_in"));
        assertTrue(signals.has("fan_out"));
        assertTrue(signals.has("git"));
        assertTrue(signals.get("git").get("note").asText().contains("unavailable"));

        assertTrue(root.get("recommendation").asText().contains("Git data unavailable"));
    }

    @Test
    void getRiskClassNotFound() {
        String result = JDBI_READS.getRisk(jdbi, "NonExistentClass");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Class not found"));
    }
}
