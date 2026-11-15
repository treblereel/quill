package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * V41 CQ-032: 40 of 55 rows of find_configuration_references(*security*) were test-profile
 * overrides repeating their profile.
 */
class ConfigurationRowDefaultsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String row(String key, String profile, int line, String value) {
        return "{\"key\":\""
                + key
                + "\",\"kind\":\"quarkus_test_profile\",\"file\":\"src/test/java/p/"
                + profile
                + ".java\",\"line\":"
                + line
                + ",\"declaring_class\":\"p."
                + profile
                + "$Profile\",\"method\":\"getConfigOverrides()\",\"activation\":\"unknown\""
                + ",\"declared_source\":{\"status\":\"declared_scalar\",\"declared_value\":\""
                + value
                + "\"}}";
    }

    @Test
    void testProfileOverridesAreGroupedByTheProfileDeclaringThem() throws Exception {
        String json =
                "{\"key\":\"a.*\",\"references\":["
                        + "{\"key\":\"a.mode\",\"kind\":\"property\",\"file\":\"src/main/resources/application.properties\",\"line\":3,\"entry_type\":\"definition\"},"
                        + row("a.mode", "FirstTest", 40, "x")
                        + ","
                        + row("a.size", "FirstTest", 40, "0")
                        + ","
                        + row("a.mode", "SecondTest", 12, "y")
                        + "]}";
        JsonNode result = ConfigurationRowDefaults.compact((ObjectNode) JSON.readTree(json));

        assertEquals(
                List.of("a.mode"),
                result.path("references")
                        .valueStream()
                        .map(row -> row.path("key").asText())
                        .toList(),
                result.toString());
        JsonNode groups = result.path("test_profile_overrides");
        assertEquals(2, groups.size(), result.toString());
        JsonNode first = groups.get(0);
        assertEquals("p.FirstTest$Profile", first.path("declaring_class").asText());
        assertEquals("src/test/java/p/FirstTest.java", first.path("file").asText());
        assertEquals("getConfigOverrides()", first.path("method").asText());
        assertEquals("quarkus_test_profile", first.path("kind").asText());
        assertEquals(
                "[{\"key\":\"a.mode\",\"line\":40,\"declared_source\":{\"declared_value\":\"x\"}},"
                        + "{\"key\":\"a.size\",\"line\":40,\"declared_source\":{\"declared_value\":\"0\"}}]",
                first.path("overrides").toString());
        // Values every override shares still move to the kind's row defaults.
        assertEquals(
                "unknown",
                result.path("row_defaults_by_kind")
                        .path("quarkus_test_profile")
                        .path("activation")
                        .asText(),
                result.toString());
        assertFalse(first.path("overrides").get(0).has("declaring_class"));
    }

    // V52 CQ-035: 13 config_key rows of storage.metadata* each repeated the same annotation,
    // definition files and statuses.
    @Test
    void valuesEveryConsumerRowSharesAreWrittenOnce() throws Exception {
        String consumer =
                "{\"key\":\"%s\",\"kind\":\"config_key\",\"className\":\"p.%s\",\"annotation\":\"org.eclipse.microprofile.config.inject.ConfigProperty\","
                        + "\"definition_files\":[\"src/main/resources/application.properties\"],\"location_status\":\"source_declaration\",\"resolution_status\":\"resolved\"}";
        String json =
                "{\"references\":["
                        + consumer.formatted("a.one", "One")
                        + ","
                        + consumer.formatted("a.two", "Two")
                        + "]}";
        JsonNode result = ConfigurationRowDefaults.compact((ObjectNode) JSON.readTree(json));
        assertEquals(
                "{\"annotation\":\"org.eclipse.microprofile.config.inject.ConfigProperty\",\"definition_files\":[\"src/main/resources/application.properties\"],"
                        + "\"location_status\":\"source_declaration\",\"resolution_status\":\"resolved\"}",
                result.path("row_defaults_by_kind").path("config_key").toString(),
                result.toString());
        assertEquals(
                "{\"key\":\"a.one\",\"kind\":\"config_key\",\"className\":\"p.One\"}",
                result.path("references").get(0).toString());
    }
}
