package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.BuildSystem;

class BuildIntegrationCapabilitiesTest {
    @TempDir Path root;
    Properties values() throws Exception {
        var values = new Properties(); values.setProperty("manifestVersion", "1"); values.setProperty("buildTool", "maven");
        values.setProperty("producer", "quill-maven-extension"); values.setProperty("producerVersion", "0.1.3");
        values.setProperty("buildEventProtocol", "3"); values.setProperty("compilerSettingsProtocol", "1");
        String[] features = {"build_events", "runtime_classpath", "kotlin_compiler_settings", "java_compiler_settings"};
        values.setProperty("capability.count", "4"); for (int i = 0; i < features.length; i++) values.setProperty("capability." + i, features[i]);
        Path config = root.resolve(".mvn/extensions.xml"); Files.createDirectories(config.getParent()); Files.writeString(config, "old");
        values.setProperty("configuration.path", config.toString());
        values.setProperty("configuration.sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(config))));
        return values;
    }
    void save(Properties values) throws Exception {
        Path file = root.resolve(".quill/build-integration.properties"); Files.createDirectories(file.getParent());
        try (var writer = Files.newBufferedWriter(file)) { values.store(writer, "test"); }
    }
    @Test void missingManifestIsLegacyRatherThanACompatibilityFailure() {
        var report = BuildIntegrationCapabilities.inspect(root, BuildSystem.MAVEN);
        assertEquals("legacy", report.path("status").asText()); assertTrue(report.path("available_capabilities").isEmpty());
    }
    @Test void reportsSupportedCapabilitiesAndReactorLookup() throws Exception {
        save(values());
        var report = BuildIntegrationCapabilities.inspect(root.resolve("module"), BuildSystem.MAVEN);
        assertEquals("supported", report.path("status").asText()); assertEquals(4, report.path("available_capabilities").size());
        assertEquals("available", report.path("features").path("java_compiler_settings").asText());
    }
    @Test void compilerProtocolMismatchPreservesCompatibleBuildEventsAndClasspath() throws Exception {
        var values = values(); values.setProperty("compilerSettingsProtocol", "99"); save(values);
        var report = BuildIntegrationCapabilities.inspect(root, BuildSystem.MAVEN);
        assertEquals("partially_supported", report.path("status").asText()); assertEquals(2, report.path("available_capabilities").size());
        assertEquals("available", report.path("features").path("build_events").asText());
        assertEquals("unsupported_protocol", report.path("features").path("kotlin_compiler_settings").asText());
    }
    @Test void unknownManifestIsNotInterpretedAsCurrentSchema() throws Exception {
        var values = values(); values.setProperty("manifestVersion", "999"); values.remove("capability.count"); save(values);
        var report = BuildIntegrationCapabilities.inspect(root, BuildSystem.MAVEN);
        assertEquals("unsupported_manifest", report.path("status").asText()); assertTrue(report.path("available_capabilities").isEmpty());
    }
    @Test void updatedConfigurationMarksLoadedProducerAsStale() throws Exception {
        save(values()); Path config = root.resolve(".mvn/extensions.xml"); var time = Files.getLastModifiedTime(config);
        Files.writeString(config, "new"); Files.setLastModifiedTime(config, time);
        var report = BuildIntegrationCapabilities.inspect(root, BuildSystem.MAVEN);
        assertEquals("stale", report.path("status").asText()); assertTrue(report.path("available_capabilities").isEmpty());
    }
    @Test void unknownFeaturesAndUnadvertisedFeaturesAreExplicit() throws Exception {
        var values = values(); values.setProperty("capability.count", "1"); values.setProperty("capability.0", "future_feature"); save(values);
        var report = BuildIntegrationCapabilities.inspect(root, BuildSystem.MAVEN);
        assertEquals("future_feature", report.path("unknown_capabilities").get(0).asText());
        assertEquals("not_advertised", report.path("features").path("java_compiler_settings").asText());
    }
    @TestFactory Stream<DynamicTest> rejectsMalformedProducerContracts() {
        return Stream.of("wrong_tool", "negative_count", "large_count", "missing_capability", "duplicate", "bad_hash", "bad_protocol", "missing_producer")
                .map(label -> DynamicTest.dynamicTest(label, () -> {
                    var values = values();
                    switch (label) {
                        case "wrong_tool" -> values.setProperty("buildTool", "gradle");
                        case "negative_count" -> values.setProperty("capability.count", "-1");
                        case "large_count" -> values.setProperty("capability.count", "129");
                        case "missing_capability" -> values.remove("capability.0");
                        case "duplicate" -> values.setProperty("capability.1", "build_events");
                        case "bad_hash" -> values.setProperty("configuration.sha256", "abc");
                        case "bad_protocol" -> values.setProperty("compilerSettingsProtocol", "xyz");
                        case "missing_producer" -> values.remove("producer");
                    }
                    save(values); var report = BuildIntegrationCapabilities.inspect(root, BuildSystem.MAVEN);
                    assertEquals("invalid", report.path("status").asText()); assertTrue(report.path("available_capabilities").isEmpty());
                }));
    }
}
