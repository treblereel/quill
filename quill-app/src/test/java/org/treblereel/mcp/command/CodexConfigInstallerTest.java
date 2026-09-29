package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexConfigInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNothingWhenProjectConfigIsAbsent() {
        var result = CodexConfigInstaller.installIfPresent(tempDir, null);

        assertEquals(CodexConfigInstaller.Result.NOT_PRESENT, result);
        assertFalse(Files.exists(tempDir.resolve(".codex")));
    }

    @Test
    void projectInitializationCreatesMissingCodexConfig() throws Exception {
        var result = ProjectInitializer.ensureCodexConfig(tempDir, false);

        assertEquals(CodexConfigInstaller.Result.ADDED, result);
        Path config = tempDir.resolve(".codex/config.toml");
        assertTrue(Files.isRegularFile(config));
        String content = Files.readString(config);
        assertTrue(content.contains("[mcp_servers.quill]"));
        assertTrue(content.contains("args = [\"--mcp\"]"));
        assertTrue(content.contains(
                "cwd = \"" + escaped(tempDir.toAbsolutePath().toString()) + "\""));
    }

    @Test
    void projectUninstallDeletesConfigCreatedByQuill() throws Exception {
        CodexConfigInstaller.install(tempDir, null);

        assertEquals(CodexConfigInstaller.Result.REMOVED,
                CodexConfigInstaller.uninstall(tempDir));
        assertFalse(Files.exists(tempDir.resolve(".codex/config.toml")));
        assertFalse(Files.exists(tempDir.resolve(".codex")));
    }

    @Test
    void projectUninstallPreservesUserSettings() throws Exception {
        Path config = createConfig("model = \"gpt-test\"\n");
        CodexConfigInstaller.install(tempDir, null);

        assertEquals(CodexConfigInstaller.Result.REMOVED,
                CodexConfigInstaller.uninstall(tempDir));
        assertEquals("model = \"gpt-test\"\n", Files.readString(config));
    }

    @Test
    void indexOnlyInitializationLeavesExistingConfigUntouched() throws Exception {
        Path config = createConfig("model = \"gpt-test\"\n");
        String original = Files.readString(config);

        var result = ProjectInitializer.ensureCodexConfig(tempDir, true);

        assertEquals(CodexConfigInstaller.Result.SKIPPED, result);
        assertEquals(original, Files.readString(config));
    }

    @Test
    void appendsDetectedNativeLauncherWithoutChangingExistingSettings() throws Exception {
        Path config = createConfig("model = \"gpt-test\"\n");
        String launcher = "C:\\Tools\\Quill Лаунчер\\quill.exe";

        var result = CodexConfigInstaller.installIfPresent(tempDir, launcher);
        String content = Files.readString(config);

        assertEquals(CodexConfigInstaller.Result.ADDED, result);
        assertTrue(content.startsWith("model = \"gpt-test\"\n"));
        assertTrue(content.contains("[mcp_servers.quill]"));
        assertTrue(content.contains("command = \"C:\\\\Tools\\\\Quill Лаунчер\\\\quill.exe\""));
        assertTrue(content.contains("args = [\"--mcp\"]"));
        assertTrue(content.contains("cwd = \"" + escaped(tempDir.toAbsolutePath().toString()) + "\""));
    }

    @Test
    void usesBinaryFromPathWhenLauncherIsUnavailable() throws Exception {
        Path config = createConfig("");
        CodexConfigInstaller.installIfPresent(tempDir, null);

        String content = Files.readString(config);
        assertTrue(content.contains("command = \"quill\""));
        assertTrue(content.contains("args = [\"--mcp\"]"));
    }

    @Test
    void readsConfiguredQuillCommandForDiagnostics() {
        String content = """
                [mcp_servers.quill]
                command = "/tmp/quill launcher"
                args = ["--mcp"]
                """;

        assertEquals("/tmp/quill launcher",
                CodexConfigInstaller.quillCommand(content).orElseThrow());
    }

    @Test
    void repairsOnlyManagedEntryWithMissingAbsoluteLauncher() throws Exception {
        Path replacement = Files.createFile(tempDir.resolve("quill-new"));
        assertTrue(replacement.toFile().setExecutable(true));
        String missing = tempDir.resolve("missing/quill").toAbsolutePath().toString();
        Path config = createConfig("""
                # Added by Quill.
                [mcp_servers.quill]
                command = "%s"
                args = ["--mcp"]
                cwd = "/old"
                """.formatted(escaped(missing)));

        assertEquals(CodexConfigInstaller.Result.REPLACED,
                CodexConfigInstaller.installIfPresent(tempDir, replacement.toString()));

        String updated = Files.readString(config);
        assertTrue(updated.contains("command = \"" + escaped(replacement.toString()) + "\""));
        assertTrue(updated.contains("cwd = \"" + escaped(tempDir.toAbsolutePath().toString()) + "\""));
    }

    @Test
    void preservesUserOwnedEntryWithMissingLauncher() throws Exception {
        Path replacement = Files.createFile(tempDir.resolve("quill-new"));
        assertTrue(replacement.toFile().setExecutable(true));
        String missing = tempDir.resolve("missing/quill").toAbsolutePath().toString();
        Path config = createConfig("""
                [mcp_servers.quill]
                command = "%s"
                args = ["--mcp"]
                """.formatted(escaped(missing)));
        String original = Files.readString(config);

        assertEquals(CodexConfigInstaller.Result.ALREADY_CONFIGURED,
                CodexConfigInstaller.installIfPresent(tempDir, replacement.toString()));
        assertEquals(original, Files.readString(config));
    }

    @Test
    void repeatedInstallationDoesNotModifyConfig() throws Exception {
        Path config = createConfig("approval_policy = \"never\"\n");

        assertEquals(CodexConfigInstaller.Result.ADDED,
                CodexConfigInstaller.installIfPresent(tempDir, null));
        String first = Files.readString(config);

        assertEquals(CodexConfigInstaller.Result.ALREADY_CONFIGURED,
                CodexConfigInstaller.installIfPresent(tempDir, null));
        assertEquals(first, Files.readString(config));
    }

    @Test
    void preservesExistingQuotedQuillTable() throws Exception {
        Path config = createConfig("""
                [mcp_servers."quill"]
                command = "custom-quill"
                args = ["--mcp"]
                """);
        String original = Files.readString(config);

        var result = CodexConfigInstaller.installIfPresent(tempDir, null);

        assertEquals(CodexConfigInstaller.Result.ALREADY_CONFIGURED, result);
        assertEquals(original, Files.readString(config));
    }

    @Test
    void preservesQuillEntryInParentMcpTable() throws Exception {
        Path config = createConfig("""
                [mcp_servers]
                quill = { command = "custom-quill", args = ["--mcp"] }
                """);
        String original = Files.readString(config);

        var result = CodexConfigInstaller.installIfPresent(tempDir, null);

        assertEquals(CodexConfigInstaller.Result.ALREADY_CONFIGURED, result);
        assertEquals(original, Files.readString(config));
    }

    @Test
    void leavesRootInlineMcpTableUnchanged() throws Exception {
        Path config = createConfig("mcp_servers = { other = { command = \"server\" } }\n");
        String original = Files.readString(config);

        var result = CodexConfigInstaller.installIfPresent(tempDir, null);

        assertEquals(CodexConfigInstaller.Result.UNSUPPORTED, result);
        assertEquals(original, Files.readString(config));
    }

    @Test
    void keepsWindowsLineEndings() throws Exception {
        Path config = createConfig("model = \"gpt-test\"\r\n");

        CodexConfigInstaller.installIfPresent(tempDir, null);

        String content = Files.readString(config);
        assertFalse(content.replace("\r\n", "").contains("\n"));
        assertTrue(content.contains("\r\n[mcp_servers.quill]\r\n"));
    }

    @Test
    void replacesLegacyQuillWithWorkspaceEntryAndRemovesIt() throws Exception {
        Path config = createConfig("""
                model = "gpt-test"

                [mcp_servers.quill]
                command = "old-quill"
                args = ["--mcp", "--project", "/old"]

                [mcp_servers.other]
                command = "other-server"
                """);
        Path workspace = tempDir.resolve("workspace");

        assertEquals(CodexConfigInstaller.Result.REPLACED,
                CodexConfigInstaller.installWorkspaceIfPresent(
                        tempDir, workspace, "/opt/quill"));
        String installed = Files.readString(config);
        assertFalse(installed.contains("old-quill"));
        assertTrue(installed.contains("\"--workspace\""));
        assertTrue(installed.contains(escaped(workspace.toAbsolutePath().toString())));
        assertTrue(installed.contains("[mcp_servers.other]"));

        assertEquals(CodexConfigInstaller.Result.REMOVED,
                CodexConfigInstaller.uninstallWorkspaceIfPresent(tempDir, workspace));
        String cleaned = Files.readString(config);
        assertFalse(cleaned.contains("[mcp_servers.quill]"));
        assertTrue(cleaned.contains("[mcp_servers.other]"));
        assertTrue(cleaned.contains("model = \"gpt-test\""));
    }

    @Test
    void doesNotRemoveWorkspaceEntryManagedForAnotherWorkspace() throws Exception {
        createConfig("model = \"gpt-test\"\n");
        Path first = tempDir.resolve("first");
        CodexConfigInstaller.installWorkspaceIfPresent(tempDir, first, null);

        assertEquals(CodexConfigInstaller.Result.ALREADY_CONFIGURED,
                CodexConfigInstaller.uninstallWorkspaceIfPresent(
                        tempDir, tempDir.resolve("second")));
        assertTrue(Files.readString(tempDir.resolve(".codex/config.toml"))
                .contains("# Added by Quill for workspace "));
    }

    private Path createConfig(String content) throws Exception {
        Path config = tempDir.resolve(".codex/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, content);
        return config;
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
