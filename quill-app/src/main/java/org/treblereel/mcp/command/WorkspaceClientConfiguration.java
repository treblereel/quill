package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.QuillLauncher;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;
import org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore;

/** Installs and removes workspace-aware project-local MCP client configuration. */
final class WorkspaceClientConfiguration {

    private WorkspaceClientConfiguration() {}

    static void install(Path workspaceRoot,
            List<WorkspaceDiscovery.Repository> repositories, boolean indexOnly)
            throws IOException {
        if (indexOnly) return;
        String binary = QuillLauncher.detect();
        for (Path target : supportedTargets(workspaceRoot, repositories)) {
            McpJsonInstaller.Result mcp =
                    McpJsonInstaller.installWorkspace(target, workspaceRoot, binary);
            if (mcp == McpJsonInstaller.Result.UNSUPPORTED) {
                System.err.println("[quill] Warning: could not update "
                        + target.resolve(".mcp.json") + ": unsupported structure");
            }
            CodexConfigInstaller.Result codex = CodexConfigInstaller.installWorkspaceIfPresent(
                    target, workspaceRoot, binary);
            if (codex == CodexConfigInstaller.Result.FAILED) {
                throw new IOException("Could not update " + target.resolve(".codex/config.toml"));
            }
        }
    }

    static void uninstall(Path workspaceRoot,
            List<WorkspaceRepositoryStateStore.Repository> repositories) throws IOException {
        Set<Path> targets = new LinkedHashSet<>();
        targets.add(workspaceRoot.toAbsolutePath().normalize());
        repositories.forEach(repository -> targets.add(workspaceRoot
                .resolve(repository.relativePath()).toAbsolutePath().normalize()));
        for (Path target : targets) uninstallTarget(target, workspaceRoot);
    }

    static void uninstallRemoved(Path workspaceRoot,
            List<WorkspaceRepositoryStateStore.Repository> previous,
            List<WorkspaceDiscovery.Repository> current) throws IOException {
        Set<String> currentPaths = current.stream().map(WorkspaceDiscovery.Repository::relativePath)
                .collect(java.util.stream.Collectors.toSet());
        for (WorkspaceRepositoryStateStore.Repository repository : previous) {
            if (!currentPaths.contains(repository.relativePath())) {
                uninstallTarget(workspaceRoot.resolve(repository.relativePath()), workspaceRoot);
            }
        }
    }

    private static Set<Path> supportedTargets(Path workspaceRoot,
            List<WorkspaceDiscovery.Repository> repositories) {
        Set<Path> targets = new LinkedHashSet<>();
        targets.add(workspaceRoot.toAbsolutePath().normalize());
        for (WorkspaceDiscovery.Repository repository : repositories) {
            try {
                BuildSystem.detect(repository.root());
                targets.add(repository.root().toAbsolutePath().normalize());
            } catch (IllegalArgumentException ignored) {
                // Non-Java repositories are visible to discovery but do not need Quill MCP config.
            }
        }
        return targets;
    }

    private static void uninstallTarget(Path target, Path workspaceRoot) throws IOException {
        McpJsonInstaller.uninstallWorkspace(target, workspaceRoot);
        CodexConfigInstaller.Result codex =
                CodexConfigInstaller.uninstallWorkspaceIfPresent(target, workspaceRoot);
        if (codex == CodexConfigInstaller.Result.FAILED) {
            throw new IOException("Could not clean " + target.resolve(".codex/config.toml"));
        }
    }
}
