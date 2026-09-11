package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.core.GitHookInstaller;

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
        var launcher = new GitHookInstaller.Launcher(
                List.of("C:\\Tools\\Quill Лаунчер\\quill.exe"), Path.of("quill.exe"));

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
    void usesBinaryFromPathWhenInitRunsFromJar() throws Exception {
        Path config = createConfig("");
        var launcher = new GitHookInstaller.Launcher(
                List.of("/opt/java/bin/java", "-jar", "/opt/quill/quill.jar"),
                Path.of("/opt/quill/quill.jar"));

        CodexConfigInstaller.installIfPresent(tempDir, launcher);

        String content = Files.readString(config);
        assertTrue(content.contains("command = \"quill\""));
        assertTrue(content.contains("args = [\"--mcp\"]"));
        assertFalse(content.contains("java"));
        assertFalse(content.contains("quill.jar"));
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
