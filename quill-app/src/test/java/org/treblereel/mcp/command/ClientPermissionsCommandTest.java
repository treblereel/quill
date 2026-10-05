package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
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

class ClientPermissionsCommandTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void statusIsReadOnlyAndWorksWithoutBuildOrIndex() throws Exception {
        Captured result = execute("status", "--project", root.toString(), "--json");
        assertEquals(0, result.code(), result.err());
        var document = JSON.readTree(result.out());
        assertEquals(1, document.path("schema_version").asInt());
        var report = document.path("projects").get(0);
        assertFalse(report.path("server_configured").asBoolean());
        assertEquals(report.path("tool_count").asInt(), report.path("missing_tools").size());
        assertTrue(report.path("limitation").asText().contains("not checked"));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test
    void grantAndRevokePreserveServerAndPreExistingUserRules() throws Exception {
        McpJsonInstaller.installProject(root, null);
        ClaudeSettingsInstaller.install(root);
        Path settings = root.resolve(".claude/settings.local.json");
        Files.writeString(settings, """
                {"permissions":{"allow":["Read","mcp__quill__get_overview"],"deny":["Edit"]}}
                """);
        String server = Files.readString(root.resolve(".mcp.json"));
        assertEquals(0, execute("grant", "--project", root.toString()).code());
        var granted = ClaudePermissionStatus.inspect(root);
        assertTrue(granted.unrestrictedLocally());
        assertFalse(granted.owned_rules().contains("mcp__quill__get_overview"));
        String first = Files.readString(settings);
        assertEquals(0, execute("grant", "--project", root.toString()).code());
        assertEquals(first, Files.readString(settings));
        assertEquals(0, execute("revoke", "--project", root.toString()).code());
        assertEquals(0, execute("revoke", "--project", root.toString()).code());
        var revoked = ClaudePermissionStatus.inspect(root);
        assertEquals(List.of("mcp__quill__get_overview"), revoked.allowed_tools());
        assertEquals(0, revoked.owned_rules().size());
        assertEquals(server, Files.readString(root.resolve(".mcp.json")));
        assertTrue(ClaudeSettingsInstaller.isEnabled(root));
        assertTrue(Files.readString(settings).contains("Read"));
        assertTrue(Files.readString(settings).contains("Edit"));
        assertFalse(Files.exists(root.resolve(".quill")));
    }

    @Test
    void distinguishesAllowFromDenyAndAskWithClaudeGlobSemantics() throws Exception {
        Path file = root.resolve(".claude/settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"permissions":{"allow":["mcp__quill__*"],"deny":["mcp__quill__find_*"],
                    "ask":["mcp__quill__get_overview"]}}
                """);
        var report = ClaudePermissionStatus.inspect(root);
        assertTrue(report.missing_tools().isEmpty());
        assertTrue(report.denied_tools().contains("mcp__quill__find_symbol_usages"));
        assertEquals(List.of("mcp__quill__get_overview"), report.ask_tools());
        assertFalse(report.unrestrictedLocally());
        Files.writeString(file, "{\"permissions\":{\"allow\":[\"mcp__*\"],\"deny\":[\"*\"]}}");
        report = ClaudePermissionStatus.inspect(root);
        assertEquals(0, report.allowed_tools().size());
        assertEquals(report.tool_count(), report.denied_tools().size());
        Files.writeString(file, "{\"permissions\":{\"allow\":[\"mcp__quill\"]}}");
        assertTrue(ClaudePermissionStatus.inspect(root).missing_tools().isEmpty());
    }

    @Test
    void invalidSettingsAndDuplicateKeysAreReportedWithoutOverwriting() throws Exception {
        McpJsonInstaller.installProject(root, null);
        Path file = root.resolve(".claude/settings.local.json");
        Files.createDirectories(file.getParent());
        for (String content : List.of("", "[]", "bad", "{\"permissions\":{\"allow\":[7]}}",
                "{\"permissions\":{},\"permissions\":{\"allow\":[\"Read\"]}}")) {
            Files.writeString(file, content);
            Captured status = execute("status", "--project", root.toString(), "--json");
            assertEquals(1, status.code());
            assertTrue(status.out().contains("invalid_files"));
            assertEquals(1, execute("grant", "--project", root.toString()).code());
            assertEquals(content, Files.readString(file));
            assertFalse(Files.exists(root.resolve(".claude/quill-permissions.json")));
        }
    }

    @Test
    void requiresExistingServerForGrantAndRefusesSymlinkWrites() throws Exception {
        assertEquals(1, execute("grant", "--project", root.toString()).code());
        assertFalse(Files.exists(root.resolve(".claude")));
        McpJsonInstaller.installProject(root, null);
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve(".claude"), outside);
        assertEquals(1, execute("grant", "--project", root.toString()).code());
        try (var files = Files.list(outside)) { assertEquals(0, files.count()); }
    }

    @Test
    void workspaceUsesRecordedTargetsAndPreflightsBeforeMutation() throws Exception {
        WorkspaceManifestStore.initialize(root, 1);
        Path repository = Files.createDirectory(root.resolve("repository"));
        Path state = WorkspaceRepositoryStateStore.path(root);
        Files.writeString(state, """
                {"repositories":[{"name":"repository","relativePath":"repository"}]}
                """);
        for (Path target : List.of(root, repository)) {
            McpJsonInstaller.installWorkspace(target, root, null);
            ClaudeSettingsInstaller.install(target);
        }
        Path settings = repository.resolve(".claude/settings.local.json");
        Files.writeString(settings, "[]");
        assertEquals(1, execute("grant", "--workspace", root.toString()).code());
        assertFalse(Files.exists(root.resolve(".claude/settings.local.json")));
        Files.delete(settings);
        assertEquals(0, execute("grant", "--workspace", root.toString()).code());
        assertTrue(ClaudePermissionStatus.inspect(repository).unrestrictedLocally());
        assertEquals(0, execute("revoke", "--workspace", root.toString()).code());
        assertTrue(ClaudePermissionStatus.inspect(repository).allowed_tools().isEmpty());
        assertTrue(Files.exists(WorkspaceManifestStore.manifest(root)));
        assertFalse(Files.exists(repository.resolve(".quill")));
    }

    @Test
    void rejectsOutOfWorkspacePathsAndConflictingSelectors() throws Exception {
        WorkspaceManifestStore.initialize(root, 1);
        Files.writeString(WorkspaceRepositoryStateStore.path(root),
                "{\"repositories\":[{\"name\":\"outside\",\"relativePath\":\"../outside\"}]}");
        assertNotEquals(0, execute("grant", "--workspace", root.toString()).code());
        assertFalse(Files.exists(root.resolve(".claude")));
        assertEquals(2, execute("status", "--project", root.toString(), "--workspace", root.toString()).code());
    }

    private Captured execute(String... options) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new QuillTopCommand());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));
        String[] args = new String[options.length + 2];
        args[0] = "client";
        args[1] = "permissions";
        System.arraycopy(options, 0, args, 2, options.length);
        return new Captured(cli.execute(args), out.toString(), err.toString());
    }
    private record Captured(int code, String out, String err) {}
}
