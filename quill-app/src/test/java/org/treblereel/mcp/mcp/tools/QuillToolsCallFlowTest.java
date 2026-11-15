package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.*;

/** Call hierarchy, state lifecycle and execution order. */
class QuillToolsCallFlowTest extends QuillToolsTestBase {

    @Test
    void getCallHierarchyReturnsMethodLevelInboundAndOutboundEvidence() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'createOrder',
                            'createOrder(java.lang.String):org.acme.Order',
                            '(Ljava/lang/String;)Lorg/acme/Order;', 'org.acme.Order',
                            '["java.lang.String"]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (1, 'createOrder', '(Ljava/lang/String;)Lorg/acme/Order;',
                            4, 'audit', '(Lorg/acme/Order;)V', 'virtual', 2, '[21,24]'),
                           (3, 'charge', '()V', 1, 'createOrder',
                            '(Ljava/lang/String;)Lorg/acme/Order;', 'virtual', 1, '[42]'),
                           (4, 'audit', '(Lorg/acme/Order;)V', 2, 'notify',
                            '()V', 'interface', 1, '[17]')""");
                    handle.execute(
                            """
                    INSERT INTO kotlin_declarations
                      (class_id, kind, name, jvm_name, descriptor, semantic_model,
                       is_suspend)
                    VALUES (1, 'FUNCTION', 'createKotlinOrder', 'createOrder',
                            '(Ljava/lang/String;)Lorg/acme/Order;',
                            'kotlin_metadata', 1)""");
                });

        JsonNode result =
                JDBI_READS.getCallHierarchy(jdbi, "OrderService", "createOrder", "both", 10, 0);
        JsonNode invalid =
                JDBI_READS.getCallHierarchy(jdbi, "OrderService", null, "sideways", 10, 0);
        JsonNode transitive =
                JDBI_READS.getCallHierarchy(
                        jdbi, "OrderService", "createOrder", "outbound", true, 2, 10, 0);
        JsonNode kotlinResult =
                JDBI_READS.getCallHierarchy(
                        jdbi, "OrderService", "createKotlinOrder", "both", 10, 0);
        JsonNode byId =
                JDBI_READS.getCallHierarchy(
                        jdbi, kotlinResult.path("symbol_id").asText(), null, "both", 10, 0);

        assertEquals(2, result.path("total").asInt());
        assertTrue(result.path("declared_method_found").asBoolean());
        assertTrue(result.path("direct_only").asBoolean());
        assertTrue(
                result.path("calls")
                        .valueStream()
                        .anyMatch(
                                call ->
                                        call.path("caller")
                                                        .path("class")
                                                        .asText()
                                                        .equals("org.acme.StripePaymentService")
                                                && call.path("callee")
                                                        .path("method")
                                                        .asText()
                                                        .equals("createOrder")));
        JsonNode outbound =
                result.path("calls")
                        .valueStream()
                        .filter(call -> call.path("callee").path("method").asText().equals("audit"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                List.of(21, 24),
                outbound.path("evidence_lines").valueStream().map(JsonNode::asInt).toList());
        assertTrue(
                outbound.path("callee")
                        .path("descriptor")
                        .asText()
                        .startsWith("(Lorg/acme/Order;"));
        assertFalse(outbound.path("callee").has("parameters"));
        assertTrue(invalid.path("message").asText().contains("Invalid direction"));
        assertEquals(2, transitive.path("total").asInt());
        assertFalse(transitive.path("direct_only").asBoolean());
        assertEquals(2, transitive.path("max_depth").asInt());
        JsonNode indirect =
                transitive
                        .path("calls")
                        .valueStream()
                        .filter(
                                call ->
                                        call.path("callee")
                                                .path("method")
                                                .asText()
                                                .equals("notify"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, indirect.path("depth").asInt());
        assertEquals("outbound", indirect.path("traversal_direction").asText());
        assertEquals(3, indirect.path("path").size());
        assertEquals(2, kotlinResult.path("total").asInt(), kotlinResult.toPrettyString());
        assertEquals("kotlin", kotlinResult.path("language").asText());
        assertEquals("createKotlinOrder", kotlinResult.path("kotlin_name").asText());
        assertEquals("createOrder", kotlinResult.path("jvm_name").asText());
        assertEquals(kotlinResult.path("symbol_id").asText(), byId.path("symbol_id").asText());
        assertTrue(kotlinResult.path("suspend").asBoolean());
    }

    @Test
    void getCallHierarchyRequiresExactOverloadSelection() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'process', 'process(int):void', '(I)V', 'void',
                            '["int"]', 'public', '[]'),
                           (1, 'METHOD', 'process', 'process(java.lang.String):void',
                            '(Ljava/lang/String;)V', 'void', '["java.lang.String"]',
                            'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (3, 'first', '()V', 1, 'process', '(I)V',
                            'virtual', 1, '[31]'),
                           (3, 'second', '()V', 1, 'process',
                            '(Ljava/lang/String;)V', 'virtual', 1, '[37]')""");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode ambiguous =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getCallHierarchy(
                                        readScope,
                                        "OrderService",
                                        "process",
                                        null,
                                        "inbound",
                                        false,
                                        1,
                                        10,
                                        0));
        assertTrue(ambiguous.path("message").asText().startsWith("Ambiguous method"));
        assertEquals(2, ambiguous.path("candidates").size());
        String candidateId = ambiguous.path("candidates").get(0).path("symbol_id").asText();
        assertTrue(candidateId.startsWith("quill:symbol:v3:"));
        JsonNode byId =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getCallHierarchy(
                                        readScope,
                                        candidateId,
                                        null,
                                        null,
                                        "inbound",
                                        false,
                                        1,
                                        10,
                                        0));
        assertFalse(byId.has("error"));
        assertEquals(candidateId, byId.path("symbol_id").asText());

        JsonNode exact =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getCallHierarchy(
                                        readScope,
                                        "OrderService",
                                        "process",
                                        "(I)V",
                                        "inbound",
                                        false,
                                        1,
                                        10,
                                        0));
        assertEquals("(I)V", exact.path("descriptor").asText());
        assertEquals("process(int):void", exact.path("signature").asText());
        JsonNode parameters =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getCallHierarchy(
                                        readScope,
                                        "OrderService",
                                        "process",
                                        "( int )",
                                        "inbound",
                                        false,
                                        1,
                                        10,
                                        0));
        assertEquals(exact.path("symbol_id"), parameters.path("symbol_id"));
        assertEquals(exact.path("calls"), parameters.path("calls"));
        assertEquals(1, exact.path("total").asInt());
        assertEquals(
                List.of(31),
                exact.path("calls")
                        .get(0)
                        .path("evidence_lines")
                        .valueStream()
                        .map(JsonNode::asInt)
                        .toList());

        for (String selector :
                List.of(
                        "process(java.lang.String)",
                        "(java.lang.String)",
                        "process(String)",
                        "(String)")) {
            JsonNode source =
                    ReadQueryContext.readWithGraph(
                            jdbi,
                            context ->
                                    queries.getCallHierarchy(
                                            context,
                                            "OrderService",
                                            "process",
                                            selector,
                                            "inbound",
                                            false,
                                            1,
                                            10,
                                            0));
            assertEquals("(Ljava/lang/String;)V", source.path("descriptor").asText(), selector);
            assertEquals(
                    List.of(37),
                    source.path("calls")
                            .get(0)
                            .path("evidence_lines")
                            .valueStream()
                            .map(JsonNode::asInt)
                            .toList());
        }

        JsonNode missing =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getCallHierarchy(
                                        readScope,
                                        "OrderService",
                                        "process",
                                        "(J)V",
                                        "inbound",
                                        false,
                                        1,
                                        10,
                                        0));
        assertEquals("Method not found", missing.path("message").asText());
        assertEquals(2, missing.path("candidates").size());
    }

    @Test
    void getCallHierarchyCanSuppressIntraClassNoiseBeforePaging() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO method_calls
                  (from_class_id, from_method, from_descriptor,
                   to_class_id, to_method, to_descriptor, invocation_kind,
                   occurrence_count, evidence_lines)
                VALUES (1, 'execute', '()V', 1, 'helper', '()V', 'special', 1, '[10]'),
                       (1, 'execute', '()V', 4, 'audit', '()V', 'virtual', 1, '[11]')"""));

        JsonNode result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .getCallHierarchy(
                                                readScope,
                                                "OrderService",
                                                null,
                                                null,
                                                "outbound",
                                                false,
                                                1,
                                                "cross_class",
                                                1,
                                                0));

        assertEquals("cross_class", result.path("scope").asText());
        assertEquals(1, result.path("total").asInt());
        assertEquals(1, result.path("showing").asInt());
        assertEquals(1, result.path("suppressed_call_count").asInt());
        assertEquals(
                "org.acme.AuditService",
                result.path("calls").get(0).path("callee").path("class").asText());
    }

    @Test
    void traceStateLifecycleCorrelatesDurabilityAndDispatchEvidenceWithoutClaimingOrder()
            throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            "UPDATE classes SET class_name = 'org.acme.CasePersistenceSession' WHERE id = 3");
                    handle.execute(
                            "UPDATE classes SET class_name = 'org.acme.WorkerBatchDispatcher' WHERE id = 4");
                    handle.execute(
                            "UPDATE classes SET class_name = 'org.acme.CaseRecoveryTimer' WHERE id = 2");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'CONSTRUCTOR', 'OrderService', 'OrderService()', '()V',
                            'org.acme.OrderService', '[]', 'public', '[]'),
                           (1, 'FIELD', 'plan', 'plan:java.lang.String',
                            'Ljava/lang/String;', 'java.lang.String', '[]', 'private', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (3, 'persistPlan', '()V', 1, '<init>', '()V', 'special', 1, '[12]')""");
                    handle.execute(
                            """
                    INSERT INTO field_accesses
                      (from_class_id, from_method, from_descriptor, to_class_id,
                       field_name, field_descriptor, access_kind, occurrence_count, evidence_lines)
                    VALUES (3, 'savePlan', '()V', 1, 'plan', 'Ljava/lang/String;',
                            'write_instance', 1, '[20]'),
                           (4, 'dispatchBatch', '()V', 1, 'plan', 'Ljava/lang/String;',
                            'read_instance', 1, '[31]'),
                           (2, 'recoverTimer', '()V', 1, 'plan', 'Ljava/lang/String;',
                            'read_instance', 1, '[42]')""");
                });

        JsonNode result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .traceStateLifecycle(readScope, "OrderService", 20));

        assertTrue(
                result.path("inferred_roles")
                        .valueStream()
                        .anyMatch(
                                role -> role.asText().equals("durable_execution_state_candidate")));
        assertTrue(
                result.path("inferred_roles")
                        .valueStream()
                        .anyMatch(role -> role.asText().equals("recovery_state_candidate")));
        assertFalse(result.path("ordered_lifecycle_proven").asBoolean());
        assertEquals(1, result.path("semantic_boundaries").path("dispatch").size());
        assertEquals("high", result.path("inference_confidence").asText());
    }

    @Test
    void analyzeExecutionOrderProvesInstructionOrderWithoutClaimingRuntimeProof() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'run', 'run():void', '()V', 'void',
                            '[]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines, instruction_ordinals)
                    VALUES (1, 'run', '()V', 3, 'persistPlan', '()V', 'virtual', 1,
                            '[20]', '[4]'),
                           (1, 'run', '()V', 4, 'dispatchBatch', '()V', 'virtual', 1,
                            '[21]', '[9]')""");
                    handle.execute(
                            """
                    INSERT INTO kotlin_declarations
                      (class_id, kind, name, jvm_name, descriptor, semantic_model,
                       is_suspend)
                    VALUES (1, 'FUNCTION', 'executeOrders', 'run', '()V',
                            'kotlin_metadata', 1)""");
                });

        JsonNode result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope, "OrderService", "run", null));

        assertEquals(2, result.path("event_count").asInt());
        JsonNode noArguments =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope, "OrderService", "run", "()"));
        assertEquals(result.path("bytecode_sequence"), noArguments.path("bytecode_sequence"));
        JsonNode hashTarget =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope, "OrderService#run()", null, null));
        assertEquals(
                result.path("bytecode_sequence"),
                hashTarget.path("bytecode_sequence"),
                hashTarget.toString());
        JsonNode hashName =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope, "OrderService#run", null, null));
        assertEquals(
                result.path("bytecode_sequence"),
                hashName.path("bytecode_sequence"),
                hashName.toString());
        assertEquals(
                4, result.path("bytecode_sequence").get(0).path("instruction_ordinal").asInt());
        assertEquals(
                "proven",
                result.path("persist_before_dispatch").path("instruction_order_status").asText());
        assertEquals(
                "proven_on_normal_completion",
                result.path("persist_before_dispatch").path("runtime_order_status").asText());
        assertTrue(result.path("control_flow").path("straight_line").asBoolean());
        JsonNode byId =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope,
                                                result.path("symbol_id").asText(),
                                                null,
                                                null));
        assertEquals(result.path("symbol_id").asText(), byId.path("symbol_id").asText());

        JsonNode custom =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope,
                                                "OrderService",
                                                "run",
                                                null,
                                                Set.of("persistplan"),
                                                Set.of("dispatchbatch")));
        JsonNode kotlin =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope, "OrderService", "executeOrders", null));
        assertEquals(
                "proven",
                custom.path("ordering_analysis").path("instruction_order_status").asText());
        assertEquals(
                List.of("persistplan"),
                custom.path("ordering_analysis")
                        .path("before_terms")
                        .valueStream()
                        .map(JsonNode::asText)
                        .toList());
        assertFalse(custom.has("persist_before_dispatch"));
        assertEquals(2, kotlin.path("event_count").asInt());
        assertEquals("kotlin", kotlin.path("language").asText());
        assertEquals("executeOrders", kotlin.path("kotlin_name").asText());
        assertEquals("run", kotlin.path("jvm_name").asText());
        assertTrue(kotlin.path("suspend").asBoolean());
    }

    @Test
    void analyzeExecutionOrderUsesCfgDominanceAcrossBranches() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'branchingRun', 'branchingRun():void', '()V',
                            'void', '[]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor, to_class_id, to_method,
                       to_descriptor, invocation_kind, occurrence_count, evidence_lines,
                       instruction_ordinals)
                    VALUES (1, 'branchingRun', '()V', 3, 'persistPlan', '()V', 'virtual',
                            1, '[20]', '[2]'),
                           (1, 'branchingRun', '()V', 4, 'dispatchBatch', '()V', 'virtual',
                            1, '[24]', '[7]')""");
                    handle.execute(
                            """
                    INSERT INTO method_evidence
                      (from_class_id, from_method, from_descriptor, caller_branch_count,
                       caller_exception_handler_count, caller_control_flow_edges)
                    VALUES (1, 'branchingRun', '()V', 1, 0, '["1>2","2>3","3>4","3>6","4>5","5>7","6>7"]')""");
                });

        JsonNode result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope, "OrderService", "branchingRun", null));

        assertEquals(
                "proven_on_all_cfg_paths",
                result.path("ordering_analysis").path("runtime_order_status").asText());
        assertTrue(result.path("control_flow").path("dominance_proven").asBoolean());
        assertEquals(7, result.path("control_flow").path("edge_count").asInt());
    }

    @Test
    void analyzeExecutionOrderRejectsBypassAndIncludesExceptionalPaths() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'bypassRun', 'bypassRun():void', '()V',
                            'void', '[]', 'public', '[]'),
                           (1, 'METHOD', 'guardedRun', 'guardedRun():void', '()V',
                            'void', '[]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor, to_class_id, to_method,
                       to_descriptor, invocation_kind, occurrence_count, evidence_lines,
                       instruction_ordinals)
                    VALUES (1, 'bypassRun', '()V', 3, 'persistPlan', '()V', 'virtual',
                            1, '[20]', '[2]'),
                           (1, 'bypassRun', '()V', 4, 'dispatchBatch', '()V', 'virtual',
                            1, '[24]', '[5]'),
                           (1, 'guardedRun', '()V', 3, 'persistPlan', '()V', 'virtual',
                            1, '[30]', '[2]'),
                           (1, 'guardedRun', '()V', 4, 'dispatchBatch', '()V', 'virtual',
                            1, '[36]', '[7]')""");
                    handle.execute(
                            """
                    INSERT INTO method_evidence
                      (from_class_id, from_method, from_descriptor, caller_branch_count,
                       caller_exception_handler_count, caller_control_flow_edges)
                    VALUES (1, 'bypassRun', '()V', 1, 0, '["1>2","1>4","2>3","3>5","4>5"]'),
                           (1, 'guardedRun', '()V', 0, 1, '["1>2","2>3","3>4","3>5","4>7","5>6","6>7"]')""");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode bypass =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.analyzeExecutionOrder(
                                        readScope, "OrderService", "bypassRun", null));
        assertEquals(
                "likely", bypass.path("ordering_analysis").path("runtime_order_status").asText());
        assertFalse(bypass.path("control_flow").path("dominance_proven").asBoolean());

        JsonNode guarded =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.analyzeExecutionOrder(
                                        readScope, "OrderService", "guardedRun", null));
        assertEquals(
                "proven_on_all_cfg_paths",
                guarded.path("ordering_analysis").path("runtime_order_status").asText());
        assertTrue(guarded.path("control_flow").path("exceptional_edges_included").asBoolean());

        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                UPDATE method_evidence
                SET caller_async_boundaries =
                    '["java.util.concurrent.Executor.execute"]',
                    caller_external_calls =
                    '["4|33|java.util.concurrent.Executor|execute|(Ljava/lang/Runnable;)V|scheduling"]'
                WHERE from_class_id = 1 AND from_method = 'guardedRun'"""));
        JsonNode async =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.analyzeExecutionOrder(
                                        readScope, "OrderService", "guardedRun", null));
        assertEquals(
                "invocation_order_proven_completion_unknown",
                async.path("ordering_analysis").path("runtime_order_status").asText());
        assertTrue(async.path("async_semantics").path("completion_order_unknown").asBoolean());
        assertEquals(1, async.path("async_semantics").path("phases").path("scheduling").asInt());
        assertTrue(
                async.path("bytecode_sequence")
                        .valueStream()
                        .anyMatch(
                                event ->
                                        event.path("external").asBoolean()
                                                && event.path("source_line").asInt() == 33
                                                && event.path("source")
                                                        .asText()
                                                        .endsWith("OrderService.java")));
    }

    @Test
    void analyzeExecutionOrderHandlesLoopsMultipleDispatchesAndEarlyExit() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'loopRun', 'loopRun():void', '()V',
                            'void', '[]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor, to_class_id, to_method,
                       to_descriptor, invocation_kind, occurrence_count, evidence_lines,
                       instruction_ordinals)
                    VALUES (1, 'loopRun', '()V', 3, 'persistPlan', '()V', 'virtual',
                            1, '[40]', '[2]'),
                           (1, 'loopRun', '()V', 4, 'dispatchBatch', '()V', 'virtual',
                            2, '[42,46]', '[4,7]')""");
                    handle.execute(
                            """
                    INSERT INTO method_evidence
                      (from_class_id, from_method, from_descriptor, caller_branch_count,
                       caller_exception_handler_count, caller_control_flow_edges)
                    VALUES (1, 'loopRun', '()V', 2, 0, '["1>2","1>9","2>3","3>4","4>5","5>3","5>6","6>7","7>8"]')""");
                });

        JsonNode result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .analyzeExecutionOrder(
                                                readScope, "OrderService", "loopRun", null));

        assertEquals(
                "proven_on_all_cfg_paths",
                result.path("ordering_analysis").path("runtime_order_status").asText());
        assertEquals(2, result.path("ordering_analysis").path("after_event_count").asInt());
        assertTrue(result.path("control_flow").path("dominance_proven").asBoolean());
    }
}
