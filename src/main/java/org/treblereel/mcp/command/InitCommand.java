package org.treblereel.mcp.command;

import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", description = "Index a Quarkus project's CDI dependency graph")
public class InitCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        System.out.println("joker init: not yet implemented");
    }
}
