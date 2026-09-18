package org.treblereel.mcp.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Reads and atomically publishes workspace configuration. */
public final class WorkspaceManifestStore {

    public static final String DIRECTORY = ".quill-workspace";
    public static final String MANIFEST = "workspace.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> DEFAULT_EXCLUDES = List.of(
            ".git", ".quill", ".quill-workspace", "target", "build", "node_modules");

    private WorkspaceManifestStore() {}

    public static WorkspaceManifest initialize(Path requestedRoot, int discoveryDepth)
            throws IOException {
        Path root = normalizeRoot(requestedRoot);
        if (discoveryDepth < 1 || discoveryDepth > 10) {
            throw new IllegalArgumentException("Discovery depth must be between 1 and 10");
        }
        Path directory = Files.createDirectories(directory(root));
        Path manifestPath = directory.resolve(MANIFEST);
        if (Files.isRegularFile(manifestPath)) return read(root);

        WorkspaceManifest manifest = new WorkspaceManifest(
                WorkspaceManifest.CURRENT_FORMAT, root, discoveryDepth,
                DEFAULT_EXCLUDES, Instant.now().toString());
        writeAtomically(manifestPath, manifest);
        return manifest;
    }

    public static WorkspaceManifest read(Path requestedRoot) throws IOException {
        Path root = normalizeRoot(requestedRoot);
        Path manifestPath = manifest(root);
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalArgumentException("Workspace is not initialized at " + root
                    + ". Run: quill workspace init --project " + root);
        }
        var json = JSON.readTree(manifestPath.toFile());
        WorkspaceManifest value = new WorkspaceManifest(
                json.path("format_version").asInt(), Path.of(json.path("root").asText()),
                json.path("discovery_depth").asInt(),
                JSON.convertValue(json.path("excludes"),
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class)),
                json.path("created_at").asText());
        if (value.formatVersion() != WorkspaceManifest.CURRENT_FORMAT) {
            throw new IllegalArgumentException("Unsupported workspace format "
                    + value.formatVersion() + " at " + manifestPath);
        }
        if (!value.root().equals(root)) {
            throw new IllegalArgumentException("Workspace manifest belongs to " + value.root()
                    + ", not " + root);
        }
        return value;
    }

    public static ClearResult clear(Path requestedRoot) throws IOException {
        Path root = normalizeRoot(requestedRoot);
        Path workspace = directory(root);
        if (!Files.exists(workspace, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return new ClearResult(root, false);
        }
        if (Files.isSymbolicLink(workspace)) {
            throw new IllegalArgumentException("Refusing to clear symbolic link: " + workspace);
        }
        if (!Files.isDirectory(workspace, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Workspace data is not a directory: " + workspace);
        }

        Path staged = root.resolve(DIRECTORY + ".clearing-" + UUID.randomUUID());
        WorkspaceLock acquired = WorkspaceLock.tryAcquire(root);
        if (acquired == null) {
            throw new IllegalStateException("Workspace is in use: " + root);
        }
        try (WorkspaceLock lock = acquired) {
            moveForRemoval(workspace, staged);
            deleteTree(staged);
        }
        return new ClearResult(root, true);
    }

    public static Path normalizeRoot(Path requestedRoot) {
        Path root = requestedRoot == null
                ? Path.of(System.getProperty("user.dir")) : requestedRoot;
        root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Workspace root is not a directory: " + root);
        }
        return root;
    }

    public static Path directory(Path root) {
        return root.toAbsolutePath().normalize().resolve(DIRECTORY);
    }

    public static Path manifest(Path root) {
        return directory(root).resolve(MANIFEST);
    }

    private static void writeAtomically(Path destination, WorkspaceManifest manifest)
            throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "workspace-", ".tmp");
        try {
            ObjectNode json = JSON.createObjectNode();
            json.put("format_version", manifest.formatVersion());
            json.put("root", manifest.root().toString());
            json.put("discovery_depth", manifest.discoveryDepth());
            json.set("excludes", JSON.valueToTree(manifest.excludes()));
            json.put("created_at", manifest.createdAt());
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), json);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveForRemoval(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, destination);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    public record ClearResult(Path root, boolean removed) {}
}
