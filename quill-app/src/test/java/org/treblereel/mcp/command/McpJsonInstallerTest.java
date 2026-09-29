package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpJsonInstallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path project;

    @Test
    void createsProjectEntryAndRemovesItsOwnEmptyFile() throws Exception {
        assertEquals(McpJsonInstaller.Result.ADDED,
                McpJsonInstaller.installProject(project, "/opt/quill"));
        var quill = JSON.readTree(project.resolve(".mcp.json").toFile())
                .path("mcpServers").path("quill");
        assertEquals("stdio", quill.path("type").asText());
        assertEquals("/opt/quill", quill.path("command").asText());
        assertEquals("--mcp", quill.path("args").get(0).asText());
        assertEquals(project.toAbsolutePath().normalize().toString(),
                quill.path("cwd").asText());

        assertEquals(McpJsonInstaller.Result.UNCHANGED,
                McpJsonInstaller.installProject(project, "/opt/quill"));
        assertEquals(McpJsonInstaller.Result.REMOVED,
                McpJsonInstaller.uninstallProject(project));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
    }

    @Test
    void projectUninstallPreservesOtherServersAndSettings() throws Exception {
        Files.writeString(project.resolve(".mcp.json"), """
                {"mcpServers": {
                  "quill": {"command": "quill", "args": ["--mcp"]},
                  "other": {"command": "other-server"}
                }, "projectSetting": true}
                """);

        assertEquals(McpJsonInstaller.Result.REMOVED,
                McpJsonInstaller.uninstallProject(project));
        var root = JSON.readTree(project.resolve(".mcp.json").toFile());
        assertTrue(root.path("projectSetting").asBoolean());
        assertEquals("other-server",
                root.path("mcpServers").path("other").path("command").asText());
        assertFalse(root.path("mcpServers").has("quill"));
    }

    @Test
    void createsWorkspaceEntryAndRemovesItsOwnEmptyFile() throws Exception {
        Path workspace = project.resolve("workspace");

        assertEquals(McpJsonInstaller.Result.ADDED,
                McpJsonInstaller.installWorkspace(project, workspace, "/opt/quill"));
        var quill = JSON.readTree(project.resolve(".mcp.json").toFile())
                .path("mcpServers").path("quill");
        assertEquals("/opt/quill", quill.path("command").asText());
        assertEquals("--workspace", quill.path("args").get(1).asText());
        assertEquals(workspace.toAbsolutePath().normalize().toString(),
                quill.path("args").get(2).asText());

        assertEquals(McpJsonInstaller.Result.REMOVED,
                McpJsonInstaller.uninstallWorkspace(project, workspace));
        assertFalse(Files.exists(project.resolve(".mcp.json")));
    }

    @Test
    void replacesLegacyQuillAndPreservesOtherServers() throws Exception {
        Files.writeString(project.resolve(".mcp.json"), """
                {"mcpServers": {
                  "quill": {"command": "old", "args": ["--mcp", "--project", "/old"]},
                  "other": {"command": "other-server"}
                }, "projectSetting": true}
                """);
        Path workspace = project.resolve("workspace");

        assertEquals(McpJsonInstaller.Result.REPLACED,
                McpJsonInstaller.installWorkspace(project, workspace, null));
        assertEquals(McpJsonInstaller.Result.REMOVED,
                McpJsonInstaller.uninstallWorkspace(project, workspace));

        var root = JSON.readTree(project.resolve(".mcp.json").toFile());
        assertTrue(root.path("projectSetting").asBoolean());
        assertEquals("other-server",
                root.path("mcpServers").path("other").path("command").asText());
        assertFalse(root.path("mcpServers").has("quill"));
    }

    @Test
    void doesNotRemoveQuillBelongingToAnotherWorkspace() throws Exception {
        Path first = project.resolve("first");
        McpJsonInstaller.installWorkspace(project, first, null);

        assertEquals(McpJsonInstaller.Result.UNCHANGED,
                McpJsonInstaller.uninstallWorkspace(project, project.resolve("second")));
        assertTrue(JSON.readTree(project.resolve(".mcp.json").toFile())
                .path("mcpServers").has("quill"));
    }
}
