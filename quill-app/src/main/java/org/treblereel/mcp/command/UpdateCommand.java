package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "update", mixinStandardHelpOptions = true,
        description = "Incrementally update the project index")
public class UpdateCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--force", description = "Force full re-index even if no changes detected")
    boolean force;

    @Override
    public Integer call() {
        Path root = ProjectRootFinder.find(projectPath);

        Path lockedRoot = root;
        try {
            ProjectIndexLock.withLock(lockedRoot, () -> {
                updateLocked(lockedRoot, System.out::println, false);
                return null;
            });
            return picocli.CommandLine.ExitCode.OK;
        } catch (IOException e) {
            System.err.println("[quill] Could not lock index for " + root + ": " + e.getMessage());
            return picocli.CommandLine.ExitCode.SOFTWARE;
        }
    }

    public static void updateAfterSuccessfulBuild(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        try {
            ProjectIndexLock.withLock(normalized, () -> {
                new UpdateCommand().updateLocked(normalized,
                        message -> System.err.println("[quill] " + message), true);
                return null;
            });
        } catch (IOException e) {
            throw new IllegalStateException("Could not lock index for " + normalized, e);
        }
    }

    private void updateLocked(
            Path root, Consumer<String> output, boolean externallyCompiled) {
        Path exactDb = ProjectIndexStore.findExactDbForHead(root);
        Path existingDb = exactDb != null
                ? exactDb : ProjectIndexStore.findBestAvailableDb(root);
        if (existingDb == null) {
            output.accept("No existing index found. Running full init...");
            runInit(root, null, externallyCompiled);
            return;
        }

        if (exactDb == null) {
            output.accept(
                    "No index for the current commit. Re-indexing from the latest generation...");
            runInit(root, existingDb, externallyCompiled);
            return;
        }

        boolean refreshCompiledSnapshot = externallyCompiled
                && requiresPostCompileRefresh(root, existingDb);
        if (!force && !refreshCompiledSnapshot && !hasProjectChanges(root, existingDb)) {
            output.accept("Index is up to date — project fingerprint has not changed.");
            return;
        }

        if (force) {
            output.accept("Forced full re-index...");
        } else if (refreshCompiledSnapshot) {
            output.accept("Build completed. Refreshing the stale compiled snapshot...");
        } else {
            output.accept("Changes detected. Re-indexing...");
        }
        runInit(root, existingDb, externallyCompiled);
    }

    static boolean requiresPostCompileRefresh(Path root, Path dbPath) {
        try {
            Map<String, String> meta = IndexReader.getMetadata(QuillDatabase.open(dbPath));
            boolean compiledSnapshot = Boolean.parseBoolean(
                    meta.getOrDefault("compiled_before_index", "false"));
            return !compiledSnapshot && WorktreeInspector.inspect(root).structuralDirty();
        } catch (Exception e) {
            // A missing or unreadable freshness marker must not suppress a normal change check.
            return false;
        }
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
            String indexedWorktree = meta.get("indexed_structure_fingerprint");
            WorktreeInspector.Snapshot worktree = WorktreeInspector.inspect(root);
            boolean worktreeChanged = indexedWorktree != null
                    ? !indexedWorktree.equals(worktree.structuralFingerprint())
                    : worktree.structuralDirty();
            return !indexedFingerprint.equals(currentFingerprint)
                    || worktreeChanged;
        } catch (Exception e) {
            return true;
        }
    }

    private void runInit(Path root, Path incrementalBase, boolean externallyCompiled) {
        ProjectInitializer.InitializationResult result =
                ProjectInitializer.initializeLockedDetailed(
                        root, true, externallyCompiled, incrementalBase);
        if (!result.successful()) {
            throw new IllegalStateException(result.diagnostic()
                    + " (after " + result.elapsedMillis() + " ms)");
        }
    }
}
