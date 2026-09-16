package org.treblereel.mcp.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.treblereel.mcp.core.GitAnalyzer;

/**
 * Owns immutable SQLite index generations and the refs that publish them.
 *
 * <p>Keeping index publication separate from project discovery and indexing makes the atomic
 * visibility rules explicit: readers only open generations reachable through {@code refs.json}.
 */
public final class ProjectIndexStore {

    private static final int MAX_INDEXES = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        cleanupLru(root, refs);
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

        refs.entrySet().removeIf(entry -> !keptHashes.contains(entry.getValue()));
        writeRefs(root.resolve(".quill/refs.json"), refs);
    }

    public static Map<String, String> readRefs(Path refsPath) {
        if (!Files.exists(refsPath)) return new LinkedHashMap<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return MAPPER.readValue(refsPath.toFile(),
                        new TypeReference<LinkedHashMap<String, String>>() {});
            } catch (IOException e) {
                if (attempt == 2) return new LinkedHashMap<>();
                pauseForFileRelease();
            }
        }
        return new LinkedHashMap<>();
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
        Path refsPath = root.resolve(".quill/refs.json");
        Map<String, String> refs = readRefs(refsPath);
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

        Path refsPath = root.resolve(".quill/refs.json");
        Map<String, String> refs = readRefs(refsPath);
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
        if (expectedCommit != null
                && !indexId.equals(expectedCommit)
                && !indexId.startsWith(expectedCommit + "-")) {
            return null;
        }
        Path database = root.resolve(".quill").resolve(indexId + ".db").normalize();
        Path quillDir = root.resolve(".quill").toAbsolutePath().normalize();
        database = database.toAbsolutePath().normalize();
        return database.startsWith(quillDir) && Files.isRegularFile(database)
                ? database : null;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName() : message;
    }
}
