package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Index a Maven or Gradle Java project's "
        + "dependency graph (CDI and Spring).")
public class InitCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--index-only", description = "Only create the index; skip build integration, "
            + "agent configuration, and .gitignore modifications")
    boolean indexOnly;

    @Option(names = "--timings", description = "Report elapsed time for each indexing phase")
    boolean timings;

    @Option(names = "--probe-mcp", description = "After indexing, execute the project .mcp.json "
            + "launcher and check MCP connectivity (30s timeout; no AI requests)")
    boolean probeMcp;

    @Option(names = "--allow-quill-tools", negatable = true,
            description = "Allow current read-only Quill tools in local Claude permissions; "
                    + "--no-allow-quill-tools skips the interactive consent question")
    Boolean allowQuillTools;

    @Override
    public Integer call() {
        if (indexOnly && (probeMcp || Boolean.TRUE.equals(allowQuillTools))) {
            System.err.println("[quill] --probe-mcp/--allow-quill-tools cannot be combined with --index-only");
            return picocli.CommandLine.ExitCode.USAGE;
        }
        Path root = ProjectRootFinder.find(projectPath);

        System.out.println("Indexing project at " + root + " ...");
        ProjectInitializer.InitializationResult result =
                ProjectInitializer.initializeDetailed(root, indexOnly);
        if (timings) {
            System.err.println("[quill] " + result.timingsDiagnostic());
        }
        if (!result.successful()) {
            System.err.println("[quill] " + result.diagnostic()
                    + " (after " + result.elapsedMillis() + " ms)");
            return picocli.CommandLine.ExitCode.SOFTWARE;
        }
        if (!indexOnly) ClaudePermissionConsent.configure(allowQuillTools, java.util.List.of(root));
        McpConnectivityProbe.guidance(indexOnly);
        if (probeMcp) {
            DoctorCommand.Check check = McpConnectivityProbe.inspect(root);
            System.out.println("[" + check.status().label() + "] " + check.message());
            if (check.action() != null) System.out.println("Action: " + check.action());
            if (check.status() == DoctorCommand.Status.ERROR) return picocli.CommandLine.ExitCode.SOFTWARE;
        }
        return picocli.CommandLine.ExitCode.OK;
    }
}
