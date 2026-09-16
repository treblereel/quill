package org.treblereel.mcp.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.treblereel.mcp.command.UpdateCommand;

/** Consumes successful-build markers before MCP opens an immutable index generation. */
final class BuildEventConsumer {

    private final ConcurrentHashMap<Path, Object> projectLocks = new ConcurrentHashMap<>();

    String consume(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        Object lock = projectLocks.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (lock) {
            Path directory = normalized.resolve(".quill/build-events");
            List<Path> events;
            try {
                if (!Files.isDirectory(directory)) return null;
                try (var files = Files.list(directory)) {
                    events = files.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString)).toList();
                }
                if (events.isEmpty()) return null;
                UpdateCommand.updateAfterSuccessfulBuild(normalized);
                for (Path event : events) Files.deleteIfExists(event);
                try (var remaining = Files.list(directory)) {
                    if (remaining.findAny().isEmpty()) Files.deleteIfExists(directory);
                }
                return null;
            } catch (Exception e) {
                return "Successful build was detected, but the index refresh failed: "
                        + ProjectRegistry.safeMessage(e);
            }
        }
    }
}
