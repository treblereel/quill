package org.treblereel.mcp.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.MavenSession;

/** Records a successful Maven session for lazy consumption by Quill. */
@Named
@Singleton
public final class QuillBuildEventSpy extends AbstractEventSpy {

    @Override
    public void onEvent(Object event) {
        if (!(event instanceof ExecutionEvent execution)
                || execution.getType() != ExecutionEvent.Type.SessionEnded) {
            return;
        }
        MavenSession session = execution.getSession();
        if (session == null || session.getResult() == null
                || session.getResult().hasExceptions()) {
            return;
        }
        Path root = session.getTopLevelProject() == null
                ? session.getRequest().getMultiModuleProjectDirectory().toPath()
                : session.getTopLevelProject().getBasedir().toPath();
        try {
            writeEvent(root.toAbsolutePath().normalize());
        } catch (IOException e) {
            // A notification must never turn a successful user build into a failed build.
            System.err.println("[quill] Could not record Maven build completion: "
                    + e.getMessage());
        }
    }

    static void writeEvent(Path root) throws IOException {
        Path directory = root.resolve(".quill/build-events");
        Files.createDirectories(directory);
        long now = System.currentTimeMillis();
        String name = "maven-" + now + "-" + UUID.randomUUID() + ".json";
        Path destination = directory.resolve(name);
        Path temporary = Files.createTempFile(directory, ".maven-", ".tmp");
        try {
            String json = "{\"version\":1,\"buildTool\":\"maven\","
                    + "\"successful\":true,\"finishedAt\":" + now + "}\n";
            Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
