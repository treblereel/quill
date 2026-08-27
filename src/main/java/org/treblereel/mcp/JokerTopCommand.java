package org.treblereel.mcp;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import org.treblereel.mcp.command.InitCommand;
import org.treblereel.mcp.command.StatusCommand;
import org.treblereel.mcp.command.UpdateCommand;
import picocli.CommandLine.Command;

@TopCommand
@Command(name = "joker", mixinStandardHelpOptions = true,
        subcommands = {InitCommand.class, UpdateCommand.class, StatusCommand.class})
public class JokerTopCommand {
}
