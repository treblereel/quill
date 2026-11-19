package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.model.*;

/** Bean, injection point and bean-origin tools. */
class QuillToolsBeansTest extends QuillToolsTestBase {

    @Test
    void getBeansReturnsAllBeans() {
        String result = JDBI_READS.getBeans(jdbi, null, null, null, null, null).toString();
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("\"total\":3"));
    }

    @Test
    void getBeansResolvesShortClassName() throws Exception {
        JsonNode shortName =
                JDBI_READS.getBeans(jdbi, "StripePaymentService", null, null, null, null);
        JsonNode fqcn =
                JDBI_READS.getBeans(jdbi, "org.acme.StripePaymentService", null, null, null, null);

        assertEquals(1, shortName.get("total").asInt());
        assertEquals(fqcn.get("beans"), shortName.get("beans"));
    }

    @Test
    void getBeansReturnsDependencyBeanWithConfigurationContext() throws Exception {
        IndexFixture.writeExternalBeans(
                jdbi,
                List.of(
                        new ExternalBeanRecord(
                                1,
                                "io.casehub.connectors.twilio.TwilioSmsConnector",
                                "CLASS",
                                "@ApplicationScoped",
                                List.of("@Default", "@Any"),
                                List.of(),
                                false,
                                false,
                                null,
                                List.of(),
                                null,
                                List.of("io.casehub.connectors.twilio.TwilioSmsConnector"),
                                "cdi",
                                "io.casehub:casehub-connectors-core:0.2-SNAPSHOT",
                                "/tmp/casehub-connectors-core.jar",
                                List.of(
                                        new ExternalInjectionPointRecord(
                                                "FIELD",
                                                "java.lang.String",
                                                List.of("@ConfigProperty"),
                                                "accountSid",
                                                List.of(
                                                        "org.eclipse.microprofile.config.inject.ConfigProperty"),
                                                "casehub.connectors.twilio.account-sid",
                                                null,
                                                true)))));

        JsonNode result =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .getBeans(
                                                readScope,
                                                "TwilioSmsConnector",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "dependency",
                                                50,
                                                0));

        assertEquals(1, result.path("total").asInt());
        JsonNode bean = result.path("beans").get(0);
        assertEquals("dependency", bean.path("origin").asText());
        assertEquals(
                "io.casehub:casehub-connectors-core:0.2-SNAPSHOT", bean.path("artifact").asText());
        assertEquals(
                "casehub.connectors.twilio.account-sid",
                bean.path("injection_points").get(0).path("configurationKey").asText());
        assertTrue(bean.path("injection_points").get(0).path("configurationRequired").asBoolean());
        assertEquals(1, result.path("configuration_summary").path("required_count").asInt());
        assertEquals(
                "casehub.connectors.twilio.account-sid",
                result.path("configuration_summary")
                        .path("required_properties")
                        .get(0)
                        .path("key")
                        .asText());
        assertTrue(result.path("answer_coverage").path("configuration_requirements").asBoolean());
    }

    @Test
    void getBeansReturnsCandidatesForUnknownClassName() throws Exception {
        JsonNode result = JDBI_READS.getBeans(jdbi, "PaymentServ", null, null, null, null);

        assertEquals("CLASS_NOT_FOUND", result.path("error_code").asText());
        assertEquals("Class not found", result.path("message").asText());
        assertTrue(result.get("candidates").toString().contains("PaymentService"));
    }

    @Test
    void singleProjectQueryFailureReturnsStructuredToolError() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute("DROP TABLE dependencies");
                    handle.execute("DROP TABLE injection_points");
                    handle.execute("DROP TABLE beans");
                });
        ProjectRegistry registry =
                new ProjectRegistry() {
                    @Override
                    public Resolution resolve() {
                        return new Resolution(
                                List.of(projectEntries.entry("broken-project", tempDir, jdbi)),
                                List.of());
                    }
                };

        JsonNode root =
                new QuillTools(registry)
                        .list_beans(
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty());

        assertTrue(root.get("error").asText().contains("broken-project"));
        assertTrue(root.get("error").asText().contains("beans"));
    }

    @Test
    void getBeansFiltersByScope() {
        String result =
                JDBI_READS.getBeans(jdbi, null, "@ApplicationScoped", null, null, null).toString();
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("AuditService"));
    }

    @Test
    void getBeansFiltersByProfile() {
        String result = JDBI_READS.getBeans(jdbi, null, null, null, "dev", null).toString();
        assertTrue(result.contains("AuditService"));
        assertTrue(result.contains("OrderService"));
        assertFalse(result.contains("StripePaymentService"));
        assertTrue(result.contains("\"total\":2"));
    }

    @Test
    void getBeansFiltersByQualifier() {
        String result = JDBI_READS.getBeans(jdbi, null, null, null, null, "@Premium").toString();
        assertTrue(result.contains("StripePaymentService"));
        assertFalse(result.contains("OrderService"));
        assertFalse(result.contains("AuditService"));
        assertTrue(result.contains("\"total\":1"));
    }

    @Test
    void beanOriginAcceptsSourceForApplication() throws Exception {
        JsonNode source =
                ReadQueryContext.read(
                        jdbi,
                        reader ->
                                new QuillToolQueries()
                                        .getBeans(
                                                reader, null, null, null, null, null, null, null,
                                                "SOURCE", 50, 0));
        JsonNode application =
                ReadQueryContext.read(
                        jdbi,
                        reader ->
                                new QuillToolQueries()
                                        .getBeans(
                                                reader,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "application",
                                                50,
                                                0));

        assertFalse(source.has("error_code"), source.toString());
        assertEquals(application.path("beans"), source.path("beans"));
    }

    @Test
    void interfaceInjectionQueryGuidesToConsumers() throws Exception {
        JsonNode result = JDBI_READS.getInjectionPoints(jdbi, "PaymentService");
        assertEquals("dependencies_injected_into_bean", result.path("query_direction").asText());
        assertEquals("find_usages", result.path("next_step").path("tool").asText());
        assertEquals(
                "org.acme.PaymentService",
                result.path("next_step").path("arguments").path("target").asText());
    }

    @Test
    void getInjectionPointsShowsResolutionEvidenceAndKeepsMissingCandidateUnknown()
            throws Exception {
        String result = JDBI_READS.getInjectionPoints(jdbi, "OrderService").toString();
        JsonNode root = JSON.readTree(result);
        assertEquals(2, root.get("injection_points").size());
        assertEquals(2, root.path("injection_points_total").asInt());
        assertEquals(2, root.path("injection_points_by_kind").path("FIELD").asInt());
        assertEquals("@ApplicationScoped", root.path("scope").asText());
        assertEquals("CLASS", root.path("bean_kind").asText());
        assertEquals(
                "@ApplicationScoped",
                root.path("injection_points").get(0).path("resolved_scope").asText());
        assertFalse(root.path("injection_points").get(1).has("resolved_scope"));
        JsonNode unknown = root.get("injection_points").get(1);
        assertEquals("unknown", unknown.get("resolution").asText());
        assertEquals("STATIC_CDI", unknown.get("resolution_strategy").asText());
        assertEquals("NO_STATIC_CANDIDATE", unknown.get("reason").asText());
        assertEquals("low", unknown.get("confidence").asText());
        assertFalse(root.path("resolution_context").path("limitations").isEmpty());
        assertTrue(unknown.path("limitations").isEmpty());
        assertTrue(
                root.path("resolution_context")
                        .path("applies_to")
                        .asText()
                        .contains("All injection_points"));
        assertFalse(root.path("resolution_context").has("applied_rules"));
        assertTrue(
                root.path("injection_points")
                        .get(0)
                        .path("resolution_trace")
                        .path("applied_rules")
                        .toString()
                        .contains("TYPE_ASSIGNABILITY"));
        assertEquals(0, root.get("unsatisfied").size());
        assertEquals("runtimeProvidedService", root.get("unknown").get(0).asText());
        JsonNode selected =
                root.get("injection_points")
                        .get(0)
                        .get("resolution_trace")
                        .get("candidates")
                        .get(0);
        assertEquals("org.acme.StripePaymentService", selected.get("class").asText());
        assertEquals(
                "src/main/java/org/acme/StripePaymentService.java", selected.get("file").asText());
        assertEquals("@ApplicationScoped", selected.path("scope").asText());
        assertEquals("selected", selected.get("disposition").asText());
        assertEquals("UNIQUE_ELIGIBLE_CANDIDATE", selected.get("reason").asText());
    }

    @Test
    void injectionEvidenceSeparatesOwnerScopeFromProducerScope() throws Exception {
        Jdbi jdbi = QuillDatabase.create(tempDir.resolve("producer.db"));
        var classes =
                List.of(
                        new ClassRecord(
                                0,
                                "example.Consumer",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "Consumer.java",
                                1,
                                true,
                                10),
                        new ClassRecord(
                                0,
                                "example.Factory",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "Factory.java",
                                1,
                                true,
                                10));
        var beans =
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
                                List.of("example.Consumer")),
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
                                List.of("example.Factory")),
                        new BeanRecord(
                                0,
                                2,
                                "PRODUCER_METHOD",
                                "@RequestScoped",
                                List.of("@Default"),
                                List.of(),
                                false,
                                null,
                                null,
                                2,
                                "create",
                                List.of("example.Widget")));
        var ips =
                List.of(
                        InjectionPointRecord.staticAnalysis(
                                0,
                                1,
                                "FIELD",
                                "example.Widget",
                                List.of("@Default"),
                                "widget",
                                3,
                                false,
                                InjectionPointRecord.STATIC_CDI));
        IndexWriter.writeIncremental(
                jdbi,
                new IndexFixture().classes(classes).beans(beans).injectionPoints(ips).snapshot());
        var response = JDBI_READS.getInjectionPoints(jdbi, "example.Consumer");
        assertEquals("@Singleton", response.path("scope").asText());
        assertEquals(1, response.path("injection_points_total").asInt());
        assertEquals(1, response.path("injection_points_by_kind").path("FIELD").asInt());
        var point = response.path("injection_points").get(0);
        assertEquals("@RequestScoped", point.path("resolved_scope").asText());
        assertEquals("PRODUCER_METHOD", point.path("resolved_kind").asText());
        assertEquals("create", point.path("resolved_member").asText());
        assertEquals("example.Widget", point.path("resolved_produced_type").asText());
    }

    @Test
    void getBeansRespectsLimit() throws Exception {
        String result = JDBI_READS.getBeans(jdbi, null, null, null, null, null, 1).toString();
        JsonNode root = JSON.readTree(result);
        assertEquals(1, root.get("showing").asInt());
        assertEquals(3, root.get("total").asInt());
    }

    @Test
    void listBeansUsesStablePaginationEnvelopeAndOffset() throws Exception {
        JsonNode root =
                ReadQueryContext.readWithGraph(
                        jdbi,
                        readScope ->
                                new QuillToolQueries()
                                        .getBeans(
                                                readScope, null, null, null, null, null, null, null,
                                                1, 1));

        assertEquals(1, root.path("showing").asInt());
        assertEquals(3, root.path("total").asInt());
        assertEquals(1, root.path("limit").asInt());
        assertEquals(1, root.path("offset").asInt());
        assertTrue(root.path("has_more").asBoolean());
        assertEquals(2, root.path("next_offset").asInt());
        assertTrue(root.path("truncated").asBoolean());
    }
}
