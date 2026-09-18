package org.treblereel.mcp.command;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "workspace", mixinStandardHelpOptions = true,
        description = "Manage a federated workspace of independently indexed repositories",
        subcommands = {WorkspaceInitCommand.class, WorkspaceStatusCommand.class})
public final class WorkspaceCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("Use a workspace subcommand: init or status.");
        return CommandLine.ExitCode.USAGE;
    }
}
