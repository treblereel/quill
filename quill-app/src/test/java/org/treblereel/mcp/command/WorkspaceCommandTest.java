package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.QuillTopCommand;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceLock;
import picocli.CommandLine;

class WorkspaceCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path workspace;

    @Test
    void initializesIdempotentlyWithoutTouchingRepositoryIndexes() throws Exception {
        Path repositoryIndex = Files.createDirectories(workspace.resolve("engine/.quill"));
        Files.writeString(repositoryIndex.resolve("keep.db"), "keep");

        assertEquals(CommandLine.ExitCode.OK, execute("workspace", "init",
                "--project", workspace.toString()).exitCode());
        String firstManifest = Files.readString(WorkspaceManifestStore.manifest(workspace));
        assertEquals(CommandLine.ExitCode.OK, execute("workspace", "init",
                "--project", workspace.toString(), "--depth", "3").exitCode());

        assertEquals(firstManifest, Files.readString(WorkspaceManifestStore.manifest(workspace)));
        assertTrue(Files.isRegularFile(repositoryIndex.resolve("keep.db")));
        assertFalse(Files.exists(workspace.resolve("engine/target")));
    }

    @Test
    void reportsMachineReadableStatus() throws Exception {
        execute("workspace", "init", "--project", workspace.toString(), "--depth", "2");

        Captured status = execute("workspace", "status", "--project", workspace.toString(),
                "--json");

        assertEquals(CommandLine.ExitCode.OK, status.exitCode());
        var json = JSON.readTree(status.stdout());
        assertEquals(workspace.toAbsolutePath().normalize().toString(),
                json.path("workspace_root").asText());
        assertEquals(2, json.path("discovery_depth").asInt());
        assertTrue(json.path("excludes").isArray());
    }

    @Test
    void rejectsInvalidDiscoveryDepthWithoutPublishingManifest() {
        Captured result = execute("workspace", "init", "--project", workspace.toString(),
                "--depth", "0");

        assertEquals(CommandLine.ExitCode.SOFTWARE, result.exitCode());
        assertFalse(Files.exists(WorkspaceManifestStore.manifest(workspace)));
    }

    @Test
    void clearRemovesOnlyWorkspaceDataAndIsIdempotent() throws Exception {
        Path repositoryIndex = Files.createDirectories(workspace.resolve("engine/.quill"));
        Files.writeString(repositoryIndex.resolve("keep.db"), "keep");
        execute("workspace", "init", "--project", workspace.toString());

        Captured first = execute("workspace", "clear", "--project", workspace.toString());
        Captured second = execute("workspace", "clear", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, first.exitCode());
        assertEquals(CommandLine.ExitCode.OK, second.exitCode());
        assertFalse(Files.exists(WorkspaceManifestStore.directory(workspace)));
        assertTrue(Files.isRegularFile(repositoryIndex.resolve("keep.db")));
        assertTrue(first.stdout().contains("Repository indexes were preserved"));
        assertTrue(second.stdout().contains("No workspace data found"));
    }

    @Test
    void clearRejectsSymbolicWorkspaceDirectory() throws Exception {
        Path outside = Files.createDirectories(workspace.resolve("outside"));
        Path workspaceData = WorkspaceManifestStore.directory(workspace);
        try {
            Files.createSymbolicLink(workspaceData, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        Captured result = execute("workspace", "clear", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.SOFTWARE, result.exitCode());
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void clearRefusesAnActivelyLockedWorkspace() throws Exception {
        execute("workspace", "init", "--project", workspace.toString());
        Path lockPath = workspace.resolve(WorkspaceLock.FILE);
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            Captured result = execute("workspace", "clear", "--project", workspace.toString());
            assertEquals(CommandLine.ExitCode.SOFTWARE, result.exitCode());
            assertTrue(Files.isRegularFile(WorkspaceManifestStore.manifest(workspace)));
        }
    }

    @Test
    void workspaceCanBeInitializedAgainAfterClear() throws Exception {
        execute("workspace", "init", "--project", workspace.toString());
        execute("workspace", "clear", "--project", workspace.toString());

        Captured result = execute("workspace", "init", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(Files.isRegularFile(WorkspaceManifestStore.manifest(workspace)));
    }

    private Captured execute(String... arguments) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            CommandLine command = new CommandLine(new QuillTopCommand());
            command.setExecutionExceptionHandler((error, ignored, parseResult) ->
                    CommandLine.ExitCode.SOFTWARE);
            int exitCode = command.execute(arguments);
            return new Captured(exitCode, stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record Captured(int exitCode, String stdout, String stderr) {}
}
