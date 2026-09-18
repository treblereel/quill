package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.workspace.WorkspaceManifest;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceLock;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Initialize a Quill workspace and index suitable repositories without "
                + "running their builds")
public final class WorkspaceInitCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Option(names = "--depth", description = "Maximum repository discovery depth (default: 1)")
    int discoveryDepth = 1;

    @Option(names = "--index-only", description = "Skip build integration and repository "
            + "configuration while creating indexes")
    boolean indexOnly;

    @Override
    public Integer call() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.initialize(
                workspaceRoot, discoveryDepth);
        System.out.println("Initialized Quill workspace at " + manifest.root());
        System.out.println("Manifest: " + WorkspaceManifestStore.manifest(manifest.root()));
        WorkspaceLock lock = WorkspaceLock.tryAcquire(manifest.root());
        if (lock == null) {
            System.err.println("[quill] Workspace is in use: " + manifest.root());
            return CommandLine.ExitCode.SOFTWARE;
        }
        WorkspaceRepositoryInitializer.Result result;
        try (lock) {
            result = WorkspaceRepositoryInitializer.initialize(
                    manifest, indexOnly, System.out::println);
        }
        System.out.println("Workspace initialization complete: indexed=" + result.indexed()
                + ", skipped=" + result.skipped() + ", failed=" + result.failed());
        return result.successful() ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
    }
}
