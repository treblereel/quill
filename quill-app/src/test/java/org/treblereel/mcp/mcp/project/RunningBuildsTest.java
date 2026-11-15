package org.treblereel.mcp.mcp.project;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunningBuildsTest {

    @TempDir Path root;

    @Test
    void onlyAMarkerOfALiveProcessThatWroteItRecentlyCounts() throws Exception {
        assertFalse(RunningBuilds.running(root));
        Path directory = Files.createDirectories(root.resolve(RunningBuilds.DIRECTORY));
        long pid = ProcessHandle.current().pid();
        Instant now = Instant.now();

        Path live = marker(directory, "live", pid, now);
        assertTrue(RunningBuilds.live(live, now));
        assertTrue(RunningBuilds.running(root));

        Instant processStart = ProcessHandle.current().info().startInstant().orElseThrow();
        assertFalse(RunningBuilds.live(marker(directory, "reused", pid, processStart.minusSeconds(60)), now), "pid reused");
        assertFalse(RunningBuilds.live(marker(directory, "old", pid, now.minus(RunningBuilds.MAX_AGE).minusSeconds(1)), now));
        assertFalse(RunningBuilds.live(marker(directory, "dead", Long.MAX_VALUE, now), now));

        Path partial = Files.writeString(directory.resolve("partial"), "");
        assertTrue(RunningBuilds.live(partial, now), "a marker being written");
        Files.setLastModifiedTime(partial, FileTime.from(now.minusSeconds(60)));
        assertFalse(RunningBuilds.live(partial, now));

        Files.delete(live);
        assertFalse(RunningBuilds.running(root));
        try (var left = Files.list(directory)) {
            assertTrue(left.findAny().isEmpty(), "stale markers are removed");
        }
    }

    private static Path marker(Path directory, String name, long pid, Instant started) throws Exception {
        return Files.writeString(directory.resolve(name), "pid=" + pid + "\nstarted=" + started + "\n");
    }
}
