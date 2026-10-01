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
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.QuillTopCommand;
import org.treblereel.mcp.mcp.ProjectRegistry;
import org.treblereel.mcp.mcp.WorkspaceProjectScope;
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
    void initIndexesEverySuitableRepositoryWithoutRunningBuilds() throws Exception {
        createCompiledRepository("engine");
        createCompiledRepository("platform");

        Captured result = execute("workspace", "init", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, result.exitCode(),
                result.stdout() + System.lineSeparator() + result.stderr());
        assertTrue(result.stdout().contains(
                "indexed=2, pending_build=0, skipped=0, failed=0"), result.stdout());
        for (String name : java.util.List.of("engine", "platform")) {
            Path repository = workspace.resolve(name);
            assertTrue(ProjectIndexStore.findBestAvailableDb(repository) != null);
            assertTrue(Files.isRegularFile(repository.resolve(".mvn/extensions.xml")));
            assertWorkspaceMcp(repository.resolve(".mcp.json"));
            assertFalse(Files.exists(repository.resolve("build-was-invoked")));
        }
        assertWorkspaceMcp(workspace.resolve(".mcp.json"));
    }

    @Test
    void initReplacesLegacyClientEntriesAndClearRemovesWorkspaceEntries() throws Exception {
        createCompiledRepository("engine");
        Path repository = workspace.resolve("engine");
        Files.writeString(repository.resolve(".mcp.json"), """
                {"mcpServers": {
                  "quill": {"command": "old-quill", "args": ["--mcp", "--project", "/old"]},
                  "other": {"command": "other-server"}
                }}
                """);
        Path codex = repository.resolve(".codex/config.toml");
        Files.createDirectories(codex.getParent());
        Files.writeString(codex, """
                model = "gpt-test"
                [mcp_servers.quill]
                command = "old-quill"
                args = ["--mcp", "--project", "/old"]
                """);

        assertEquals(CommandLine.ExitCode.OK, execute("workspace", "init",
                "--project", workspace.toString()).exitCode());
        assertWorkspaceMcp(repository.resolve(".mcp.json"));
        String installedCodex = Files.readString(codex);
        assertFalse(installedCodex.contains("old-quill"));
        assertTrue(installedCodex.contains("\"--workspace\""));
        String tomlWorkspacePath = workspace.toAbsolutePath().normalize().toString()
                .replace("\\", "\\\\");
        assertTrue(installedCodex.contains(tomlWorkspacePath));

        Captured cleared = execute("workspace", "clear", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, cleared.exitCode(), cleared.stderr());
        var mcp = JSON.readTree(repository.resolve(".mcp.json").toFile());
        assertFalse(mcp.path("mcpServers").has("quill"));
        assertEquals("other-server",
                mcp.path("mcpServers").path("other").path("command").asText());
        String cleanedCodex = Files.readString(codex);
        assertFalse(cleanedCodex.contains("[mcp_servers.quill]"));
        assertTrue(cleanedCodex.contains("model = \"gpt-test\""));
        assertFalse(Files.exists(workspace.resolve(".mcp.json")));
        assertTrue(Files.isDirectory(repository.resolve(".quill")));
    }

    @Test
    void initPreparesSupportedUncompiledRepositoriesForTheirFirstBuild() throws Exception {
        Files.createDirectories(workspace.resolve("docs/.git"));
        Path uncompiled = Files.createDirectories(workspace.resolve("uncompiled"));
        Files.createDirectories(uncompiled.resolve(".git"));
        Files.writeString(uncompiled.resolve("pom.xml"), "<project/>");

        Captured result = execute("workspace", "init", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains(
                "indexed=0, pending_build=1, skipped=1, failed=0"), result.stdout());
        assertTrue(Files.isRegularFile(uncompiled.resolve(".mvn/extensions.xml")));
        assertWorkspaceMcp(uncompiled.resolve(".mcp.json"));
        assertFalse(Files.exists(uncompiled.resolve(".quill/refs.json")));
    }

    @Test
    void successfulBuildMakesPendingRepositoryQueryReady() throws Exception {
        Path repository = createRepository("engine");

        Captured initialized = execute(
                "workspace", "init", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, initialized.exitCode(), initialized.stderr());
        assertTrue(initialized.stdout().contains("pending_build=1"), initialized.stdout());
        assertTrue(Files.isRegularFile(repository.resolve(".mvn/extensions.xml")));
        assertFalse(Files.exists(repository.resolve(".quill/refs.json")));

        compileRepository(repository);
        ProjectRegistry registry = new ProjectRegistry(new WorkspaceProjectScope(workspace));
        ProjectRegistry.Resolution missingIndex = registry.resolve("engine");
        assertEquals("index_required", missingIndex.issues().getFirst().code());
        assertTrue(missingIndex.issues().getFirst().recommendedAction()
                .contains("quill workspace refresh --project " + workspace));

        Path events = Files.createDirectories(repository.resolve(".quill/build-events"));
        Files.writeString(events.resolve("maven-test.json"), """
                {"version":3,"buildTool":"maven","successful":true,
                 "finishedAt":%d,"captureScope":"exception_chain",
                 "failureMessagesBase64":[],"diagnosticsBase64":[]}
                """.formatted(System.currentTimeMillis()));

        ProjectRegistry.Resolution resolution = registry.resolve("engine");

        assertTrue(resolution.errors().isEmpty(), String.join("; ", resolution.errors()));
        assertEquals(1, resolution.projects().size());
        assertTrue(ProjectIndexStore.findBestAvailableDb(repository) != null);
        assertFalse(Files.exists(events));
    }

    @Test
    void indexOnlyLeavesPendingRepositoryForExplicitRefresh() throws Exception {
        Path repository = createRepository("engine");

        Captured initialized = execute("workspace", "init", "--project", workspace.toString(),
                "--index-only");

        assertEquals(CommandLine.ExitCode.OK, initialized.exitCode(), initialized.stderr());
        assertTrue(initialized.stdout().contains("pending_build=1"), initialized.stdout());
        assertTrue(initialized.stdout().contains("run workspace refresh"), initialized.stdout());
        assertFalse(Files.exists(repository.resolve(".mvn/extensions.xml")));
        assertFalse(Files.exists(repository.resolve(".mcp.json")));
        assertFalse(Files.exists(repository.resolve(".quill/refs.json")));
    }

    @Test
    void reportsMachineReadableStatus() throws Exception {
        createCompiledRepository("engine");
        execute("workspace", "init", "--project", workspace.toString(), "--depth", "2");

        Captured status = execute("workspace", "status", "--project", workspace.toString(),
                "--json");

        assertEquals(CommandLine.ExitCode.OK, status.exitCode());
        var json = JSON.readTree(status.stdout());
        assertEquals(workspace.toAbsolutePath().normalize().toString(),
                json.path("workspace_root").asText());
        assertEquals(2, json.path("discovery_depth").asInt());
        assertTrue(json.path("excludes").isArray());
        assertEquals(1, json.path("repository_count").asInt());
        var repository = json.path("repositories").get(0);
        assertEquals("engine", repository.path("name").asText());
        assertTrue(repository.path("supported").asBoolean());
        assertTrue(repository.path("compiled").asBoolean());
        assertTrue(repository.path("indexed").asBoolean());
        assertEquals(25, repository.path("indexSchema").asInt());
        assertEquals(25, repository.path("currentSchema").asInt());
        assertEquals("installed", repository.path("buildIntegration").asText());
        assertTrue(repository.path("queryReady").asBoolean());
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
    void clearRepositoriesRemovesIndexesAndBuildIntegration() throws Exception {
        createCompiledRepository("engine");
        Files.createDirectories(workspace.resolve("documentation/.git"));
        assertEquals(CommandLine.ExitCode.OK, execute("workspace", "init",
                "--project", workspace.toString()).exitCode());
        Path repository = workspace.resolve("engine");
        assertTrue(Files.isDirectory(repository.resolve(".quill")));
        assertTrue(Files.isRegularFile(repository.resolve(".mvn/extensions.xml")));

        Captured result = execute("workspace", "clear", "--project", workspace.toString(),
                "--repositories");

        assertEquals(CommandLine.ExitCode.OK, result.exitCode(), result.stderr());
        assertFalse(Files.exists(WorkspaceManifestStore.directory(workspace)));
        assertFalse(Files.exists(repository.resolve(".quill")));
        assertFalse(Files.exists(repository.resolve(".mvn/extensions.xml")));
        assertTrue(result.stdout().contains("Cleaned 1 repositories"), result.stdout());
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
    void clearRefusesAWorkspaceServedByMcpSharedLock() throws Exception {
        execute("workspace", "init", "--project", workspace.toString());
        try (WorkspaceLock ignored = WorkspaceLock.tryAcquireShared(workspace)) {
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

    @Test
    void refreshDiscoversRepositoriesWithoutRunningTheirBuilds() throws Exception {
        execute("workspace", "init", "--project", workspace.toString());
        Path repository = Files.createDirectories(workspace.resolve("engine"));
        Files.createDirectories(repository.resolve(".git"));
        Files.writeString(repository.resolve("pom.xml"), "<project/>");

        Captured result = execute("workspace", "refresh", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, result.exitCode());
        assertTrue(result.stdout().contains("Repositories: 1"));
        assertTrue(result.stdout().contains("engine ->"));
        assertFalse(Files.exists(repository.resolve("target")));
    }

    @Test
    void refreshIndexesNewCompiledRepositoriesAndReconcilesRemovals() throws Exception {
        assertEquals(CommandLine.ExitCode.OK, execute("workspace", "init",
                "--project", workspace.toString()).exitCode());
        createCompiledRepository("engine");

        Captured added = execute("workspace", "refresh", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, added.exitCode(),
                added.stdout() + System.lineSeparator() + added.stderr());
        assertTrue(added.stdout().contains("added=1, removed=0, indexed=1"), added.stdout());
        assertTrue(ProjectIndexStore.findBestAvailableDb(workspace.resolve("engine")) != null);
        assertFalse(Files.exists(workspace.resolve("engine/build-was-invoked")));

        Files.delete(workspace.resolve("engine/.git"));
        Captured removed = execute("workspace", "refresh", "--project", workspace.toString());

        assertEquals(CommandLine.ExitCode.OK, removed.exitCode(), removed.stderr());
        assertTrue(removed.stdout().contains("added=0, removed=1"), removed.stdout());
        assertTrue(removed.stdout().contains("Repositories: 0"), removed.stdout());
        assertFalse(Files.exists(workspace.resolve("engine/.mcp.json")));
    }

    private void assertWorkspaceMcp(Path file) throws Exception {
        var quill = JSON.readTree(file.toFile()).path("mcpServers").path("quill");
        assertEquals("--mcp", quill.path("args").get(0).asText());
        assertEquals("--workspace", quill.path("args").get(1).asText());
        assertEquals(workspace.toAbsolutePath().normalize().toString(),
                quill.path("args").get(2).asText());
    }

    private void createCompiledRepository(String name) throws Exception {
        compileRepository(createRepository(name));
    }

    private Path createRepository(String name) throws Exception {
        Path repository = Files.createDirectories(workspace.resolve(name));
        Files.createDirectories(repository.resolve(".git"));
        Files.writeString(repository.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>org.acme</groupId><artifactId>%s</artifactId><version>1</version>
                </project>
                """.formatted(name));
        Files.writeString(repository.resolve("mvnw"),
                "#!/bin/sh\ntouch build-was-invoked\nexit 99\n");
        Files.writeString(repository.resolve(".gitignore"), "target/\n");
        Path source = repository.resolve("src/main/java/org/acme/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.acme; public final class App {}\n");
        return repository;
    }

    private void compileRepository(Path repository) throws Exception {
        Path source = repository.resolve("src/main/java/org/acme/App.java");
        Path classes = Files.createDirectories(repository.resolve("target/classes"));
        int compilation = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-d", classes.toString(), source.toString());
        assertEquals(0, compilation);
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
