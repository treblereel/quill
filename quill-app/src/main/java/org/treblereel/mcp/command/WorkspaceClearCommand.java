package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;
import org.treblereel.mcp.workspace.WorkspaceManifest;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "clear", mixinStandardHelpOptions = true,
        description = "Remove workspace data and optionally clean every repository")
public final class WorkspaceClearCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Option(names = "--repositories", description = "Also remove repository indexes and Quill "
            + "build integration")
    boolean repositories;

    @Override
    public Integer call() throws Exception {
        Path root = WorkspaceManifestStore.normalizeRoot(workspaceRoot);
        var manifestPath = WorkspaceManifestStore.manifest(root);
        if (repositories && !Files.isRegularFile(manifestPath)) {
            System.out.println("No workspace data found at "
                    + WorkspaceManifestStore.directory(root));
            return CommandLine.ExitCode.OK;
        }
        WorkspaceManifest manifest = Files.isRegularFile(manifestPath)
                ? WorkspaceManifestStore.read(root) : null;
        WorkspaceDiscovery.Result discovery = repositories
                ? WorkspaceDiscovery.discover(manifest) : null;
        var configuredRepositories = manifest == null
                ? java.util.List.<WorkspaceRepositoryStateStore.Repository>of()
                : WorkspaceRepositoryStateStore.read(root);
        ArrayList<String> cleaned = new ArrayList<>();
        WorkspaceManifestStore.ClearResult result = WorkspaceManifestStore.clear(root, () -> {
            WorkspaceClientConfiguration.uninstall(root, configuredRepositories);
            if (discovery == null) return;
            for (WorkspaceDiscovery.Repository repository : discovery.repositories()) {
                try {
                    BuildSystem.detect(repository.root());
                } catch (IllegalArgumentException unsupported) {
                    continue;
                }
                CleanCommand.CleanResult clean = CleanCommand.cleanProject(repository.root());
                if (clean.integration() == BuildIntegrationInstaller.Result.FAILED) {
                    throw new IOException("Could not remove build integration from "
                            + repository.root());
                }
                cleaned.add(repository.name());
            }
        });
        if (result.removed()) {
            System.out.println("Removed workspace data from "
                    + WorkspaceManifestStore.directory(result.root()));
            if (repositories) {
                System.out.println("Cleaned " + cleaned.size() + " repositories.");
            } else {
                System.out.println("Repository indexes were preserved.");
            }
        } else {
            System.out.println("No workspace data found at "
                    + WorkspaceManifestStore.directory(result.root()));
        }
        return CommandLine.ExitCode.OK;
    }
}
