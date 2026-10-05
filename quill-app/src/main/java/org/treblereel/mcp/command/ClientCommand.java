package org.treblereel.mcp.command;

import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "client", mixinStandardHelpOptions = true,
        description = "Manage local AI client integration without indexing or building",
        subcommands = ClientPermissionsCommand.class)
public final class ClientCommand implements Callable<Integer> {
    @Override public Integer call() {
        System.err.println("Use: quill client permissions status|grant|revoke");
        return CommandLine.ExitCode.USAGE;
    }
}
