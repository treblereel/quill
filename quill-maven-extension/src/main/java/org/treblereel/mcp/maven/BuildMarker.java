package org.treblereel.mcp.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.maven.execution.MavenSession;

/**
 * Marks a running Maven session in {@code .quill/builds-running} so Quill holds its background
 * input preparation until the build ends instead of re-checking outputs the build is still writing.
 * Quill ignores a marker whose process is gone or that outlived any plausible build.
 */
final class BuildMarker {

    /** Read by Quill's ProjectPreparation; keep the two in step. */
    static final String DIRECTORY = ".quill/builds-running";

    private static final Map<Object, Path> MARKERS = Collections.synchronizedMap(new IdentityHashMap<>());

    private BuildMarker() {}

    static void start(MavenSession session, Path root) {
        if (session == null || root == null || !Files.isDirectory(root.resolve(".quill"))) return;
        try {
            Path directory = Files.createDirectories(root.resolve(DIRECTORY));
            long pid = ProcessHandle.current().pid();
            Path marker = directory.resolve(pid + "-" + UUID.randomUUID());
            Files.writeString(marker, "pid=" + pid + "\nstarted=" + Instant.now() + "\n", StandardCharsets.UTF_8);
            MARKERS.put(session.getRequest(), marker);
        } catch (IOException | RuntimeException error) {
            // A marker is an optimization; the build must not depend on it.
            System.err.println("[quill] Could not mark the running build: " + error.getMessage());
        }
    }

    static void finish(MavenSession session) {
        if (session == null) return;
        Path marker = MARKERS.remove(session.getRequest());
        if (marker == null) return;
        try {
            Files.deleteIfExists(marker);
        } catch (IOException error) {
            System.err.println("[quill] Could not clear the running-build marker: " + error.getMessage());
        }
    }
}
