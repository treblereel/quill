package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMappingKeysTest {

    @TempDir Path root;

    @Test
    void mappingExpandsToNamedKebabCaseAndNestedGroupKeys() throws Exception {
        String retention = "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) ";
        var db = JavaEvidenceFixture.index(root, Map.of(
                "src/main/java/io/smallrye/config/ConfigMapping.java",
                "package io.smallrye.config; " + retention + "public @interface ConfigMapping { String prefix(); }",
                "src/main/java/io/smallrye/config/WithName.java",
                "package io.smallrye.config; " + retention + "public @interface WithName { String value(); }",
                "src/main/java/io/smallrye/config/WithDefault.java",
                "package io.smallrye.config; " + retention + "public @interface WithDefault { String value(); }",
                "src/main/resources/application.properties", "app.cache.max-entries=500\n",
                "src/main/java/p/Settings.java", """
                        package p;
                        import io.smallrye.config.*;
                        @ConfigMapping(prefix = "app.cache")
                        public interface Settings {
                            @WithName("max-entries") long size();
                            @WithDefault("10s") java.time.Duration expireAfterWrite();
                            java.util.Optional<String> region();
                            Tier tier();
                            static Settings none() { return null; }
                            interface Tier { boolean enabled(); }
                        }
                        """));
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(db, reader -> {
            ConfigMappingKeys.append(reader, fact, reader.symbols.findClassesByName("p.Settings").getFirst());
            return null;
        });

        var mapping = fact.path("config_mapping");
        assertEquals("app.cache", mapping.path("prefix").asText(), fact.toString());
        List<String> keys = mapping.path("keys").valueStream().map(node -> node.asText()).toList();
        assertEquals(List.of(
                "app.cache.expire-after-write: java.time.Duration default=10s",
                "app.cache.region: java.util.Optional<java.lang.String> optional",
                // V41 CQ-037: asked for defaults, Claude read application.properties for the values the mapping leaves to it.
                "app.cache.max-entries: long; line 1=500",
                "app.cache.tier.enabled: boolean"), keys);
        assertEquals("src/main/resources/application.properties", mapping.path("main_declarations_in").asText());
        assertTrue(mapping.path("groups").get(0).asText().equals("app.cache.tier -> p.Settings.Tier"), mapping.toString());
    }

    @Test
    void kebabCaseSplitsEveryCapital() {
        assertEquals("expire-after-access", ConfigMappingKeys.kebab("expireAfterAccess"));
        assertEquals("ttl", ConfigMappingKeys.kebab("ttl"));
    }
}
