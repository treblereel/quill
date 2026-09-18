package org.treblereel.mcp.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

/**
 * Owns immutable SQLite index generations and the refs that publish them.
 *
 * <p>Keeping index publication separate from project discovery and indexing makes the atomic
 * visibility rules explicit: readers only open generations reachable through {@code refs.json}.
 */
public final class ProjectIndexStore {

    private static final int MAX_INDEXES = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentMap<Path, ValidatedDatabase> VALID_DATABASES =
            new ConcurrentHashMap<>();

    private ProjectIndexStore() {}

    static String createIndexId(String commitHash) {
        String snapshot = commitHash != null && !"unknown".equals(commitHash)
                ? commitHash : "nocommit";
        return snapshot + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static Path resolveDbPath(Path root, String indexId) {
        return root.resolve(".quill/" + indexId + ".db");
    }

    static Path sourceTokenCache(Path root) {
        return root.resolve(".quill/source-tokens.cache");
    }

    static Path applicationIndexCache(Path root) {
        return root.resolve(".quill/application-jandex.cache");
    }

    static void updateRefs(Path root, String commitHash, String indexId) {
        Path refsPath = root.resolve(".quill/refs.json");
        Map<String, String> refs = readRefs(refsPath);
        if (commitHash != null && !"unknown".equals(commitHash)) {
            refs.put(headRef(commitHash), indexId);
            String branch = GitAnalyzer.resolveCurrentBranch(root);
            if (branch != null) {
                refs.put(branch, indexId);
            }
        } else {
            refs.put("@worktree", indexId);
        }
        // Publish the new pointer before pruning old generations. If cleanup cannot delete an
        // open file on Windows, the only consequence is a temporary extra cache entry.
        writeRefsOrThrow(refsPath, refs);
        clearRecovery(root);
        cleanupLru(root, refs);
    }

    /** Rejects an incomplete or corrupt staging database before it can become visible to readers. */
    static void validateForPublication(Path database) {
        VALID_DATABASES.remove(database.toAbsolutePath().normalize());
        if (validatedDatabase(database).isEmpty()) {
            throw new IllegalStateException("SQLite integrity validation failed for " + database);
        }
    }

    static void cleanupLru(Path root, Map<String, String> refs) {
        Path quillDir = root.resolve(".quill");
        List<Path> databases;
        try (Stream<Path> files = Files.list(quillDir)) {
            databases = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path path) -> {
                        try {
                            return Files.getLastModifiedTime(path);
                        } catch (IOException ex) {
                            return java.nio.file.attribute.FileTime.fromMillis(0);
                        }
                    }).reversed())
                    .toList();
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not inspect index cache: " + e.getMessage());
            return;
        }

        Set<String> keptHashes = new HashSet<>();
        for (int i = 0; i < databases.size(); i++) {
            Path database = databases.get(i);
            String fileName = database.getFileName().toString();
            String hash = fileName.substring(0, fileName.length() - ".db".length());
            if (i < MAX_INDEXES) {
                keptHashes.add(hash);
            } else {
                deleteDatabaseArtifacts(database);
            }
        }

        refs.entrySet().removeIf(entry -> !keptHashes.contains(entry.getValue())
                || referencedDatabase(root, entry.getValue(), null) == null);
        writeRefs(root.resolve(".quill/refs.json"), refs);
    }

    public static Map<String, String> readRefs(Path refsPath) {
        return readRefsState(refsPath).refs();
    }

    private static RefsState readRefsState(Path refsPath) {
        if (!Files.exists(refsPath)) return new RefsState(new LinkedHashMap<>(), true);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Map<String, String> refs = MAPPER.readValue(refsPath.toFile(),
                        new TypeReference<LinkedHashMap<String, String>>() {});
                return new RefsState(refs, true);
            } catch (IOException e) {
                if (attempt == 2) return new RefsState(new LinkedHashMap<>(), false);
                pauseForFileRelease();
            }
        }
        return new RefsState(new LinkedHashMap<>(), false);
    }

    private static void writeRefs(Path refsPath, Map<String, String> refs) {
        try {
            writeRefsOrThrow(refsPath, refs);
        } catch (RuntimeException e) {
            System.err.println("[quill] Warning: could not write refs.json: " + rootMessage(e));
        }
    }

    private static void writeRefsOrThrow(Path refsPath, Map<String, String> refs) {
        Path temp = refsPath.resolveSibling("." + refsPath.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(refsPath.toAbsolutePath().normalize().getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), refs);
            atomicMoveWithRetry(temp, refsPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not write " + refsPath, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static void atomicMoveWithRetry(Path source, Path target) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                atomicMove(source, target);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < 4) pauseForFileRelease();
            }
        }
        throw lastFailure;
    }

    private static void pauseForFileRelease() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void deleteDatabaseArtifacts(Path dbPath) {
        VALID_DATABASES.remove(dbPath.toAbsolutePath().normalize());
        for (Path path : List.of(dbPath, Path.of(dbPath + "-wal"), Path.of(dbPath + "-shm"),
                Path.of(dbPath + "-journal"))) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    public static Path findDbForHead(Path root) {
        return findExactDbForHead(root);
    }

    public static Path findExactDbForHead(Path root) {
        String head = GitAnalyzer.resolveHead(root);
        Map<String, String> refs = refsForRead(root);
        if (head != null) {
            Path active = referencedDatabase(root, refs.get(headRef(head)), head);
            if (active != null) return active;

            String branch = GitAnalyzer.resolveCurrentBranch(root);
            Path branchDb = branch != null
                    ? referencedDatabase(root, refs.get(branch), head) : null;
            if (branchDb != null) return branchDb;
        } else {
            Path active = referencedDatabase(root, refs.get("@worktree"), null);
            if (active != null) return active;
        }

        return null;
    }

    /** Finds the best atomically published immutable generation for read-only queries. */
    public static Path findBestAvailableDb(Path root) {
        Path exact = findExactDbForHead(root);
        if (exact != null) return exact;

        Map<String, String> refs = refsForRead(root);
        String branch = GitAnalyzer.resolveCurrentBranch(root);
        if (branch != null) {
            Path branchDb = referencedDatabase(root, refs.get(branch), null);
            if (branchDb != null) return branchDb;
        }

        return refs.values().stream()
                .distinct()
                .map(indexId -> referencedDatabase(root, indexId, null))
                .filter(Objects::nonNull)
                .max(Comparator.comparing(path -> {
                    try {
                        return Files.getLastModifiedTime(path);
                    } catch (IOException ignored) {
                        return java.nio.file.attribute.FileTime.fromMillis(0);
                    }
                }))
                .orElse(null);
    }

    private static String headRef(String commitHash) {
        return "@head:" + commitHash;
    }

    private static Path referencedDatabase(Path root, String indexId, String expectedCommit) {
        if (indexId == null || indexId.isBlank()
                || indexId.contains("/") || indexId.contains("\\")) {
            return null;
        }
        Path database = root.resolve(".quill").resolve(indexId + ".db").normalize();
        Path quillDir = root.resolve(".quill").toAbsolutePath().normalize();
        database = database.toAbsolutePath().normalize();
        if (!database.startsWith(quillDir) || !Files.isRegularFile(database)) return null;
        ValidatedDatabase validated = validatedDatabase(database).orElse(null);
        if (validated == null || !indexId.equals(validated.indexId())) return null;
        return expectedCommit == null || expectedCommit.equals(validated.lastCommit())
                ? database : null;
    }

    private static Map<String, String> refsForRead(Path root) {
        Path refsPath = root.resolve(".quill/refs.json");
        RefsState state = readRefsState(refsPath);
        boolean invalidTarget = state.refs().values().stream().distinct()
                .anyMatch(indexId -> referencedDatabase(root, indexId, null) == null);
        boolean orphanedDatabases = state.refs().isEmpty() && hasDatabases(root);
        if (state.readable() && !invalidTarget && !orphanedDatabases) return state.refs();
        String reason = !state.readable() ? "refs_corrupt"
                : invalidTarget ? "referenced_generation_invalid" : "refs_missing";
        return recoverRefs(root, state.refs(), reason);
    }

    private static synchronized Map<String, String> recoverRefs(
            Path root, Map<String, String> existingRefs, String reason) {
        // Another reader may have repaired the file while this reader was waiting for the lock.
        RefsState current = readRefsState(root.resolve(".quill/refs.json"));
        if (current.readable() && !current.refs().isEmpty()
                && current.refs().values().stream().distinct()
                        .allMatch(indexId -> referencedDatabase(root, indexId, null) != null)) {
            return current.refs();
        }

        List<DatabaseCandidate> candidates = databaseCandidates(root);
        Map<String, String> recovered = new LinkedHashMap<>();
        existingRefs.forEach((ref, indexId) -> {
            if (referencedDatabase(root, indexId, null) != null) recovered.put(ref, indexId);
        });
        for (DatabaseCandidate candidate : candidates) {
            if (candidate.lastCommit() == null || "unknown".equals(candidate.lastCommit())) {
                recovered.putIfAbsent("@worktree", candidate.indexId());
            } else {
                recovered.putIfAbsent(headRef(candidate.lastCommit()), candidate.indexId());
            }
        }

        String head = GitAnalyzer.resolveHead(root);
        String branch = GitAnalyzer.resolveCurrentBranch(root);
        if (head != null && branch != null) {
            String exact = recovered.get(headRef(head));
            if (exact != null) recovered.put(branch, exact);
        }
        if (!recovered.isEmpty()) {
            writeRefs(root.resolve(".quill/refs.json"), recovered);
            String selected = head != null ? recovered.get(headRef(head)) : recovered.get("@worktree");
            if (selected == null) selected = candidates.get(0).indexId();
            writeRecovery(root, new RecoveryStatus(reason, selected, Instant.now().toString()));
            System.err.println("[quill] Recovered index refs; using generation " + selected
                    + " (" + reason + ").");
        }
        return recovered;
    }

    private static List<DatabaseCandidate> databaseCandidates(Path root) {
        Path quillDir = root.resolve(".quill");
        if (!Files.isDirectory(quillDir)) return List.of();
        List<Path> paths;
        try (Stream<Path> files = Files.list(quillDir)) {
            paths = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(ProjectIndexStore::lastModified).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
        List<DatabaseCandidate> result = new ArrayList<>();
        for (Path path : paths) {
            validatedDatabase(path).ifPresent(validated -> {
                String name = path.getFileName().toString();
                String indexId = name.substring(0, name.length() - 3);
                if (indexId.equals(validated.indexId())) {
                    result.add(new DatabaseCandidate(indexId, validated.lastCommit()));
                }
            });
        }
        return result;
    }

    private static boolean hasDatabases(Path root) {
        Path dir = root.resolve(".quill");
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".db")
                    && !path.getFileName().toString().startsWith("."));
        } catch (IOException e) {
            return false;
        }
    }

    private static Optional<ValidatedDatabase> validatedDatabase(Path database) {
        Path normalized = database.toAbsolutePath().normalize();
        try {
            FileIdentity identity = new FileIdentity(
                    Files.size(normalized), Files.getLastModifiedTime(normalized).toMillis());
            ValidatedDatabase cached = VALID_DATABASES.get(normalized);
            if (cached != null && cached.identity().equals(identity)) return Optional.of(cached);

            Jdbi jdbi = QuillDatabase.open(normalized);
            boolean healthy = jdbi.withHandle(handle -> handle.createQuery("PRAGMA quick_check")
                    .mapTo(String.class).list().stream().allMatch("ok"::equalsIgnoreCase));
            if (!healthy) return Optional.empty();
            Map<String, String> metadata = IndexReader.getMetadata(jdbi);
            String indexId = metadata.get("index_id");
            if (indexId == null || indexId.isBlank()) return Optional.empty();
            ValidatedDatabase validated = new ValidatedDatabase(identity, indexId,
                    metadata.getOrDefault("last_commit", "unknown"));
            VALID_DATABASES.put(normalized, validated);
            return Optional.of(validated);
        } catch (RuntimeException | IOException e) {
            VALID_DATABASES.remove(normalized);
            return Optional.empty();
        }
    }

    public static Optional<RecoveryStatus> readRecovery(Path root) {
        Path recovery = root.resolve(".quill/recovery.json");
        if (!Files.isRegularFile(recovery)) return Optional.empty();
        try {
            Map<String, String> values = MAPPER.readValue(recovery.toFile(),
                    new TypeReference<LinkedHashMap<String, String>>() {});
            return Optional.of(new RecoveryStatus(values.get("reason"),
                    values.get("selected_index_id"), values.get("recovered_at")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void writeRecovery(Path root, RecoveryStatus recovery) {
        Path target = root.resolve(".quill/recovery.json");
        Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("reason", recovery.reason());
            values.put("selected_index_id", recovery.selectedIndexId());
            values.put("recovered_at", recovery.recoveredAt());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), values);
            atomicMoveWithRetry(temp, target);
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not record index recovery: " + e.getMessage());
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private static void clearRecovery(Path root) {
        try { Files.deleteIfExists(root.resolve(".quill/recovery.json")); }
        catch (IOException ignored) { }
    }

    private static java.nio.file.attribute.FileTime lastModified(Path path) {
        try { return Files.getLastModifiedTime(path); }
        catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }

    public record RecoveryStatus(String reason, String selectedIndexId, String recoveredAt) {}
    private record RefsState(Map<String, String> refs, boolean readable) {}
    private record FileIdentity(long size, long modifiedAtMillis) {}
    private record ValidatedDatabase(FileIdentity identity, String indexId, String lastCommit) {}
    private record DatabaseCandidate(String indexId, String lastCommit) {}

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
