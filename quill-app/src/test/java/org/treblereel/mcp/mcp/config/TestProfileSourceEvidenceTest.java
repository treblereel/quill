package org.treblereel.mcp.mcp.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.mcp.prefetch.PrefetchContext;

class TestProfileSourceEvidenceTest {
    @TempDir Path root;
    Jdbi db;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() throws Exception {
        db =
                JavaEvidenceFixture.index(
                        root,
                        Map.of(
                                "src/main/java/p/Fixture.java", "package p;class Fixture {}",
                                "src/test/java/io/quarkus/test/junit/QuarkusTestProfile.java",
                                        "package io.quarkus.test.junit;public interface QuarkusTestProfile {java.util.Map<String,String> getConfigOverrides();}",
                                "src/test/java/p/Profile.java",
                                        """
              package p;
              import java.util.Map;
              import io.quarkus.test.junit.QuarkusTestProfile;
              public class Profile implements QuarkusTestProfile {
                public Map<String,String> getConfigOverrides() {
                  return Map.of("warehouse.enabled","false","warehouse.password","1234567");
                }
              }
              """));
    }

    JsonNode query(String key, String className, String view) throws Exception {
        return new ConfigurationReferenceQueries()
                .find(db, key, className, "all", null, 100, 0, view);
    }

    @Test
    void indexHasProvenanceAndPrefetchIncludesSafeDeclarationWithoutAssumingActivation()
            throws Exception {
        var indexed = query("warehouse.enabled", null, "index");
        assertEquals(1, indexed.path("definition_count").asInt(), indexed.toString());
        assertFalse(indexed.toString().contains("declared_value"));
        var data = query("warehouse.enabled", "Profile", "declared");
        assertEquals(1, data.path("definition_count").asInt(), data.toString());
        var ref = data.path("references").get(0);
        assertEquals("test", ref.path("sourceSet").asText());
        assertEquals(
                "false",
                ref.path("declared_source").path("declared_value").asText(),
                data.toString());
        assertEquals("unknown", ref.path("activation").asText());
        assertFalse(ref.has("key_profile"));
        assertFalse(data.path("effective_runtime_values_proven").asBoolean());
        String stored =
                db.withHandle(
                        h ->
                                h.createQuery(
                                                "SELECT group_concat(evidence) FROM configuration_definitions")
                                        .mapTo(String.class)
                                        .one());
        assertFalse(stored.contains("1234567"));
        String packet =
                TestReads.read(
                                db,
                                readScope ->
                                        PrefetchContext.collect(
                                                readScope, root, "Объясни warehouse.enabled."))
                        .toString();
        assertTrue(packet.contains("quarkus_test_profile"), packet);
        assertTrue(packet.contains("declared_value\":\"false"), packet);
        String secret = query("warehouse.password", null, "declared").toString();
        assertTrue(secret.contains("redacted_key"));
        assertFalse(secret.contains("1234567"));
    }

    @Test
    void sourceOrBinaryChangesRemoveDeclaredValues() throws Exception {
        Path binary = root.resolve("target/classes/p/Profile.class");
        byte[] original = Files.readAllBytes(binary);
        Files.write(binary, new byte[] {0});
        assertFalse(
                query("warehouse.enabled", null, "declared").toString().contains("declared_value"));
        Files.write(binary, original);
        Files.writeString(root.resolve("src/test/java/p/Profile.java"), "// modified source\n");
        String result = query("warehouse.enabled", null, "declared").toString();
        assertTrue(result.contains("inputs_changed"));
        assertFalse(result.contains("declared_value"));
    }

    @Test
    void testDeclarationDoesNotBecomeProductionResolution() throws Exception {
        db.useHandle(
                h ->
                        h.execute(
                                "INSERT INTO configuration_usages(key,kind,class_id,class_name,member,annotation,source,module,source_set) SELECT 'warehouse.enabled','config_property',id,'p.Fixture','enabled','ConfigProperty','src/main/java/p/Fixture.java','.','main' FROM classes WHERE class_name='p.Fixture'"));
        var data = query("warehouse.enabled", null, "declared");
        var usage =
                data.path("references")
                        .valueStream()
                        .filter(r -> r.path("entry_type").asText().equals("usage"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                "no_indexed_definition", usage.path("resolution_status").asText(), data.toString());
    }
}
