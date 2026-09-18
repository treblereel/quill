package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.workspace.WorkspaceManifest;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Initialize a Quill workspace without indexing or building its repositories")
public final class WorkspaceInitCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Option(names = "--depth", description = "Maximum repository discovery depth (default: 1)")
    int discoveryDepth = 1;

    @Override
    public Integer call() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.initialize(
                workspaceRoot, discoveryDepth);
        System.out.println("Initialized Quill workspace at " + manifest.root());
        System.out.println("Manifest: " + WorkspaceManifestStore.manifest(manifest.root()));
        return CommandLine.ExitCode.OK;
    }
}
