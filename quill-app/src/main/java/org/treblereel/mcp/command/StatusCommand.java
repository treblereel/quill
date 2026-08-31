package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", description = "Show index state and diagnostics")
public class StatusCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        Path root = ProjectRootFinder.find(projectPath);
        Path dbPath = root.resolve(".quill/index.db");

        try (Connection conn = QuillDatabase.open(dbPath)) {
            Map<String, String> meta = IndexReader.getMetadata(conn);
            var classes = IndexReader.findAllClasses(conn);
            var beans = IndexReader.findBeans(conn, null);

            int totalTokens = classes.stream().mapToInt(c -> c.sourceTokens()).sum();

            System.out.println("Index: " + dbPath);
            System.out.println("  Indexed at:          " + meta.getOrDefault("indexed_at", "unknown"));
            System.out.println("  Last commit:         " + meta.getOrDefault("last_commit", "unknown"));
            System.out.println("  Classes:             " + classes.size());
            System.out.println("  Beans:               " + beans.size());
            System.out.println("  Total source tokens: " + String.format("%,d", totalTokens));
            if (!classes.isEmpty()) {
                System.out.println("  Avg tokens/class:    " + String.format("%,d", totalTokens / classes.size()));
            }
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read index from " + dbPath, e);
        }
    }
}
