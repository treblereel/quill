package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private static boolean contains(com.fasterxml.jackson.databind.JsonNode array, String value) {
        for (var item : array) if (value.equals(item.asText())) return true;
        return false;
    }
}
