package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.concurrent.Callable;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;
import org.treblereel.mcp.workspace.WorkspaceLock;
import org.treblereel.mcp.workspace.WorkspaceManifest;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "refresh", mixinStandardHelpOptions = true,
        description = "Rescan and index new repositories without invoking Maven or Gradle")
public final class WorkspaceRefreshCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Option(names = "--index-only", description = "Skip build integration and repository "
            + "configuration while creating missing indexes")
    boolean indexOnly;

    @Override
    public Integer call() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.read(workspaceRoot);
        WorkspaceLock lock = WorkspaceLock.tryAcquire(manifest.root());
        if (lock == null) {
            System.err.println("[quill] Workspace is in use: " + manifest.root());
            return CommandLine.ExitCode.SOFTWARE;
        }
        WorkspaceDiscovery.Result discovery;
        WorkspaceRepositoryInitializer.Result result;
        int added;
        int removed;
        try (lock) {
            var previous = WorkspaceRepositoryStateStore.read(manifest.root());
            discovery = WorkspaceDiscovery.discover(manifest);
            var previousPaths = previous.stream().map(
                    WorkspaceRepositoryStateStore.Repository::relativePath)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var currentPaths = discovery.repositories().stream().map(
                    WorkspaceDiscovery.Repository::relativePath)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            added = currentPaths.stream().filter(path -> !previousPaths.contains(path)).toList()
                    .size();
            removed = previousPaths.stream().filter(path -> !currentPaths.contains(path)).toList()
                    .size();
            result = WorkspaceRepositoryInitializer.initializeMissing(
                    discovery, indexOnly, System.out::println);
            WorkspaceRepositoryStateStore.write(manifest.root(), discovery.repositories());
        }
        System.out.println("Workspace: " + manifest.root());
        System.out.println("Repositories: " + discovery.repositories().size());
        discovery.repositories().forEach(repository ->
                System.out.println("  " + repository.name() + " -> " + repository.root()));
        discovery.diagnostics().forEach(diagnostic ->
                System.err.println("[quill] Warning: " + diagnostic));
        System.out.println("Workspace refresh complete: added=" + added + ", removed=" + removed
                + ", indexed=" + result.indexed() + ", skipped=" + result.skipped()
                + ", unchanged=" + result.unchanged() + ", failed=" + result.failed());
        return result.successful() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }
}
