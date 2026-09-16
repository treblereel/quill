package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.MetaEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", description = "Show index state and diagnostics")
public class StatusCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Override
    public void run() {
        Path root = ProjectRootFinder.find(projectPath);
        Path dbPath = ProjectIndexStore.findBestAvailableDb(root);

        if (dbPath == null) {
            System.err.println("No index found. Run: quill init --project " + root);
            System.exit(1);
            return;
        }

        try {
            Jdbi jdbi = QuillDatabase.open(dbPath);
            Map<String, String> meta = IndexReader.getMetadata(jdbi);
            var classes = IndexReader.findAllClasses(jdbi);
            var beans = IndexReader.findBeans(jdbi, null);

            int totalTokens = classes.stream().mapToInt(c -> c.sourceTokens()).sum();
            MetaEnvelope freshness = MetaEnvelope.from(jdbi, 0, 0);

            System.out.println("Index: " + dbPath);
            System.out.println("  Index generation:    " + freshness.indexId());
            System.out.println("  Indexed at:          " + meta.getOrDefault("indexed_at", "unknown"));
            System.out.println("  Indexed commit:      " + freshness.lastCommit());
            System.out.println("  Current commit:      "
                    + (freshness.currentCommit() != null ? freshness.currentCommit() : "unknown"));
            System.out.println("  Worktree:            " + (freshness.worktreeDirty()
                    ? "dirty (" + freshness.worktreeChangedFiles() + " changed files)" : "clean"));
            System.out.println("  Structural changes:  " + freshness.structuralChangedFiles());
            System.out.println("  Structure snapshot:  "
                    + (freshness.structureStale() ? "stale" : "current"));
            if (!freshness.staleReasons().isEmpty()) {
                System.out.println("  Stale reasons:       "
                        + String.join(", ", freshness.staleReasons()));
            }
            System.out.println("  Classes:             " + classes.size());
            System.out.println("  Beans:               " + beans.size());
            System.out.println("  Total source tokens: " + String.format("%,d", totalTokens));
            if (!classes.isEmpty()) {
                System.out.println("  Avg tokens/class:    " + String.format("%,d", totalTokens / classes.size()));
            }
        } catch (IllegalStateException | QuillDatabase.SchemaVersionException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            throw new RuntimeException("Failed to read index from " + dbPath, e);
        }
    }
}
