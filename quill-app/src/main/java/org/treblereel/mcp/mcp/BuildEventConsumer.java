package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.treblereel.mcp.command.UpdateCommand;
import org.treblereel.mcp.core.WorktreeInspector;

/** Consumes build-result markers before MCP opens an immutable index generation. */
final class BuildEventConsumer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PROTOCOL_VERSION = 3;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Set<String> BUILD_TOOLS = Set.of("maven", "gradle");
    private static final Set<String> FIELDS =
            Set.of("version", "buildTool", "successful", "finishedAt", "captureScope",
                    "failureMessagesBase64", "diagnosticsBase64");

    private final ConcurrentHashMap<Path, Object> projectLocks = new ConcurrentHashMap<>();

    static boolean hasPendingEvents(Path root) {
        Path directory = root.toAbsolutePath().normalize().resolve(".quill/build-events");
        if (!Files.isDirectory(directory)) return false;
        try (var files = Files.list(directory)) {
            return files.anyMatch(Files::isRegularFile);
        } catch (IOException ignored) {
            return true;
        }
    }

    String consume(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        Object lock = projectLocks.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (lock) {
            Path directory = normalized.resolve(".quill/build-events");
            List<Path> eventFiles;
            try {
                if (!Files.isDirectory(directory)) return null;
                try (var files = Files.list(directory)) {
                    eventFiles = files.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(Path::toString)).toList();
                }
                if (eventFiles.isEmpty()) return null;

                List<Path> rejected = new ArrayList<>();
                List<BuildEvent> events = new ArrayList<>();
                for (Path file : eventFiles) {
                    Optional<BuildEvent> event = read(file);
                    if (event.isPresent()) events.add(event.get());
                    else rejected.add(file);
                }
                delete(rejected);
                if (!rejected.isEmpty()) {
                    System.err.println("[quill] Ignored " + rejected.size()
                            + " invalid build event(s).");
                }
                if (events.isEmpty()) {
                    removeIfEmpty(directory);
                    return null;
                }

                BuildEvent latest = events.stream()
                        .max(Comparator.comparingLong(BuildEvent::finishedAt))
                        .orElseThrow();
                persistBuildState(normalized, latest);
                if (!latest.successful()) {
                    delete(events.stream().map(BuildEvent::file).toList());
                    removeIfEmpty(directory);
                    return null;
                }
                Optional<String> newerInput = newerStructuralInput(normalized, latest.finishedAt());
                if (newerInput.isPresent()) {
                    delete(events.stream().map(BuildEvent::file).toList());
                    removeIfEmpty(directory);
                    System.err.println("[quill] Ignored stale build event: "
                            + newerInput.get() + " changed after the build completed."
                            + " Build the project again before Quill refreshes its index.");
                    return null;
                }

                UpdateCommand.updateAfterSuccessfulBuild(normalized);
                delete(events.stream().map(BuildEvent::file).toList());
                removeIfEmpty(directory);
                return null;
            } catch (Exception e) {
                return "Successful build was detected, but the index refresh failed: "
                        + ProjectRegistry.safeMessage(e);
            }
        }
    }

    private static Optional<BuildEvent> read(Path file) {
        try {
            JsonNode root = JSON.readTree(file.toFile());
            if (root == null || !root.isObject()) return Optional.empty();
            var names = root.fieldNames();
            while (names.hasNext()) {
                if (!FIELDS.contains(names.next())) return Optional.empty();
            }
            JsonNode version = root.get("version");
            JsonNode buildTool = root.get("buildTool");
            JsonNode successful = root.get("successful");
            JsonNode finishedAt = root.get("finishedAt");
            JsonNode messages = root.get("failureMessagesBase64");
            JsonNode diagnostics = root.get("diagnosticsBase64");
            JsonNode captureScope = root.get("captureScope");
            if (version == null || !version.isIntegralNumber()
                    || version.intValue() != PROTOCOL_VERSION
                    || buildTool == null || !buildTool.isTextual()
                    || !BUILD_TOOLS.contains(buildTool.textValue())
                    || successful == null || !successful.isBoolean()
                    || finishedAt == null || !finishedAt.isIntegralNumber()
                    || captureScope == null || !captureScope.isTextual()
                    || !Set.of("exception_chain", "task_output").contains(captureScope.textValue())
                    || messages == null || !messages.isArray() || messages.size() > 50
                    || diagnostics == null || !diagnostics.isArray()
                    || diagnostics.size() > 200) {
                return Optional.empty();
            }
            long timestamp = finishedAt.longValue();
            long latestAllowed = System.currentTimeMillis() + MAX_CLOCK_SKEW.toMillis();
            if (timestamp <= 0 || timestamp > latestAllowed) return Optional.empty();
            List<String> failureMessages = decode(messages);
            List<String> compilerDiagnostics = decode(diagnostics);
            if (successful.booleanValue() && !failureMessages.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new BuildEvent(file, buildTool.textValue(),
                    successful.booleanValue(), timestamp, captureScope.textValue(),
                    List.copyOf(failureMessages), List.copyOf(compilerDiagnostics)));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static List<String> decode(JsonNode values) {
        List<String> decodedValues = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().length() > 100_000) {
                throw new IllegalArgumentException("Invalid encoded build diagnostic");
            }
            byte[] decoded = Base64.getDecoder().decode(value.textValue());
            if (decoded.length > 64_000) {
                throw new IllegalArgumentException("Build diagnostic exceeds 64 KiB");
            }
            decodedValues.add(new String(decoded, StandardCharsets.UTF_8));
        }
        return decodedValues;
    }

    private static Optional<String> newerStructuralInput(Path root, long finishedAt) {
        WorktreeInspector.Snapshot snapshot = WorktreeInspector.inspect(root);
        Path repositoryRoot = snapshot.repositoryRoot();
        if (repositoryRoot == null) return Optional.empty();
        for (WorktreeInspector.Change change : snapshot.structuralChanges()) {
            Path changed = repositoryRoot.resolve(change.repositoryPath()).normalize();
            if (!changed.startsWith(repositoryRoot)) continue;
            Path timestampSource = Files.exists(changed)
                    ? changed : nearestExistingParent(changed, repositoryRoot);
            try {
                if (timestampSource != null
                        && Files.getLastModifiedTime(timestampSource).toMillis() > finishedAt) {
                    return Optional.of(change.projectPath());
                }
            } catch (IOException ignored) {
                // Freshness cannot be disproved from an unreadable timestamp. The regular
                // worktree fingerprint still marks the served response as structurally stale.
            }
        }
        return Optional.empty();
    }

    private static Path nearestExistingParent(Path path, Path repositoryRoot) {
        Path current = path.getParent();
        while (current != null && current.startsWith(repositoryRoot)) {
            if (Files.exists(current)) return current;
            current = current.getParent();
        }
        return null;
    }

    private static void delete(List<Path> files) throws IOException {
        for (Path file : files) Files.deleteIfExists(file);
    }

    private static void removeIfEmpty(Path directory) throws IOException {
        try (var remaining = Files.list(directory)) {
            if (remaining.findAny().isEmpty()) Files.deleteIfExists(directory);
        }
    }

    private static void persistBuildState(Path root, BuildEvent event) throws IOException {
        Path quill = Files.createDirectories(root.resolve(".quill"));
        Path destination = quill.resolve("build-state.json");
        Path temporary = Files.createTempFile(quill, ".build-state-", ".tmp");
        try {
            var state = JSON.createObjectNode();
            state.put("version", PROTOCOL_VERSION);
            state.put("buildTool", event.buildTool());
            state.put("successful", event.successful());
            state.put("finishedAt", event.finishedAt());
            state.put("captureScope", event.captureScope());
            state.set("failureMessages", JSON.valueToTree(event.failureMessages()));
            state.set("diagnostics", JSON.valueToTree(event.diagnostics()));
            JSON.writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record BuildEvent(
            Path file, String buildTool, boolean successful, long finishedAt,
            String captureScope, List<String> failureMessages, List<String> diagnostics) {}
}
