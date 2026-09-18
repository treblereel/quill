package org.treblereel.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.treblereel.mcp.command.CleanCommand;
import org.treblereel.mcp.command.DoctorCommand;
import org.treblereel.mcp.command.InitCommand;
import org.treblereel.mcp.command.StatusCommand;
import org.treblereel.mcp.command.UpdateCommand;
import org.treblereel.mcp.command.WorkspaceCommand;
import org.treblereel.mcp.mcp.ProjectRegistry;
import org.treblereel.mcp.mcp.WorkspaceProjectScope;
import org.treblereel.mcp.mcp.McpStdioServer;
import org.treblereel.mcp.mcp.McpToolProfile;
import org.treblereel.mcp.workspace.WorkspaceLock;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;

@Command(name = "quill", mixinStandardHelpOptions = true,
        versionProvider = QuillTopCommand.VersionProvider.class,
        subcommands = {InitCommand.class, UpdateCommand.class, StatusCommand.class,
                DoctorCommand.class, CleanCommand.class, WorkspaceCommand.class})
public class QuillTopCommand implements Callable<Integer> {

    @Option(names = "--mcp", description = "Start an MCP server (stdio transport)")
    boolean mcp;

    @Option(names = "--project", description = "Project path(s) to serve via MCP (repeatable)")
    List<Path> projects;

    @Option(names = "--workspace", description = "Initialized workspace root to serve via MCP")
    Path workspace;

    @Option(names = "--tools", description = "MCP tool profiles: full, router, core, code, di, git; "
            + "comma-separated unions are allowed (default: full)")
    String toolProfiles = "full";

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new QuillTopCommand());
        commandLine.setExecutionExceptionHandler((error, command, parseResult) -> {
            command.getErr().println("[quill] " + rootMessage(error));
            return CommandLine.ExitCode.SOFTWARE;
        });
        int exitCode = commandLine.execute(args);
        if (exitCode != 0) System.exit(exitCode);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }

    public static final class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"quill " + version()};
        }

        private static String versionFromResource() {
            try (InputStream in = QuillTopCommand.class.getResourceAsStream("/quill.properties")) {
                if (in == null) return null;
                Properties properties = new Properties();
                properties.load(in);
                return properties.getProperty("version");
            } catch (IOException ignored) {
                return null;
            }
        }
    }

    public static String version() {
        String version = QuillTopCommand.class.getPackage().getImplementationVersion();
        if (version == null) version = VersionProvider.versionFromResource();
        return version == null ? "dev" : version;
    }

    @Override
    public Integer call() throws Exception {
        if (mcp) {
            ProjectRegistry registry;
            WorkspaceLock workspaceLock = null;
            if (workspace != null) {
                if (projects != null && !projects.isEmpty()) {
                    throw new IllegalArgumentException(
                            "--workspace cannot be combined with --project");
                }
                WorkspaceProjectScope workspaceScope = new WorkspaceProjectScope(workspace);
                workspaceLock = WorkspaceLock.tryAcquireShared(workspaceScope.root());
                if (workspaceLock == null) {
                    throw new IllegalStateException("Workspace is being cleared: "
                            + workspaceScope.root());
                }
                registry = new ProjectRegistry(workspaceScope);
            } else {
                registry = new ProjectRegistry();
                if (projects != null && !projects.isEmpty()) {
                    for (Path p : projects) registry.register(p);
                } else {
                    registry.register(null);
                }
            }
            try (WorkspaceLock lock = workspaceLock) {
                McpStdioServer.start(registry, System.in, System.out,
                        McpToolProfile.parse(toolProfiles));
            }
            return CommandLine.ExitCode.OK;
        }
        System.err.println("Use a subcommand (init, update, status, doctor, clean)"
                + " or --mcp to start the MCP server.");
        System.err.println("Run 'quill --help' for more information.");
        return CommandLine.ExitCode.USAGE;
    }
}
