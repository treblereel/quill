package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** Reads the live worktree state that is intentionally absent from commit history. */
public final class WorktreeInspector {

    private WorktreeInspector() {}

    public record Change(String projectPath, String repositoryPath, String status) {}

    public record Snapshot(String currentCommit, Path repositoryRoot, List<Change> changes,
            String fingerprint, List<Change> structuralChanges, String structuralFingerprint) {
        public boolean dirty() {
            return !changes.isEmpty();
        }

        public Map<String, String> statusesByRepositoryPath() {
            Map<String, String> result = new LinkedHashMap<>();
            for (Change change : changes) result.put(change.repositoryPath(), change.status());
            return result;
        }

        public boolean structuralDirty() {
            return !structuralChanges.isEmpty();
        }

        public static Snapshot empty() {
            String empty = emptyFingerprint();
            return new Snapshot(null, null, List.of(), empty, List.of(), empty);
        }
    }

    public static Snapshot inspect(Path projectRoot) {
        Path gitDir = GitAnalyzer.findGitDir(projectRoot);
        if (gitDir == null) return Snapshot.empty();

        try (Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .readEnvironment()
                .build();
                Git git = new Git(repository)) {
            Path repositoryRoot = repository.getWorkTree().toPath().toRealPath();
            Path normalizedProject = projectRoot.toRealPath();
            if (!normalizedProject.startsWith(repositoryRoot)) return Snapshot.empty();
            String projectPrefix = normalize(repositoryRoot.relativize(normalizedProject));
            if (!projectPrefix.isEmpty()) projectPrefix += "/";

            Status status = git.status().call();
            Map<String, String> statuses = new LinkedHashMap<>();
            putAll(statuses, status.getAdded(), "added");
            putAll(statuses, status.getChanged(), "modified");
            putAll(statuses, status.getModified(), "modified");
            putAll(statuses, status.getMissing(), "deleted");
            putAll(statuses, status.getRemoved(), "deleted");
            putAll(statuses, status.getUntracked(), "untracked");
            putAll(statuses, status.getConflicting(), "conflicting");

            List<Change> changes = new ArrayList<>();
            for (var entry : statuses.entrySet()) {
                String repositoryPath = normalize(Path.of(entry.getKey()));
                if (!projectPrefix.isEmpty() && !repositoryPath.startsWith(projectPrefix)) continue;
                String projectPath = projectPrefix.isEmpty()
                        ? repositoryPath : repositoryPath.substring(projectPrefix.length());
                if (projectPath.equals(".quill") || projectPath.startsWith(".quill/")) continue;
                changes.add(new Change(projectPath, repositoryPath, entry.getValue()));
            }
            changes.sort(Comparator.comparing(Change::repositoryPath));
            List<Change> structuralChanges = changes.stream()
                    .filter(change -> isStructuralPath(change.projectPath()))
                    .toList();

            ObjectId head = repository.resolve("HEAD");
            return new Snapshot(head != null ? head.getName() : null, repositoryRoot,
                    List.copyOf(changes), fingerprint(repositoryRoot, changes),
                    structuralChanges, fingerprint(repositoryRoot, structuralChanges));
        } catch (IOException | GitAPIException e) {
            return Snapshot.empty();
        }
    }

    private static void putAll(Map<String, String> target, Iterable<String> paths, String status) {
        for (String path : paths) target.put(path, status);
    }

    private static String fingerprint(Path repositoryRoot, List<Change> changes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Change change : changes) {
                update(digest, change.repositoryPath());
                update(digest, change.status());
                Path file = repositoryRoot.resolve(change.repositoryPath()).normalize();
                if (!file.startsWith(repositoryRoot) || !Files.isRegularFile(file)) continue;
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
                } catch (IOException e) {
                    update(digest, "unreadable");
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String emptyFingerprint() {
        return fingerprint(Path.of("."), List.of());
    }

    static boolean isStructuralPath(String projectPath) {
        String path = projectPath.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.equals("pom.xml") || name.equals("build.gradle")
                || name.equals("build.gradle.kts") || name.equals("settings.gradle")
                || name.equals("settings.gradle.kts") || name.equals("gradle.properties")) {
            return true;
        }
        return path.startsWith("src/main/") || path.contains("/src/main/")
                || path.startsWith("target/generated-sources/")
                || path.contains("/target/generated-sources/")
                || path.startsWith("build/generated/") || path.contains("/build/generated/")
                || path.startsWith("buildSrc/") || path.contains("/buildSrc/")
                || path.startsWith(".mvn/") || path.contains("/.mvn/")
                || path.startsWith("gradle/") || path.contains("/gradle/");
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }
}
