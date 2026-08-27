package org.treblereel.mcp.command;

import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update", description = "Incrementally update the CDI index")
public class UpdateCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        System.out.println("joker update: not yet implemented");
    }
}
