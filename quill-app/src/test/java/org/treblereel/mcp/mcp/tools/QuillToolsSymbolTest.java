package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.semantic.SymbolContract;
import org.treblereel.mcp.model.*;

/** Symbol lookup, hierarchy, annotation and override tools. */
class QuillToolsSymbolTest extends QuillToolsTestBase {

    @Test
    void getSymbolDetailsCombinesMembersDiAndDependencyMetrics() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'jakarta.enterprise.context.ApplicationScoped', 1, NULL)""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'FIELD', 'paymentService',
                            'paymentService:org.acme.PaymentService',
                            'org.acme.PaymentService', '[]', '', '["jakarta.inject.Inject"]')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'METHOD', 'createOrder',
                            'createOrder(java.lang.String):org.acme.Order',
                            'org.acme.Order', '["java.lang.String"]', 'public', '[]')""");
                });

        JsonNode first = JDBI_READS.getSymbolDetails(jdbi, "OrderService", true, null, 1, 0);
        JsonNode methods = JDBI_READS.getSymbolDetails(jdbi, "OrderService", true, "method", 10, 0);

        assertEquals("org.acme.OrderService", first.path("class").asText());
        assertEquals("java", first.path("language").asText());
        assertEquals("OrderService", first.path("source_name").asText());
        assertEquals("org.acme.OrderService", first.path("jvm_name").asText());
        assertTrue(first.path("symbol_id").asText().startsWith("quill:symbol:v3:"));
        assertEquals(
                "src/main/java/org/acme/OrderService.java",
                first.path("location").path("path").asText());
        assertEquals("@ApplicationScoped", first.path("bean").path("scope").asText());
        assertEquals(1, first.path("dependency_metrics").path("fan_out").asInt());
        assertEquals(
                "jakarta.enterprise.context.ApplicationScoped",
                first.path("annotations").get(0).asText());
        assertEquals(2, first.path("total").asInt());
        assertTrue(first.path("has_more").asBoolean());
        JsonNode createOrder = methods.path("members").get(0);
        assertEquals("createOrder", createOrder.path("name").asText());
        assertTrue(
                createOrder.path("signature").asText().contains("java.lang.String"),
                createOrder.toString());
        for (String repeated :
                List.of(
                        "symbol_id",
                        "language",
                        "source_name",
                        "jvm_name",
                        "jvm_descriptor",
                        "location",
                        "parameters",
                        "annotation_details")) {
            assertFalse(createOrder.has(repeated), repeated);
        }
        // The fixture records no descriptor, so fill the format with one.
        String id =
                methods.path("member_symbol_id_format")
                        .asText()
                        .replace("{kind}", "method")
                        .replace("{name}", "createOrder")
                        .replace("{descriptor}", "(Ljava/lang/String;)V");
        var reference = SymbolContract.parse(id).orElseThrow();
        assertEquals("org.acme.OrderService", reference.className());
        assertEquals("createOrder", reference.jvmName());
        assertEquals("(Ljava/lang/String;)V", reference.descriptor());
    }

    @Test
    void getSymbolDetailsCanSkipMembersAndValidatesMemberKind() throws Exception {
        JsonNode compact = JDBI_READS.getSymbolDetails(jdbi, "PaymentService", false, null, 10, 0);

        JsonNode invalid =
                JDBI_READS.getSymbolDetails(jdbi, "PaymentService", true, "property", 10, 0);

        assertFalse(compact.path("members_included").asBoolean());
        assertFalse(compact.has("members"));
        assertTrue(invalid.path("message").asText().startsWith("Invalid member_kind"));
    }

    @Test
    void getSymbolDetailsIncludesKotlinSemanticDeclarations() throws Exception {
        IndexFixture.writeKotlinDeclarations(
                jdbi,
                List.of(
                        new KotlinDeclarationRecord(
                                1,
                                "CLASS",
                                "org.acme.OrderService",
                                "org.acme.OrderService",
                                "",
                                "kotlin_metadata",
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false),
                        new KotlinDeclarationRecord(
                                1,
                                "FUNCTION",
                                "createOrder",
                                "createOrder",
                                "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;",
                                "kotlin_metadata",
                                true,
                                true,
                                true,
                                false,
                                false,
                                false,
                                false)));

        JsonNode result = JDBI_READS.getSymbolDetails(jdbi, "OrderService", false, null, 10, 0);

        assertEquals("kotlin", result.path("language").asText());
        assertEquals("OrderService", result.path("source_name").asText());
        assertTrue(result.path("symbol_id").asText().startsWith("quill:symbol:v3:"));
        assertEquals("kotlin_metadata", result.path("semantic_model").asText());
        JsonNode function = result.path("kotlin_declarations").get(1);
        assertEquals("function", function.path("kind").asText());
        assertTrue(function.path("suspend").asBoolean());
        assertTrue(function.path("extension").asBoolean());
        assertTrue(function.path("default_parameters").asBoolean());
    }

    @Test
    void getTypeHierarchyReturnsAncestorAndDescendantPaths() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO classes
                  (class_name, kind, superclass, interfaces, source_file, source_line,
                   is_bean, source_tokens, origin, lifecycle, module, source_set)
                VALUES ('org.acme.PremiumStripePaymentService', 'CLASS',
                        'org.acme.StripePaymentService', '[]',
                        'src/main/java/org/acme/PremiumStripePaymentService.java', 1,
                        0, 50, 'source', 'current', '.', 'main')"""));

        JsonNode descendants =
                JDBI_READS.getTypeHierarchy(jdbi, "PaymentService", "descendants", 5, 10, 0);
        JsonNode ancestors =
                JDBI_READS.getTypeHierarchy(
                        jdbi, "PremiumStripePaymentService", "ancestors", 5, 10, 0);

        assertEquals(2, descendants.path("descendant_count").asInt());
        assertEquals(
                "org.acme.StripePaymentService",
                descendants.path("hierarchy").get(0).path("class").asText());
        assertEquals(1, descendants.path("hierarchy").get(0).path("depth").asInt());
        assertEquals(
                List.of("implements"),
                descendants
                        .path("hierarchy")
                        .get(0)
                        .path("relations")
                        .valueStream()
                        .map(JsonNode::asText)
                        .toList());
        assertEquals(
                "org.acme.PremiumStripePaymentService",
                descendants.path("hierarchy").get(1).path("class").asText());
        assertEquals(2, descendants.path("hierarchy").get(1).path("depth").asInt());
        assertEquals(
                List.of("implements", "extends"),
                descendants
                        .path("hierarchy")
                        .get(1)
                        .path("relations")
                        .valueStream()
                        .map(JsonNode::asText)
                        .toList());

        assertTrue(
                ancestors
                        .path("hierarchy")
                        .valueStream()
                        .anyMatch(
                                node ->
                                        node.path("class")
                                                        .asText()
                                                        .equals("org.acme.PaymentService")
                                                && node.path("depth").asInt() == 2));
        assertTrue(
                ancestors
                        .path("hierarchy")
                        .valueStream()
                        .anyMatch(
                                node ->
                                        node.path("class").asText().equals("java.lang.Object")
                                                && !node.path("indexed").asBoolean()));
    }

    @Test
    void getTypeHierarchyValidatesDirectionAndPaginates() throws Exception {
        JsonNode page = JDBI_READS.getTypeHierarchy(jdbi, "PaymentService", "down", 5, 1, 0);
        JsonNode invalid =
                JDBI_READS.getTypeHierarchy(jdbi, "PaymentService", "sideways", 5, 10, 0);

        assertEquals("descendants", page.path("direction").asText());
        assertEquals(1, page.path("showing").asInt());
        assertEquals(1, page.path("total").asInt());
        assertTrue(invalid.path("message").asText().contains("Invalid direction"));
    }

    @Test
    void searchSymbolsFindsAndFiltersMemberDeclarations() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'METHOD', 'createOrder',
                            'createOrder(java.lang.String):org.acme.Order',
                            'org.acme.Order', '["java.lang.String"]', 'public',
                            '["jakarta.transaction.Transactional"]'),
                           (1, 'FIELD', 'orderRepository',
                            'orderRepository:org.acme.OrderRepository',
                            'org.acme.OrderRepository', '[]', 'private', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO kotlin_declarations
                      (class_id, kind, name, jvm_name, descriptor, semantic_model,
                       is_suspend, has_default_parameters)
                    VALUES (1, 'FUNCTION', 'createKotlinOrder', 'createOrder', '',
                            'kotlin_metadata', 1, 1),
                           (1, 'PROPERTY', 'orders', 'orderRepository', '',
                            'kotlin_metadata', 0, 0)""");
                });

        JsonNode result = JDBI_READS.searchSymbols(jdbi, "order", "method", 10, 0);
        JsonNode invalid = JDBI_READS.searchSymbols(jdbi, "order", "package", 10, 0);

        assertEquals(1, result.path("total").asInt());
        JsonNode symbol = result.path("symbols").get(0);
        assertEquals("method", symbol.path("kind").asText());
        assertEquals("kotlin", symbol.path("language").asText());
        assertEquals("createKotlinOrder", symbol.path("source_name").asText());
        assertEquals("createOrder", symbol.path("jvm_name").asText());
        assertTrue(symbol.path("symbol_id").asText().startsWith("quill:symbol:v3:"));
        assertEquals(
                "src/main/java/org/acme/OrderService.java",
                symbol.path("location").path("path").asText());
        assertEquals("createOrder", symbol.path("name").asText());
        assertEquals("org.acme.OrderService", symbol.path("declaring_class").asText());
        assertEquals("org.acme.Order", symbol.path("type").asText());
        assertTrue(symbol.path("annotations").toString().contains("Transactional"));
        JsonNode kotlinResult =
                JDBI_READS.searchSymbols(jdbi, "createKotlinOrder", "method", 10, 0);
        JsonNode kotlinOnly = JDBI_READS.searchSymbols(jdbi, "order", null, "kotlin", 10, 0);
        JsonNode invalidLanguage = JDBI_READS.searchSymbols(jdbi, "order", null, "scala", 10, 0);
        JsonNode property =
                JDBI_READS
                        .searchSymbols(jdbi, "orders", "field", "kotlin", 10, 0)
                        .path("symbols")
                        .get(0);
        JsonNode kotlinSymbol = kotlinResult.path("symbols").get(0);
        assertEquals("createOrder", kotlinSymbol.path("name").asText());
        assertEquals("kotlin", kotlinSymbol.path("language").asText());
        assertEquals("createKotlinOrder", kotlinSymbol.path("source_name").asText());
        assertEquals("createOrder", kotlinSymbol.path("jvm_name").asText());
        assertEquals("function", kotlinSymbol.path("kotlin_kind").asText());
        assertEquals("createKotlinOrder", kotlinSymbol.path("kotlin_name").asText());
        assertTrue(kotlinSymbol.path("suspend").asBoolean());
        assertTrue(kotlinSymbol.path("default_parameters").asBoolean());
        assertTrue(
                kotlinOnly
                        .path("symbols")
                        .valueStream()
                        .allMatch(value -> value.path("language").asText().equals("kotlin")));
        assertFalse(invalidLanguage.has("error"));
        assertEquals("INVALID_ARGUMENT", invalidLanguage.path("error_code").asText());
        assertTrue(invalidLanguage.path("message").asText().contains("Invalid language"));
        assertFalse(invalidLanguage.path("retryable").asBoolean());
        assertEquals("orders", property.path("source_name").asText());
        assertEquals("property", property.path("source_kind").asText());
        assertEquals("property_field", property.path("jvm_role").asText());
        assertTrue(invalid.path("message").asText().contains("Invalid kind"));
    }

    @Test
    void searchSymbolsDoesNotReportUnknownDeclarationLineAsZero() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute("UPDATE classes SET source_line = 0 WHERE id = 1");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'FIELD', 'orderRepository',
                            'orderRepository:org.acme.OrderRepository',
                            'org.acme.OrderRepository', '[]', 'private', '[]')""");
                });

        JsonNode symbol =
                JDBI_READS
                        .searchSymbols(jdbi, "orderRepository", "field", 10, 0)
                        .path("symbols")
                        .get(0);

        assertEquals("src/main/java/org/acme/OrderService.java", symbol.path("source").asText());
        assertEquals(
                "src/main/java/org/acme/OrderService.java",
                symbol.path("location").path("path").asText());
        assertTrue(symbol.path("location").path("line").isNull());
    }

    @Test
    void indexedMembersDoNotBorrowTheirOwnersDeclarationLine() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO class_members
                  (class_id, kind, name, signature, descriptor, type_name,
                   parameter_types, modifiers, annotations)
                VALUES (1, 'METHOD', 'runPrecisely', 'runPrecisely()', '()V', 'void',
                        '[]', 'public', '[]')"""));
        JsonNode symbol =
                JDBI_READS
                        .searchSymbols(jdbi, "runPrecisely", "method", 10, 0)
                        .path("symbols")
                        .get(0);
        assertTrue(symbol.path("location").path("line").isNull());
        assertEquals("unknown", symbol.path("location").path("basis").asText());
        assertTrue(symbol.path("location").path("class_declaration_line").asInt() > 0);
        assertFalse(symbol.path("source").asText().matches(".*:[0-9]+"));
        JsonNode detail =
                JDBI_READS.getSymbolDetails(jdbi, "org.acme.OrderService", true, "method", 100, 0);
        JsonNode member =
                detail.path("members")
                        .valueStream()
                        .filter(value -> value.path("name").asText().equals("runPrecisely"))
                        .findFirst()
                        .orElseThrow();
        // The class location is stated once; members carry no line evidence of their own.
        assertFalse(member.has("location"));
    }

    @Test
    void searchSymbolsIncludesTypesAndPaginates() throws Exception {
        JsonNode page = JDBI_READS.searchSymbols(jdbi, "Service", null, 2, 1);
        JsonNode blank = JDBI_READS.searchSymbols(jdbi, " ", null, 10, 0);

        assertEquals(4, page.path("total").asInt());
        assertEquals(2, page.path("showing").asInt());
        assertEquals(1, page.path("offset").asInt());
        assertTrue(page.path("has_more").asBoolean());
        assertTrue(blank.path("message").asText().contains("must not be blank"));
    }

    @Test
    void findSymbolUsagesResolvesOverloadsConstructorsAndFieldAccess() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'submit', 'submit(java.lang.String):void',
                            '(Ljava/lang/String;)V', 'void', '["java.lang.String"]',
                            'public', '[]'),
                           (1, 'METHOD', 'submit', 'submit(int):void',
                            '(I)V', 'void', '["int"]', 'public', '[]'),
                           (1, 'CONSTRUCTOR', 'OrderService',
                            'OrderService(java.lang.String)', '(Ljava/lang/String;)V',
                            'org.acme.OrderService', '["java.lang.String"]',
                            'public', '[]'),
                           (1, 'FIELD', 'status', 'status:java.lang.String',
                            'Ljava/lang/String;', 'java.lang.String', '[]',
                            'private', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (3, 'run', '()V', 1, 'submit',
                            '(Ljava/lang/String;)V', 'virtual', 2, '[31,35]'),
                           (4, 'run', '()V', 1, 'submit', '(I)V',
                            'virtual', 1, '[18]'),
                           (3, 'create', '()V', 1, '<init>',
                            '(Ljava/lang/String;)V', 'special', 1, '[12]')""");
                    handle.execute(
                            """
                    INSERT INTO field_accesses
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, field_name, field_descriptor, access_kind,
                       occurrence_count, evidence_lines)
                    VALUES (3, 'read', '()V', 1, 'status', 'Ljava/lang/String;',
                            'read_instance', 2, '[41,44]'),
                           (4, 'write', '()V', 1, 'status', 'Ljava/lang/String;',
                            'write_instance', 1, '[22]')""");
                    handle.execute(
                            """
                    INSERT INTO kotlin_declarations
                      (class_id, kind, name, jvm_name, descriptor, semantic_model,
                       has_default_parameters)
                    VALUES (1, 'FUNCTION', 'submitOrder', 'submit',
                            '(Ljava/lang/String;)V', 'kotlin_metadata', 1)""");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode ambiguous =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "OrderService",
                                        "submit",
                                        "method",
                                        null,
                                        "all",
                                        10,
                                        0));
        assertFalse(ambiguous.has("error"));
        assertEquals("AMBIGUOUS_SYMBOL", ambiguous.path("error_code").asText());
        assertEquals("Ambiguous symbol", ambiguous.path("message").asText());
        assertTrue(ambiguous.path("retryable").asBoolean());
        assertTrue(ambiguous.path("retry_with").path("signature").asText().contains("descriptor"));
        assertEquals(2, ambiguous.path("candidates").size());
        String candidateId = ambiguous.path("candidates").get(0).path("symbol_id").asText();
        assertTrue(candidateId.startsWith("quill:symbol:v3:"));

        JsonNode selectedById =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope, candidateId, null, null, null, "all", 10, 0));
        assertFalse(selectedById.has("error"));
        assertEquals(candidateId, selectedById.path("symbol_id").asText());

        JsonNode method =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "OrderService",
                                        "submit",
                                        "method",
                                        "(Ljava/lang/String;)V",
                                        "all",
                                        10,
                                        0));
        for (String selector :
                List.of("submit(java.lang.String)", "(java.lang.String)", "(String)")) {
            JsonNode source =
                    ReadQueryContext.readWithGraph(
                            jdbi,
                            context ->
                                    queries.findSymbolUsages(
                                            context,
                                            "OrderService",
                                            "submit",
                                            "method",
                                            selector,
                                            "all",
                                            10,
                                            0));
            assertEquals(method.path("symbol_id"), source.path("symbol_id"), selector);
            assertEquals(method.path("usages"), source.path("usages"), selector);
        }
        assertEquals(1, method.path("total").asInt());
        assertEquals("(Ljava/lang/String;)V", method.path("descriptor").asText());
        JsonNode byParameters =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "OrderService",
                                        "submit",
                                        "method",
                                        "(java.lang.String)",
                                        "all",
                                        10,
                                        0));
        assertEquals(method.path("symbol_id"), byParameters.path("symbol_id"));
        assertEquals(method.path("usages"), byParameters.path("usages"));
        assertEquals(
                List.of(31, 35),
                method.path("usages")
                        .get(0)
                        .path("evidence_lines")
                        .valueStream()
                        .map(JsonNode::asInt)
                        .toList());
        assertEquals(
                "org.acme.StripePaymentService",
                method.path("usages").get(0).path("caller").path("class").asText());
        assertTrue(method.path("symbol_id").asText().startsWith("quill:symbol:v3:"));

        JsonNode kotlinMethod =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "OrderService",
                                        "submitOrder",
                                        "method",
                                        null,
                                        "all",
                                        10,
                                        0));
        assertEquals(1, kotlinMethod.path("total").asInt());
        assertEquals("kotlin", kotlinMethod.path("language").asText());
        assertEquals("submitOrder", kotlinMethod.path("kotlin_name").asText());
        assertEquals("submit", kotlinMethod.path("jvm_name").asText());
        assertTrue(kotlinMethod.path("default_parameters").asBoolean());
        JsonNode byId =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        kotlinMethod.path("symbol_id").asText(),
                                        null,
                                        null,
                                        null,
                                        "all",
                                        10,
                                        0));
        assertEquals(kotlinMethod.path("symbol_id").asText(), byId.path("symbol_id").asText());
        assertEquals(1, byId.path("total").asInt());

        JsonNode constructor =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "OrderService",
                                        null,
                                        "constructor",
                                        "OrderService(java.lang.String)",
                                        "all",
                                        10,
                                        0));
        assertEquals(1, constructor.path("total").asInt());
        assertEquals("special", constructor.path("usages").get(0).path("invocation_kind").asText());
        JsonNode constructorParameters =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "OrderService",
                                        null,
                                        "constructor",
                                        "(java.lang.String)",
                                        "all",
                                        10,
                                        0));
        assertEquals(constructor.path("symbol_id"), constructorParameters.path("symbol_id"));

        JsonNode reads =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "OrderService",
                                        "status",
                                        "field",
                                        null,
                                        "read",
                                        10,
                                        0));
        assertEquals(1, reads.path("total").asInt());
        assertEquals("read", reads.path("usages").get(0).path("usage_kind").asText());
        assertEquals(2, reads.path("usages").get(0).path("occurrence_count").asInt());
    }

    @Test
    void staleSymbolIdsFailClosedAndReturnCurrentCandidates() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO class_members
                  (class_id, kind, name, signature, descriptor, type_name,
                   parameter_types, modifiers, annotations)
                VALUES (1, 'METHOD', 'submit', 'submit(int):void', '(I)V', 'void',
                        '["int"]', 'public', '[]')"""));
        QuillToolQueries queries = new QuillToolQueries();

        JsonNode missingClass =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        SymbolContract.id(
                                                "org.acme.RemovedService",
                                                "METHOD",
                                                "submit",
                                                "(I)V"),
                                        null,
                                        null,
                                        null,
                                        "all",
                                        10,
                                        0));
        assertEquals("CLASS_NOT_FOUND", missingClass.path("error_code").asText());

        JsonNode removedMember =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        SymbolContract.id(
                                                "org.acme.OrderService",
                                                "METHOD",
                                                "removed",
                                                "()V"),
                                        null,
                                        null,
                                        null,
                                        "all",
                                        10,
                                        0));
        assertEquals("SYMBOL_NOT_FOUND", removedMember.path("error_code").asText());
        assertFalse(removedMember.has("symbol_id"));

        String staleDescriptor =
                SymbolContract.id("org.acme.OrderService", "METHOD", "submit", "(J)V");
        JsonNode changedUsage =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        staleDescriptor,
                                        null,
                                        null,
                                        null,
                                        "all",
                                        10,
                                        0));
        assertEquals("SYMBOL_NOT_FOUND", changedUsage.path("error_code").asText());
        assertEquals("(I)V", changedUsage.path("candidates").get(0).path("descriptor").asText());
        assertNotEquals(
                staleDescriptor, changedUsage.path("candidates").get(0).path("symbol_id").asText());

        JsonNode changedHierarchy =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getCallHierarchy(
                                        readScope,
                                        staleDescriptor,
                                        null,
                                        null,
                                        "both",
                                        false,
                                        1,
                                        10,
                                        0));
        assertEquals("METHOD_NOT_FOUND", changedHierarchy.path("error_code").asText());
        assertEquals(
                "(I)V", changedHierarchy.path("candidates").get(0).path("descriptor").asText());

        JsonNode futureVersion =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findSymbolUsages(
                                        readScope,
                                        "quill:symbol:v4:anything",
                                        null,
                                        null,
                                        null,
                                        "all",
                                        10,
                                        0));
        assertEquals("UNSUPPORTED_SYMBOL_ID_VERSION", futureVersion.path("error_code").asText());
        assertFalse(futureVersion.has("error"));
    }

    @Test
    void findMethodOverridesHandlesOverloadsAndTransitiveDescendants() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.PremiumStripePaymentService', 'CLASS',
                            'org.acme.StripePaymentService', '[]',
                            'src/main/java/org/acme/PremiumStripePaymentService.java', 1,
                            0, 50, 'source', 'current', '.', 'main')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (2, 'METHOD', 'processPayment',
                            'processPayment(double):void', '(D)V', 'void', '["double"]',
                            'public abstract', '[]'),
                           (2, 'METHOD', 'processPayment',
                            'processPayment(java.lang.String):void',
                            '(Ljava/lang/String;)V', 'void',
                            '["java.lang.String"]', 'public abstract', '[]'),
                           (3, 'METHOD', 'processPayment',
                            'processPayment(double):void', '(D)V', 'void', '["double"]',
                            'public', '[]'),
                           (5, 'METHOD', 'processPayment',
                            'processPayment(double):void', '(D)V', 'void', '["double"]',
                            'public final', '[]'),
                           (5, 'METHOD', 'processPayment',
                            'processPayment(java.lang.String):void',
                            '(Ljava/lang/String;)V', 'void',
                            '["java.lang.String"]', 'public', '[]')""");
                    handle.execute(
                            """
                    INSERT INTO kotlin_declarations
                      (class_id, kind, name, jvm_name, descriptor, semantic_model)
                    VALUES (2, 'FUNCTION', 'processKotlinPayment', 'processPayment',
                            '(D)V', 'kotlin_metadata'),
                           (2, 'FUNCTION', 'processKotlinPayment', 'processPayment',
                            '(Ljava/lang/String;)V', 'kotlin_metadata')""");
                });

        JsonNode all =
                JDBI_READS.findMethodOverrides(
                        jdbi, "PaymentService", "processPayment", null, true, 10, 0);
        JsonNode direct =
                JDBI_READS.findMethodOverrides(
                        jdbi, "PaymentService", "processPayment", null, false, 10, 0);
        JsonNode overload =
                JDBI_READS.findMethodOverrides(
                        jdbi,
                        "PaymentService",
                        "processPayment",
                        "processPayment(java.lang.String):void",
                        true,
                        10,
                        0);
        JsonNode kotlin =
                JDBI_READS.findMethodOverrides(
                        jdbi, "PaymentService", "processKotlinPayment", null, true, 10, 0);

        assertEquals(3, all.path("total").asInt());
        assertEquals(2, all.path("base_declarations").size());
        assertTrue(
                all.path("overrides")
                        .valueStream()
                        .anyMatch(
                                node ->
                                        node.path("class")
                                                        .asText()
                                                        .equals(
                                                                "org.acme.PremiumStripePaymentService")
                                                && node.path("distance").asInt() == 2
                                                && node.path("hierarchy_path").size() == 3));
        assertEquals(1, direct.path("total").asInt());
        assertEquals(1, overload.path("total").asInt());
        JsonNode parameterOverload =
                JDBI_READS.findMethodOverrides(
                        jdbi,
                        "PaymentService",
                        "processPayment",
                        "(java.lang.String)",
                        true,
                        10,
                        0);
        assertEquals(overload.path("overrides"), parameterOverload.path("overrides"));
        assertEquals(
                "processPayment(java.lang.String):void",
                overload.path("overrides").get(0).path("base_signature").asText());
        assertEquals(3, kotlin.path("total").asInt());
        assertEquals("kotlin", kotlin.path("language").asText());
        assertEquals("processKotlinPayment", kotlin.path("kotlin_name").asText());
        assertEquals(
                List.of("processPayment"),
                kotlin.path("jvm_names").valueStream().map(JsonNode::asText).toList());
        String symbolId = kotlin.path("base_declarations").get(0).path("symbol_id").asText();
        JsonNode byId = JDBI_READS.findMethodOverrides(jdbi, symbolId, null, null, true, 10, 0);

        assertEquals(symbolId, byId.path("base_declarations").get(0).path("symbol_id").asText());
    }

    @Test
    void parameterSelectorNeverGuessesBetweenJvmReturnOverloads() throws Exception {
        jdbi.useHandle(
                h ->
                        h.execute(
                                """
                INSERT INTO class_members
                  (class_id, kind, name, signature, descriptor, type_name,
                   parameter_types, modifiers, annotations)
                VALUES (2,'METHOD','get','get():java.lang.String','()Ljava/lang/String;',
                        'java.lang.String','[]','public','[]'),
                       (2,'METHOD','get','get():java.lang.Object','()Ljava/lang/Object;',
                        'java.lang.Object','[]','public','[]')"""));
        JsonNode result =
                JDBI_READS.findMethodOverrides(jdbi, "PaymentService", "get", "()", true, 10, 0);
        assertEquals("AMBIGUOUS_METHOD", result.path("error_code").asText());
        assertEquals(2, result.path("candidates").size());
        assertTrue(result.path("retryable").asBoolean());
    }

    @Test
    void findMethodOverridesReturnsOverloadCandidatesForMissingSignature() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO class_members
                  (class_id, kind, name, signature, type_name, parameter_types,
                   modifiers, annotations)
                VALUES (2, 'METHOD', 'processPayment',
                        'processPayment(double):void', 'void', '["double"]',
                        'public abstract', '[]')"""));

        JsonNode result =
                JDBI_READS.findMethodOverrides(
                        jdbi, "PaymentService", "processPayment", "missing()", true, 10, 0);

        assertEquals("Method signature not found", result.path("message").asText());
        assertEquals("processPayment(double):void", result.path("candidates").get(0).asText());
    }

    @Test
    void memberKindAcceptsItsPlural() throws Exception {
        JsonNode plural =
                JDBI_READS.getSymbolDetails(jdbi, "PaymentService", true, "methods", 10, 0);
        JsonNode singular =
                JDBI_READS.getSymbolDetails(jdbi, "PaymentService", true, "method", 10, 0);

        assertFalse(plural.has("error_code"), plural.toString());
        assertEquals(singular.path("members"), plural.path("members"));
    }

    @Test
    void searchClassesReportsExactTotalAndNextOffset() throws Exception {
        JsonNode root =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .searchClasses(readScope, "org.acme.*", null, null, 1, 0));

        assertEquals(1, root.path("showing").asInt());
        assertEquals(4, root.path("total").asInt());
        assertTrue(root.path("has_more").asBoolean());
        assertEquals(1, root.path("next_offset").asInt());
    }

    @Test
    void searchClassesIncludesOriginAndLifecycle() throws Exception {
        JsonNode result = JDBI_READS.searchClasses(jdbi, "OrderService", 10);

        assertEquals("source", result.path("classes").get(0).path("origin").asText());
        assertEquals("current", result.path("classes").get(0).path("lifecycle").asText());
    }

    @Test
    void getAnnotatedClassesSeparatesDirectAndMetaMatches() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'org.acme.Tracked', 1, NULL)""");
                    handle.execute(
                            """
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (3, 'org.acme.Tracked', 0, 'org.acme.Specialized')""");
                    handle.execute("UPDATE classes SET origin = 'generated' WHERE id = 3");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode all =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getAnnotatedClasses(readScope, "@Tracked", true, 1, 0));
        assertEquals("org.acme.Tracked", all.path("annotation").asText());
        assertEquals(2, all.path("annotated_class_count").asInt());
        assertEquals(1, all.path("direct_class_count").asInt());
        assertEquals(1, all.path("meta_only_class_count").asInt());
        assertEquals(1, all.path("origin_breakdown").path("source").asInt());
        assertEquals(1, all.path("origin_breakdown").path("generated").asInt());
        assertEquals(1, all.path("showing").asInt());
        assertTrue(all.path("has_more").asBoolean());

        JsonNode meta =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getAnnotatedClasses(
                                        readScope, "org.acme.Tracked", true, 10, 1));
        assertEquals("meta", meta.path("classes").get(0).path("match").asText());
        assertEquals(
                "org.acme.Specialized",
                meta.path("classes").get(0).path("via_annotations").get(0).asText());

        JsonNode directOnly =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getAnnotatedClasses(readScope, "Tracked", false, 10, 0));
        assertEquals(1, directOnly.path("annotated_class_count").asInt());
        assertEquals("direct", directOnly.path("classes").get(0).path("match").asText());
    }

    @Test
    void getAnnotatedClassesRejectsAmbiguousShortNames() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'org.acme.Tracked', 1, NULL)""");
                    handle.execute(
                            """
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (2, 'other.Tracked', 1, NULL)""");
                });

        JsonNode result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .getAnnotatedClasses(readScope, "Tracked", true, 10, 0));

        assertEquals("Ambiguous annotation name", result.path("error").asText());
        assertEquals(
                List.of("org.acme.Tracked", "other.Tracked"),
                result.path("candidates").valueStream().map(JsonNode::asText).toList());
    }

    @Test
    void findAnnotatedSymbolsIncludesTypesAndMembers() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'org.acme.Tracked', 0, 'org.acme.Specialized')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations, annotation_details)
                    VALUES (1, 'METHOD', 'submit', 'submit():void', 'void', '[]',
                            'public', '["org.acme.Tracked"]',
                            '[{"annotationName":"org.acme.Tracked","targetKind":"METHOD","parameterIndex":-1,"parameterName":"","parameterType":""}]')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations, annotation_details)
                    VALUES (1, 'METHOD', 'lookup', 'lookup(java.lang.String):void',
                            'void', '["java.lang.String"]', 'public',
                            '["org.acme.Tracked"]',
                            '[{"annotationName":"org.acme.Tracked","targetKind":"METHOD_PARAMETER","parameterIndex":0,"parameterName":"id","parameterType":"java.lang.String"}]')""");
                    handle.execute(
                            """
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (3, 'FIELD', 'audit', 'audit:boolean', 'boolean', '[]',
                            'private', '["org.acme.Tracked"]')""");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode all =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findAnnotatedSymbols(
                                        readScope, "@Tracked", "all", true, 2, 0));
        assertEquals("org.acme.Tracked", all.path("annotation").asText());
        assertEquals(4, all.path("total").asInt());
        assertEquals(2, all.path("showing").asInt());
        assertTrue(all.path("has_more").asBoolean());
        assertEquals("TYPE", all.path("symbols").get(0).path("kind").asText());
        assertEquals("meta", all.path("symbols").get(0).path("match").asText());
        assertEquals(
                "org.acme.Specialized", all.path("symbols").get(0).path("via_annotation").asText());

        JsonNode fields =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findAnnotatedSymbols(
                                        readScope, "org.acme.Tracked", "field", false, 10, 0));
        assertEquals(1, fields.path("total").asInt());
        assertEquals("audit", fields.path("symbols").get(0).path("name").asText());
        assertEquals("direct", fields.path("symbols").get(0).path("match").asText());
        assertEquals("type_declarations_only", fields.path("meta_annotation_scope").asText());
        assertTrue(fields.path("_meta").isObject());

        JsonNode parameters =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.findAnnotatedSymbols(
                                        readScope, "Tracked", "parameter", false, 10, 0));
        assertEquals(1, parameters.path("total").asInt());
        assertEquals("PARAMETER", parameters.path("symbols").get(0).path("kind").asText());
        assertEquals("METHOD", parameters.path("symbols").get(0).path("declared_kind").asText());
        assertEquals(
                "METHOD_PARAMETER",
                parameters.path("symbols").get(0).path("annotation_target").asText());
        assertEquals(0, parameters.path("symbols").get(0).path("parameter").path("index").asInt());
        assertEquals(
                "id", parameters.path("symbols").get(0).path("parameter").path("name").asText());
    }

    @Test
    void structuralQueriesExposeDuplicateOccurrencesAndFilterByOccurrenceModule() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                            1,
                            "app-one",
                            "app-one/target/classes",
                            "app-one/target/classes/org/acme/StripePaymentService.class",
                            "app-one/src/main/java/org/acme/StripePaymentService.java",
                            "source");
                    handle.execute(
                            """
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                            2,
                            "app-two",
                            "app-two/target/classes",
                            "app-two/target/classes/org/acme/StripePaymentService.class",
                            "app-two/target/generated-sources/annotations/org/acme/StripePaymentService.java",
                            "generated");
                });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode search =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.searchClasses(
                                        readScope, "StripePaymentService", "app-two", "main", 10));
        JsonNode found = search.path("classes").get(0);
        assertEquals(2, found.path("occurrence_count").asInt());
        assertEquals("app-one", found.path("class_occurrences").get(0).path("module").asText());
        assertEquals("app-two", found.path("class_occurrences").get(1).path("module").asText());

        JsonNode beans =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getBeans(
                                        readScope,
                                        "*StripePaymentService*",
                                        null,
                                        null,
                                        null,
                                        null,
                                        "app-two",
                                        "main",
                                        10));
        assertEquals(1, beans.path("total").asInt());
        assertEquals(2, beans.path("beans").get(0).path("occurrence_count").asInt());

        JsonNode dependencies =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                queries.getDependencies(
                                        readScope, "StripePaymentService", "both", 1));
        assertEquals(2, dependencies.path("occurrence_count").asInt());
    }

    @Test
    void findImplementationsReportsGeneratedOccurrencesByModule() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                            11,
                            "checkout-one",
                            "checkout-one/target/classes",
                            "checkout-one/target/classes/org/acme/StripePaymentService.class",
                            "checkout-one/target/generated-sources/annotations/org/acme/StripePaymentService.java",
                            "generated");
                    handle.execute(
                            """
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                            12,
                            "checkout-two",
                            "checkout-two/target/classes",
                            "checkout-two/target/classes/org/acme/StripePaymentService.class",
                            "checkout-two/target/generated-sources/annotations/org/acme/StripePaymentService.java",
                            "generated");
                });

        JsonNode result =
                JDBI_READS.findImplementations(jdbi, "PaymentService", true, null, null, 10, 0);

        assertEquals("org.acme.PaymentService", result.path("target").asText());
        assertEquals(1, result.path("implementation_class_count").asInt());
        assertEquals(2, result.path("implementation_occurrence_count").asInt());
        assertEquals(2, result.path("generated_implementation_count").asInt());
        assertEquals(
                List.of("checkout-one", "checkout-two"),
                result.path("generated_modules").valueStream().map(JsonNode::asText).toList());
        assertTrue(result.path("selection_depends_on_application_context").asBoolean());
        assertEquals("maven_reactor", result.path("index_scope").path("module_discovery").asText());
        assertTrue(result.path("index_scope").path("complete").asBoolean());
        assertTrue(
                result.path("index_scope")
                        .path("excludes_undeclared_project_directories")
                        .asBoolean());
        assertEquals(
                "org.acme.StripePaymentService",
                result.path("implementations").get(0).path("class").asText());
        assertEquals(2, result.path("implementations").get(0).path("occurrence_count").asInt());

        JsonNode filtered =
                JDBI_READS.findImplementations(
                        jdbi, "PaymentService", true, "checkout-two", null, 10, 0);
        assertEquals(1, filtered.path("implementation_occurrence_count").asInt());
        assertEquals(
                List.of("checkout-two"),
                filtered.path("generated_modules").valueStream().map(JsonNode::asText).toList());
        assertFalse(filtered.path("selection_depends_on_application_context").asBoolean());
    }

    @Test
    void findImplementationsCanExcludeTransitiveDescendants() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO classes
                  (class_name, kind, superclass, interfaces, source_file, source_line,
                   is_bean, source_tokens, origin, lifecycle, module, source_set)
                VALUES ('org.acme.SpecialStripePaymentService', 'CLASS',
                        'org.acme.StripePaymentService', '[]',
                        'src/main/java/org/acme/SpecialStripePaymentService.java', 1,
                        0, 20, 'source', 'current', 'payments', 'main')"""));

        JsonNode direct =
                JDBI_READS.findImplementations(jdbi, "PaymentService", false, null, null, 10, 0);
        JsonNode transitive =
                JDBI_READS.findImplementations(jdbi, "PaymentService", true, null, null, 10, 0);

        assertEquals(1, direct.path("implementation_class_count").asInt());
        assertEquals(2, transitive.path("implementation_class_count").asInt());
        assertEquals(2, transitive.path("implementations").get(1).path("distance").asInt());
    }
}
