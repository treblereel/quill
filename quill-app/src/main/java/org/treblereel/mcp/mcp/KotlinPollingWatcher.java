package org.treblereel.mcp.mcp;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.function.Predicate;

/** Metadata only, for providers whose WatchService already polls at a slow fixed interval. */
final class KotlinPollingWatcher {
    static final long INTERVAL_MILLIS = 100;
    private record Stamp(FileTime modified, long size, Object identity, Object changedAt, boolean directory, boolean symlink) {}
    private record Snapshot(boolean readable, Map<Path, Stamp> entries) {}
    private final Map<Path, Snapshot> directories = new HashMap<>();
    private Predicate<Path> relevant = path -> false;

    synchronized void configure(Set<Path> desired, Predicate<Path> filter) {
        relevant = filter;
        directories.keySet().retainAll(desired);
        // Preserve existing baselines: configuring during a save must not swallow it.
        for (Path directory : desired) directories.computeIfAbsent(directory, this::snapshot);
    }

    synchronized boolean poll() {
        boolean changed = false;
        for (var entry : directories.entrySet()) {
            Snapshot next = snapshot(entry.getKey());
            changed |= !next.equals(entry.getValue());
            entry.setValue(next);
        }
        return changed;
    }

    synchronized Set<Path> directories() { return Set.copyOf(directories.keySet()); }

    private Snapshot snapshot(Path directory) {
        var entries = new HashMap<Path, Stamp>();
        try (var stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (!relevant.test(path)) continue;
                try {
                    var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    Object changedAt;
                    try { changedAt = Files.getAttribute(path, "unix:ctime", LinkOption.NOFOLLOW_LINKS); }
                    catch (UnsupportedOperationException | IllegalArgumentException unsupported) { changedAt = null; }
                    entries.put(path, new Stamp(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey(),
                            changedAt, attributes.isDirectory(), attributes.isSymbolicLink()));
                } catch (NoSuchFileException deletedDuringScan) { }
            }
            return new Snapshot(true, entries);
        } catch (NoSuchFileException deletedDirectory) {
            return new Snapshot(true, Map.of());
        } catch (IOException | DirectoryIteratorException | SecurityException inaccessible) {
            // Notify once on losing visibility, and again on recovery, without a retry storm.
            return new Snapshot(false, Map.of());
        }
    }
}
