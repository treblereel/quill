package org.treblereel.mcp.index;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildStatusInspectorTest {
    @TempDir Path root;

    @Test void completeObservationRetainsSettingsAndOutputsBeyondOneHundredModules() throws Exception {
        var modules = new StringBuilder();
        for (int i = 0; i < 105; i++) {
            String name = String.format("module-%03d", i);
            modules.append("<module>").append(name).append("</module>");
            Path module = Files.createDirectories(root.resolve(name));
            Files.writeString(module.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                    + "<groupId>test</groupId><artifactId>" + name + "</artifactId><version>1</version></project>");
            Path output = module.resolve("target/classes/Marker.class");
            Files.createDirectories(output.getParent()); Files.write(output, new byte[]{1});
        }
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>"
                + "<artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><modules>"
                + modules + "</modules></project>");
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(BuildStatusInspector.inspect(root, Map.of()));
        assertEquals(105, result.at("/compiled_outputs/count").asInt());
        assertEquals(105, result.at("/compiled_outputs/directories").size());
        assertFalse(result.at("/compiled_outputs/directories_truncated").asBoolean());
        assertTrue(result.path("java_compiler_settings").valueStream()
                .anyMatch(item -> item.path("module").asText().equals(root.resolve("module-104").toString())));
    }

    @Test void commitOnlyLegacyWorktreeFingerprintIsNotACompilationFailure() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path output = root.resolve("target/classes/Marker.class");
        Files.createDirectories(output.getParent()); Files.write(output, new byte[]{1});
        var stale = new org.treblereel.mcp.model.MetaEnvelope("old", "unknown", "old", "new", true,
                false, 0, 0, true, java.util.List.of("commit_changed_after_index", "indexed_inputs_changed"), true, 0, 0, 0);
        var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                BuildStatusInspector.capture(root, Map.of(), stale).inspect(null));
        assertEquals("fresh", result.at("/compiled_outputs/status").asText());
        assertNotEquals("build_required", result.path("status").asText());
        assertTrue(result.path("build_reason").isNull());
    }

    @Test void pinnedEvidenceDoesNotResolveOrRepairNewerGenerationRefs() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path output = root.resolve("target/classes/Marker.class");
        Files.createDirectories(output.getParent()); Files.write(output, new byte[]{1});
        Path refs = root.resolve(".quill/refs.json");
        Files.createDirectories(refs.getParent()); Files.writeString(refs, "{refs-being-replaced");
        var metadata = Map.of("index_id", "pinned-generation", "project_root", root.toString(),
                "indexed_at", "2026-01-01T00:00:00Z", "state_fingerprint", "previous-inputs");
        var observation = BuildStatusInspector.capture(root, metadata, BuildStatusInspector.freshness(metadata, 0, 0));
        assertEquals(Boolean.TRUE, observation.fingerprintChanged(), "Pinned inputs still require a real freshness check");
        assertEquals("{refs-being-replaced", Files.readString(refs));
        assertFalse(Files.exists(root.resolve(".quill/recovery.json")));
    }
}
