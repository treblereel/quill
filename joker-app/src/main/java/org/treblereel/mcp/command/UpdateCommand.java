package org.treblereel.mcp.command;

import java.nio.file.Files;
import java.nio.file.Path;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update", description = "Incrementally update the CDI index")
public class UpdateCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        // For MVP, update = full re-index (BeanProcessor doesn't support partial resolution)
        // Future: compare .class timestamps and only re-scan changed files via Jandex
        Path root = (projectPath != null) ? projectPath : Path.of(System.getProperty("user.dir"));
        Path dbPath = root.resolve(".joker/index.db");

        if (!Files.exists(dbPath)) {
            System.out.println("No existing index found. Running full init...");
        } else {
            System.out.println("Updating index (full re-index)...");
        }

        InitCommand init = new InitCommand();
        init.projectPath = projectPath;
        init.run();
    }
}
