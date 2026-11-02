package org.treblereel.mcp.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.ClassFileSnapshot;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.db.IndexRepository;
import org.treblereel.mcp.db.QuillDatabase;

/** Updates index generations independently of CLI parsing. */
public final class ProjectUpdater {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ProjectUpdater() {}

    /** Called only by background preparation after a Git HEAD change. */
    public static boolean updateAfterCommit(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        try {
            return ProjectIndexLock.withLock(normalized, () -> {
                Path base = ProjectIndexStore.findBestAvailableDb(normalized);
                if (base == null) return false;
                Map<String, String> metadata = metadata(base);
                String head = GitAnalyzer.resolveHead(normalized);
                if (head == null || head.equals(metadata.get("last_commit"))) return false;
                var discovery = ProjectLayout.discoverClassesDirs(normalized, false);
                String classes = ClassFileSnapshot.fingerprint(discovery.classesDirectories());
                var sources = SourceFileHashes.captureInputsCached(normalized,
                        ProjectLayout.findIndexInputRoots(normalized, discovery.outputs()));
                if (!compiledInputsMatch(normalized, base, discovery.classesDirectories(), classes, sources)) return false;
                System.err.println("[quill] Refreshing commit " + head + " from unchanged compiled inputs; no build is started.");
                var result = ProjectInitializer.initializeLockedDetailed(normalized, true,
                        Boolean.parseBoolean(metadata.get("compiled_before_index")), base, true);
                if (!result.successful()) throw new IllegalStateException(result.diagnostic());
                return true;
            });
        } catch (IOException failure) { throw new IllegalStateException("Could not lock commit refresh for " + normalized, failure); }
    }

    private static Map<String, String> metadata(Path database) {
        return QuillDatabase.open(database).withHandle(handle -> new IndexRepository(handle).getMetadata());
    }

    static boolean compiledInputsMatch(Path root, Path database, List<Path> directories,
            String classes, Map<String, String> sources) {
        Map<String, String> metadata = metadata(database);
        if (!Boolean.parseBoolean(metadata.get("compiled_before_index"))
                && !"0".equals(metadata.get("indexed_structural_changed_files"))) return false;
        try {
            String key = metadata.containsKey("source_input_hashes") ? "source_input_hashes" : "java_source_hashes";
            if (key.equals("java_source_hashes") && sources.keySet().stream().anyMatch(path -> !path.endsWith(".java"))) return false;
            return JSON.valueToTree(sources).equals(JSON.readTree(metadata.getOrDefault(key, "null")))
                    && metadata.getOrDefault("state_fingerprint", "").equals(ProjectLayout.computeStateFingerprint(
                            root, directories, classes, "unknown".equals(metadata.get("last_commit")) ? null : metadata.get("last_commit")));
        } catch (IOException invalid) { return false; }
    }

    static boolean hasCompiledOutputChanges(Path root, Map<String, String> metadata, List<Path> directories) {
        if (directories.isEmpty() || !metadata.containsKey("state_fingerprint") || !metadata.containsKey("last_commit")) return true;
        try {
            return !metadata.get("state_fingerprint").equals(ProjectLayout.computeStateFingerprint(root, directories,
                    ClassFileSnapshot.fingerprint(directories), "unknown".equals(metadata.get("last_commit")) ? null : metadata.get("last_commit")));
        } catch (RuntimeException outputsInFlux) {
            // A build deleting or rewriting classes during the walk; the next observation settles it.
            return true;
        }
    }

    public static void updateAfterSuccessfulBuild(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        try {
            update(normalized, false, message -> System.err.println("[quill] " + message), true);
        } catch (IOException e) {
            throw new IllegalStateException("Could not lock index for " + normalized, e);
        }
    }

    public static void update(Path root, boolean force, Consumer<String> output) throws IOException {
        update(root, force, output, false);
    }

    private static void update(Path root, boolean force, Consumer<String> output, boolean externallyCompiled) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        ProjectIndexLock.withLock(normalized, () -> {
            updateLocked(normalized, output, externallyCompiled, force);
            return null;
        });
    }

    private static void updateLocked(
            Path root, Consumer<String> output, boolean externallyCompiled, boolean force) {
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

    public static boolean requiresPostCompileRefresh(Path root, Path dbPath) {
        try {
            Map<String, String> meta = QuillDatabase.open(dbPath).withHandle(repositoryHandle1 -> new IndexRepository(repositoryHandle1).getMetadata());
            boolean compiledSnapshot = Boolean.parseBoolean(
                    meta.getOrDefault("compiled_before_index", "false"));
            return !compiledSnapshot && WorktreeInspector.inspect(root).structuralDirty();
        } catch (Exception e) {
            // A missing or unreadable freshness marker must not suppress a normal change check.
            return false;
        }
    }

    public static boolean hasProjectChanges(Path root, Path dbPath) {
        try {
            var jdbi = QuillDatabase.open(dbPath);
            Map<String, String> meta = jdbi.withHandle(repositoryHandle2 -> new IndexRepository(repositoryHandle2).getMetadata());
            List<Path> classesDirs = ProjectInitializer.findClassesDirs(root);
            return hasProjectChanges(root, meta, classesDirs);
        } catch (Exception e) {
            return true;
        }
    }

    static boolean hasProjectChanges(Path root, Map<String, String> metadata, List<Path> classesDirs) {
        try {
            String indexedFingerprint = metadata.get("state_fingerprint");
            if (indexedFingerprint == null || indexedFingerprint.isBlank() || classesDirs.isEmpty()) return true;
            String currentFingerprint = ProjectLayout.computeStateFingerprint(root, classesDirs);
            String indexedWorktree = metadata.get("indexed_structure_fingerprint");
            WorktreeInspector.Snapshot worktree = WorktreeInspector.inspect(root);
            String indexedInputs = metadata.get("indexed_input_fingerprint");
            boolean worktreeChanged = indexedInputs != null
                    ? !indexedInputs.equals(worktree.indexInputFingerprint())
                    : indexedWorktree != null ? !indexedWorktree.equals(worktree.structuralFingerprint()) : worktree.structuralDirty();
            return !indexedFingerprint.equals(currentFingerprint) || worktreeChanged;
        } catch (Exception unavailable) {
            return true;
        }
    }

    private static void runInit(Path root, Path incrementalBase, boolean externallyCompiled) {
        ProjectInitializer.InitializationResult result =
                ProjectInitializer.initializeLockedDetailed(
                        root, true, externallyCompiled, incrementalBase);
        if (!result.successful()) {
            throw new IllegalStateException(result.diagnostic()
                    + " (after " + result.elapsedMillis() + " ms)");
        }
    }
}
