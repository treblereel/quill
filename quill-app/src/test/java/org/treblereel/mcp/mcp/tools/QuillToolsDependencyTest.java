package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.model.*;

/** Dependency, usage and external dependency tools. */
class QuillToolsDependencyTest extends QuillToolsTestBase {

    @Test
    void getDependenciesShowsOutbound() {
        String result = JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 1).toString();
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("CDI_INJECT"));
    }

    @Test
    void getDependenciesReturnsCallSiteEvidence() throws Exception {
        JsonNode result = JDBI_READS.getDependencies(jdbi, "AuditService", "inbound", 1);
        JsonNode edge = result.get("depended_by").get(0);
        assertEquals("CONSTRUCTS", edge.get("kind").asText());
        assertEquals(
                "src/main/java/org/acme/StripePaymentService.java",
                edge.get("evidence_file").asText());
        assertEquals(42, edge.get("evidence_lines").get(0).asInt());
        assertNull(result.get("depends_on"));
    }

    @Test
    void findUsagesReturnsConstructorEvidenceAndFiltersKinds() throws Exception {
        JsonNode result =
                JDBI_READS.findUsages(jdbi, "AuditService", "constructor_call", null, 10, 0);

        assertEquals("org.acme.AuditService", result.path("target").asText());
        assertEquals("class", result.path("granularity").asText());
        assertEquals(1, result.path("usage_group_count").asInt());
        JsonNode usage = result.path("usages").get(0);
        assertEquals("org.acme.StripePaymentService", usage.path("class").asText());
        assertEquals("constructor_call", usage.path("usage_kind").asText());
        assertEquals("CONSTRUCTS", usage.path("indexed_kind").asText());
        assertEquals(42, usage.path("evidence_lines").get(0).asInt());
    }

    @Test
    void classUsagesExposeInvokedMembersWithoutMixingOverloadsOrCallbacks() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO dependencies(from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (3, 1, 'CALLS', 6, '[11,12,20,21,22,30]'), (4, 1, 'CALLS', 1, '[40]'),
                      (3, 1, 'CALLBACK_REFERENCE', 4, '[50]')""");
                    handle.execute(
                            """
                    INSERT INTO method_calls(from_class_id, from_method, from_descriptor,
                      to_class_id, to_method, to_descriptor, invocation_kind, occurrence_count, evidence_lines)
                    VALUES (3, 'a', '()V', 1, 'selected', '(I)V', 'virtual', 2, '[11,12]'),
                      (3, 'b', '()V', 1, 'selected', '(I)V', 'virtual', 3, '[20,21,22]'),
                      (3, 'c', '()V', 1, 'selected', '(Ljava/lang/String;)V', 'virtual', 1, '[30]'),
                      (3, 'constructor', '()V', 1, '<init>', '()V', 'special', 1, '[35]'),
                      (3, 'callback', '()V', 1, 'selected', '(I)V', 'dynamic', 4, '[50]'),
                      (4, 'otherTarget', '()V', 2, 'notThisTarget', '()V', 'interface', 1, '[60]')""");
                });
        JsonNode first = JDBI_READS.findUsages(jdbi, "OrderService", "method_call", null, 1, 0);

        JsonNode second = JDBI_READS.findUsages(jdbi, "OrderService", "method_call", null, 1, 1);

        assertEquals(2, first.path("total").asInt());
        assertTrue(first.path("has_more").asBoolean());
        assertEquals("org.acme.AuditService", first.path("usages").get(0).path("class").asText());
        assertFalse(first.path("usages").get(0).has("invoked_members"));
        JsonNode usage = second.path("usages").get(0);
        assertEquals("org.acme.StripePaymentService", usage.path("class").asText());
        assertEquals(2, usage.path("invoked_member_groups").asInt());
        assertFalse(usage.path("invoked_members_truncated").asBoolean());
        assertEquals(2, usage.path("invoked_members").size());
        assertEquals("(I)V", usage.path("invoked_members").get(0).path("descriptor").asText());
        assertEquals(
                5, usage.path("invoked_members").get(0).path("instruction_occurrences").asLong());
        assertEquals(
                "virtual", usage.path("invoked_members").get(0).path("invocation_kind").asText());
        assertEquals(
                "(Ljava/lang/String;)V",
                usage.path("invoked_members").get(1).path("descriptor").asText());
        JsonNode callback =
                JDBI_READS.findUsages(jdbi, "OrderService", "callback_reference", null, 10, 0);
        assertFalse(callback.path("usages").get(0).has("invoked_members"));
        assertTrue(
                org.treblereel.mcp.db.IndexReader.findInvokedMembers(jdbi, 1, List.of()).isEmpty());
        var batches =
                org.treblereel.mcp.db.IndexReader.findInvokedMembers(
                        jdbi, 1, java.util.stream.IntStream.range(1, 1200).boxed().toList());
        assertEquals(2, batches.get(3).size());
    }

    @Test
    void classUsageInvokedMemberDetailsAreBoundedAndExplicitlyTruncated() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO dependencies(from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (3, 1, 'CALLS', 40, '[]')""");
                    for (int index = 0; index < 40; index++) {
                        handle.execute(
                                """
                        INSERT INTO method_calls(from_class_id, from_method, from_descriptor,
                          to_class_id, to_method, to_descriptor, invocation_kind, occurrence_count, evidence_lines)
                        VALUES (3, 'run', '()V', 1, ?, '()V', 'static', 1, '[]')""",
                                "m" + index);
                    }
                });
        JsonNode result = JDBI_READS.findUsages(jdbi, "OrderService", "method_call", null, 10, 0);

        JsonNode usage = result.path("usages").get(0);
        assertEquals(40, usage.path("invoked_member_groups").asInt());
        assertEquals(32, usage.path("invoked_members").size());
        assertTrue(usage.path("invoked_members_truncated").asBoolean());
    }

    @Test
    void findUsagesIncludesInheritanceAndSupportsPagination() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.execute(
                                """
                INSERT INTO dependencies
                  (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                VALUES (1, 2, 'TYPE_USE', 2, '[18,21]')"""));

        JsonNode first = JDBI_READS.findUsages(jdbi, "PaymentService", null, null, 1, 0);
        JsonNode second = JDBI_READS.findUsages(jdbi, "PaymentService", null, null, 1, 1);

        assertEquals(2, first.path("total").asInt());
        assertTrue(first.path("has_more").asBoolean());
        assertEquals("inheritance", first.path("usages").get(0).path("usage_kind").asText());
        assertEquals("type_reference", second.path("usages").get(0).path("usage_kind").asText());
        assertEquals(2, second.path("usages").get(0).path("occurrences").asInt());
    }

    @Test
    void findUsagesValidatesKindAndFiltersModule() throws Exception {
        jdbi.useHandle(
                handle -> handle.execute("UPDATE classes SET module = 'payments' WHERE id = 3"));

        JsonNode matching = JDBI_READS.findUsages(jdbi, "AuditService", null, "payments", 10, 0);
        JsonNode excluded = JDBI_READS.findUsages(jdbi, "AuditService", null, "orders", 10, 0);
        JsonNode invalid = JDBI_READS.findUsages(jdbi, "AuditService", "reflection", null, 10, 0);

        assertEquals(1, matching.path("total").asInt());
        assertEquals(0, excluded.path("total").asInt());
        assertTrue(invalid.path("message").asText().startsWith("Invalid usage_kind"));
        JsonNode alias = JDBI_READS.findUsages(jdbi, "AuditService", "di", null, 10, 0);
        JsonNode canonical = JDBI_READS.findUsages(jdbi, "AuditService", "injection", null, 10, 0);

        assertEquals(canonical.path("usages"), alias.path("usages"));
        assertEquals("injection", alias.path("usage_kind").asText());
    }

    @Test
    void dependencyGraphIsBounded() throws Exception {
        jdbi.useHandle(
                handle -> {
                    var classes =
                            handle.prepareBatch(
                                    "INSERT INTO classes(class_name, kind, is_bean) VALUES (:name, 'CLASS', 0)");
                    for (int i = 0; i < 250; i++) {
                        classes.bind("name", "org.acme.Generated" + i).add();
                    }
                    classes.execute();
                    var dependencies =
                            handle.prepareBatch(
                                    "INSERT INTO dependencies(from_class_id, to_class_id, kind) VALUES (1, :target, 'CLASS_REFERENCE')");
                    for (int id = 5; id < 255; id++) {
                        dependencies.bind("target", id).add();
                    }
                    dependencies.execute();
                });

        JsonNode result = JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 1);
        assertEquals(200, result.get("depends_on").size());
        assertTrue(result.get("truncated").asBoolean());
        assertEquals(251, result.get("total").asInt());
        assertEquals(200, result.get("next_offset").asInt());
    }

    @Test
    void getDependenciesDepth2ExpandsTransitive() throws Exception {
        String result = JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 2).toString();
        JsonNode root = JSON.readTree(result);
        JsonNode graph = root.get("graph");
        assertEquals(2, graph.size());
        assertEquals("org.acme.StripePaymentService", graph.get(0).get("class").asText());
        assertEquals(1, graph.get(0).get("depth").asInt());
        assertEquals("org.acme.AuditService", graph.get(1).get("class").asText());
        assertEquals(2, graph.get(1).get("depth").asInt());
        assertEquals("cursor", root.get("pagination").asText());
    }

    @Test
    void getDependenciesDepth1DoesNotExpandNested() throws Exception {
        String result = JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 1).toString();
        JsonNode root = JSON.readTree(result);
        JsonNode dependsOn = root.get("depends_on");
        assertNull(
                dependsOn.get(0).get("depends_on"),
                "depth=1 should not expand nested dependencies");
    }

    @Test
    void getDependenciesCanReturnCompactMetricsOnly() throws Exception {
        JsonNode root =
                JDBI_READS.getDependencies(jdbi, "OrderService", "both", 1, false, 50, 0, null);

        assertFalse(root.get("nodes_included").asBoolean());
        assertEquals(1, root.get("metrics").get("fan_out").asInt());
        assertNull(root.get("depends_on"));
        assertNull(root.get("depended_by"));
        assertNull(root.get("pagination"));
    }

    @Test
    void getDependenciesPaginatesDepthOneWithOffset() throws Exception {
        JsonNode first =
                JDBI_READS.getDependencies(
                        jdbi, "StripePaymentService", "both", 1, true, 1, 0, null);
        assertEquals(1, first.get("showing").asInt());
        assertEquals(2, first.get("total").asInt());
        assertEquals(1, first.get("next_offset").asInt());
        assertTrue(first.get("has_more").asBoolean());

        JsonNode second =
                JDBI_READS.getDependencies(
                        jdbi, "StripePaymentService", "both", 1, true, 1, 1, null);
        assertEquals(1, second.get("showing").asInt());
        assertEquals(2, second.get("total").asInt());
        assertFalse(second.get("has_more").asBoolean());
        assertNotEquals(relationClass(first), relationClass(second));
    }

    @Test
    void getDependenciesContinuesDeepBreadthFirstTraversalWithCursor() throws Exception {
        JsonNode first =
                JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 2, true, 1, 0, null);
        assertEquals(
                "org.acme.StripePaymentService", first.get("graph").get(0).get("class").asText());
        assertEquals(1, first.get("graph").get(0).get("depth").asInt());
        assertTrue(first.get("has_more").asBoolean());
        String cursor = first.get("next_cursor").asText();

        JsonNode second =
                JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 2, true, 1, 0, cursor);
        assertEquals("org.acme.AuditService", second.get("graph").get(0).get("class").asText());
        assertEquals(2, second.get("graph").get(0).get("depth").asInt());
        assertFalse(second.get("has_more").asBoolean());
    }

    @Test
    void getDependenciesRejectsCursorForDifferentTraversal() throws Exception {
        JsonNode first =
                JDBI_READS.getDependencies(jdbi, "OrderService", "outbound", 2, true, 1, 0, null);
        String cursor = first.get("next_cursor").asText();

        JsonNode invalid =
                JDBI_READS.getDependencies(jdbi, "OrderService", "both", 2, true, 1, 0, cursor);
        assertEquals("Invalid or expired dependency cursor", invalid.path("message").asText());
    }

    @Test
    void getExternalDepsForClassShowsTypesByKind() throws Exception {
        String result =
                JDBI_READS.getExternalDeps(jdbi, "StripePaymentService", null, 20).toString();
        JsonNode root = JSON.readTree(result);

        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        JsonNode deps = root.get("external_dependencies");
        assertNotNull(deps);
        assertTrue(deps.has("annotation"));
        assertTrue(deps.has("field"));
        assertTrue(deps.has("method"));

        boolean hasStripe = false;
        for (JsonNode t : deps.get("field")) {
            if (t.asText().contains("com.stripe")) hasStripe = true;
        }
        assertTrue(hasStripe, "Should include com.stripe.Stripe as FIELD dependency");
        assertEquals(3, root.get("total_external_types").asInt());
    }

    @Test
    void getExternalDepsLibrarySummary() throws Exception {
        String result = JDBI_READS.getExternalDeps(jdbi, null, null, 20).toString();
        JsonNode root = JSON.readTree(result);

        JsonNode libraries = root.get("libraries");
        assertNotNull(libraries);
        assertTrue(libraries.size() >= 2);

        boolean hasJakarta = false;
        boolean hasStripe = false;
        for (JsonNode lib : libraries) {
            String pkg = lib.get("package").asText();
            if (pkg.startsWith("jakarta.")) hasJakarta = true;
            if (pkg.startsWith("com.stripe")) hasStripe = true;
        }
        assertTrue(hasJakarta, "Should include jakarta libraries");
        assertTrue(hasStripe, "Should include com.stripe library");
    }

    @Test
    void getExternalDepsFilterByLibrary() throws Exception {
        String result = JDBI_READS.getExternalDeps(jdbi, null, "com.stripe", 20).toString();
        JsonNode root = JSON.readTree(result);

        assertEquals("com.stripe", root.get("library_filter").asText());
        JsonNode classes = root.get("classes_using_library");
        assertNotNull(classes);
        assertEquals(1, classes.size());
        assertEquals("org.acme.StripePaymentService", classes.get(0).asText());
    }
}
