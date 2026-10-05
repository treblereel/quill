package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.QuillTopCommand;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore;
import picocli.CommandLine;

class ClientRefreshCommandTest {
    @TempDir Path root;

    @Test void refreshDoesNotRequireOrCreateIndexBuildIntegrationOrPermissionGrants() throws Exception {
        Files.writeString(root.resolve("AGENTS.md"), "User conventions\n");
        Files.writeString(root.resolve("CLAUDE.md"), "Legacy unmarked Quill instructions\n");
        Files.createDirectories(root.resolve(".claude"));
        String permissions = "{\"permissions\":{\"allow\":[\"Read\"],\"deny\":[\"Edit\"]}}";
        Files.writeString(root.resolve(".claude/settings.local.json"), permissions);
        Files.writeString(root.resolve(".mcp.json"), "{\"mcpServers\":{\"other\":{\"command\":\"other\"}}}");
        var result = execute("--project", root.toString());
        assertEquals(0, result.code(), result.err());
        assertEquals(ProjectConfiguration.InstructionsState.CURRENT, ProjectConfiguration.inspectAgentsMd(root));
        assertEquals(ProjectConfiguration.InstructionsState.CURRENT, ProjectConfiguration.inspectClaudeMd(root));
        assertTrue(Files.readString(root.resolve("AGENTS.md")).startsWith("User conventions\n"));
        assertTrue(Files.readString(root.resolve("CLAUDE.md")).startsWith("Legacy unmarked Quill instructions\n"));
        assertTrue(Files.readString(root.resolve(".mcp.json")).contains("other"));
        assertEquals(permissions, Files.readString(root.resolve(".claude/settings.local.json")));
        assertTrue(ClaudeSettingsInstaller.isEnabled(root));
        assertTrue(Files.exists(root.resolve(".codex/config.toml")));
        for (String name : List.of(".quill", ".mvn", ".gitignore", ".claude/quill-permissions.json")) {
            assertFalse(Files.exists(root.resolve(name)), name);
        }
        String agents = Files.readString(root.resolve("AGENTS.md"));
        String mcp = Files.readString(root.resolve(".mcp.json"));
        assertEquals(0, execute("--project", root.toString()).code());
        assertEquals(agents, Files.readString(root.resolve("AGENTS.md")));
        assertEquals(mcp, Files.readString(root.resolve(".mcp.json")));
    }

    @Test void preservesUserOwnedCodexConfiguration() throws Exception {
        Files.createDirectories(root.resolve(".codex"));
        String config = "model = 'user-model'\n[mcp_servers.quill]\ncommand = 'custom-quill'\nargs = ['--mcp']\n";
        Files.writeString(root.resolve(".codex/config.toml"), config);
        assertEquals(0, execute("--project", root.toString()).code());
        assertEquals(config, Files.readString(root.resolve(".codex/config.toml")));
    }

    @Test void refusesMalformedMarkersBeforeAnyWrites() throws Exception {
        String malformed = "User text\n<!-- quill:managed:start -->\n";
        Files.writeString(root.resolve("AGENTS.md"), malformed);
        assertEquals(1, execute("--project", root.toString()).code());
        assertEquals(malformed, Files.readString(root.resolve("AGENTS.md")));
        assertFalse(Files.exists(root.resolve("CLAUDE.md")));
        assertFalse(Files.exists(root.resolve(".mcp.json")));
    }

    @Test void refusesInvalidMcpBeforeAnyWrites() throws Exception {
        for (String content : List.of("[]", "bad", "{\"mcpServers\":[]}",
                "{\"mcpServers\":{},\"mcpServers\":{}}")) {
            Files.writeString(root.resolve(".mcp.json"), content);
            assertEquals(1, execute("--project", root.toString()).code());
            assertEquals(content, Files.readString(root.resolve(".mcp.json")));
            assertFalse(Files.exists(root.resolve("AGENTS.md")));
        }
    }

    @Test void refusesUnsupportedSettingsAndInlineCodexBeforeAnyWrites() throws Exception {
        Files.createDirectories(root.resolve(".claude"));
        Path settings = root.resolve(".claude/settings.json");
        Files.writeString(settings, "{\"enabledMcpjsonServers\":\"quill\"}");
        assertEquals(1, execute("--project", root.toString()).code());
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
        Files.delete(settings);
        Files.createDirectories(root.resolve(".codex"));
        Files.writeString(root.resolve(".codex/config.toml"), "mcp_servers = {}\n");
        assertEquals(1, execute("--project", root.toString()).code());
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
    }

    @Test void refusesSymlinkedInstructionsAndParentDirectories() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path instructions = outside.resolve("AGENTS.md");
        Files.writeString(instructions, "Preserve me");
        Files.createSymbolicLink(root.resolve("AGENTS.md"), instructions);
        assertEquals(1, execute("--project", root.toString()).code());
        assertEquals("Preserve me", Files.readString(instructions));
        Files.delete(root.resolve("AGENTS.md"));
        Files.createSymbolicLink(root.resolve(".codex"), outside);
        assertEquals(1, execute("--project", root.toString()).code());
        assertFalse(Files.exists(outside.resolve("config.toml")));
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
    }

    @Test void workspaceUsesRecordedConfiguredTargetsAndPreflightsAllBeforeMutation() throws Exception {
        WorkspaceManifestStore.initialize(root, 1);
        Path repository = Files.createDirectory(root.resolve("repository"));
        Path unsupported = Files.createDirectory(root.resolve("unsupported"));
        Files.writeString(WorkspaceRepositoryStateStore.path(root), """
                {"repositories":[{"name":"repository","relativePath":"repository"},
                                 {"name":"unsupported","relativePath":"unsupported"}]}
                """);
        McpJsonInstaller.installWorkspace(repository, root, null);
        Files.writeString(repository.resolve("AGENTS.md"), "<!-- quill:managed:end -->");
        assertEquals(1, execute("--workspace", root.toString()).code());
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
        Files.writeString(repository.resolve("AGENTS.md"), "User text\n");
        assertEquals(0, execute("--workspace", root.toString()).code());
        for (Path target : List.of(root, repository)) {
            assertEquals(ProjectConfiguration.InstructionsState.CURRENT, ProjectConfiguration.inspectAgentsMd(target));
            assertTrue(Files.readString(target.resolve(".mcp.json")).contains("--workspace"));
            assertTrue(Files.readString(target.resolve(".codex/config.toml")).contains("--workspace"));
        }
        assertFalse(Files.exists(unsupported.resolve("AGENTS.md")));
        assertFalse(Files.exists(repository.resolve(".quill")));
        assertEquals(1, execute("--project", root.toString()).code());
        assertEquals(2, execute("--project", root.toString(), "--workspace", root.toString()).code());
    }

    @Test void rejectsOutsideWorkspacePathsAndMissingRoots() throws Exception {
        WorkspaceManifestStore.initialize(root, 1);
        Files.writeString(WorkspaceRepositoryStateStore.path(root),
                "{\"repositories\":[{\"name\":\"outside\",\"relativePath\":\"../outside\"}]}");
        assertEquals(1, execute("--workspace", root.toString()).code());
        assertFalse(Files.exists(root.resolve("AGENTS.md")));
        assertEquals(1, execute("--project", root.resolve("missing").toString()).code());
    }

    private Captured execute(String... options) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new QuillTopCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        String[] args = new String[options.length + 2];
        args[0] = "client";
        args[1] = "refresh";
        System.arraycopy(options, 0, args, 2, options.length);
        return new Captured(cli.execute(args), out.toString(), err.toString());
    }
    private record Captured(int code, String out, String err) {}
}
