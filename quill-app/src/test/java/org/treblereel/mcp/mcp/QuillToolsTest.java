package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.model.*;

class QuillToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;
    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = QuillDatabase.create(tempDir.resolve("test.db"));
        var classes = List.of(
                new ClassRecord(0, "org.acme.OrderService", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/org/acme/OrderService.java", 10, true, 500),
                new ClassRecord(0, "org.acme.PaymentService", "INTERFACE", null,
                        List.of(), "src/main/java/org/acme/PaymentService.java", 5, false, 100),
                new ClassRecord(0, "org.acme.StripePaymentService", "CLASS", "java.lang.Object",
                        List.of("org.acme.PaymentService"), "src/main/java/org/acme/StripePaymentService.java", 8, true, 300),
                new ClassRecord(0, "org.acme.AuditService", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/org/acme/AuditService.java", 5, true, 200)
        );
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("OrderService", "Object")),
                new BeanRecord(0, 3, "CLASS", "@ApplicationScoped", List.of("@Default", "@Premium"),
                        List.of(), false, null, List.of("prod"), null, null, List.of("PaymentService", "StripePaymentService", "Object")),
                new BeanRecord(0, 4, "CLASS", "@Dependent", List.of("@Default"),
                        List.of(), false, null, List.of("dev", "test"), null, null, List.of("AuditService", "Object"))
        );
        var ips = List.of(
                InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "PaymentService",
                        List.of("@Default"), "paymentService", 2, false,
                        InjectionPointRecord.STATIC_CDI).withResolution(2, false,
                        new ResolutionTrace(List.of(new ResolutionCandidate(2,
                                "org.acme.StripePaymentService",
                                CandidateDisposition.SELECTED,
                                "UNIQUE_ELIGIBLE_CANDIDATE",
                                List.of("TYPE_ASSIGNABILITY", "QUALIFIER_MATCHING"))),
                                List.of("TYPE_ASSIGNABILITY", "QUALIFIER_MATCHING"),
                                List.of("RUNTIME_CDI_EXTENSIONS"))),
                InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "RuntimeProvidedService",
                        List.of("@Default"), "runtimeProvidedService", null, false,
                        InjectionPointRecord.STATIC_CDI)
        );
        var deps = List.of(
                new DependencyRecord(1, 3, "CDI_INJECT", 1),
                new DependencyRecord(3, 4, "CONSTRUCTS", null, 1, List.of(42))
        );
        IndexWriter.write(jdbi, classes, beans, ips, deps,
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "abc1234",
                        "dependency_index", "degraded",
                        "dependency_index_detail", "1/2 modules resolved",
                        "git_scanned_commits", "3",
                        "git_repository_commits", "3",
                        "git_history_complete", "true",
                        "module_discovery_scope", "maven_reactor",
                        "module_discovery_complete", "true",
                        "service_registrations_detail", """
                                [{"serviceType":"javax.annotation.processing.Processor",
                                  "providerType":"org.acme.FirstProcessor",
                                  "descriptorPath":"processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor","line":1},
                                 {"serviceType":"javax.annotation.processing.Processor",
                                  "providerType":"org.acme.SecondProcessor",
                                  "descriptorPath":"processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor","line":3}]
                                """));

        var gitCommits = List.of(
                new GitCommitRecord(1, "aaa1111aaa1111aaa1111aaa1111aaa1111aaa111", "aaa1111",
                        "dev1", "dev1@test.com", "2026-08-28T10:00:00Z", "feat: add order service"),
                new GitCommitRecord(2, "bbb2222bbb2222bbb2222bbb2222bbb2222bbb222", "bbb2222",
                        "dev2", "dev2@test.com", "2026-08-27T09:00:00Z", "fix: payment edge case"),
                new GitCommitRecord(3, "ccc3333ccc3333ccc3333ccc3333ccc3333ccc333", "ccc3333",
                        "dev1", "dev1-alias@test.com", "2026-08-26T08:00:00Z", "initial commit")
        );
        var gitFiles = List.of(
                new GitCommitFile(1, 1, "src/main/java/org/acme/OrderService.java", "MODIFY"),
                new GitCommitFile(1, 3, "src/main/java/org/acme/StripePaymentService.java", "MODIFY"),
                new GitCommitFile(2, 3, "src/main/java/org/acme/StripePaymentService.java", "MODIFY"),
                new GitCommitFile(3, 1, "src/main/java/org/acme/OrderService.java", "ADD"),
                new GitCommitFile(3, 2, "src/main/java/org/acme/PaymentService.java", "ADD"),
                new GitCommitFile(3, 3, "src/main/java/org/acme/StripePaymentService.java", "ADD")
        );
        var gitStats = List.of(
                new GitFileStats(1, "src/main/java/org/acme/OrderService.java", 1, 2,
                        "2026-08-28T10:00:00Z", "dev1", "2026-08-26T08:00:00Z", 1),
                new GitFileStats(2, "src/main/java/org/acme/StripePaymentService.java", 3, 3,
                        "2026-08-28T10:00:00Z", "dev1", "2026-08-26T08:00:00Z", 2),
                new GitFileStats(3, "src/main/java/org/acme/PaymentService.java", 2, 1,
                        "2026-08-26T08:00:00Z", "dev1", "2026-08-26T08:00:00Z", 1)
        );
        IndexWriter.writeGitData(jdbi, gitStats, gitCommits, gitFiles);

        var externalDeps = List.of(
                new ExternalDepRecord(1, "jakarta.enterprise.context.ApplicationScoped", "ANNOTATION"),
                new ExternalDepRecord(1, "jakarta.inject.Inject", "ANNOTATION"),
                new ExternalDepRecord(3, "jakarta.enterprise.context.ApplicationScoped", "ANNOTATION"),
                new ExternalDepRecord(3, "com.stripe.Stripe", "FIELD"),
                new ExternalDepRecord(3, "com.stripe.model.Charge", "METHOD"),
                new ExternalDepRecord(4, "jakarta.enterprise.context.Dependent", "ANNOTATION")
        );
        IndexWriter.writeExternalDeps(jdbi, externalDeps);
    }

    @Test
    void getBeansReturnsAllBeans() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, null, null);
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("\"total\":3"));
    }

    @Test
    void getBeansResolvesShortClassName() throws Exception {
        var tools = new QuillTools();
        JsonNode shortName = JSON.readTree(tools.getBeans(
                jdbi, "StripePaymentService", null, null, null, null));
        JsonNode fqcn = JSON.readTree(tools.getBeans(
                jdbi, "org.acme.StripePaymentService", null, null, null, null));

        assertEquals(1, shortName.get("total").asInt());
        assertEquals(fqcn.get("beans"), shortName.get("beans"));
    }

    @Test
    void getBeansReturnsCandidatesForUnknownClassName() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools().getBeans(
                jdbi, "PaymentServ", null, null, null, null));

        assertEquals("Class not found", result.get("error").asText());
        assertTrue(result.get("candidates").toString().contains("PaymentService"));
    }

    @Test
    void singleProjectQueryFailureReturnsStructuredToolError() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("DROP TABLE dependencies");
            handle.execute("DROP TABLE injection_points");
            handle.execute("DROP TABLE beans");
        });
        ProjectRegistry registry = new ProjectRegistry() {
            @Override
            public Resolution resolve() {
                return new Resolution(
                        List.of(new ProjectEntry("broken-project", tempDir, jdbi)),
                        List.of());
            }
        };

        String result = new QuillTools(registry).list_beans(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        JsonNode root = JSON.readTree(result);
        assertTrue(root.get("error").asText().contains("broken-project"));
        assertTrue(root.get("error").asText().contains("beans"));
    }

    @Test
    void getBeansFiltersByScope() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, "@ApplicationScoped", null, null, null);
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("AuditService"));
    }

    @Test
    void getBeansFiltersByProfile() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, "dev", null);
        assertTrue(result.contains("AuditService"));
        assertFalse(result.contains("OrderService"));
        assertTrue(result.contains("\"total\":1"));
    }

    @Test
    void getBeansFiltersByQualifier() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, null, "@Premium");
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("OrderService"));
        assertFalse(result.contains("AuditService"));
        assertTrue(result.contains("\"total\":1"));
    }

    @Test
    void getDependenciesShowsOutbound() {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "OrderService", "outbound", 1);
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("CDI_INJECT"));
    }

    @Test
    void getDependenciesReturnsCallSiteEvidence() throws Exception {
        JsonNode result = JSON.readTree(
                new QuillTools().getDependencies(jdbi, "AuditService", "inbound", 1));
        JsonNode edge = result.get("depended_by").get(0);
        assertEquals("CONSTRUCTS", edge.get("kind").asText());
        assertEquals("src/main/java/org/acme/StripePaymentService.java",
                edge.get("evidence_file").asText());
        assertEquals(42, edge.get("evidence_lines").get(0).asInt());
        assertNull(result.get("depends_on"));
    }

    @Test
    void findUsagesReturnsConstructorEvidenceAndFiltersKinds() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools().findUsages(
                jdbi, "AuditService", "constructor_call", null, 10, 0));

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
    void findUsagesIncludesInheritanceAndSupportsPagination() throws Exception {
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO dependencies
                  (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                VALUES (1, 2, 'TYPE_USE', 2, '[18,21]')"""));

        QuillTools tools = new QuillTools();
        JsonNode first = JSON.readTree(
                tools.findUsages(jdbi, "PaymentService", null, null, 1, 0));
        JsonNode second = JSON.readTree(
                tools.findUsages(jdbi, "PaymentService", null, null, 1, 1));

        assertEquals(2, first.path("total").asInt());
        assertTrue(first.path("has_more").asBoolean());
        assertEquals("inheritance", first.path("usages").get(0).path("usage_kind").asText());
        assertEquals("type_reference", second.path("usages").get(0)
                .path("usage_kind").asText());
        assertEquals(2, second.path("usages").get(0).path("occurrences").asInt());
    }

    @Test
    void findUsagesValidatesKindAndFiltersModule() throws Exception {
        jdbi.useHandle(handle -> handle.execute(
                "UPDATE classes SET module = 'payments' WHERE id = 3"));
        QuillTools tools = new QuillTools();

        JsonNode matching = JSON.readTree(tools.findUsages(
                jdbi, "AuditService", null, "payments", 10, 0));
        JsonNode excluded = JSON.readTree(tools.findUsages(
                jdbi, "AuditService", null, "orders", 10, 0));
        JsonNode invalid = JSON.readTree(tools.findUsages(
                jdbi, "AuditService", "reflection", null, 10, 0));

        assertEquals(1, matching.path("total").asInt());
        assertEquals(0, excluded.path("total").asInt());
        assertTrue(invalid.path("error").asText().startsWith("Invalid usage_kind"));
    }

    @Test
    void getSymbolDetailsCombinesMembersDiAndDependencyMetrics() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'jakarta.enterprise.context.ApplicationScoped', 1, NULL)""");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'FIELD', 'paymentService',
                            'paymentService:org.acme.PaymentService',
                            'org.acme.PaymentService', '[]', '', '["jakarta.inject.Inject"]')""");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'METHOD', 'createOrder',
                            'createOrder(java.lang.String):org.acme.Order',
                            'org.acme.Order', '["java.lang.String"]', 'public', '[]')""");
        });

        QuillTools tools = new QuillTools();
        JsonNode first = JSON.readTree(tools.getSymbolDetails(
                jdbi, "OrderService", true, null, 1, 0));
        JsonNode methods = JSON.readTree(tools.getSymbolDetails(
                jdbi, "OrderService", true, "method", 10, 0));

        assertEquals("org.acme.OrderService", first.path("class").asText());
        assertEquals("@ApplicationScoped", first.path("bean").path("scope").asText());
        assertEquals(1, first.path("dependency_metrics").path("fan_out").asInt());
        assertEquals("jakarta.enterprise.context.ApplicationScoped",
                first.path("annotations").get(0).asText());
        assertEquals(2, first.path("total").asInt());
        assertTrue(first.path("has_more").asBoolean());
        assertEquals("createOrder", methods.path("members").get(0).path("name").asText());
        assertEquals("java.lang.String",
                methods.path("members").get(0).path("parameters").get(0).asText());
    }

    @Test
    void getSymbolDetailsCanSkipMembersAndValidatesMemberKind() throws Exception {
        QuillTools tools = new QuillTools();
        JsonNode compact = JSON.readTree(tools.getSymbolDetails(
                jdbi, "PaymentService", false, null, 10, 0));
        JsonNode invalid = JSON.readTree(tools.getSymbolDetails(
                jdbi, "PaymentService", true, "property", 10, 0));

        assertFalse(compact.path("members_included").asBoolean());
        assertFalse(compact.has("members"));
        assertTrue(invalid.path("error").asText().startsWith("Invalid member_kind"));
    }

    @Test
    void findImpactedTestsCombinesTransitiveStaticAndGitEvidence() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.OrderHelper', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/OrderHelper.java', 1, 0, 40,
                            'source', 'current', '.', 'main')""");
            handle.execute("""
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.OrderServiceTest', 'CLASS', 'java.lang.Object', '[]',
                            'src/test/java/org/acme/OrderServiceTest.java', 1, 0, 80,
                            'source', 'current', '.', 'test')""");
            handle.execute("""
                    INSERT INTO dependencies
                      (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (5, 1, 'CALLS', 1, '[12]'),
                           (6, 5, 'CALLS', 2, '[20,24]')""");
            handle.execute("""
                    INSERT INTO git_commit_files(commit_id, class_id, file_path, change_type)
                    VALUES (1, 6, 'src/test/java/org/acme/OrderServiceTest.java', 'MODIFY')""");
        });

        JsonNode result = JSON.readTree(new QuillTools().findImpactedTests(
                jdbi, List.of("OrderService"), true, 3, 10, 0));

        assertEquals(1, result.path("indexed_test_class_count").asInt());
        assertTrue(result.path("compiled_test_outputs_indexed").asBoolean());
        assertEquals(1, result.path("total").asInt());
        JsonNode test = result.path("tests").get(0);
        assertEquals("org.acme.OrderServiceTest", test.path("class").asText());
        assertEquals(2, test.path("dependency_depth").asInt());
        assertEquals("medium", test.path("confidence").asText());
        assertEquals(1, test.path("co_change_count").asInt());
        assertEquals(List.of("org.acme.OrderService", "org.acme.OrderHelper",
                        "org.acme.OrderServiceTest"),
                test.path("dependency_path").valueStream().map(JsonNode::asText).toList());
    }

    @Test
    void findImpactedTestsReportsMissingCompiledTestCoverage() throws Exception {
        QuillTools tools = new QuillTools();
        JsonNode result = JSON.readTree(tools.findImpactedTests(
                jdbi, List.of("AuditService"), true, 3, 10, 0));
        JsonNode invalid = JSON.readTree(tools.findImpactedTests(
                jdbi, List.of(), true, 3, 10, 0));

        assertFalse(result.path("compiled_test_outputs_indexed").asBoolean());
        assertTrue(result.path("limitations").get(0).asText()
                .contains("No compiled test classes"));
        assertEquals("At least one target is required", invalid.path("error").asText());
    }

    @Test
    void getTypeHierarchyReturnsAncestorAndDescendantPaths() throws Exception {
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO classes
                  (class_name, kind, superclass, interfaces, source_file, source_line,
                   is_bean, source_tokens, origin, lifecycle, module, source_set)
                VALUES ('org.acme.PremiumStripePaymentService', 'CLASS',
                        'org.acme.StripePaymentService', '[]',
                        'src/main/java/org/acme/PremiumStripePaymentService.java', 1,
                        0, 50, 'source', 'current', '.', 'main')"""));

        QuillTools tools = new QuillTools();
        JsonNode descendants = JSON.readTree(tools.getTypeHierarchy(
                jdbi, "PaymentService", "descendants", 5, 10, 0));
        JsonNode ancestors = JSON.readTree(tools.getTypeHierarchy(
                jdbi, "PremiumStripePaymentService", "ancestors", 5, 10, 0));

        assertEquals(2, descendants.path("descendant_count").asInt());
        assertEquals("org.acme.StripePaymentService",
                descendants.path("hierarchy").get(0).path("class").asText());
        assertEquals(1, descendants.path("hierarchy").get(0).path("depth").asInt());
        assertEquals(List.of("implements"), descendants.path("hierarchy").get(0)
                .path("relations").valueStream().map(JsonNode::asText).toList());
        assertEquals("org.acme.PremiumStripePaymentService",
                descendants.path("hierarchy").get(1).path("class").asText());
        assertEquals(2, descendants.path("hierarchy").get(1).path("depth").asInt());
        assertEquals(List.of("implements", "extends"), descendants.path("hierarchy").get(1)
                .path("relations").valueStream().map(JsonNode::asText).toList());

        assertTrue(ancestors.path("hierarchy").valueStream().anyMatch(node ->
                node.path("class").asText().equals("org.acme.PaymentService")
                        && node.path("depth").asInt() == 2));
        assertTrue(ancestors.path("hierarchy").valueStream().anyMatch(node ->
                node.path("class").asText().equals("java.lang.Object")
                        && !node.path("indexed").asBoolean()));
    }

    @Test
    void getTypeHierarchyValidatesDirectionAndPaginates() throws Exception {
        QuillTools tools = new QuillTools();
        JsonNode page = JSON.readTree(tools.getTypeHierarchy(
                jdbi, "PaymentService", "down", 5, 1, 0));
        JsonNode invalid = JSON.readTree(tools.getTypeHierarchy(
                jdbi, "PaymentService", "sideways", 5, 10, 0));

        assertEquals("descendants", page.path("direction").asText());
        assertEquals(1, page.path("showing").asInt());
        assertEquals(1, page.path("total").asInt());
        assertTrue(invalid.path("error").asText().contains("Invalid direction"));
    }

    @Test
    void searchSymbolsFindsAndFiltersMemberDeclarations() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
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
        });

        QuillTools tools = new QuillTools();
        JsonNode result = JSON.readTree(tools.searchSymbols(
                jdbi, "order", "method", 10, 0));
        JsonNode invalid = JSON.readTree(tools.searchSymbols(
                jdbi, "order", "package", 10, 0));

        assertEquals(1, result.path("total").asInt());
        JsonNode symbol = result.path("symbols").get(0);
        assertEquals("method", symbol.path("kind").asText());
        assertEquals("createOrder", symbol.path("name").asText());
        assertEquals("org.acme.OrderService", symbol.path("declaring_class").asText());
        assertEquals("org.acme.Order", symbol.path("type").asText());
        assertTrue(symbol.path("annotations").toString().contains("Transactional"));
        assertTrue(invalid.path("error").asText().contains("Invalid kind"));
    }

    @Test
    void searchSymbolsIncludesTypesAndPaginates() throws Exception {
        QuillTools tools = new QuillTools();
        JsonNode page = JSON.readTree(tools.searchSymbols(jdbi, "Service", null, 2, 1));
        JsonNode blank = JSON.readTree(tools.searchSymbols(jdbi, " ", null, 10, 0));

        assertEquals(4, page.path("total").asInt());
        assertEquals(2, page.path("showing").asInt());
        assertEquals(1, page.path("offset").asInt());
        assertTrue(page.path("has_more").asBoolean());
        assertTrue(blank.path("error").asText().contains("must not be blank"));
    }

    @Test
    void getCallHierarchyReturnsMethodLevelInboundAndOutboundEvidence() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (1, 'METHOD', 'createOrder',
                            'createOrder(java.lang.String):org.acme.Order',
                            'org.acme.Order', '["java.lang.String"]', 'public', '[]')""");
            handle.execute("""
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
        });

        QuillTools tools = new QuillTools();
        JsonNode result = JSON.readTree(tools.getCallHierarchy(
                jdbi, "OrderService", "createOrder", "both", 10, 0));
        JsonNode invalid = JSON.readTree(tools.getCallHierarchy(
                jdbi, "OrderService", null, "sideways", 10, 0));
        JsonNode transitive = JSON.readTree(tools.getCallHierarchy(
                jdbi, "OrderService", "createOrder", "outbound", true, 2, 10, 0));

        assertEquals(2, result.path("total").asInt());
        assertTrue(result.path("declared_method_found").asBoolean());
        assertTrue(result.path("direct_only").asBoolean());
        assertTrue(result.path("calls").valueStream().anyMatch(call ->
                call.path("caller").path("class").asText()
                        .equals("org.acme.StripePaymentService")
                        && call.path("callee").path("method").asText().equals("createOrder")));
        JsonNode outbound = result.path("calls").valueStream()
                .filter(call -> call.path("callee").path("method").asText().equals("audit"))
                .findFirst().orElseThrow();
        assertEquals(List.of(21, 24), outbound.path("evidence_lines").valueStream()
                .map(JsonNode::asInt).toList());
        assertEquals("org.acme.Order", outbound.path("callee").path("parameters")
                .get(0).asText());
        assertTrue(invalid.path("error").asText().contains("Invalid direction"));
        assertEquals(2, transitive.path("total").asInt());
        assertFalse(transitive.path("direct_only").asBoolean());
        assertEquals(2, transitive.path("max_depth").asInt());
        JsonNode indirect = transitive.path("calls").valueStream()
                .filter(call -> call.path("callee").path("method").asText().equals("notify"))
                .findFirst().orElseThrow();
        assertEquals(2, indirect.path("depth").asInt());
        assertEquals("outbound", indirect.path("traversal_direction").asText());
        assertEquals(3, indirect.path("path").size());
    }

    @Test
    void getCallHierarchyRequiresExactOverloadSelection() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, descriptor, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'process', 'process(int):void', '(I)V', 'void',
                            '["int"]', 'public', '[]'),
                           (1, 'METHOD', 'process', 'process(java.lang.String):void',
                            '(Ljava/lang/String;)V', 'void', '["java.lang.String"]',
                            'public', '[]')""");
            handle.execute("""
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
        JsonNode ambiguous = JSON.readTree(queries.getCallHierarchy(
                jdbi, "OrderService", "process", null,
                "inbound", false, 1, 10, 0));
        assertTrue(ambiguous.path("error").asText().startsWith("Ambiguous method"));
        assertEquals(2, ambiguous.path("candidates").size());

        JsonNode exact = JSON.readTree(queries.getCallHierarchy(
                jdbi, "OrderService", "process", "(I)V",
                "inbound", false, 1, 10, 0));
        assertEquals("(I)V", exact.path("descriptor").asText());
        assertEquals("process(int):void", exact.path("signature").asText());
        assertEquals(1, exact.path("total").asInt());
        assertEquals(List.of(31), exact.path("calls").get(0).path("evidence_lines")
                .valueStream().map(JsonNode::asInt).toList());

        JsonNode missing = JSON.readTree(queries.getCallHierarchy(
                jdbi, "OrderService", "process", "(J)V",
                "inbound", false, 1, 10, 0));
        assertEquals("Method not found", missing.path("error").asText());
        assertEquals(2, missing.path("candidates").size());
    }

    @Test
    void findSymbolUsagesResolvesOverloadsConstructorsAndFieldAccess() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
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
            handle.execute("""
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
            handle.execute("""
                    INSERT INTO field_accesses
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, field_name, field_descriptor, access_kind,
                       occurrence_count, evidence_lines)
                    VALUES (3, 'read', '()V', 1, 'status', 'Ljava/lang/String;',
                            'read_instance', 2, '[41,44]'),
                           (4, 'write', '()V', 1, 'status', 'Ljava/lang/String;',
                            'write_instance', 1, '[22]')""");
        });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode ambiguous = JSON.readTree(queries.findSymbolUsages(
                jdbi, "OrderService", "submit", "method", null, "all", 10, 0));
        assertEquals("Ambiguous symbol", ambiguous.path("error").asText());
        assertEquals(2, ambiguous.path("candidates").size());

        JsonNode method = JSON.readTree(queries.findSymbolUsages(
                jdbi, "OrderService", "submit", "method", "(Ljava/lang/String;)V",
                "all", 10, 0));
        assertEquals(1, method.path("total").asInt());
        assertEquals("(Ljava/lang/String;)V", method.path("descriptor").asText());
        assertEquals(List.of(31, 35), method.path("usages").get(0)
                .path("evidence_lines").valueStream().map(JsonNode::asInt).toList());
        assertEquals("org.acme.StripePaymentService",
                method.path("usages").get(0).path("caller").path("class").asText());

        JsonNode constructor = JSON.readTree(queries.findSymbolUsages(
                jdbi, "OrderService", null, "constructor",
                "OrderService(java.lang.String)", "all", 10, 0));
        assertEquals(1, constructor.path("total").asInt());
        assertEquals("special", constructor.path("usages").get(0)
                .path("invocation_kind").asText());

        JsonNode reads = JSON.readTree(queries.findSymbolUsages(
                jdbi, "OrderService", "status", "field", null, "read", 10, 0));
        assertEquals(1, reads.path("total").asInt());
        assertEquals("read", reads.path("usages").get(0).path("usage_kind").asText());
        assertEquals(2, reads.path("usages").get(0).path("occurrence_count").asInt());
    }

    @Test
    void findMethodOverridesHandlesOverloadsAndTransitiveDescendants() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.PremiumStripePaymentService', 'CLASS',
                            'org.acme.StripePaymentService', '[]',
                            'src/main/java/org/acme/PremiumStripePaymentService.java', 1,
                            0, 50, 'source', 'current', '.', 'main')""");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (2, 'METHOD', 'processPayment',
                            'processPayment(double):void', 'void', '["double"]',
                            'public abstract', '[]'),
                           (2, 'METHOD', 'processPayment',
                            'processPayment(java.lang.String):void', 'void',
                            '["java.lang.String"]', 'public abstract', '[]'),
                           (3, 'METHOD', 'processPayment',
                            'processPayment(double):void', 'void', '["double"]',
                            'public', '[]'),
                           (5, 'METHOD', 'processPayment',
                            'processPayment(double):void', 'void', '["double"]',
                            'public final', '[]'),
                           (5, 'METHOD', 'processPayment',
                            'processPayment(java.lang.String):void', 'void',
                            '["java.lang.String"]', 'public', '[]')""");
        });

        QuillTools tools = new QuillTools();
        JsonNode all = JSON.readTree(tools.findMethodOverrides(
                jdbi, "PaymentService", "processPayment", null, true, 10, 0));
        JsonNode direct = JSON.readTree(tools.findMethodOverrides(
                jdbi, "PaymentService", "processPayment", null, false, 10, 0));
        JsonNode overload = JSON.readTree(tools.findMethodOverrides(
                jdbi, "PaymentService", "processPayment",
                "processPayment(java.lang.String):void", true, 10, 0));

        assertEquals(3, all.path("total").asInt());
        assertEquals(2, all.path("base_declarations").size());
        assertTrue(all.path("overrides").valueStream().anyMatch(node ->
                node.path("class").asText().equals("org.acme.PremiumStripePaymentService")
                        && node.path("distance").asInt() == 2
                        && node.path("hierarchy_path").size() == 3));
        assertEquals(1, direct.path("total").asInt());
        assertEquals(1, overload.path("total").asInt());
        assertEquals("processPayment(java.lang.String):void",
                overload.path("overrides").get(0).path("base_signature").asText());
    }

    @Test
    void findMethodOverridesReturnsOverloadCandidatesForMissingSignature() throws Exception {
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO class_members
                  (class_id, kind, name, signature, type_name, parameter_types,
                   modifiers, annotations)
                VALUES (2, 'METHOD', 'processPayment',
                        'processPayment(double):void', 'void', '["double"]',
                        'public abstract', '[]')"""));

        JsonNode result = JSON.readTree(new QuillTools().findMethodOverrides(
                jdbi, "PaymentService", "processPayment", "missing()", true, 10, 0));

        assertEquals("Method signature not found", result.path("error").asText());
        assertEquals("processPayment(double):void", result.path("candidates").get(0).asText());
    }

    @Test
    void findUnusedClassesExcludesRuntimeRootsAndSupportsScopeOptions() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
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
            handle.execute("""
                    INSERT INTO class_annotations(class_id, annotation_name, direct, via_annotation)
                    SELECT id, 'org.acme.RuntimeHook', 1, NULL FROM classes
                    WHERE class_name = 'org.acme.FrameworkHook'""");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    SELECT id, 'METHOD', 'main', 'main(java.lang.String[]):void', 'void',
                           '["java.lang.String[]"]', 'public static', '[]'
                    FROM classes WHERE class_name = 'org.acme.MainApp'""");
        });

        QuillTools tools = new QuillTools();
        JsonNode defaults = JSON.readTree(tools.findUnusedClasses(
                jdbi, null, false, false, 20, 0));
        JsonNode expanded = JSON.readTree(tools.findUnusedClasses(
                jdbi, null, true, true, 20, 0));

        assertEquals("candidates_not_proven_dead_code",
                defaults.path("classification").asText());
        assertEquals(2, defaults.path("total").asInt());
        assertEquals("org.acme.UnusedHelper",
                defaults.path("candidates").get(0).path("class").asText());
        assertEquals("medium", defaults.path("candidates").get(0)
                .path("confidence").asText());
        assertEquals("low", defaults.path("candidates").get(1)
                .path("confidence").asText());
        assertEquals(4, expanded.path("total").asInt());
        assertTrue(defaults.path("excluded_reason_counts")
                .path("hierarchy_reference").asInt() >= 1);
        assertEquals(1, defaults.path("excluded_reason_counts")
                .path("service_provider").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts")
                .path("main_entry_point").asInt());
        assertTrue(defaults.path("service_descriptor_evidence_available").asBoolean());
    }

    @Test
    void findUnusedMethodsMatchesOverloadsByDescriptorAndExcludesCallbacks() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO classes
                      (class_name, kind, superclass, interfaces, source_file, source_line,
                       is_bean, source_tokens, origin, lifecycle, module, source_set)
                    VALUES ('org.acme.PlainUtility', 'CLASS', 'java.lang.Object', '[]',
                            'src/main/java/org/acme/PlainUtility.java', 1,
                            0, 35, 'source', 'current', '.', 'main')""");
            handle.execute("""
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
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    SELECT id, 'METHOD', 'abandoned', 'abandoned():int', 'int',
                           '[]', 'private static', '[]'
                    FROM classes WHERE class_name = 'org.acme.PlainUtility'""");
            handle.execute("""
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (1, 'createOrder', '()V', 1, 'helper',
                            '(Ljava/lang/String;)V', 'special', 2, '[18,21]')""");
        });

        JsonNode result = JSON.readTree(new QuillTools().findUnusedMethods(
                jdbi, null, false, false, 20, 0));

        assertEquals("private_method_candidates_not_proven_dead_code",
                result.path("classification").asText());
        assertEquals(2, result.path("total").asInt());
        assertEquals("org.acme.PlainUtility",
                result.path("candidates").get(0).path("class").asText());
        assertEquals("medium", result.path("candidates").get(0)
                .path("confidence").asText());
        JsonNode overloaded = result.path("candidates").valueStream()
                .filter(candidate -> candidate.path("method").asText().equals("helper"))
                .findFirst().orElseThrow();
        assertEquals("helper(int):void", overloaded.path("signature").asText());
        assertEquals("(I)V", overloaded.path("descriptor").asText());
        assertEquals("low", overloaded.path("confidence").asText());
        assertEquals(1, result.path("excluded_reason_counts")
                .path("inbound_bytecode_call").asInt());
        assertEquals(1, result.path("excluded_reason_counts")
                .path("annotated_method").asInt());
        assertEquals(1, result.path("excluded_reason_counts")
                .path("conventional_runtime_callback").asInt());
        assertEquals(1, result.path("excluded_reason_counts")
                .path("native_method").asInt());
    }

    @Test
    void findUnusedFieldsUsesExactDescriptorsAndConservativeExclusions() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
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
            handle.execute("""
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
            handle.execute("""
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

        QuillTools tools = new QuillTools();
        JsonNode defaults = JSON.readTree(tools.findUnusedFields(
                jdbi, null, false, false, false, 20, 0));
        JsonNode expanded = JSON.readTree(tools.findUnusedFields(
                jdbi, null, false, false, true, 20, 0));

        assertEquals("private_field_candidates_not_proven_dead_code",
                defaults.path("classification").asText());
        assertEquals(1, defaults.path("total").asInt());
        assertEquals("unused", defaults.path("candidates").get(0).path("field").asText());
        assertEquals("I", defaults.path("candidates").get(0).path("descriptor").asText());
        assertEquals("never_accessed",
                defaults.path("candidates").get(0).path("candidate_kind").asText());
        assertEquals(2, expanded.path("total").asInt());
        JsonNode writeOnly = expanded.path("candidates").valueStream()
                .filter(candidate -> candidate.path("field").asText().equals("writeOnly"))
                .findFirst().orElseThrow();
        assertEquals("write_only", writeOnly.path("candidate_kind").asText());
        assertEquals(1, writeOnly.path("write_occurrences").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts")
                .path("annotated_field").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts")
                .path("static_final_constant").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts")
                .path("serializable_state").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts")
                .path("indexed_read").asInt());
        assertEquals(1, defaults.path("excluded_reason_counts")
                .path("write_only_not_requested").asInt());
    }

    @Test
    void findEntryPointsClassifiesFrameworkMethodsAndServiceProviders() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
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
            handle.execute("""
                    INSERT INTO class_annotations(class_id, annotation_name, direct, via_annotation)
                    SELECT id, 'jakarta.ws.rs.Path', 1, NULL FROM classes
                    WHERE class_name = 'org.acme.GreetingResource'""");
            handle.execute("""
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
            handle.execute("UPDATE metadata SET value = ? WHERE key = ?", """
                    [{"serviceType":"javax.annotation.processing.Processor",
                      "providerType":"org.acme.FirstProcessor",
                      "descriptorPath":"processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor","line":1},
                     {"serviceType":"java.lang.Runnable",
                      "providerType":"org.acme.PluginProvider",
                      "descriptorPath":"app/src/main/resources/META-INF/services/java.lang.Runnable","line":1}]
                    """, "service_registrations_detail");
        });

        QuillTools tools = new QuillTools();
        JsonNode result = JSON.readTree(tools.findEntryPoints(
                jdbi, null, null, false, false, 20, 0));
        JsonNode processors = JSON.readTree(tools.findEntryPoints(
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
        assertEquals("service_descriptor_registration",
                processors.path("entry_points").get(0).path("detection_rule").asText());
        assertEquals("javax.annotation.processing.Processor",
                processors.path("entry_points").get(0).path("service").path("type").asText());
    }

    @Test
    void getModuleGraphSeparatesDirectDependenciesFromTransitiveVisibility() throws Exception {
        IndexWriter.writeModuleClasspath(jdbi, List.of(
                new ModuleClasspathRecord("app", "app", 0, "self"),
                new ModuleClasspathRecord("app", "service", 1, "project_dependency"),
                new ModuleClasspathRecord("app", "common", 2, "project_dependency"),
                new ModuleClasspathRecord("service", "service", 0, "self"),
                new ModuleClasspathRecord("service", "common", 1, "project_dependency"),
                new ModuleClasspathRecord("common", "common", 0, "self")));
        jdbi.useHandle(handle -> handle.execute("""
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

        QuillTools tools = new QuillTools();
        JsonNode direct = JSON.readTree(tools.getModuleGraph(
                jdbi, null, "both", 5, 20, 0));
        JsonNode outbound = JSON.readTree(tools.getModuleGraph(
                jdbi, "app", "outbound", 2, 20, 0));
        JsonNode inbound = JSON.readTree(tools.getModuleGraph(
                jdbi, "common", "inbound", 2, 20, 0));
        JsonNode missing = JSON.readTree(tools.getModuleGraph(
                jdbi, "missing", "both", 2, 20, 0));

        assertEquals(2, direct.path("total").asInt());
        assertEquals(2, direct.path("direct_relations").asInt());
        assertEquals(0, direct.path("transitive_relations").asInt());
        assertEquals(2, outbound.path("total").asInt());
        assertEquals("direct_project_dependency",
                outbound.path("relations").get(0).path("kind").asText());
        assertEquals("transitive_classpath_visibility",
                outbound.path("relations").get(1).path("kind").asText());
        assertEquals(2, inbound.path("total").asInt());
        assertEquals("service", inbound.path("relations").get(0).path("from").asText());
        JsonNode common = inbound.path("nodes").valueStream()
                .filter(node -> node.path("module").asText().equals("common"))
                .findFirst().orElseThrow();
        assertEquals(1, common.path("generated_classes").asInt());
        assertEquals("Module not found: missing", missing.path("error").asText());
        assertEquals(3, missing.path("available_modules").size());
    }

    @Test
    void getPackageGraphAggregatesCouplingAndSupportsDirections() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    UPDATE classes
                    SET class_name = 'org.audit.AuditService',
                        source_file = 'src/main/java/org/audit/AuditService.java'
                    WHERE id = 4""");
            handle.execute("""
                    INSERT INTO dependencies
                      (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (4, 1, 'TYPE_REFERENCE', 2, '[8,12]')""");
        });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode graph = JSON.readTree(queries.getPackageGraph(
                jdbi, null, "both", null, false, false, 1, 0));
        assertEquals(2, graph.path("package_count").asInt());
        assertEquals(2, graph.path("total").asInt());
        assertEquals(1, graph.path("showing").asInt());
        assertTrue(graph.path("has_more").asBoolean());
        assertTrue(graph.path("relations").get(0).path("occurrence_count").asInt() >= 1);

        JsonNode inbound = JSON.readTree(queries.getPackageGraph(
                jdbi, "audit", "inbound", null, false, false, 10, 0));
        assertEquals("org.audit", inbound.path("package").asText());
        assertEquals(1, inbound.path("total").asInt());
        assertEquals("org.acme", inbound.path("relations").get(0).path("from").asText());
        assertEquals("org.audit", inbound.path("relations").get(0).path("to").asText());
        assertEquals(1, inbound.path("relations").get(0)
                .path("occurrences_by_kind").path("CONSTRUCTS").asInt());

        JsonNode outbound = JSON.readTree(queries.getPackageGraph(
                jdbi, "org.audit", "outbound", null, false, false, 10, 0));
        assertEquals(1, outbound.path("total").asInt());
        assertEquals(2, outbound.path("relations").get(0)
                .path("occurrences_by_kind").path("TYPE_REFERENCE").asInt());

        JsonNode missing = JSON.readTree(queries.getPackageGraph(
                jdbi, "missing", "both", null, false, false, 10, 0));
        assertEquals("Package not found: missing", missing.path("error").asText());
        assertTrue(missing.path("_meta").isObject());
    }

    @Test
    void findArchitectureViolationsEvaluatesPackageAndModuleRules() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    UPDATE classes
                    SET class_name = 'org.persistence.AuditRepository',
                        source_file = 'src/main/java/org/persistence/AuditRepository.java',
                        module = 'persistence', source_set = 'main'
                    WHERE id = 4""");
            handle.execute("UPDATE classes SET module = 'web', source_set = 'main' WHERE id = 1");
            handle.execute("""
                    INSERT INTO dependencies
                      (from_class_id, to_class_id, kind, occurrence_count, evidence_lines)
                    VALUES (1, 4, 'CALLS', 3, '[21,27]')""");
        });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode packages = JSON.readTree(queries.findArchitectureViolations(
                jdbi, "package", "org.acme..", List.of("org.persistence.."),
                List.of("CALLS"), false, false, 10, 0));
        assertFalse(packages.path("compliant").asBoolean());
        assertEquals(1, packages.path("total").asInt());
        assertEquals(3, packages.path("occurrence_count").asInt());
        assertEquals(3, packages.path("occurrences_by_kind").path("CALLS").asInt());
        assertEquals("org.acme.OrderService",
                packages.path("violations").get(0).path("from_class").asText());
        assertEquals("org.persistence.AuditRepository",
                packages.path("violations").get(0).path("to_class").asText());
        assertEquals(List.of(21, 27), packages.path("violations").get(0)
                .path("evidence_lines").valueStream().map(JsonNode::asInt).toList());
        assertTrue(packages.path("_meta").isObject());

        JsonNode modules = JSON.readTree(queries.findArchitectureViolations(
                jdbi, "module", "web", List.of("persistence"),
                List.of(), false, false, 10, 0));
        assertEquals(1, modules.path("total").asInt());
        assertEquals("web", modules.path("violations").get(0)
                .path("from_boundary").asText());

        JsonNode missingSource = JSON.readTree(queries.findArchitectureViolations(
                jdbi, "package", "org.missing..", List.of("org.persistence.."),
                List.of(), false, false, 10, 0));
        assertEquals("Source boundary pattern matched no indexed classes",
                missingSource.path("error").asText());
        assertTrue(missingSource.path("available_boundaries").isArray());

        JsonNode compliant = JSON.readTree(queries.findArchitectureViolations(
                jdbi, "package", "org.acme..", List.of("org.absent.."),
                List.of(), false, false, 10, 0));
        assertTrue(compliant.path("compliant").asBoolean());
        assertEquals(0, compliant.path("matched_forbidden_classes").asInt());
        assertEquals(0, compliant.path("total").asInt());
    }

    @Test
    void findArchitectureViolationsValidatesRules() throws Exception {
        QuillToolQueries queries = new QuillToolQueries();
        assertEquals("Invalid scope: expected package or module", JSON.readTree(
                queries.findArchitectureViolations(jdbi, "class", "org.acme",
                        List.of("org.persistence"), List.of(), false, false, 10, 0))
                .path("error").asText());
        assertEquals("At least one forbidden pattern is required", JSON.readTree(
                queries.findArchitectureViolations(jdbi, "package", "org.acme",
                        List.of(), List.of(), false, false, 10, 0))
                .path("error").asText());
    }

    @Test
    void findCyclesReturnsClassComponentsAndRepresentativePath() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
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
            handle.execute("""
                    INSERT INTO dependencies(from_class_id, to_class_id, kind,
                                             occurrence_count, evidence_lines)
                    VALUES (4, 1, 'CLASS_REFERENCE', 1, '[7]'),
                           (1, 4, 'METHOD_CALL', 2, '[18,21]'),
                           (5, 6, 'CONSTRUCTS', 1, '[]'),
                           (6, 5, 'TYPE_USE', 1, '[]')
                    """);
        });

        JsonNode result = JSON.readTree(new QuillTools().findCycles(
                jdbi, "class", null, false, false, 20, 0));

        assertEquals("class", result.path("scope").asText());
        assertEquals(1, result.path("total").asInt());
        assertEquals("collapsed_into_top_level_owner",
                result.path("nested_class_handling").asText());
        JsonNode cycle = result.path("cycles").get(0);
        assertEquals(3, cycle.path("member_count").asInt());
        assertEquals("org.acme.AuditService",
                cycle.path("representative_path").get(0).asText());
        assertEquals(cycle.path("representative_path").get(0).asText(),
                cycle.path("representative_path")
                        .get(cycle.path("representative_path").size() - 1).asText());
        assertTrue(cycle.path("edges").valueStream()
                .anyMatch(edge -> edge.path("kinds").toString().contains("CDI_INJECT")));
    }

    @Test
    void findCyclesUsesOnlyDirectModuleDependencies() throws Exception {
        IndexWriter.writeModuleClasspath(jdbi, List.of(
                new ModuleClasspathRecord("app", "app", 0, "self"),
                new ModuleClasspathRecord("app", "service", 1, "project_dependency"),
                new ModuleClasspathRecord("app", "common", 2, "project_dependency"),
                new ModuleClasspathRecord("service", "service", 0, "self"),
                new ModuleClasspathRecord("service", "app", 1, "project_dependency"),
                new ModuleClasspathRecord("common", "common", 0, "self")));

        JsonNode result = JSON.readTree(new QuillTools().findCycles(
                jdbi, "module", null, false, false, 20, 0));

        assertEquals(1, result.path("total").asInt());
        assertEquals(2, result.path("cycles").get(0).path("member_count").asInt());
        assertEquals(3, result.path("cycles").get(0)
                .path("representative_path").size());
        assertFalse(result.path("cycles").get(0).path("members").toString()
                .contains("common"));
    }

    @Test
    void findCyclesRejectsUnknownScope() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools().findCycles(
                jdbi, "package", null, false, false, 20, 0));
        assertEquals("Invalid scope: expected class or module", result.path("error").asText());
    }

    @Test
    void compareIndexReportsStructuralAndResolutionDeltas() throws Exception {
        Path quillDir = Files.createDirectories(tempDir.resolve(".quill"));
        Jdbi baseline = QuillDatabase.create(quillDir.resolve("baseline.db"));
        IndexWriter.write(baseline,
                List.of(
                        new ClassRecord(0, "org.acme.OrderService", "CLASS",
                                "java.lang.Object", List.of(),
                                "src/main/java/org/acme/OrderService.java", 10, true, 500),
                        new ClassRecord(0, "org.acme.PaymentService", "INTERFACE",
                                null, List.of(),
                                "src/main/java/org/acme/PaymentService.java", 5, false, 100),
                        new ClassRecord(0, "org.acme.StripePaymentService", "CLASS",
                                "java.lang.Object", List.of("org.acme.PaymentService"),
                                "src/main/java/org/acme/StripePaymentService.java", 8, true, 300)),
                List.of(
                        new BeanRecord(0, 1, "CLASS", "@ApplicationScoped",
                                List.of("@Default"), List.of(), false, null,
                                null, null, null, List.of("OrderService", "Object")),
                        new BeanRecord(0, 3, "CLASS", "@ApplicationScoped",
                                List.of("@Default", "@Premium"), List.of(), false, null,
                                List.of("prod"), null, null,
                                List.of("PaymentService", "StripePaymentService", "Object"))),
                List.of(InjectionPointRecord.staticAnalysis(0, 1, "FIELD", "PaymentService",
                        List.of("@Default"), "paymentService", 2, false,
                        InjectionPointRecord.STATIC_CDI).withResolution(2, false,
                        new ResolutionTrace(List.of(), List.of("TYPE_ASSIGNABILITY"), List.of()))),
                List.of(new DependencyRecord(1, 3, "CDI_INJECT", 1, 2)),
                Map.of("index_id", "baseline", "indexed_at", "2026-09-16T10:00:00Z",
                        "last_commit", "old-commit"));
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key, value) VALUES ('index_id', 'current')");
            handle.execute("UPDATE metadata SET value = '2026-09-17T10:00:00Z' "
                    + "WHERE key = 'indexed_at'");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name,
                       parameter_types, modifiers, annotations)
                    VALUES (1, 'METHOD', 'checkout', 'checkout():void', 'void',
                            '[]', '[public]', '[]')
                    """);
        });

        JsonNode result = JSON.readTree(new QuillTools().compareIndex(
                jdbi, tempDir, null, 20));

        assertEquals("baseline", result.path("baseline").path("index_id").asText());
        assertEquals("current", result.path("current").path("index_id").asText());
        assertEquals(1, result.path("classes").path("added_count").asInt());
        assertEquals("org.acme.AuditService",
                result.path("classes").path("added").get(0).asText());
        assertEquals(1, result.path("classes").path("modified_count").asInt());
        assertEquals(1, result.path("dependencies").path("added_count").asInt());
        assertEquals(1, result.path("dependencies")
                .path("occurrence_count_changed_count").asInt());
        assertEquals(1, result.path("beans").path("added_count").asInt());
        assertEquals(1, result.path("injections").path("added_count").asInt());
    }

    @Test
    void compareIndexListsAvailableBaselinesWhenRequestedOneIsMissing() throws Exception {
        Files.createDirectories(tempDir.resolve(".quill"));
        Jdbi baseline = QuillDatabase.create(tempDir.resolve(".quill/baseline.db"));
        IndexWriter.write(baseline, List.of(), List.of(), List.of(), List.of(),
                Map.of("index_id", "baseline", "indexed_at", "2026-09-16T10:00:00Z",
                        "last_commit", "old-commit"));
        jdbi.useHandle(handle -> handle.execute(
                "INSERT INTO metadata(key, value) VALUES ('index_id', 'current')"));

        JsonNode result = JSON.readTree(new QuillTools().compareIndex(
                jdbi, tempDir, "missing", 20));

        assertEquals("Baseline index not found: missing", result.path("error").asText());
        assertEquals("baseline", result.path("available_baselines").get(0).asText());
    }

    @Test
    void getBuildStatusReportsIntegrationWithoutStartingBuild() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>sample</artifactId><version>1</version>
                </project>
                """);
        Path classes = Files.createDirectories(tempDir.resolve("target/classes/org/acme"));
        Files.write(classes.resolve("Sample.class"), new byte[] {0, 1, 2});

        JsonNode result = JSON.readTree(new QuillTools().getBuildStatus(jdbi, tempDir));

        assertEquals("maven", result.path("build_system").asText());
        assertEquals(1, result.path("compiled_outputs").path("count").asInt());
        assertEquals("missing", result.path("integration").path("state").asText());
        assertEquals("integration_required", result.path("status").asText());
        assertTrue(result.path("action_required").asBoolean());
        assertFalse(result.path("build_was_started").asBoolean());
        assertEquals(0, result.path("build_events").path("pending").asInt());
    }

    @Test
    void resolveEntitiesSeparatesCurrentAndHistoricalPaths() throws Exception {
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO git_file_stats(file_path, class_id, commit_count, last_modified,
                    last_author, first_commit, distinct_authors)
                VALUES (?, NULL, 4, ?, ?, ?, 2)""",
                "src/main/java/org/acme/DeletedGenerator.java",
                "2026-08-25T08:00:00Z", "dev2", "2026-08-20T08:00:00Z"));
        JsonNode result = JSON.readTree(new QuillTools().resolveEntities(
                jdbi, List.of("OrderService", "DeletedGenerator.java", "NeverExisted")));
        JsonNode current = result.get("entities").get(0);
        assertTrue(current.get("current").asBoolean());
        assertEquals("current", current.get("resolution").asText());
        JsonNode deleted = result.get("entities").get(1);
        assertFalse(deleted.get("current").asBoolean());
        assertTrue(deleted.get("historical").asBoolean());
        assertTrue(deleted.get("deleted").asBoolean());
        assertEquals("src/main/java/org/acme/DeletedGenerator.java",
                deleted.get("historical_paths").get(0).get("file").asText());
        assertEquals("not_found", result.get("entities").get(2).get("resolution").asText());
    }

    @Test
    void inspectServiceDescriptorsPreservesProviderOrder() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools().inspectServiceDescriptors(
                jdbi, "Processor"));
        JsonNode descriptor = result.get("descriptors").get(0);
        assertEquals(2, descriptor.get("provider_count").asInt());
        assertEquals("org.acme.FirstProcessor",
                descriptor.get("providers").get(0).get("provider").asText());
        assertEquals(1, descriptor.get("providers").get(0).get("position").asInt());
        assertEquals(3, descriptor.get("providers").get(1).get("line").asInt());
        assertTrue(descriptor.get("order_can_affect_execution").asBoolean());
        assertEquals("potentially_significant", descriptor.get("order_sensitivity").asText());
    }

    @Test
    void dependencyGraphIsBounded() throws Exception {
        jdbi.useHandle(handle -> {
            var classes = handle.prepareBatch(
                    "INSERT INTO classes(class_name, kind, is_bean) VALUES (:name, 'CLASS', 0)");
            for (int i = 0; i < 250; i++) {
                classes.bind("name", "org.acme.Generated" + i).add();
            }
            classes.execute();
            var dependencies = handle.prepareBatch(
                    "INSERT INTO dependencies(from_class_id, to_class_id, kind) VALUES (1, :target, 'CLASS_REFERENCE')");
            for (int id = 5; id < 255; id++) {
                dependencies.bind("target", id).add();
            }
            dependencies.execute();
        });

        JsonNode result = JSON.readTree(
                new QuillTools().getDependencies(jdbi, "OrderService", "outbound", 1));
        assertEquals(200, result.get("depends_on").size());
        assertTrue(result.get("truncated").asBoolean());
        assertEquals(251, result.get("total").asInt());
        assertEquals(200, result.get("next_offset").asInt());
    }

    @Test
    void multiProjectResultsHaveDeterministicOrder() throws Exception {
        ProjectRegistry registry = new ProjectRegistry() {
            @Override
            public Resolution resolve() {
                return new Resolution(List.of(
                        new ProjectEntry("z-project", tempDir, jdbi),
                        new ProjectEntry("a-project", tempDir, jdbi)), List.of());
            }
        };

        JsonNode result = JSON.readTree(new QuillTools(registry).get_overview(
                Optional.empty(), Optional.empty()));
        assertEquals("a-project", result.get("projects").get(0).get("project").asText());
        assertEquals("z-project", result.get("projects").get(1).get("project").asText());
    }

    @Test
    void getDependenciesDepth2ExpandsTransitive() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "OrderService", "outbound", 2);
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
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "OrderService", "outbound", 1);
        JsonNode root = JSON.readTree(result);
        JsonNode dependsOn = root.get("depends_on");
        assertNull(dependsOn.get(0).get("depends_on"),
                "depth=1 should not expand nested dependencies");
    }

    @Test
    void getDependenciesCanReturnCompactMetricsOnly() throws Exception {
        JsonNode root = JSON.readTree(new QuillTools().getDependencies(
                jdbi, "OrderService", "both", 1, false, 50, 0, null));

        assertFalse(root.get("nodes_included").asBoolean());
        assertEquals(1, root.get("metrics").get("fan_out").asInt());
        assertNull(root.get("depends_on"));
        assertNull(root.get("depended_by"));
        assertNull(root.get("pagination"));
    }

    @Test
    void getDependenciesPaginatesDepthOneWithOffset() throws Exception {
        JsonNode first = JSON.readTree(new QuillTools().getDependencies(
                jdbi, "StripePaymentService", "both", 1, true, 1, 0, null));
        assertEquals(1, first.get("showing").asInt());
        assertEquals(2, first.get("total").asInt());
        assertEquals(1, first.get("next_offset").asInt());
        assertTrue(first.get("has_more").asBoolean());

        JsonNode second = JSON.readTree(new QuillTools().getDependencies(
                jdbi, "StripePaymentService", "both", 1, true, 1, 1, null));
        assertEquals(1, second.get("showing").asInt());
        assertEquals(2, second.get("total").asInt());
        assertFalse(second.get("has_more").asBoolean());
        assertNotEquals(relationClass(first), relationClass(second));
    }

    @Test
    void getDependenciesContinuesDeepBreadthFirstTraversalWithCursor() throws Exception {
        JsonNode first = JSON.readTree(new QuillTools().getDependencies(
                jdbi, "OrderService", "outbound", 2, true, 1, 0, null));
        assertEquals("org.acme.StripePaymentService",
                first.get("graph").get(0).get("class").asText());
        assertEquals(1, first.get("graph").get(0).get("depth").asInt());
        assertTrue(first.get("has_more").asBoolean());
        String cursor = first.get("next_cursor").asText();

        JsonNode second = JSON.readTree(new QuillTools().getDependencies(
                jdbi, "OrderService", "outbound", 2, true, 1, 0, cursor));
        assertEquals("org.acme.AuditService",
                second.get("graph").get(0).get("class").asText());
        assertEquals(2, second.get("graph").get(0).get("depth").asInt());
        assertFalse(second.get("has_more").asBoolean());
    }

    @Test
    void getDependenciesRejectsCursorForDifferentTraversal() throws Exception {
        JsonNode first = JSON.readTree(new QuillTools().getDependencies(
                jdbi, "OrderService", "outbound", 2, true, 1, 0, null));
        String cursor = first.get("next_cursor").asText();

        JsonNode invalid = JSON.readTree(new QuillTools().getDependencies(
                jdbi, "OrderService", "both", 2, true, 1, 0, cursor));
        assertEquals("Invalid or expired dependency cursor", invalid.get("error").asText());
    }

    private static String relationClass(JsonNode page) {
        JsonNode outbound = page.get("depends_on");
        if (outbound != null && !outbound.isEmpty()) return outbound.get(0).get("class").asText();
        return page.get("depended_by").get(0).get("class").asText();
    }

    @Test
    void getInjectionPointsShowsResolutionEvidenceAndKeepsMissingCandidateUnknown()
            throws Exception {
        var tools = new QuillTools();
        String result = tools.getInjectionPoints(jdbi, "OrderService");
        JsonNode root = JSON.readTree(result);
        assertEquals(2, root.get("injection_points").size());
        JsonNode unknown = root.get("injection_points").get(1);
        assertEquals("unknown", unknown.get("resolution").asText());
        assertEquals("STATIC_CDI", unknown.get("resolution_strategy").asText());
        assertEquals("NO_STATIC_CANDIDATE", unknown.get("reason").asText());
        assertEquals("low", unknown.get("confidence").asText());
        assertFalse(unknown.get("limitations").isEmpty());
        assertEquals(0, root.get("unsatisfied").size());
        assertEquals("runtimeProvidedService", root.get("unknown").get(0).asText());
        JsonNode selected = root.get("injection_points").get(0)
                .get("resolution_trace").get("candidates").get(0);
        assertEquals("org.acme.StripePaymentService", selected.get("class").asText());
        assertEquals("src/main/java/org/acme/StripePaymentService.java",
                selected.get("file").asText());
        assertEquals("selected", selected.get("disposition").asText());
        assertEquals("UNIQUE_ELIGIBLE_CANDIDATE", selected.get("reason").asText());
    }

    @Test
    void getHotspotsReturnsMostChanged() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, null);
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        assertNotNull(hotspots);
        assertTrue(hotspots.size() >= 2);
        assertEquals("src/main/java/org/acme/StripePaymentService.java",
                hotspots.get(0).get("file").asText());
        assertEquals(3, hotspots.get(0).get("commit_count").asInt());
    }

    @Test
    void hotspotPaginationReportsFullTotalAndTruncation() throws Exception {
        JsonNode root = JSON.readTree(new QuillTools().getHotspots(jdbi, 1, null));

        assertEquals(1, root.path("showing").asInt());
        assertEquals(3, root.path("total").asInt());
        assertTrue(root.path("truncated").asBoolean());
    }

    @Test
    void getHotspotsFiltersBySince() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, "2026-08-27");
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        for (JsonNode h : hotspots) {
            assertTrue(h.get("last_modified").asText().compareTo("2026-08-27") >= 0);
        }
    }

    @Test
    void getFileHistoryReturnsCommits() throws Exception {
        var tools = new QuillTools();
        String result = tools.getFileHistory(jdbi, "StripePaymentService", 10);
        JsonNode root = JSON.readTree(result);
        assertEquals("org.acme.StripePaymentService", root.get("target").asText());
        JsonNode commits = root.get("commits");
        assertNotNull(commits);
        assertEquals(3, commits.size());
        assertEquals("aaa1111", commits.get(0).get("hash").asText());
        assertEquals("dev1@test.com", commits.get(0).get("author_email").asText());
        JsonNode window = root.get("returned_window");
        assertEquals(3, window.get("commit_count").asInt());
        assertEquals(2, window.get("distinct_author_labels").asInt());
        assertEquals(3, window.get("distinct_author_emails").asInt());
        assertEquals("not_attempted", window.get("identity_resolution").asText());
        assertEquals(List.of("dev1", "dev2"), JSON.convertValue(
                window.get("author_labels"), JSON.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertEquals(3, root.get("total_commits").asInt());
        assertTrue(root.get("history_complete").asBoolean());
        assertFalse(root.get("has_more").asBoolean());
    }

    @Test
    void getFileHistorySupportsOffsetPagination() throws Exception {
        var tools = new QuillTools();
        JsonNode first = JSON.readTree(
                tools.getFileHistory(jdbi, "StripePaymentService", 2, 0));
        JsonNode second = JSON.readTree(
                tools.getFileHistory(jdbi, "StripePaymentService", 2, 2));

        assertEquals(2, first.get("showing").asInt());
        assertEquals(2, first.get("returned_window").get("commit_count").asInt());
        assertEquals(1, second.get("returned_window").get("commit_count").asInt());
        assertEquals(3, first.get("total_commits").asInt());
        assertEquals(2, first.get("next_offset").asInt());
        assertTrue(first.get("has_more").asBoolean());
        assertFalse(first.get("history_complete").asBoolean());
        assertEquals(1, second.get("showing").asInt());
        assertFalse(second.get("has_more").asBoolean());
        assertTrue(second.get("history_complete").asBoolean());
        assertNotEquals(first.get("commits").get(0).get("hash").asText(),
                second.get("commits").get(0).get("hash").asText());
    }

    @Test
    void getCoChangesFindsCorrelatedFiles() throws Exception {
        var tools = new QuillTools();
        String result = tools.getCoChanges(jdbi, "OrderService", 10);
        JsonNode root = JSON.readTree(result);
        assertEquals("org.acme.OrderService", root.get("target").asText());
        JsonNode coChanges = root.get("co_changes");
        assertNotNull(coChanges);
        assertTrue(coChanges.size() >= 1);
        boolean hasStripe = false;
        for (JsonNode co : coChanges) {
            if (co.get("file").asText().contains("StripePaymentService")) {
                hasStripe = true;
                assertTrue(co.get("co_change_count").asInt() >= 1);
                assertTrue(co.get("coupling_ratio").asDouble() > 0);
            }
        }
        assertTrue(hasStripe, "OrderService should co-change with StripePaymentService");
    }

    @Test
    void getRecentChangesReturnsCommitsWithFiles() throws Exception {
        var tools = new QuillTools();
        String result = tools.getRecentChanges(jdbi, 5);
        JsonNode root = JSON.readTree(result);
        JsonNode changes = root.get("recent_changes");
        assertNotNull(changes);
        assertEquals(3, changes.size());
        assertEquals("aaa1111", changes.get(0).get("commit").asText());
        JsonNode files = changes.get(0).get("files");
        assertNotNull(files);
        assertTrue(files.size() >= 1);
    }

    @Test
    void getRecentChangesPagesFilesAndSupportsCompactResponses() throws Exception {
        var tools = new QuillTools();
        JsonNode first = JSON.readTree(tools.getRecentChanges(jdbi, 5, 2, 0, false));
        JsonNode second = JSON.readTree(tools.getRecentChanges(jdbi, 5, 2, 2, false));

        assertEquals(2, first.path("showing").asInt());
        assertEquals(6, first.path("total").asInt());
        assertEquals(2, first.path("next_offset").asInt());
        assertTrue(first.path("has_more").asBoolean());
        assertEquals(2, second.path("showing").asInt());
        assertFalse(first.path("details").asBoolean());
        JsonNode firstCommit = first.path("recent_changes").get(0);
        assertFalse(firstCommit.has("author"));
        assertFalse(firstCommit.path("files").get(0).has("class"));
    }

    @Test
    void getOverviewReturnsProjectSummary() throws Exception {
        var tools = new QuillTools();
        String result = tools.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);

        JsonNode project = root.get("project");
        assertNotNull(project);
        assertEquals(4, project.get("classes").asInt());
        assertEquals(3, project.get("beans").asInt());
        assertEquals("degraded", project.get("dependency_index").asText());
        assertEquals("1/2 modules resolved", project.get("dependency_index_detail").asText());
        assertEquals(0, project.get("configuration_definitions").asInt());
        assertEquals(0, project.get("configuration_usages").asInt());

        JsonNode byScope = root.get("beans_by_scope");
        assertNotNull(byScope);
        assertTrue(byScope.has("@ApplicationScoped"));

        JsonNode byKind = root.get("beans_by_kind");
        assertNotNull(byKind);
        assertTrue(byKind.has("CLASS"));

        JsonNode hubs = root.get("architecture_hubs");
        assertNotNull(hubs);
        assertTrue(hubs.size() >= 1);
        assertTrue(hubs.get(0).has("total_dependents"));
        assertTrue(hubs.get(0).has("source_dependents"));
        assertTrue(hubs.get(0).has("generated_dependents"));
        assertFalse(hubs.get(0).has("dependents"),
                "The overview must not expose an unexplained mixed dependent count");
        JsonNode rankings = root.get("architecture_hub_rankings");
        assertNotNull(rankings);
        assertTrue(rankings.path("source").isArray());
        assertTrue(rankings.path("generated").isArray());
        assertTrue(rankings.path("production").isArray());
        assertTrue(rankings.path("test").isArray());

        JsonNode problems = root.get("problems");
        assertNotNull(problems);
        assertTrue(problems.has("unsatisfied_count"));
        assertTrue(problems.has("ambiguous_count"));
        assertEquals(0, problems.get("unsatisfied_count").asInt());
        assertEquals(1, problems.get("unknown_count").asInt());
        assertEquals("NO_STATIC_CANDIDATE",
                problems.get("unknown_injection_points_sample").get(0)
                        .get("reason").asText());

        JsonNode gitSummary = root.get("git_summary");
        assertNotNull(gitSummary);
        assertFalse(gitSummary.isNull());
        assertEquals(3, gitSummary.get("total_commits_indexed").asInt());
        assertTrue(gitSummary.get("top_hotspots").size() >= 1);

        assertTrue(root.has("_meta"));
        assertTrue(root.path("_meta").has("indexed_commit"));
        assertFalse(root.path("_meta").has("compression"));
    }

    @Test
    void getOverviewWithoutGitDataShowsNull() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit-overview.db"));
        var classes = List.of(
                new ClassRecord(0, "org.acme.Foo", "CLASS", null, List.of(), "Foo.java", 1, true, 100));
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("Foo")));
        IndexWriter.write(noGitJdbi, classes, beans, List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "unknown"));

        var tools = new QuillTools();
        String result = tools.getOverview(noGitJdbi);
        JsonNode root = JSON.readTree(result);
        assertTrue(root.get("git_summary").isNull());
        assertEquals(1, root.get("project").get("beans").asInt());
    }

    @Test
    void getRiskReturnsScoreAndSignals() throws Exception {
        var tools = new QuillTools();
        String result = tools.getRisk(jdbi, "StripePaymentService");
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

        JsonNode sourcePath = JSON.readTree(tools.getRisk(jdbi,
                "src/main/java/org/acme/StripePaymentService.java"));
        assertEquals("class", sourcePath.path("target_type").asText());
        assertEquals("org.acme.StripePaymentService", sourcePath.path("target").asText());
    }

    @Test
    void getRiskWithoutGitDataExcludesGitSignals() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit-risk.db"));
        var classes = List.of(
                new ClassRecord(0, "org.acme.Bar", "CLASS", null, List.of(), "Bar.java", 1, true, 100));
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("Bar")));
        IndexWriter.write(noGitJdbi, classes, beans, List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "unknown"));

        var tools = new QuillTools();
        String result = tools.getRisk(noGitJdbi, "Bar");
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
        var tools = new QuillTools();
        String result = tools.getRisk(jdbi, "NonExistentClass");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Class not found"));
    }

    @Test
    void getExternalDepsForClassShowsTypesByKind() throws Exception {
        var tools = new QuillTools();
        String result = tools.getExternalDeps(jdbi, "StripePaymentService", null, 20);
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
        var tools = new QuillTools();
        String result = tools.getExternalDeps(jdbi, null, null, 20);
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
        var tools = new QuillTools();
        String result = tools.getExternalDeps(jdbi, null, "com.stripe", 20);
        JsonNode root = JSON.readTree(result);

        assertEquals("com.stripe", root.get("library_filter").asText());
        JsonNode classes = root.get("classes_using_library");
        assertNotNull(classes);
        assertEquals(1, classes.size());
        assertEquals("org.acme.StripePaymentService", classes.get(0).asText());
    }

    @Test
    void getBeansRespectsLimit() throws Exception {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, null, null, 1);
        JsonNode root = JSON.readTree(result);
        assertEquals(1, root.get("showing").asInt());
        assertEquals(3, root.get("total").asInt());
    }

    @Test
    void listBeansUsesStablePaginationEnvelopeAndOffset() throws Exception {
        JsonNode root = JSON.readTree(new QuillToolQueries().getBeans(
                jdbi, null, null, null, null, null, null, null, 1, 1));

        assertEquals(1, root.path("showing").asInt());
        assertEquals(3, root.path("total").asInt());
        assertEquals(1, root.path("limit").asInt());
        assertEquals(1, root.path("offset").asInt());
        assertTrue(root.path("has_more").asBoolean());
        assertEquals(2, root.path("next_offset").asInt());
        assertTrue(root.path("truncated").asBoolean());
    }

    @Test
    void searchClassesReportsExactTotalAndNextOffset() throws Exception {
        JsonNode root = JSON.readTree(new QuillToolQueries().searchClasses(
                jdbi, "org.acme.*", null, null, 1, 0));

        assertEquals(1, root.path("showing").asInt());
        assertEquals(4, root.path("total").asInt());
        assertTrue(root.path("has_more").asBoolean());
        assertEquals(1, root.path("next_offset").asInt());
    }

    @Test
    void compactOverviewOmitsDiagnosticSamplesAndDuplicateRankings() throws Exception {
        ProjectOverviewQueries overview = new ProjectOverviewQueries(new GitToolQueries());
        JsonNode root = JSON.readTree(overview.getOverview(jdbi, false));

        assertFalse(root.has("architecture_hub_rankings"));
        assertFalse(root.path("problems").has("unknown_injection_points_sample"));
        assertEquals(1, root.path("problems").path("unknown_count").asInt());

        JsonNode detailed = JSON.readTree(overview.getOverview(jdbi, true));
        assertTrue(detailed.has("architecture_hub_rankings"));
        assertTrue(detailed.path("problems").has("unknown_injection_points_sample"));
    }

    @Test
    void getHotspotsSinceRecomputesCounts() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, "2026-08-28");
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        assertNotNull(hotspots);
        for (JsonNode h : hotspots) {
            String file = h.get("file").asText();
            int count = h.get("commit_count").asInt();
            if (file.contains("OrderService")) {
                assertEquals(1, count,
                        "OrderService had 1 commit on 2026-08-28, not lifetime count of 2");
            }
        }
    }

    @Test
    void getHotspotsSinceExcludesOlderCommits() throws Exception {
        var tools = new QuillTools();
        String result = tools.getHotspots(jdbi, 10, "2026-08-28");
        JsonNode root = JSON.readTree(result);
        JsonNode hotspots = root.get("hotspots");
        for (JsonNode h : hotspots) {
            assertFalse(h.get("file").asText().equals("src/main/java/org/acme/PaymentService.java"),
                    "PaymentService.java only has commits before 2026-08-28 and should be excluded");
        }
    }

    @Test
    void currentHotspotsExcludeDeletedFilesButHistoryRemainsAddressableByPath() throws Exception {
        Path repository = tempDir.resolve("history-repository");
        Path current = repository.resolve("src/main/java/example/Current.java");
        Path deleted = repository.resolve("src/main/java/example/Deleted.java");
        java.nio.file.Files.createDirectories(current.getParent());
        java.nio.file.Files.writeString(current, "package example; class Current {}\n");
        java.nio.file.Files.writeString(deleted, "package example; class Deleted {}\n");

        GitAnalyzer.GitAnalysisResult analysis;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
            java.nio.file.Files.delete(deleted);
            git.rm().addFilepattern("src/main/java/example/Deleted.java").call();
            git.commit().setMessage("delete stale class").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
            analysis = GitAnalyzer.analyze(repository, 10,
                    Map.of("src/main/java/example/Current.java", 1));
        }

        Jdbi historyDb = QuillDatabase.create(tempDir.resolve("history.db"));
        IndexWriter.write(historyDb, List.of(new ClassRecord(0, "example.Current", "CLASS",
                        "java.lang.Object", List.of(), "src/main/java/example/Current.java",
                        1, false, 10)), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-09-08T00:00:00Z",
                        "last_commit", analysis.headHash(),
                        "project_root", repository.toString()));
        IndexWriter.writeGitData(historyDb, analysis.fileStats(), analysis.commits(),
                analysis.commitFiles());
        historyDb.useHandle(handle -> handle.createUpdate("""
                        INSERT INTO files(project_path, repository_path, kind, origin, lifecycle)
                        VALUES (:path, :path, 'java', 'source', 'historical')""")
                .bind("path", "src/main/java/example/Deleted.java")
                .execute());

        var tools = new QuillTools();
        JsonNode currentOnly = JSON.readTree(tools.getHotspots(historyDb, 10, null, false));
        assertFalse(currentOnly.path("hotspots").toString().contains("Deleted.java"));
        JsonNode overview = JSON.readTree(tools.getOverview(historyDb));
        assertFalse(overview.path("git_summary").path("top_hotspots").toString()
                .contains("Deleted.java"));

        JsonNode withHistory = JSON.readTree(tools.getHotspots(historyDb, 10, null, true));
        assertTrue(withHistory.path("hotspots").toString().contains("Deleted.java"));
        assertTrue(withHistory.path("hotspots").toString().contains("historical"));

        JsonNode history = JSON.readTree(tools.getFileHistory(historyDb,
                "src/main/java/example/Deleted.java", 10));
        assertEquals("historical", history.path("lifecycle").asText());
        assertEquals(2, history.path("commits").size());

        JsonNode missingClass = JSON.readTree(tools.getRisk(historyDb, "example.Deleted"));
        assertEquals("Class not found", missingClass.path("error").asText());
        assertTrue(missingClass.path("candidates").toString().contains("Deleted.java"));
        assertTrue(missingClass.path("candidates").toString().contains("historical"));
        assertEquals(1, missingClass.path("candidates").size());
        assertTrue(missingClass.path("_meta").isObject());
    }

    @Test
    void searchClassesIncludesOriginAndLifecycle() throws Exception {
        JsonNode result = JSON.readTree(new QuillTools().searchClasses(jdbi, "OrderService", 10));

        assertEquals("source", result.path("classes").get(0).path("origin").asText());
        assertEquals("current", result.path("classes").get(0).path("lifecycle").asText());
    }

    @Test
    void getAnnotatedClassesSeparatesDirectAndMetaMatches() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'org.acme.Tracked', 1, NULL)""");
            handle.execute("""
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (3, 'org.acme.Tracked', 0, 'org.acme.Specialized')""");
            handle.execute("UPDATE classes SET origin = 'generated' WHERE id = 3");
        });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode all = JSON.readTree(
                queries.getAnnotatedClasses(jdbi, "@Tracked", true, 1, 0));
        assertEquals("org.acme.Tracked", all.path("annotation").asText());
        assertEquals(2, all.path("annotated_class_count").asInt());
        assertEquals(1, all.path("direct_class_count").asInt());
        assertEquals(1, all.path("meta_only_class_count").asInt());
        assertEquals(1, all.path("origin_breakdown").path("source").asInt());
        assertEquals(1, all.path("origin_breakdown").path("generated").asInt());
        assertEquals(1, all.path("showing").asInt());
        assertTrue(all.path("has_more").asBoolean());

        JsonNode meta = JSON.readTree(
                queries.getAnnotatedClasses(jdbi, "org.acme.Tracked", true, 10, 1));
        assertEquals("meta", meta.path("classes").get(0).path("match").asText());
        assertEquals("org.acme.Specialized", meta.path("classes").get(0)
                .path("via_annotations").get(0).asText());

        JsonNode directOnly = JSON.readTree(
                queries.getAnnotatedClasses(jdbi, "Tracked", false, 10, 0));
        assertEquals(1, directOnly.path("annotated_class_count").asInt());
        assertEquals("direct", directOnly.path("classes").get(0).path("match").asText());
    }

    @Test
    void getAnnotatedClassesRejectsAmbiguousShortNames() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'org.acme.Tracked', 1, NULL)""");
            handle.execute("""
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (2, 'other.Tracked', 1, NULL)""");
        });

        JsonNode result = JSON.readTree(new QuillToolQueries()
                .getAnnotatedClasses(jdbi, "Tracked", true, 10, 0));

        assertEquals("Ambiguous annotation name", result.path("error").asText());
        assertEquals(List.of("org.acme.Tracked", "other.Tracked"), result.path("candidates")
                .valueStream().map(JsonNode::asText).toList());
    }

    @Test
    void findAnnotatedSymbolsIncludesTypesAndMembers() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_annotations
                      (class_id, annotation_name, direct, via_annotation)
                    VALUES (1, 'org.acme.Tracked', 0, 'org.acme.Specialized')""");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations, annotation_details)
                    VALUES (1, 'METHOD', 'submit', 'submit():void', 'void', '[]',
                            'public', '["org.acme.Tracked"]',
                            '[{"annotationName":"org.acme.Tracked","targetKind":"METHOD","parameterIndex":-1,"parameterName":"","parameterType":""}]')""");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations, annotation_details)
                    VALUES (1, 'METHOD', 'lookup', 'lookup(java.lang.String):void',
                            'void', '["java.lang.String"]', 'public',
                            '["org.acme.Tracked"]',
                            '[{"annotationName":"org.acme.Tracked","targetKind":"METHOD_PARAMETER","parameterIndex":0,"parameterName":"id","parameterType":"java.lang.String"}]')""");
            handle.execute("""
                    INSERT INTO class_members
                      (class_id, kind, name, signature, type_name, parameter_types,
                       modifiers, annotations)
                    VALUES (3, 'FIELD', 'audit', 'audit:boolean', 'boolean', '[]',
                            'private', '["org.acme.Tracked"]')""");
        });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode all = JSON.readTree(queries.findAnnotatedSymbols(
                jdbi, "@Tracked", "all", true, 2, 0));
        assertEquals("org.acme.Tracked", all.path("annotation").asText());
        assertEquals(4, all.path("total").asInt());
        assertEquals(2, all.path("showing").asInt());
        assertTrue(all.path("has_more").asBoolean());
        assertEquals("TYPE", all.path("symbols").get(0).path("kind").asText());
        assertEquals("meta", all.path("symbols").get(0).path("match").asText());
        assertEquals("org.acme.Specialized",
                all.path("symbols").get(0).path("via_annotation").asText());

        JsonNode fields = JSON.readTree(queries.findAnnotatedSymbols(
                jdbi, "org.acme.Tracked", "field", false, 10, 0));
        assertEquals(1, fields.path("total").asInt());
        assertEquals("audit", fields.path("symbols").get(0).path("name").asText());
        assertEquals("direct", fields.path("symbols").get(0).path("match").asText());
        assertEquals("type_declarations_only",
                fields.path("meta_annotation_scope").asText());
        assertTrue(fields.path("_meta").isObject());

        JsonNode parameters = JSON.readTree(queries.findAnnotatedSymbols(
                jdbi, "Tracked", "parameter", false, 10, 0));
        assertEquals(1, parameters.path("total").asInt());
        assertEquals("PARAMETER", parameters.path("symbols").get(0).path("kind").asText());
        assertEquals("METHOD", parameters.path("symbols").get(0)
                .path("declared_kind").asText());
        assertEquals("METHOD_PARAMETER", parameters.path("symbols").get(0)
                .path("annotation_target").asText());
        assertEquals(0, parameters.path("symbols").get(0)
                .path("parameter").path("index").asInt());
        assertEquals("id", parameters.path("symbols").get(0)
                .path("parameter").path("name").asText());
    }

    @Test
    void findConfigurationReferencesLinksDefinitionsWithoutExposingValues() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO configuration_definitions
                      (key, kind, file, line, module, source_set) VALUES
                      ('orders.region', 'property',
                       'src/main/resources/application.properties', 1, '.', 'main'),
                      ('orders.timeout', 'yaml_property',
                       'src/main/resources/application.yml', 2, '.', 'main'),
                      ('unrelated.enabled', 'property',
                       'src/main/resources/application.properties', 3, '.', 'main')
                    """);
            handle.execute("""
                    INSERT INTO configuration_usages
                      (key, kind, class_id, class_name, member, parameter_index,
                       annotation, source, module, source_set) VALUES
                      ('orders.region', 'config_key', 1, 'org.acme.OrderService',
                       'region', NULL,
                       'org.springframework.beans.factory.annotation.Value',
                       'src/main/java/org/acme/OrderService.java', '.', 'main'),
                      ('orders', 'config_prefix', 1, 'org.acme.OrderService',
                       NULL, NULL,
                       'org.springframework.boot.context.properties.ConfigurationProperties',
                       'src/main/java/org/acme/OrderService.java', '.', 'main'),
                      ('<dynamic>', 'dynamic_config_key', 1, 'org.acme.OrderService',
                       'lookup', NULL, 'java.lang.System#getProperty',
                       'src/main/java/org/acme/OrderService.java', '.', 'main')
                    """);
        });

        JsonNode result = JSON.readTree(new QuillToolQueries().findConfigurationReferences(
                jdbi, "orders*", "OrderService", "all", null, 20, 0));

        assertEquals(4, result.path("total").asInt(), result.toString());
        assertEquals(2, result.path("definition_count").asInt());
        assertEquals(2, result.path("usage_count").asInt());
        assertFalse(result.path("values_indexed").asBoolean());
        JsonNode directUsage = result.path("references").valueStream()
                .filter(value -> value.path("entry_type").asText().equals("usage"))
                .filter(value -> value.path("key").asText().equals("orders.region"))
                .findFirst().orElseThrow();
        assertTrue(directUsage.path("resolved").asBoolean());
        assertEquals(1, directUsage.path("definition_count").asInt());
        assertEquals("src/main/resources/application.properties",
                directUsage.path("definition_files").get(0).asText());
        assertFalse(result.toString().contains("us-west"));

        JsonNode dynamic = JSON.readTree(new QuillToolQueries().findConfigurationReferences(
                jdbi, "<dynamic>", null, "property", null, 20, 0));
        JsonNode dynamicUsage = dynamic.path("references").get(0);
        assertEquals("unknown", dynamicUsage.path("resolution_status").asText());
        assertEquals("dynamic_programmatic_lookup",
                dynamicUsage.path("resolution_strategy").asText());
        assertFalse(dynamicUsage.path("resolved").asBoolean());

        JsonNode exact = JSON.readTree(new QuillToolQueries().findConfigurationReferences(
                jdbi, "orders.timeout", null, "all", null, 20, 0));
        assertTrue(exact.path("references").valueStream()
                .anyMatch(value -> value.path("kind").asText().equals("config_prefix")));

        JsonNode prefixes = JSON.readTree(new QuillToolQueries().findConfigurationReferences(
                jdbi, null, null, "prefix", null, 20, 0));
        assertEquals(3, prefixes.path("total").asInt());
        assertFalse(prefixes.toString().contains("unrelated.enabled"));
    }

    @Test
    void findResourceReferencesLinksClasspathFilesAndConsumers() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO files
                      (project_path, repository_path, kind, origin, lifecycle, module, source_set)
                    VALUES ('src/main/resources/templates/order.html',
                            'src/main/resources/templates/order.html',
                            'resource', 'resource', 'current', '.', 'main')
                    """);
            handle.execute("""
                    INSERT INTO resource_usages
                      (resource_path, kind, class_id, class_name, member, api, source,
                       module, source_set)
                    VALUES ('templates/order.html', 'resource', 1,
                            'org.acme.OrderService', 'render',
                            'java.lang.Class#getResource',
                            'src/main/java/org/acme/OrderService.java', '.', 'main')
                    """);
        });

        JsonNode result = JSON.readTree(new QuillToolQueries().findResourceReferences(
                jdbi, "templates/order.html", "OrderService", null, 20, 0));

        assertEquals(2, result.path("total").asInt(), result.toString());
        assertEquals(1, result.path("definition_count").asInt());
        assertEquals(1, result.path("usage_count").asInt());
        JsonNode usage = result.path("references").valueStream()
                .filter(value -> value.path("entry_type").asText().equals("usage"))
                .findFirst().orElseThrow();
        assertEquals("resolved", usage.path("resolution_status").asText());
        assertEquals("src/main/resources/templates/order.html",
                usage.path("definition_files").get(0).asText());
    }

    @Test
    void findResourceReferencesDistinguishesBundleFamiliesAndExternalLocations() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO files
                      (project_path, repository_path, kind, origin, lifecycle, module, source_set)
                    VALUES ('src/test/resources/messages_en_CA.properties',
                            'src/test/resources/messages_en_CA.properties',
                            'resource', 'resource', 'current', '.', 'test')
                    """);
            handle.execute("""
                    INSERT INTO resource_usages
                      (resource_path, kind, class_id, class_name, member, api, source,
                       module, source_set)
                    VALUES ('messages.properties', 'resource_bundle', 1,
                            'org.acme.OrderService', 'messages',
                            'java.util.ResourceBundle#getBundle',
                            'src/main/java/org/acme/OrderService.java', '.', 'main'),
                           ('file:/tmp/order.html', 'external_resource', 1,
                            'org.acme.OrderService', 'external',
                            'org.springframework.core.io.ResourceLoader#getResource',
                            'src/main/java/org/acme/OrderService.java', '.', 'main')
                    """);
        });

        JsonNode bundle = JSON.readTree(new QuillToolQueries().findResourceReferences(
                jdbi, "messages.properties", null, null, 20, 0));
        JsonNode bundleUsage = bundle.path("references").valueStream()
                .filter(value -> value.path("entry_type").asText().equals("usage"))
                .findFirst().orElseThrow();
        assertEquals("resolved", bundleUsage.path("resolution_status").asText());
        assertEquals("resource_bundle_family",
                bundleUsage.path("resolution_strategy").asText());
        assertEquals("src/test/resources/messages_en_CA.properties",
                bundleUsage.path("definition_files").get(0).asText());

        JsonNode external = JSON.readTree(new QuillToolQueries().findResourceReferences(
                jdbi, "file:/tmp/order.html", null, null, 20, 0));
        JsonNode externalUsage = external.path("references").get(0);
        assertEquals("unsupported_mechanism",
                externalUsage.path("resolution_status").asText());
        assertEquals(0, externalUsage.path("definition_files").size());
    }

    @Test
    void referenceQueriesResolveFullPagesWithBoundedSqlStatements() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO files
                      (project_path, repository_path, kind, origin, lifecycle, module, source_set)
                    VALUES ('src/main/resources/templates/order.html',
                            'src/main/resources/templates/order.html',
                            'resource', 'resource', 'current', '.', 'main')
                    """);
            handle.execute("""
                    INSERT INTO configuration_definitions
                      (key, kind, file, line, module, source_set)
                    VALUES ('app.name', 'property',
                            'src/main/resources/application.properties', 1, '.', 'main')
                    """);
            for (int index = 0; index < 100; index++) {
                handle.createUpdate("""
                        INSERT INTO configuration_usages
                          (key, kind, class_id, class_name, member, annotation, source,
                           module, source_set)
                        VALUES ('app.name', 'config_key', 1, 'org.acme.OrderService', :member,
                                'java.lang.System#getProperty',
                                'src/main/java/org/acme/OrderService.java', '.', 'main')
                        """).bind("member", "config" + index).execute();
                handle.createUpdate("""
                        INSERT INTO resource_usages
                          (resource_path, kind, class_id, class_name, member, api, source,
                           module, source_set)
                        VALUES ('templates/order.html', 'resource', 1,
                                'org.acme.OrderService', :member,
                                'java.lang.Class#getResource',
                                'src/main/java/org/acme/OrderService.java', '.', 'main')
                        """).bind("member", "resource" + index).execute();
            }
        });

        AtomicInteger statements = new AtomicInteger();
        jdbi.setSqlLogger(new SqlLogger() {
            @Override
            public void logAfterExecution(StatementContext context) {
                statements.incrementAndGet();
            }
        });
        JsonNode configuration = JSON.readTree(new QuillToolQueries()
                .findConfigurationReferences(jdbi, "app.name", null, "all", null, 100, 0));
        assertEquals(101, configuration.path("total").asInt());
        assertTrue(statements.get() <= 10, "configuration SQL statements: " + statements);

        statements.set(0);
        JsonNode resources = JSON.readTree(new QuillToolQueries()
                .findResourceReferences(jdbi, "templates/order.html", null, null, 100, 0));
        assertEquals(101, resources.path("total").asInt());
        assertTrue(statements.get() <= 10, "resource SQL statements: " + statements);
    }

    @Test
    void findFrameworkEndpointsReturnsRoutesAndDirectCalls() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (1, 'submit', '(Ljava/lang/String;)V',
                            3, 'charge', '(Ljava/lang/String;)V', 'virtual', 2, '[42]')""");
            handle.createUpdate("""
                    INSERT INTO metadata(key, value) VALUES ('framework_endpoints_detail', :value)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value""")
                    .bind("value", """
                            [{"classId":1,"className":"org.acme.OrderService",
                              "methodName":"submit","signature":"submit(java.lang.String):void",
                              "descriptor":"(Ljava/lang/String;)V","framework":"spring",
                              "httpMethods":["POST"],"classPaths":["/orders"],
                              "methodPaths":["/{id}"],
                              "annotations":["org.springframework.web.bind.annotation.PostMapping"]}]
                            """)
                    .execute();
        });

        JsonNode result = JSON.readTree(new QuillToolQueries().findFrameworkEndpoints(
                jdbi, "spring", "post", "/orders", null,
                false, false, 10, 0));

        assertEquals(1, result.path("total").asInt());
        JsonNode endpoint = result.path("endpoints").get(0);
        assertEquals("/orders/{id}", endpoint.path("paths").get(0).asText());
        assertEquals("submit", endpoint.path("method").asText());
        assertEquals(1, endpoint.path("direct_project_call_count").asInt());
        assertEquals("org.acme.StripePaymentService",
                endpoint.path("direct_project_calls").get(0).path("class").asText());
        assertEquals(2, endpoint.path("direct_project_calls").get(0)
                .path("occurrence_count").asInt());
        assertTrue(result.path("_meta").isObject());
    }

    @Test
    void structuralQueriesExposeDuplicateOccurrencesAndFilterByOccurrenceModule()
            throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                    1, "app-one", "app-one/target/classes",
                    "app-one/target/classes/org/acme/StripePaymentService.class",
                    "app-one/src/main/java/org/acme/StripePaymentService.java", "source");
            handle.execute("""
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                    2, "app-two", "app-two/target/classes",
                    "app-two/target/classes/org/acme/StripePaymentService.class",
                    "app-two/target/generated-sources/annotations/org/acme/StripePaymentService.java",
                    "generated");
        });

        QuillToolQueries queries = new QuillToolQueries();
        JsonNode search = JSON.readTree(
                queries.searchClasses(jdbi, "StripePaymentService", "app-two", "main", 10));
        JsonNode found = search.path("classes").get(0);
        assertEquals(2, found.path("occurrence_count").asInt());
        assertEquals("app-one", found.path("class_occurrences").get(0).path("module").asText());
        assertEquals("app-two", found.path("class_occurrences").get(1).path("module").asText());

        JsonNode beans = JSON.readTree(queries.getBeans(jdbi, "*StripePaymentService*",
                null, null, null, null, "app-two", "main", 10));
        assertEquals(1, beans.path("total").asInt());
        assertEquals(2, beans.path("beans").get(0).path("occurrence_count").asInt());

        JsonNode dependencies = JSON.readTree(
                queries.getDependencies(jdbi, "StripePaymentService", "both", 1));
        assertEquals(2, dependencies.path("occurrence_count").asInt());
    }

    @Test
    void findImplementationsReportsGeneratedOccurrencesByModule() throws Exception {
        jdbi.useHandle(handle -> {
            handle.execute("""
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                    11, "checkout-one", "checkout-one/target/classes",
                    "checkout-one/target/classes/org/acme/StripePaymentService.class",
                    "checkout-one/target/generated-sources/annotations/org/acme/StripePaymentService.java",
                    "generated");
            handle.execute("""
                    INSERT INTO class_occurrences
                      (id, class_id, class_name, module, source_set, output_directory,
                       class_file, source_file, origin)
                    VALUES (?, 3, 'org.acme.StripePaymentService', ?, 'main', ?, ?, ?, ?)""",
                    12, "checkout-two", "checkout-two/target/classes",
                    "checkout-two/target/classes/org/acme/StripePaymentService.class",
                    "checkout-two/target/generated-sources/annotations/org/acme/StripePaymentService.java",
                    "generated");
        });

        JsonNode result = JSON.readTree(new QuillTools().findImplementations(
                jdbi, "PaymentService", true, null, null, 10, 0));

        assertEquals("org.acme.PaymentService", result.path("target").asText());
        assertEquals(1, result.path("implementation_class_count").asInt());
        assertEquals(2, result.path("implementation_occurrence_count").asInt());
        assertEquals(2, result.path("generated_implementation_count").asInt());
        assertEquals(List.of("checkout-one", "checkout-two"),
                result.path("generated_modules").valueStream().map(JsonNode::asText).toList());
        assertTrue(result.path("selection_depends_on_application_context").asBoolean());
        assertEquals("maven_reactor",
                result.path("index_scope").path("module_discovery").asText());
        assertTrue(result.path("index_scope").path("complete").asBoolean());
        assertTrue(result.path("index_scope")
                .path("excludes_undeclared_project_directories").asBoolean());
        assertEquals("org.acme.StripePaymentService",
                result.path("implementations").get(0).path("class").asText());
        assertEquals(2, result.path("implementations").get(0)
                .path("occurrence_count").asInt());

        JsonNode filtered = JSON.readTree(new QuillTools().findImplementations(
                jdbi, "PaymentService", true, "checkout-two", null, 10, 0));
        assertEquals(1, filtered.path("implementation_occurrence_count").asInt());
        assertEquals(List.of("checkout-two"), filtered.path("generated_modules")
                .valueStream().map(JsonNode::asText).toList());
        assertFalse(filtered.path("selection_depends_on_application_context").asBoolean());
    }

    @Test
    void findImplementationsCanExcludeTransitiveDescendants() throws Exception {
        jdbi.useHandle(handle -> handle.execute("""
                INSERT INTO classes
                  (class_name, kind, superclass, interfaces, source_file, source_line,
                   is_bean, source_tokens, origin, lifecycle, module, source_set)
                VALUES ('org.acme.SpecialStripePaymentService', 'CLASS',
                        'org.acme.StripePaymentService', '[]',
                        'src/main/java/org/acme/SpecialStripePaymentService.java', 1,
                        0, 20, 'source', 'current', 'payments', 'main')"""));

        QuillTools tools = new QuillTools();
        JsonNode direct = JSON.readTree(tools.findImplementations(
                jdbi, "PaymentService", false, null, null, 10, 0));
        JsonNode transitive = JSON.readTree(tools.findImplementations(
                jdbi, "PaymentService", true, null, null, 10, 0));

        assertEquals(1, direct.path("implementation_class_count").asInt());
        assertEquals(2, transitive.path("implementation_class_count").asInt());
        assertEquals(2, transitive.path("implementations").get(1).path("distance").asInt());
    }

    @Test
    void documentationOnlyWorktreeDoesNotMakeStructureStale() throws Exception {
        Path repository = tempDir.resolve("docs-repository");
        Path source = repository.resolve("src/main/java/example/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; class App {}\n");
        String head;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            head = git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setSign(false).call().getName();
        }
        WorktreeInspector.Snapshot indexed = WorktreeInspector.inspect(repository);
        Jdbi docsDb = QuillDatabase.create(tempDir.resolve("docs.db"));
        IndexWriter.write(docsDb, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-09-09T00:00:00Z", "last_commit", head,
                        "project_root", repository.toString(),
                        "indexed_structure_fingerprint", indexed.structuralFingerprint(),
                        "compiled_before_index", "false"));

        Files.writeString(repository.resolve("README.md"), "documentation only\n");
        JsonNode meta = JSON.readTree(new QuillTools().getOverview(docsDb)).path("_meta");

        assertTrue(meta.path("worktree_dirty").asBoolean());
        assertEquals(1, meta.path("worktree_changed_files").asInt());
        assertEquals(0, meta.path("structural_changed_files").asInt());
        assertFalse(meta.path("structure_stale").asBoolean());
        assertTrue(meta.path("stale_reasons").isEmpty());
    }

    @Test
    void responsesExposeDirtyWorktreeFreshnessAndResourceOverlay() throws Exception {
        Path repository = tempDir.resolve("dirty-repository");
        Path service = repository.resolve(
                "src/main/resources/META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "example.FirstProcessor\nexample.SecondProcessor\n");

        GitAnalyzer.GitAnalysisResult analysis;
        try (Git git = Git.init().setDirectory(repository.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
            analysis = GitAnalyzer.analyze(repository, 10, Map.of());
        }
        WorktreeInspector.Snapshot indexed = WorktreeInspector.inspect(repository);
        Jdbi dirtyDb = QuillDatabase.create(tempDir.resolve("dirty.db"));
        IndexWriter.write(dirtyDb, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-09-08T00:00:00Z",
                        "last_commit", analysis.headHash(),
                        "project_root", repository.toString(),
                        "indexed_structure_fingerprint", indexed.structuralFingerprint(),
                        "compiled_before_index", "false"));
        IndexWriter.writeGitData(dirtyDb, analysis.fileStats(), analysis.commits(),
                analysis.commitFiles());

        Files.writeString(service, "example.SecondProcessor\nexample.FirstProcessor\n");

        var tools = new QuillTools();
        JsonNode overview = JSON.readTree(tools.getOverview(dirtyDb));
        JsonNode meta = overview.path("_meta");
        assertEquals(analysis.headHash(), meta.path("indexed_commit").asText());
        assertEquals(analysis.headHash(), meta.path("current_commit").asText());
        assertTrue(meta.path("worktree_dirty").asBoolean());
        assertTrue(meta.path("structure_stale").asBoolean());
        assertFalse(meta.path("commit_stale").asBoolean());
        assertTrue(meta.path("stale_reasons").toString()
                .contains("worktree_changed_after_index"));
        assertTrue(meta.path("stale_reasons").toString()
                .contains("dirty_worktree_not_compiled"));

        JsonNode hotspots = JSON.readTree(tools.getHotspots(dirtyDb, 10, null, false));
        assertTrue(hotspots.path("worktree_changes").toString()
                .contains("javax.annotation.processing.Processor"));
        assertTrue(hotspots.path("worktree_changes").toString().contains("modified"));

        JsonNode trackedRisk = JSON.readTree(tools.getRisk(dirtyDb,
                "src/main/resources/META-INF/services/javax.annotation.processing.Processor"));
        assertEquals("file", trackedRisk.path("target_type").asText());
        assertEquals("service_descriptor", trackedRisk.path("kind").asText());
        assertEquals("modified", trackedRisk.path("worktree_status").asText());
        assertTrue(trackedRisk.path("risk_score").asDouble() >= 6.0);
        assertTrue(trackedRisk.path("signals").has("file_criticality"));
        assertTrue(trackedRisk.path("signals").has("git_churn"));
        assertFalse(trackedRisk.path("signals").has("fan_in"));

        Path untrackedService = repository.resolve(
                "src/main/resources/META-INF/services/example.NewProvider");
        Files.writeString(untrackedService, "example.Provider\n");
        WorktreeSnapshotCache.shared().invalidate(repository);
        JsonNode untrackedRisk = JSON.readTree(tools.getRisk(dirtyDb,
                "src/main/resources/META-INF/services/example.NewProvider"));
        assertEquals("file", untrackedRisk.path("target_type").asText());
        assertEquals("untracked", untrackedRisk.path("worktree_status").asText());
        assertEquals("CRITICAL", untrackedRisk.path("risk_level").asText());
        assertEquals(10.0, untrackedRisk.path("risk_score").asDouble());
        assertTrue(untrackedRisk.path("signals").path("git").path("note").asText()
                .contains("No Git history"));

        Files.writeString(repository.resolve("pom.xml"), "<project/>\n");
        WorktreeSnapshotCache.shared().invalidate(repository);
        JsonNode buildRisk = JSON.readTree(tools.getRisk(dirtyDb, "./pom.xml"));
        assertEquals("file", buildRisk.path("target_type").asText());
        assertEquals("build_configuration", buildRisk.path("kind").asText());
        assertEquals("CRITICAL", buildRisk.path("risk_level").asText());
        assertEquals("untracked", buildRisk.path("worktree_status").asText());
    }

    @Test
    void gitToolsReturnErrorWithoutGitData() throws Exception {
        Jdbi noGitJdbi = QuillDatabase.create(tempDir.resolve("nogit.db"));
        IndexWriter.write(noGitJdbi, List.of(), List.of(), List.of(), List.of(),
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "unknown"));

        var tools = new QuillTools();
        String result = tools.getHotspots(noGitJdbi, 10, null);
        assertTrue(result.contains("No git data available"));
        assertTrue(result.contains("git init"));

        result = tools.getFileHistory(noGitJdbi, "SomeClass", 10);
        assertTrue(result.contains("No git data available"));
    }
}
