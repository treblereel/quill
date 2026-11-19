package org.treblereel.mcp.mcp.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.treblereel.mcp.diagnostics.QuillLog;

/**
 * Builds the Quill Maven extension marked as running in {@code .quill/builds-running}. A marker
 * counts only while its process is alive, is the process that wrote it, and is younger than any
 * plausible build; others are left from a killed build and are removed.
 */
final class RunningBuilds {

    /** Written by the Maven extension's BuildMarker; keep the two in step. */
    static final String DIRECTORY = ".quill/builds-running";
    static final Duration MAX_AGE = Duration.ofHours(3);

    private RunningBuilds() {}

    static boolean running(Path root) {
        Path directory = root.resolve(DIRECTORY);
        if (!Files.isDirectory(directory)) return false;
        boolean running = false;
        try (var markers = Files.list(directory)) {
            for (Path marker : markers.toList()) {
                if (live(marker, Instant.now())) running = true;
                else try { Files.deleteIfExists(marker); } catch (IOException ignored) { }
            }
        } catch (IOException | RuntimeException unreadable) {
            QuillLog.swallowed(unreadable);
            return false;
        }
        return running;
    }

    static boolean live(Path marker, Instant now) {
        Properties properties = new Properties();
        try (var input = Files.newBufferedReader(marker)) {
            properties.load(input);
            long pid = Long.parseLong(properties.getProperty("pid", ""));
            Instant started = Instant.parse(properties.getProperty("started", ""));
            if (started.plus(MAX_AGE).isBefore(now)) return false;
            var process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElse(null);
            if (process == null) return false;
            // A reused pid belongs to a process that started after the marker was written.
            return process.info().startInstant().map(start -> !start.isAfter(started)).orElse(true);
        } catch (IOException | RuntimeException unreadable) {
            QuillLog.swallowed(unreadable);
            // A marker still being written reads as incomplete; give it a moment before judging it stale.
            try { return Files.getLastModifiedTime(marker).toInstant().plusSeconds(10).isAfter(now); }
            catch (IOException gone) { return false; }
        }
    }
}
