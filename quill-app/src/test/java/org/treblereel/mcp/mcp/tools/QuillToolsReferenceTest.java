package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdbi.v3.core.statement.SqlLogger;
import org.jdbi.v3.core.statement.StatementContext;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.*;

/** Configuration, resource and framework endpoint references. */
class QuillToolsReferenceTest extends QuillToolsTestBase {

    @Test
    void findConfigurationReferencesLinksDefinitionsWithoutExposingValues() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO configuration_definitions
                      (key, kind, file, line, module, source_set) VALUES
                      ('orders.region', 'property',
                       'src/main/resources/application.properties', 1, '.', 'main'),
                      ('orders.timeout', 'yaml_property',
                       'src/main/resources/application.yml', 2, '.', 'main'),
                      ('unrelated.enabled', 'property',
                       'src/main/resources/application.properties', 3, '.', 'main')
                    """);
                    handle.execute(
                            """
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

        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findConfigurationReferences(
                                                        readScope,
                                                        "orders*",
                                                        "OrderService",
                                                        "all",
                                                        null,
                                                        20,
                                                        0)));

        assertEquals(4, result.path("total").asInt(), result.toString());
        assertEquals(2, result.path("definition_count").asInt());
        assertEquals(2, result.path("usage_count").asInt());
        assertFalse(result.path("values_indexed").asBoolean());
        JsonNode directUsage =
                result.path("references")
                        .valueStream()
                        .filter(value -> value.path("entry_type").asText().equals("usage"))
                        .filter(value -> value.path("key").asText().equals("orders.region"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(directUsage.path("resolved").asBoolean());
        assertEquals(1, directUsage.path("definition_count").asInt());
        assertEquals(
                "src/main/resources/application.properties",
                directUsage.path("definition_files").get(0).asText());
        assertFalse(result.toString().contains("us-west"));

        JsonNode dynamic =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findConfigurationReferences(
                                                        readScope,
                                                        "<dynamic>",
                                                        null,
                                                        "property",
                                                        null,
                                                        20,
                                                        0)));
        JsonNode dynamicUsage = dynamic.path("references").get(0);
        assertEquals("unknown", dynamicUsage.path("resolution_status").asText());
        assertEquals(
                "dynamic_programmatic_lookup", dynamicUsage.path("resolution_strategy").asText());
        assertFalse(dynamicUsage.path("resolved").asBoolean());

        JsonNode exact =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findConfigurationReferences(
                                                        readScope,
                                                        "orders.timeout",
                                                        null,
                                                        "all",
                                                        null,
                                                        20,
                                                        0)));
        assertTrue(
                exact.path("references")
                        .valueStream()
                        .anyMatch(value -> value.path("kind").asText().equals("config_prefix")));

        JsonNode prefixes =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findConfigurationReferences(
                                                        readScope, null, null, "prefix", null, 20,
                                                        0)));
        assertEquals(3, prefixes.path("total").asInt());
        assertFalse(prefixes.toString().contains("unrelated.enabled"));
    }

    @Test
    void findResourceReferencesLinksClasspathFilesAndConsumers() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO files
                      (project_path, repository_path, kind, origin, lifecycle, module, source_set)
                    VALUES ('src/main/resources/templates/order.html',
                            'src/main/resources/templates/order.html',
                            'resource', 'resource', 'current', '.', 'main')
                    """);
                    handle.execute(
                            """
                    INSERT INTO resource_usages
                      (resource_path, kind, class_id, class_name, member, api, source,
                       module, source_set)
                    VALUES ('templates/order.html', 'resource', 1,
                            'org.acme.OrderService', 'render',
                            'java.lang.Class#getResource',
                            'src/main/java/org/acme/OrderService.java', '.', 'main')
                    """);
                });

        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findResourceReferences(
                                                        readScope,
                                                        "templates/order.html",
                                                        "OrderService",
                                                        null,
                                                        20,
                                                        0)));

        assertEquals(2, result.path("total").asInt(), result.toString());
        assertEquals(1, result.path("definition_count").asInt());
        assertEquals(1, result.path("usage_count").asInt());
        JsonNode usage =
                result.path("references")
                        .valueStream()
                        .filter(value -> value.path("entry_type").asText().equals("usage"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("resolved", usage.path("resolution_status").asText());
        assertEquals(
                "src/main/resources/templates/order.html",
                usage.path("definition_files").get(0).asText());
    }

    @Test
    void findResourceReferencesDistinguishesBundleFamiliesAndExternalLocations() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO files
                      (project_path, repository_path, kind, origin, lifecycle, module, source_set)
                    VALUES ('src/test/resources/messages_en_CA.properties',
                            'src/test/resources/messages_en_CA.properties',
                            'resource', 'resource', 'current', '.', 'test')
                    """);
                    handle.execute(
                            """
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

        JsonNode bundle =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findResourceReferences(
                                                        readScope,
                                                        "messages.properties",
                                                        null,
                                                        null,
                                                        20,
                                                        0)));
        JsonNode bundleUsage =
                bundle.path("references")
                        .valueStream()
                        .filter(value -> value.path("entry_type").asText().equals("usage"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("resolved", bundleUsage.path("resolution_status").asText());
        assertEquals("resource_bundle_family", bundleUsage.path("resolution_strategy").asText());
        assertEquals(
                "src/test/resources/messages_en_CA.properties",
                bundleUsage.path("definition_files").get(0).asText());

        JsonNode external =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findResourceReferences(
                                                        readScope,
                                                        "file:/tmp/order.html",
                                                        null,
                                                        null,
                                                        20,
                                                        0)));
        JsonNode externalUsage = external.path("references").get(0);
        assertEquals("unsupported_mechanism", externalUsage.path("resolution_status").asText());
        assertEquals(0, externalUsage.path("definition_files").size());
    }

    @Test
    void referenceQueriesResolveFullPagesWithBoundedSqlStatements() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO files
                      (project_path, repository_path, kind, origin, lifecycle, module, source_set)
                    VALUES ('src/main/resources/templates/order.html',
                            'src/main/resources/templates/order.html',
                            'resource', 'resource', 'current', '.', 'main')
                    """);
                    handle.execute(
                            """
                    INSERT INTO configuration_definitions
                      (key, kind, file, line, module, source_set)
                    VALUES ('app.name', 'property',
                            'src/main/resources/application.properties', 1, '.', 'main')
                    """);
                    for (int index = 0; index < 100; index++) {
                        handle.createUpdate(
                                        """
                        INSERT INTO configuration_usages
                          (key, kind, class_id, class_name, member, annotation, source,
                           module, source_set)
                        VALUES ('app.name', 'config_key', 1, 'org.acme.OrderService', :member,
                                'java.lang.System#getProperty',
                                'src/main/java/org/acme/OrderService.java', '.', 'main')
                        """)
                                .bind("member", "config" + index)
                                .execute();
                        handle.createUpdate(
                                        """
                        INSERT INTO resource_usages
                          (resource_path, kind, class_id, class_name, member, api, source,
                           module, source_set)
                        VALUES ('templates/order.html', 'resource', 1,
                                'org.acme.OrderService', :member,
                                'java.lang.Class#getResource',
                                'src/main/java/org/acme/OrderService.java', '.', 'main')
                        """)
                                .bind("member", "resource" + index)
                                .execute();
                    }
                });

        AtomicInteger statements = new AtomicInteger();
        jdbi.setSqlLogger(
                new SqlLogger() {
                    @Override
                    public void logAfterExecution(StatementContext context) {
                        statements.incrementAndGet();
                    }
                });
        JsonNode configuration =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findConfigurationReferences(
                                                        readScope,
                                                        "app.name",
                                                        null,
                                                        "all",
                                                        null,
                                                        100,
                                                        0)));
        assertEquals(101, configuration.path("total").asInt());
        assertTrue(statements.get() <= 10, "configuration SQL statements: " + statements);

        statements.set(0);
        JsonNode resources =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findResourceReferences(
                                                        readScope,
                                                        "templates/order.html",
                                                        null,
                                                        null,
                                                        100,
                                                        0)));
        assertEquals(101, resources.path("total").asInt());
        assertTrue(statements.get() <= 10, "resource SQL statements: " + statements);
    }

    @Test
    void findFrameworkEndpointsReturnsRoutesAndDirectCalls() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO method_calls
                      (from_class_id, from_method, from_descriptor,
                       to_class_id, to_method, to_descriptor, invocation_kind,
                       occurrence_count, evidence_lines)
                    VALUES (1, 'submit', '(Ljava/lang/String;)V',
                            3, 'charge', '(Ljava/lang/String;)V', 'virtual', 2, '[42]')""");
                    handle.createUpdate(
                                    """
                    INSERT INTO metadata(key, value) VALUES ('framework_endpoints_detail', :value)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value""")
                            .bind(
                                    "value",
                                    """
                            [{"classId":1,"className":"org.acme.OrderService",
                              "methodName":"submit","signature":"submit(java.lang.String):void",
                              "descriptor":"(Ljava/lang/String;)V","framework":"spring",
                              "httpMethods":["POST"],"classPaths":["/orders"],
                              "methodPaths":["/{id}"],
                              "annotations":["org.springframework.web.bind.annotation.PostMapping"]}]
                            """)
                            .execute();
                });

        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findFrameworkEndpoints(
                                                        readScope, "spring", "post", "/orders",
                                                        null, false, false, 10, 0)));

        assertEquals(1, result.path("total").asInt());
        JsonNode endpoint = result.path("endpoints").get(0);
        assertEquals("/orders/{id}", endpoint.path("paths").get(0).asText());
        assertEquals("submit", endpoint.path("method").asText());
        assertEquals(1, endpoint.path("direct_project_call_count").asInt());
        assertEquals(
                "org.acme.StripePaymentService",
                endpoint.path("direct_project_calls").get(0).path("class").asText());
        assertEquals(
                2, endpoint.path("direct_project_calls").get(0).path("occurrence_count").asInt());
        assertTrue(result.path("_meta").isObject());
    }

    @Test
    void findFrameworkEndpointsFiltersCustomVerbsAndPreservesComposedEvidence() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.createUpdate(
                                        """
                INSERT INTO metadata(key,value) VALUES ('framework_endpoints_detail', :value)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value""")
                                .bind(
                                        "value",
                                        """
                        [{"classId":1,"className":"org.acme.OrderService","methodName":"clear",
                          "signature":"clear():void","descriptor":"()V","framework":"jax-rs",
                          "httpMethods":["purge"],"classPaths":["/cache"],"methodPaths":[""],
                          "annotations":["org.acme.Purge"]},
                         {"classId":1,"className":"org.acme.OrderService","methodName":"read",
                          "signature":"read():void","descriptor":"()V","framework":"spring",
                          "httpMethods":["GET"],"classPaths":["/api"],"methodPaths":["/one","/two"],
                          "annotations":["org.acme.Read","org.springframework.web.bind.annotation.RequestMapping"]}]
                        """)
                                .execute());
        var queries = new QuillToolQueries();
        JsonNode purge =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findFrameworkEndpoints(
                                                readScope, "jax-rs", "purge", "/cache", null, false,
                                                false, 10, 0)));
        assertEquals(1, purge.path("total").asInt());
        assertEquals("/cache", purge.path("endpoints").get(0).path("paths").get(0).asText());
        JsonNode spring =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        queries.findFrameworkEndpoints(
                                                readScope, "spring", "GET", "/api", null, false,
                                                false, 10, 0)));
        assertEquals(1, spring.path("total").asInt());
        assertEquals(
                List.of("/api/one", "/api/two"),
                spring.path("endpoints")
                        .get(0)
                        .path("paths")
                        .valueStream()
                        .map(JsonNode::asText)
                        .toList());
        assertEquals(
                "org.acme.Read",
                spring.path("endpoints").get(0).path("annotations").get(0).asText());
        assertTrue(
                spring.path("limitations")
                        .valueStream()
                        .anyMatch(v -> v.asText().contains("Inherited")));
    }

    @Test
    void inheritedEndpointsUseBodyOwnerForDirectCalls() throws Exception {
        jdbi.useHandle(
                handle -> {
                    handle.execute(
                            """
                    INSERT INTO method_calls(from_class_id,from_method,from_descriptor,
                      to_class_id,to_method,to_descriptor,invocation_kind,occurrence_count,evidence_lines)
                    VALUES(3,'handle','()V',1,'submit','()V','virtual',1,'[20]')""");
                    handle.createUpdate(
                                    "INSERT INTO metadata(key,value) VALUES ('framework_endpoints_detail', :value)")
                            .bind(
                                    "value",
                                    """
                            [{"classId":1,"className":"org.acme.OrderService","methodName":"handle",
                              "signature":"handle():void","descriptor":"()V","framework":"spring",
                              "httpMethods":["GET"],"classPaths":["/orders"],"methodPaths":[""],
                              "annotations":["GetMapping"],"declaringClassId":3,
                              "declaringClassName":"org.acme.StripePaymentService",
                              "mappingSources":["method:org.acme.StripePaymentService#handle()V"]}]
                            """)
                            .execute();
                });
        JsonNode result =
                JSON.readTree(
                        ReadQueryContext.<String>readWithGraph(
                                jdbi,
                                readScope ->
                                        new QuillToolQueries()
                                                .findFrameworkEndpoints(
                                                        readScope, "spring", null, null, null,
                                                        false, false, 10, 0)));
        JsonNode endpoint = result.path("endpoints").get(0);
        assertEquals("org.acme.OrderService", endpoint.path("class").asText());
        assertEquals("org.acme.StripePaymentService", endpoint.path("declaring_class").asText());
        assertTrue(endpoint.path("direct_call_targets_indexed").asBoolean());
        assertEquals(1, endpoint.path("direct_project_call_count").asInt());
        assertEquals(
                "org.acme.OrderService",
                endpoint.path("direct_project_calls").get(0).path("class").asText());
        assertEquals(
                "method:org.acme.StripePaymentService#handle()V",
                endpoint.path("mapping_sources").get(0).asText());
    }

    @Test
    void externalInheritedBodyDoesNotClaimEmptyProjectCallSet() throws Exception {
        jdbi.useHandle(
                handle ->
                        handle.createUpdate(
                                        "INSERT INTO metadata(key,value) VALUES ('framework_endpoints_detail', :value)")
                                .bind(
                                        "value",
                                        """
                        [{"classId":1,"className":"org.acme.OrderService","methodName":"handle",
                          "signature":"handle():void","descriptor":"()V","framework":"spring",
                          "httpMethods":["GET"],"classPaths":["/orders"],"methodPaths":[""],
                          "annotations":["GetMapping"],"declaringClassId":null,
                          "declaringClassName":"external.Base","mappingSources":[]}]
                        """)
                                .execute());
        JsonNode endpoint =
                JSON.readTree(
                                ReadQueryContext.<String>readWithGraph(
                                        jdbi,
                                        readScope ->
                                                new QuillToolQueries()
                                                        .findFrameworkEndpoints(
                                                                readScope, "spring", null, null,
                                                                null, false, false, 10, 0)))
                        .path("endpoints")
                        .get(0);
        assertFalse(endpoint.path("direct_call_targets_indexed").asBoolean());
        assertTrue(endpoint.path("direct_project_call_count").isNull());
        assertTrue(endpoint.path("direct_project_calls").isEmpty());
    }
}
