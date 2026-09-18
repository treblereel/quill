package org.treblereel.mcp.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;

/** Records the result of a Maven session for lazy consumption by Quill. */
@Named
@Singleton
public final class QuillBuildEventSpy extends AbstractEventSpy {

    private static final int MAX_PENDING_EVENTS = 16;

    @Override
    public void onEvent(Object event) {
        if (!(event instanceof ExecutionEvent execution)
                || execution.getType() != ExecutionEvent.Type.SessionEnded) {
            return;
        }
        MavenSession session = execution.getSession();
        if (session == null || session.getResult() == null
                || isInternal(session.getUserProperties())) {
            return;
        }
        Path reactorRoot = session.getRequest().getMultiModuleProjectDirectory() == null
                ? null : session.getRequest().getMultiModuleProjectDirectory().toPath();
        Path topLevelRoot = session.getTopLevelProject() == null
                ? null : session.getTopLevelProject().getBasedir().toPath();
        Path root = eventRoot(reactorRoot, topLevelRoot);
        if (root == null) return;
        try {
            boolean successful = !session.getResult().hasExceptions();
            writeEvent(root.toAbsolutePath().normalize(), successful,
                    failureMessages(session.getResult().getExceptions()));
        } catch (IOException e) {
            // A notification must never turn a successful user build into a failed build.
            System.err.println("[quill] Could not record Maven build completion: "
                    + e.getMessage());
        }
    }

    static boolean isInternal(Properties userProperties) {
        return userProperties != null
                && Boolean.parseBoolean(userProperties.getProperty("quill.internal"));
    }

    static Path eventRoot(Path reactorRoot, Path topLevelRoot) {
        return reactorRoot != null ? reactorRoot : topLevelRoot;
    }

    static void writeEvent(Path root) throws IOException {
        writeEvent(root, true, List.of());
    }

    static void writeEvent(Path root, boolean successful, List<String> failureMessages)
            throws IOException {
        Path directory = root.resolve(".quill/build-events");
        Files.createDirectories(directory);
        long now = System.currentTimeMillis();
        String name = "maven-" + now + "-" + UUID.randomUUID() + ".json";
        Path destination = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, ".maven-", ".tmp");
        try {
            String encoded = String.join(",", failureMessages.stream().limit(50)
                    .map(message -> Base64.getEncoder().encodeToString(
                            message.getBytes(StandardCharsets.UTF_8)))
                    .map(value -> "\"" + value + "\"")
                    .toList());
            String json = "{\"version\":2,\"buildTool\":\"maven\","
                    + "\"successful\":" + successful + ",\"finishedAt\":" + now + ","
                    + "\"failureMessagesBase64\":[" + encoded + "]}\n";
            Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination);
            }
            pruneOldEvents(directory);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void pruneOldEvents(Path directory) throws IOException {
        List<Path> events;
        try (var files = Files.list(directory)) {
            events = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted((left, right) -> {
                        try {
                            int modified = Files.getLastModifiedTime(left)
                                    .compareTo(Files.getLastModifiedTime(right));
                            return modified != 0 ? modified : left.compareTo(right);
                        } catch (IOException ignored) {
                            return left.compareTo(right);
                        }
                    }).toList();
        }
        for (int index = 0; index < events.size() - MAX_PENDING_EVENTS; index++) {
            Files.deleteIfExists(events.get(index));
        }
    }

    static List<String> failureMessages(List<Throwable> failures) {
        LinkedHashSet<String> messages = new LinkedHashSet<>();
        if (failures == null) return List.of();
        for (Throwable failure : failures) {
            Throwable current = failure;
            int depth = 0;
            while (current != null && depth++ < 20 && messages.size() < 50) {
                String message = current.getMessage();
                messages.add(current.getClass().getName()
                        + (message == null || message.isBlank() ? "" : ": " + message));
                current = current.getCause();
            }
        }
        return new ArrayList<>(messages);
    }
}
