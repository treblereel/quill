package org.treblereel.mcp.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Persists the last successfully reconciled repository inventory. */
public final class WorkspaceRepositoryStateStore {

    public static final String FILE = "repositories.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Repository(String name, String relativePath) {}

    private WorkspaceRepositoryStateStore() {}

    public static List<Repository> read(Path workspaceRoot) throws IOException {
        Path state = path(workspaceRoot);
        if (!Files.isRegularFile(state)) return List.of();
        var root = JSON.readTree(state.toFile());
        if (!root.path("repositories").isArray()) return List.of();
        return JSON.convertValue(root.path("repositories"), JSON.getTypeFactory()
                .constructCollectionType(List.class, Repository.class));
    }

    public static void write(Path workspaceRoot,
            List<WorkspaceDiscovery.Repository> repositories) throws IOException {
        Path destination = path(workspaceRoot);
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "repositories-", ".tmp");
        try {
            var root = JSON.createObjectNode();
            root.put("format_version", 1);
            root.set("repositories", JSON.valueToTree(repositories.stream()
                    .map(repository -> new Repository(
                            repository.name(), repository.relativePath()))
                    .toList()));
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static Path path(Path workspaceRoot) {
        return WorkspaceManifestStore.directory(workspaceRoot).resolve(FILE);
    }
}
