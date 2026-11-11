package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class ConfigurationEvidenceCompactionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode cq038() throws Exception {
        try (InputStream in = ConfigurationEvidenceCompactionTest.class
                .getResourceAsStream("/prefetch/configuration-evidence-cq038.json")) {
            return (ObjectNode) JSON.readTree(in);
        }
    }

    /** V32 CQ-038: the agent answered from this fact alone, yet ~85% of it was bookkeeping and repeated caveats. */
    @Test
    void aConfigurationAnswerKeepsWhereTheKeyIsDefinedReadAndOverridden() throws Exception {
        ObjectNode evidence = cq038();
        int before = evidence.toString().length();

        ConfigurationEvidenceCompaction.compact(evidence);

        String text = evidence.toString();
        assertEquals("cryostat.security.rbac.cache.maximum-size", evidence.path("key").asText());
        JsonNode usage = evidence.path("references").get(0);
        assertEquals("io.cryostat.security.rbac.RbacConfig", usage.path("className").asText());
        assertEquals("io.smallrye.config.ConfigMapping", usage.path("annotation").asText());
        assertEquals("src/main/java/io/cryostat/security/rbac/RbacConfig.java", usage.path("source").asText());
        assertEquals("resolved", usage.path("resolution_status").asText());
        assertEquals("src/main/resources/application.properties", usage.path("definition_files").get(0).asText());
        JsonNode property = evidence.path("references").get(1);
        assertEquals(321, property.path("line").asInt());
        assertEquals("1000", property.path("declared_source").path("declared_value").asText());
        assertEquals("declared_scalar", property.path("declared_source").path("status").asText());
        JsonNode profile = evidence.path("references").get(2);
        assertEquals("src/test/java/io/cryostat/security/rbac/SsarClientCacheDisabledBySizeTest.java", profile.path("file").asText());
        assertEquals(44, profile.path("line").asInt());
        assertEquals("unknown", profile.path("activation").asText());
        assertEquals("0", profile.path("declared_source").path("declared_value").asText());
        assertTrue(profile.path("configuration_scope").asText().contains("not a production-profile default"));
        // application.properties:321 is the definition row above; only the files checked without a match remain.
        assertFalse(evidence.path("configuration_resources").has("matches"));
        assertEquals(2, evidence.path("configuration_resources").path("checked_without_match").size());
        assertTrue(evidence.path("resolution_scope").asText().contains("not effective runtime values"));
        assertTrue(evidence.path("indexed_references_complete").asBoolean());

        for (String gone : new String[] {"sha256", "classId", "resolution_strategy", "\"confidence\":",
                "\"resolved\":", "key_constants", "test_profile_coverage", "field_annotations", "values_indexed",
                "effective_runtime_values_proven", "effective_value_proven", "source_complete", "base_key",
                "profile_count", "\"limit\"", "\"offset\"", "\"total\"", ":null"})
            assertFalse(text.contains(gone), gone + " stays in " + text);
        assertTrue(text.length() < before * 45 / 100, before + " -> " + text.length() + ": " + text);
    }

    /** Gaps are the answer too: unknown test profiles, unresolved keys, a cut page and named constants stay. */
    @Test
    void gapsAndPagesThatAreNotCompleteStay() throws Exception {
        ObjectNode evidence = cq038();
        evidence.with("test_profile_coverage").put("unknown_declarations", 2);
        evidence.put("has_more", true).put("total", 9);
        evidence.with("key_constants").withArray("items").addObject().put("className", "p.Keys").put("field", "SIZE");
        ((ObjectNode) evidence.path("references").get(0)).put("resolution_status", "unsatisfied").put("resolved", false);

        ConfigurationEvidenceCompaction.compact(evidence);

        assertEquals(2, evidence.path("test_profile_coverage").path("unknown_declarations").asInt());
        assertTrue(evidence.path("has_more").asBoolean());
        assertEquals(9, evidence.path("total").asInt());
        assertFalse(evidence.has("indexed_references_complete"));
        assertEquals("p.Keys", evidence.path("key_constants").path("items").get(0).path("className").asText());
        assertEquals("unsatisfied", evidence.path("references").get(0).path("resolution_status").asText());
    }
}
