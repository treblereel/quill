package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.mcp.WorkspaceProjectScope;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "refresh", mixinStandardHelpOptions = true,
        description = "Rescan repositories without invoking Maven or Gradle")
public final class WorkspaceRefreshCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Override
    public Integer call() {
        WorkspaceProjectScope scope = new WorkspaceProjectScope(workspaceRoot);
        var snapshot = scope.refresh();
        System.out.println("Workspace: " + scope.root());
        System.out.println("Repositories: " + snapshot.projects().size());
        snapshot.projects().forEach(project ->
                System.out.println("  " + project.name() + " -> " + project.root()));
        snapshot.diagnostics().forEach(diagnostic ->
                System.err.println("[quill] Warning: " + diagnostic));
        return CommandLine.ExitCode.OK;
    }
}
