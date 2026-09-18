package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "clear", mixinStandardHelpOptions = true,
        description = "Remove workspace data while preserving every repository index")
public final class WorkspaceClearCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Override
    public Integer call() throws Exception {
        WorkspaceManifestStore.ClearResult result = WorkspaceManifestStore.clear(workspaceRoot);
        if (result.removed()) {
            System.out.println("Removed workspace data from "
                    + WorkspaceManifestStore.directory(result.root()));
            System.out.println("Repository indexes were preserved.");
        } else {
            System.out.println("No workspace data found at "
                    + WorkspaceManifestStore.directory(result.root()));
        }
        return CommandLine.ExitCode.OK;
    }
}
