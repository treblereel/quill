package org.treblereel.mcp.index;

import org.treblereel.mcp.db.IndexSchema;
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
import org.treblereel.mcp.db.IndexRepository;
import org.treblereel.mcp.db.QuillDatabase;

/**
 * Owns immutable SQLite index generations and the refs that publish them.
 *
 * <p>Keeping index publication separate from project discovery and indexing makes the atomic
 * visibility rules explicit: readers only open generations reachable through {@code refs.json}.
 */
public final class ProjectIndexStore {

    private static final int MAX_INDEXES = 5;
    /**
     * Disk budget for retained generations besides the active one; a large project's generations are
     * hundreds of MB each, so the count alone let five of them hold gigabytes. One fallback is always kept.
     */
    static final long DEFAULT_INDEX_CACHE_BYTES = 1L << 30;
    static final java.time.Duration OUTDATED_GENERATION_GRACE = java.time.Duration.ofDays(1);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentMap<Path, Object> VALIDATION_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Path, ValidatedDatabase> VALID_DATABASES =
            new ConcurrentHashMap<>();

    private ProjectIndexStore() {}

    public static String createIndexId(String commitHash) {
        String snapshot = commitHash != null && !"unknown".equals(commitHash)
                ? commitHash : "nocommit";
        return snapshot + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static Path resolveDbPath(Path root, String indexId) {
        return root.resolve(".quill/" + indexId + ".db");
    }

    public static Path sourceTokenCache(Path root) {
        return root.resolve(".quill/source-tokens.cache");
    }

    public static Path applicationIndexCache(Path root) {
        return root.resolve(".quill/application-jandex.cache");
    }

    public static void updateRefs(Path root, String commitHash, String indexId) {
        updateRefs(root, commitHash, indexId, refs -> cleanupLru(root, refs, indexId));
    }

    static void updateRefs(Path root, String commitHash, String indexId,
            java.util.function.Consumer<Map<String,String>> afterCommit) {
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
        // Once refs have switched, cleanup cannot turn activation into a failed publication.
        try {
            clearRecovery(root);
            afterCommit.accept(refs);
        } catch (RuntimeException failure) {
            System.err.println("[quill] Index activated; cleanup deferred: " + rootMessage(failure));
        }
    }

    /** Rejects an incomplete or corrupt staging database before it can become visible to readers. */
    public static void validateForPublication(Path database) {
        VALID_DATABASES.remove(database.toAbsolutePath().normalize());
        if (validatedDatabase(database).isEmpty()) {
            throw new IllegalStateException("SQLite integrity validation failed for " + database);
        }
    }

    public static void cleanupLru(Path root, Map<String, String> refs) {
        String head = GitAnalyzer.resolveHead(root);
        String active = refs.getOrDefault(headRef(head), refs.get("@worktree"));
        cleanupLru(root, refs, active);
    }

    private static void cleanupLru(Path root, Map<String, String> refs, String activeIndex) {
        cleanupLru(root, refs, activeIndex, indexCacheBytes());
    }

    static long indexCacheBytes() {
        String configured = System.getenv("QUILL_INDEX_CACHE_BYTES");
        if (configured == null || configured.isBlank()) return DEFAULT_INDEX_CACHE_BYTES;
        try {
            long value = Long.parseLong(configured.strip());
            return value > 0 ? value : DEFAULT_INDEX_CACHE_BYTES;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INDEX_CACHE_BYTES;
        }
    }

    static void cleanupLru(Path root, Map<String, String> refs, String activeIndex, long budgetBytes) {
        Path quillDir = root.resolve(".quill");
        // Generations with an outdated schema can never be served by this version. They are kept
        // until a replacement is published (this runs after activation) and removed here; their
        // refs are dropped below because the databases no longer exist. An older Quill still
        // serving the project keeps writing fresh ones, and deleting those would only make it
        // rebuild, so only generations untouched for a grace period go.
        java.time.Instant abandoned = java.time.Instant.now().minus(OUTDATED_GENERATION_GRACE);
        for (Path outdated : outdatedGenerations(root)) {
            String fileName = outdated.getFileName().toString();
            if (!fileName.substring(0, fileName.length() - ".db".length()).equals(activeIndex)
                    && lastModified(outdated).toInstant().isBefore(abandoned)) {
                deleteDatabaseArtifacts(outdated);
            }
        }
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
        if (activeIndex != null) keptHashes.add(activeIndex);
        long retainedBytes = 0;
        int fallbacks = 0;
        for (int i = 0; i < databases.size(); i++) {
            Path database = databases.get(i);
            String fileName = database.getFileName().toString();
            String hash = fileName.substring(0, fileName.length() - ".db".length());
            if (hash.equals(activeIndex)) continue;
            long size;
            try { size = Files.size(database); } catch (IOException unavailable) { size = 0; }
            boolean withinBudget = fallbacks == 0 || retainedBytes + size <= budgetBytes;
            if (!keptHashes.contains(hash) && keptHashes.size() < MAX_INDEXES && withinBudget) {
                keptHashes.add(hash);
                retainedBytes += size;
                fallbacks++;
            } else if (!keptHashes.contains(hash)) {
                deleteDatabaseArtifacts(database);
            }
        }

        refs.entrySet().removeIf(entry -> (activeIndex == null || !activeIndex.equals(entry.getValue()))
                && (!keptHashes.contains(entry.getValue())
                    || referencedDatabase(root, entry.getValue(), null) == null));
        writeRefs(root.resolve(".quill/refs.json"), refs);
    }

    public static boolean hasOutdatedGenerations(Path root) {
        return !outdatedGenerations(root).isEmpty();
    }

    private static List<Path> outdatedGenerations(Path root) {
        Path quillDir = root.resolve(".quill");
        if (!Files.isDirectory(quillDir)) return List.of();
        List<Path> outdated = new ArrayList<>();
        try (Stream<Path> files = Files.list(quillDir)) {
            for (Path database : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .toList()) {
                try {
                    int version = IndexSchema.inspectVersion(database);
                    if (version >= 0 && version < IndexSchema.VERSION) {
                        outdated.add(database);
                    }
                } catch (RuntimeException ignored) {
                    // Corrupt databases follow the existing recovery path. A newer schema must
                    // remain untouched so the user can upgrade Quill without losing its index.
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not inspect index generations under "
                    + quillDir, e);
        }
        return List.copyOf(outdated);
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

    /** Writes {@code refs} unless the file already holds them; true when the file changed. */
    private static boolean writeRefs(Path refsPath, Map<String, String> refs) {
        try {
            return writeRefsOrThrow(refsPath, refs);
        } catch (RuntimeException e) {
            System.err.println("[quill] Warning: could not write refs.json: " + rootMessage(e));
            return false;
        }
    }

    private static boolean writeRefsOrThrow(Path refsPath, Map<String, String> refs) {
        // Other Quill servers watch this file; an identical rewrite would only wake them.
        RefsState existing = readRefsState(refsPath);
        if (existing.readable() && Files.isRegularFile(refsPath) && existing.refs().equals(refs)) return false;
        Path temp = refsPath.resolveSibling("." + refsPath.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(refsPath.toAbsolutePath().normalize().getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), refs);
            atomicMoveWithRetry(temp, refsPath);
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Could not write " + refsPath, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException ignored) {
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

    public static void atomicMove(Path source, Path target) throws IOException {
        Path from = source.toAbsolutePath().normalize(), to = target.toAbsolutePath().normalize();
        ValidatedDatabase validated = VALID_DATABASES.get(from);
        FileIdentity before = null;
        if (validated != null) try { before = FileIdentity.read(from); }
        catch (IOException unavailable) { validated = null; }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (validated != null) {
            VALID_DATABASES.remove(from, validated);
            try {
                FileIdentity after = FileIdentity.read(to);
                // Renaming changes ctime, but the validated inode, bytes and mtime must survive.
                if (validated.identity().equals(before) && before.key() != null && before.changed() != null
                        && before.key().equals(after.key()) && before.size() == after.size()
                        && before.modified().equals(after.modified())) {
                    VALID_DATABASES.put(to, new ValidatedDatabase(after, validated.indexId(), validated.lastCommit()));
                }
            } catch (IOException unavailable) { /* The reader will validate the final path instead. */ }
        }
    }

    public static void deleteDatabaseArtifacts(Path dbPath) {
        VALID_DATABASES.remove(dbPath.toAbsolutePath().normalize());
        VALIDATION_LOCKS.remove(dbPath.toAbsolutePath().normalize());
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
        // Another Quill version shares this project: its generations are intact, only unreadable here.
        // Rewriting refs.json would wake that server to rewrite them back, so read this version's
        // generations alongside its refs without touching the file.
        if (state.readable() && !orphanedDatabases && state.refs().values().stream().distinct()
                .filter(indexId -> referencedDatabase(root, indexId, null) == null)
                .allMatch(indexId -> foreignSchema(root, indexId))) {
            return withCandidates(root, state.refs());
        }
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
        Map<String, String> recovered = withCandidates(root, existingRefs, candidates);
        if (!recovered.isEmpty()) {
            String head = GitAnalyzer.resolveHead(root);
            boolean refsChanged = writeRefs(root.resolve(".quill/refs.json"), recovered);
            String selected = head != null ? recovered.get(headRef(head)) : recovered.get("@worktree");
            if (selected == null) selected = candidates.get(0).indexId();
            writeRecovery(root, new RecoveryStatus(reason, selected, Instant.now().toString()));
            if (refsChanged) System.err.println("[quill] Recovered index refs; using generation " + selected
                    + " (" + reason + ").");
        }
        return recovered;
    }

    private static Map<String, String> withCandidates(Path root, Map<String, String> existingRefs) {
        return withCandidates(root, existingRefs, databaseCandidates(root));
    }

    /** The readable refs of {@code existingRefs} plus this version's published generations by commit. */
    private static Map<String, String> withCandidates(Path root, Map<String, String> existingRefs,
            List<DatabaseCandidate> candidates) {
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
        return recovered;
    }

    /** A healthy generation file whose schema belongs to another Quill version. */
    private static boolean foreignSchema(Path root, String indexId) {
        if (indexId == null || indexId.isBlank() || indexId.contains("/") || indexId.contains("\\")) return false;
        Path database = root.resolve(".quill").resolve(indexId + ".db");
        try {
            int version = org.treblereel.mcp.db.IndexSchema.inspectVersion(database);
            return version > 0 && version != org.treblereel.mcp.db.IndexSchema.VERSION;
        } catch (RuntimeException unreadable) {
            return false;
        }
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
        FileIdentity identity;
        try { identity = FileIdentity.read(normalized); }
        catch (RuntimeException | IOException unavailable) { VALID_DATABASES.remove(normalized); return Optional.empty(); }
        ValidatedDatabase cached = VALID_DATABASES.get(normalized);
        if (cached != null && cached.identity().equals(identity)) return Optional.of(cached);
        // quick_check reads the whole file, seconds for a large index: never under the map's bin lock, which
        // would stall unrelated paths, and once per path at a time.
        synchronized (VALIDATION_LOCKS.computeIfAbsent(normalized, ignored -> new Object())) {
            cached = VALID_DATABASES.get(normalized);
            if (cached != null && cached.identity().equals(identity)) return Optional.of(cached);
            ValidatedDatabase validated = validate(normalized, identity);
            if (validated == null) VALID_DATABASES.remove(normalized);
            else VALID_DATABASES.put(normalized, validated);
            forgetMissingDatabases();
            return Optional.ofNullable(validated);
        }
    }

    private static ValidatedDatabase validate(Path path, FileIdentity identity) {
        try {
            Jdbi jdbi = QuillDatabase.open(path);
            boolean healthy = jdbi.withHandle(handle -> handle.createQuery("PRAGMA quick_check")
                    .mapTo(String.class).list().stream().allMatch("ok"::equalsIgnoreCase));
            if (!healthy) return null;
            Map<String, String> metadata = jdbi.withHandle(handle -> new IndexRepository(handle).getMetadata());
            String indexId = metadata.get("index_id");
            if (indexId == null || indexId.isBlank() || !identity.equals(FileIdentity.read(path))) return null;
            return new ValidatedDatabase(identity, indexId, metadata.getOrDefault("last_commit", "unknown"));
        } catch (RuntimeException | IOException unavailable) {
            return null;
        }
    }

    /** Databases removed outside {@link #deleteDatabaseArtifacts} would otherwise stay remembered for good. */
    private static void forgetMissingDatabases() {
        if (VALID_DATABASES.size() + VALIDATION_LOCKS.size() <= 128) return;
        VALID_DATABASES.keySet().removeIf(path -> !Files.exists(path));
        VALIDATION_LOCKS.keySet().removeIf(path -> !VALID_DATABASES.containsKey(path) && !Files.exists(path));
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
        // The same recovery repeated is not news, and a rewrite would wake other servers.
        var previous = readRecovery(root).orElse(null);
        if (previous != null && java.util.Objects.equals(previous.reason(), recovery.reason())
                && java.util.Objects.equals(previous.selectedIndexId(), recovery.selectedIndexId())) return;
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
    private record FileIdentity(Object key, long size, java.nio.file.attribute.FileTime modified,
            Object changed) {
        static FileIdentity read(Path path) throws IOException {
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            Object changed;
            try { changed = Files.getAttribute(path, "unix:ctime"); }
            catch (UnsupportedOperationException | IllegalArgumentException unavailable) { changed = null; }
            return new FileIdentity(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), changed);
        }
    }
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
