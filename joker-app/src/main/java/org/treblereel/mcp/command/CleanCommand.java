package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.treblereel.mcp.core.GitHookInstaller;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "clean", description = "Remove joker index and git hooks")
public class CleanCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        Path root = (projectPath != null) ? projectPath : Path.of(System.getProperty("user.dir"));
        root = ProjectRootFinder.find(root);
        Path jokerDir = root.resolve(".joker");

        if (Files.isDirectory(jokerDir)) {
            try (Stream<Path> walk = Files.walk(jokerDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        System.err.println("Warning: could not delete " + p + ": " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                System.err.println("Failed to walk .joker directory: " + e.getMessage());
                return;
            }
            System.out.println("Removed " + jokerDir);
        } else {
            System.out.println("No .joker directory found.");
        }

        GitHookInstaller.uninstall(root);
        System.out.println("Git hooks cleaned.");
    }
}
