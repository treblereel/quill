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
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

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
            captureClasspaths(session.getProjects());
            boolean successful = !session.getResult().hasExceptions();
            List<String> messages = failureMessages(session.getResult().getExceptions());
            writeEvent(root.toAbsolutePath().normalize(), successful, messages,
                    compilerDiagnostics(messages));
        } catch (IOException e) {
            // A notification must never turn a successful user build into a failed build.
            System.err.println("[quill] Could not record Maven build completion: "
                    + e.getMessage());
        }
    }

    static void captureClasspaths(List<MavenProject> projects) {
        if (projects == null) return;
        for (MavenProject project : projects) {
            if (project == null || project.getBasedir() == null) continue;
            Path output = project.getBasedir().toPath().resolve("target");
            try {
                writeClasspath(output.resolve("quill-classpath.txt"),
                        project.getRuntimeClasspathElements());
                writeClasspath(output.resolve("quill-test-classpath.txt"),
                        project.getTestClasspathElements());
            } catch (DependencyResolutionRequiredException | IOException error) {
                System.err.println("[quill] Could not capture Maven classpath for "
                        + project.getArtifactId() + ": " + error.getMessage());
            }
        }
    }

    static void writeClasspath(Path destination, List<String> elements) throws IOException {
        LinkedHashSet<String> jars = new LinkedHashSet<>();
        if (elements != null) {
            for (String element : elements) {
                if (element == null || !element.endsWith(".jar")) continue;
                Path path = Path.of(element).toAbsolutePath().normalize();
                if (Files.isRegularFile(path)) jars.add(path.toString());
            }
        }
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".classpath-", ".tmp");
        try {
            Files.writeString(temporary, String.join(java.io.File.pathSeparator, jars),
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
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

    static boolean isInternal(Properties userProperties) {
        return userProperties != null
                && Boolean.parseBoolean(userProperties.getProperty("quill.internal"));
    }

    static Path eventRoot(Path reactorRoot, Path topLevelRoot) {
        return reactorRoot != null ? reactorRoot : topLevelRoot;
    }

    static void writeEvent(Path root) throws IOException {
        writeEvent(root, true, List.of(), List.of());
    }

    static void writeEvent(Path root, boolean successful, List<String> failureMessages)
            throws IOException {
        writeEvent(root, successful, failureMessages, compilerDiagnostics(failureMessages));
    }

    static void writeEvent(Path root, boolean successful, List<String> failureMessages,
            List<String> diagnostics) throws IOException {
        Path directory = root.resolve(".quill/build-events");
        Files.createDirectories(directory);
        long now = System.currentTimeMillis();
        String name = "maven-" + now + "-" + UUID.randomUUID() + ".json";
        Path destination = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, ".maven-", ".tmp");
        try {
            String encoded = encoded(failureMessages, 50);
            String encodedDiagnostics = encoded(diagnostics, 200);
            String json = "{\"version\":3,\"buildTool\":\"maven\","
                    + "\"successful\":" + successful + ",\"finishedAt\":" + now + ","
                    + "\"captureScope\":\"exception_chain\","
                    + "\"failureMessagesBase64\":[" + encoded + "],"
                    + "\"diagnosticsBase64\":[" + encodedDiagnostics + "]}\n";
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

    static List<String> compilerDiagnostics(List<String> messages) {
        if (messages == null) return List.of();
        LinkedHashSet<String> diagnostics = new LinkedHashSet<>();
        for (String message : messages) {
            if (message == null) continue;
            message.lines().map(String::strip)
                    .filter(line -> line.matches("(?i).+\\.(java|kt|groovy)(?::|:\\[).*"))
                    .limit(200 - diagnostics.size()).forEach(diagnostics::add);
            if (diagnostics.size() >= 200) break;
        }
        return List.copyOf(diagnostics);
    }

    private static String encoded(List<String> values, int limit) {
        return String.join(",", values.stream().limit(limit)
                .map(message -> Base64.getEncoder().encodeToString(
                        message.getBytes(StandardCharsets.UTF_8)))
                .map(value -> "\"" + value + "\"").toList());
    }
}
