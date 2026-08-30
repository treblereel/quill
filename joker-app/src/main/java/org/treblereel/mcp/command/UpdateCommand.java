package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.JokerDatabase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update", description = "Incrementally update the CDI index")
public class UpdateCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--force", description = "Force full re-index even if no changes detected")
    boolean force;

    @Override
    public void run() {
        Path root = (projectPath != null) ? projectPath : Path.of(System.getProperty("user.dir"));
        root = ProjectRootFinder.find(root);
        Path dbPath = root.resolve(".joker/index.db");

        if (!Files.exists(dbPath)) {
            System.out.println("No existing index found. Running full init...");
            runInit();
            return;
        }

        if (!force && !hasClassChanges(root, dbPath)) {
            System.out.println("Index is up to date — no .class files changed since last indexing.");
            return;
        }

        if (force) {
            System.out.println("Forced full re-index...");
        } else {
            System.out.println("Changes detected. Re-indexing...");
        }
        runInit();
    }

    private boolean hasClassChanges(Path root, Path dbPath) {
        try (Connection conn = JokerDatabase.open(dbPath)) {
            Map<String, String> meta = IndexReader.getMetadata(conn);
            String indexedAtStr = meta.get("indexed_at");
            if (indexedAtStr == null || "unknown".equals(indexedAtStr)) return true;

            Instant indexedAt = Instant.parse(indexedAtStr);
            FileTime threshold = FileTime.from(indexedAt);

            try (Stream<Path> walk = Files.walk(root)) {
                return walk
                        .filter(p -> p.toString().endsWith(".class"))
                        .filter(p -> p.toString().contains("target" + root.getFileSystem().getSeparator() + "classes"))
                        .anyMatch(p -> {
                            try {
                                return Files.getLastModifiedTime(p).compareTo(threshold) > 0;
                            } catch (IOException e) {
                                return true;
                            }
                        });
            }
        } catch (Exception e) {
            return true;
        }
    }

    private void runInit() {
        InitCommand init = new InitCommand();
        init.projectPath = projectPath;
        init.noHooks = true;
        init.run();
    }
}
