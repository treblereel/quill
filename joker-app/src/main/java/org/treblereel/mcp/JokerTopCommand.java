package org.treblereel.mcp;

import io.quarkiverse.mcp.server.cli.adapter.runtime.McpAdapter;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import org.treblereel.mcp.command.CleanCommand;
import org.treblereel.mcp.command.InitCommand;
import org.treblereel.mcp.command.StatusCommand;
import org.treblereel.mcp.command.UpdateCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@TopCommand
@Command(name = "joker", mixinStandardHelpOptions = true,
        subcommands = {InitCommand.class, UpdateCommand.class, StatusCommand.class, CleanCommand.class})
public class JokerTopCommand implements Runnable {

    @Option(names = "--mcp", description = "Start an MCP server (stdio transport)")
    boolean mcp;

    @Override
    public void run() {
        if (mcp) {
            McpAdapter.startMcp();
            return;
        }
        System.err.println("Use a subcommand (init, update, status) or --mcp to start the MCP server.");
        System.err.println("Run 'joker --help' for more information.");
    }
}
