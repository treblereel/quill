package org.treblereel.mcp.command;

import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", description = "Show index state and diagnostics")
public class StatusCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        System.out.println("joker status: not yet implemented");
    }
}
