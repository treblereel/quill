package org.treblereel.mcp.command;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import org.treblereel.mcp.QuillLauncher;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Updates client integration independently of build outputs and index generations. */
@Command(name = "refresh", mixinStandardHelpOptions = true,
        description = "Refresh managed instructions and local MCP registration; no index, build, or permission grants")
public final class ClientRefreshCommand implements Callable<Integer> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    @Option(names = "--project", description = "Configuration directory (default: current directory)") Path project;
    @Option(names = "--workspace", description = "Initialized workspace and its recorded configured repositories") Path workspace;
    @Spec CommandLine.Model.CommandSpec spec;

    @Override public Integer call() {
        try {
            if (project != null && workspace != null) {
                spec.commandLine().getErr().println("[quill] Choose --project or --workspace, not both");
                return CommandLine.ExitCode.USAGE;
            }
            Path root = (workspace != null ? workspace : project != null ? project : Path.of("."))
                    .toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) throw new IOException("Configuration directory does not exist: " + root);
            if (workspace == null && Files.exists(WorkspaceManifestStore.manifest(root))) {
                throw new IOException("This is an initialized workspace; use --workspace " + root);
            }
            List<Path> targets = targets(root);
            // Validate all writable targets before updating any project. No trust or tool grants.
            for (Path target : targets) preflight(target);
            String binary = QuillLauncher.detect();
            boolean successful = true;
            for (Path target : targets) {
                ProjectConfiguration.ensureClaudeMd(target);
                ProjectConfiguration.ensureAgentsMd(target);
                boolean claude = ClaudeSettingsInstaller.install(target);
                var mcp = workspace == null ? McpJsonInstaller.installProject(target, binary)
                        : McpJsonInstaller.installWorkspace(target, root, binary);
                var codex = workspace == null ? CodexConfigInstaller.install(target, binary)
                        : CodexConfigInstaller.installWorkspace(target, root, binary);
                boolean current = ProjectConfiguration.inspectClaudeMd(target) == ProjectConfiguration.InstructionsState.CURRENT
                        && ProjectConfiguration.inspectAgentsMd(target) == ProjectConfiguration.InstructionsState.CURRENT;
                boolean ok = current && claude && mcp != McpJsonInstaller.Result.UNSUPPORTED
                        && codex != CodexConfigInstaller.Result.UNSUPPORTED && codex != CodexConfigInstaller.Result.FAILED;
                successful &= ok;
                spec.commandLine().getOut().println(target + ": instructions=" + (current ? "current" : "failed")
                        + ", claude=" + claude + ", mcp=" + mcp + ", codex=" + codex);
            }
            spec.commandLine().getOut().println("No indexing, builds, tool permission grants, or global trust changes. "
                    + "Start a fresh client session; live tool activation was not checked.");
            spec.commandLine().getOut().flush();
            return successful ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
        } catch (IOException | IllegalArgumentException error) {
            spec.commandLine().getErr().println("[quill] Client refresh failed: " + error.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    private List<Path> targets(Path root) throws IOException {
        if (workspace == null) return List.of(root);
        WorkspaceManifestStore.read(root);
        LinkedHashSet<Path> result = new LinkedHashSet<>();
        result.add(root);
        for (var repository : WorkspaceRepositoryStateStore.read(root)) {
            if (repository.relativePath() == null) throw new IOException("Invalid workspace repository path");
            Path target = root.resolve(repository.relativePath()).normalize();
            if (!target.startsWith(root) || !Files.isDirectory(target)
                    || !target.toRealPath().startsWith(root.toRealPath())) {
                throw new IOException("Workspace repository is missing or outside the workspace: " + target);
            }
            // Do not configure previously unsupported/unconfigured repositories or rediscover the workspace.
            if (ClaudeSettingsInstaller.isEnabled(target)
                    || Files.exists(target.resolve(".mcp.json"))
                    || Files.exists(target.resolve(".codex/config.toml"))) result.add(target);
        }
        return List.copyOf(result);
    }

    private static void preflight(Path root) throws IOException {
        for (String directory : List.of(".claude", ".codex")) {
            Path path = root.resolve(directory);
            if (Files.isSymbolicLink(path) || (Files.exists(path) && !Files.isDirectory(path))) {
                throw new IOException("Not a regular configuration directory: " + path);
            }
        }
        for (String name : List.of("AGENTS.md", "CLAUDE.md", ".mcp.json", ".codex/config.toml")) {
            Path path = root.resolve(name);
            if (Files.isSymbolicLink(path) || (Files.exists(path) && !Files.isRegularFile(path))) {
                throw new IOException("Not a regular configuration file: " + path);
            }
            if (name.endsWith(".md") && Files.exists(path)
                    && !ProjectConfiguration.validMarkers(Files.readString(path))) {
                throw new IOException("Repair malformed Quill markers before refreshing " + path);
            }
        }
        if (!ClaudePermissionStatus.inspect(root).valid()) {
            throw new IOException("Invalid local Claude settings; run quill client permissions status --project " + root);
        }
        Path mcp = root.resolve(".mcp.json");
        Path codex = root.resolve(".codex/config.toml");
        if (Files.exists(codex) && CodexConfigInstaller.definesInlineMcpServers(Files.readString(codex))) {
            throw new IOException("Inline mcp_servers table requires manual configuration: " + codex);
        }
        if (Files.exists(mcp)) {
            var config = JSON.readTree(mcp.toFile());
            if (config == null || !config.isObject()
                    || (config.has("mcpServers") && !config.get("mcpServers").isObject())) {
                throw new IOException("Unsupported MCP configuration: " + mcp);
            }
        }
        Path claude = root.resolve(".claude/settings.json");
        if (Files.exists(claude)) {
            var config = JSON.readTree(claude.toFile());
            if (config.has("enabledMcpjsonServers") && !config.get("enabledMcpjsonServers").isArray()) {
                throw new IOException("Unsupported enabledMcpjsonServers in " + claude);
            }
        }
    }
}
