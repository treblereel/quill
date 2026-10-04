package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeSettingsInstallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path root;

    @Test
    void enablesQuillAndPreservesExistingSettings() throws Exception {
        Path settings = root.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, """
                {"permissions":{"allow":["Read"]},"enabledMcpjsonServers":["other"]}
                """);

        assertTrue(ClaudeSettingsInstaller.install(root));
        assertTrue(ClaudeSettingsInstaller.install(root));
        var installed = JSON.readTree(settings.toFile());
        assertTrue(installed.path("permissions").path("allow").isArray());
        assertTrue(contains(installed.path("enabledMcpjsonServers"), "other"));
        assertTrue(contains(installed.path("enabledMcpjsonServers"), "quill"));

        assertTrue(ClaudeSettingsInstaller.uninstall(root));
        var cleaned = JSON.readTree(settings.toFile());
        assertTrue(contains(cleaned.path("enabledMcpjsonServers"), "other"));
        assertFalse(contains(cleaned.path("enabledMcpjsonServers"), "quill"));
    }

    @Test
    void uninstallRemovesSettingsFileCreatedOnlyForQuill() throws Exception {
        assertTrue(ClaudeSettingsInstaller.install(root));
        assertTrue(ClaudeSettingsInstaller.uninstall(root));
        assertFalse(Files.exists(root.resolve(".claude/settings.json")));
        assertFalse(Files.exists(root.resolve(".claude")));
    }

    @Test
    void permissionConsentPreservesPoliciesAndOnlyOwnsAddedRules() throws Exception {
        Path file = root.resolve(".claude/settings.local.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"model":"user-model","permissions":{"allow":["Read","mcp__quill__get_overview"],
                  "ask":["mcp__quill__find_classes"],"deny":["Edit"]}}
                """);
        List<String> rules = List.of("mcp__quill__get_overview", "mcp__quill__find_classes");
        assertTrue(ClaudeSettingsInstaller.allowTools(root, rules));
        String first = Files.readString(file);
        assertTrue(ClaudeSettingsInstaller.allowTools(root, rules));
        assertEquals(first, Files.readString(file));
        assertTrue(ClaudeSettingsInstaller.areToolsAllowed(root, rules));
        var installed = JSON.readTree(file.toFile());
        assertEquals(3, installed.path("permissions").path("allow").size());
        assertTrue(contains(installed.path("permissions").path("ask"), "mcp__quill__find_classes"));
        assertTrue(contains(installed.path("permissions").path("deny"), "Edit"));
        assertTrue(ClaudeSettingsInstaller.uninstall(root));
        var cleaned = JSON.readTree(file.toFile());
        assertEquals("user-model", cleaned.path("model").asText());
        assertTrue(contains(cleaned.path("permissions").path("allow"), "Read"));
        assertTrue(contains(cleaned.path("permissions").path("allow"), "mcp__quill__get_overview"));
        assertFalse(contains(cleaned.path("permissions").path("allow"), "mcp__quill__find_classes"));
        assertFalse(Files.exists(root.resolve(".claude/quill-permissions.json")));
    }

    @Test
    void permissionOnlyInstallIsReversibleAndNeverGrantsWildcards() throws Exception {
        assertFalse(ClaudeSettingsInstaller.allowTools(root, List.of("mcp__quill__*")));
        assertFalse(Files.exists(root.resolve(".claude")));
        assertTrue(ClaudeSettingsInstaller.allowTools(root, List.of("mcp__quill__get_overview")));
        assertTrue(ClaudeSettingsInstaller.uninstall(root));
        assertFalse(Files.exists(root.resolve(".claude")));
    }

    @Test
    void malformedPermissionsOrReceiptAreNotOverwritten() throws Exception {
        Path file = root.resolve(".claude/settings.local.json");
        Files.createDirectories(file.getParent());
        for (String malformed : List.of("not-json", "[]", "{\"permissions\":\"bad\"}",
                "{\"permissions\":{\"allow\":\"bad\"}}", "{\"permissions\":{\"allow\":[7]}}")) {
            Files.writeString(file, malformed);
            assertFalse(ClaudeSettingsInstaller.allowTools(root, List.of("mcp__quill__get_overview")));
            assertEquals(malformed, Files.readString(file));
        }
        Files.writeString(file, "{}");
        Path receipt = root.resolve(".claude/quill-permissions.json");
        Files.writeString(receipt, "{\"schema_version\":1,\"rules\":[\"Read\"]}");
        assertFalse(ClaudeSettingsInstaller.allowTools(root, List.of("mcp__quill__get_overview")));
        assertFalse(ClaudeSettingsInstaller.uninstall(root));
        assertEquals("{}", Files.readString(file));
    }

    @Test
    void refusesSymlinkedSettingsDirectoryOrFile() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve(".claude"), outside);
        assertFalse(ClaudeSettingsInstaller.allowTools(root, List.of("mcp__quill__get_overview")));
        assertFalse(Files.exists(outside.resolve("settings.local.json")));
        Files.delete(root.resolve(".claude"));
        Files.createDirectory(root.resolve(".claude"));
        Path external = outside.resolve("settings.json");
        Files.writeString(external, "{}");
        Files.createSymbolicLink(root.resolve(".claude/settings.local.json"), external);
        assertFalse(ClaudeSettingsInstaller.allowTools(root, List.of("mcp__quill__get_overview")));
        assertEquals("{}", Files.readString(external));
    }

    private static boolean contains(com.fasterxml.jackson.databind.JsonNode array, String value) {
        for (var item : array) if (value.equals(item.asText())) return true;
        return false;
    }
}
