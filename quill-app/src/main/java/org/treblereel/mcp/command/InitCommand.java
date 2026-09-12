package org.treblereel.mcp.command;

import java.nio.file.Path;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", description = "Index a Maven or Gradle Java project's "
        + "dependency graph (CDI and Spring). Compiles the project when bytecode is missing.")
public class InitCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--index-only", description = "Only create the index; skip git hooks, "
            + "agent configuration, and .gitignore modifications")
    boolean indexOnly;

    @Override
    public void run() {
        Path root = ProjectRootFinder.find(projectPath);

        System.out.println("Indexing project at " + root + " ...");
        ProjectInitializer.InitializationResult result =
                ProjectInitializer.initializeDetailed(root, indexOnly);
        if (!result.successful()) {
            System.err.println("[quill] " + result.diagnostic()
                    + " (after " + result.elapsedMillis() + " ms)");
            System.exit(2);
        }
    }
}
