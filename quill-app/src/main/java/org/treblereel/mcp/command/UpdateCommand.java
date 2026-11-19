package org.treblereel.mcp.command;

import org.treblereel.mcp.index.ProjectUpdater;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update", mixinStandardHelpOptions = true,
        description = "Incrementally update the project index")
public class UpdateCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--force", description = "Force full re-index even if no changes detected")
    boolean force;

    @Override
    public Integer call() {
        Path root = ProjectRootFinder.find(projectPath);

        try {
            ProjectUpdater.update(root, force, System.out::println);
            return picocli.CommandLine.ExitCode.OK;
        } catch (IOException e) {
            System.err.println("[quill] Could not lock index for " + root + ": " + e.getMessage());
            return picocli.CommandLine.ExitCode.SOFTWARE;
        }
    }

}
