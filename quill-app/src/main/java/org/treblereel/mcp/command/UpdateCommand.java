package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update", description = "Incrementally update the project index")
public class UpdateCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--force", description = "Force full re-index even if no changes detected")
    boolean force;

    @Option(names = "--compile", description = "Compile the project before checking and rebuilding the index")
    boolean compile;

    @Override
    public void run() {
        Path root = (projectPath != null) ? projectPath : Path.of(System.getProperty("user.dir"));
        root = ProjectRootFinder.find(root);

        Path lockedRoot = root;
        try {
            ProjectIndexLock.withLock(lockedRoot, () -> {
                updateLocked(lockedRoot);
                return null;
            });
        } catch (IOException e) {
            throw new RuntimeException("Could not lock index for " + root, e);
        }
    }

    private void updateLocked(Path root) {
        if (compile && !ProjectInitializer.compileProject(root)) {
            throw new IllegalStateException(
                    "Compilation failed; the existing index was left unchanged for " + root);
        }

        Path existingDb = ProjectInitializer.findDbForHead(root);
        if (existingDb == null) {
            System.out.println("No existing index found. Running full init...");
            runInit(root);
            return;
        }

        if (!force && !hasProjectChanges(root, existingDb)) {
            System.out.println("Index is up to date — project fingerprint has not changed.");
            return;
        }

        if (force) {
            System.out.println("Forced full re-index...");
        } else {
            System.out.println("Changes detected. Re-indexing...");
        }
        runInit(root);
    }

    static boolean hasProjectChanges(Path root, Path dbPath) {
        try {
            var jdbi = QuillDatabase.open(dbPath);
            Map<String, String> meta = IndexReader.getMetadata(jdbi);
            String indexedFingerprint = meta.get("state_fingerprint");
            if (indexedFingerprint == null || indexedFingerprint.isBlank()) return true;
            List<Path> classesDirs = ProjectInitializer.findClassesDirs(root);
            if (classesDirs.isEmpty()) return true;
            String currentFingerprint = ProjectInitializer.computeStateFingerprint(root, classesDirs);
            return !indexedFingerprint.equals(currentFingerprint);
        } catch (Exception e) {
            return true;
        }
    }

    private void runInit(Path root) {
        if (!ProjectInitializer.initializeLocked(root, true)) {
            throw new IllegalStateException("Initialization failed for " + root);
        }
    }
}
