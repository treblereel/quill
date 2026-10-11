package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlinPollingWatcherTest {
    @TempDir Path temp;

    @Test void tracksEditsAndAtomicReplacementEvenWithIdenticalSizeAndTimestamp() throws Exception {
        Path source = temp.resolve("Caller.kt"); Files.writeString(source, "before");
        var watcher = new KotlinPollingWatcher(); watcher.configure(Set.of(temp), source::equals);
        assertFalse(watcher.poll());
        Files.writeString(source, "after"); assertTrue(watcher.poll()); assertFalse(watcher.poll());
        FileTime modified = Files.getLastModifiedTime(source);
        Path staged = temp.resolve(".save"); Files.writeString(staged, "other"); Files.setLastModifiedTime(staged, modified);
        Files.move(staged, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        assertTrue(watcher.poll(), "Replacement identity changes even when size/time do not");
        assertFalse(watcher.poll());
    }

    @Test void tracksDeletionRecreationAndMissingDirectory() throws Exception {
        Path directory = temp.resolve("sources"); Files.createDirectory(directory);
        Path source = directory.resolve("Caller.kt"); Files.writeString(source, "one");
        var watcher = new KotlinPollingWatcher(); watcher.configure(Set.of(directory), path -> true);
        Files.delete(source); assertTrue(watcher.poll()); assertFalse(watcher.poll());
        Files.delete(directory); assertFalse(watcher.poll());
        Files.createDirectory(directory); Files.writeString(source, "two"); assertTrue(watcher.poll());
        assertFalse(watcher.poll());
    }

    @Test void watchesFutureTreesAndIgnoresUnrelatedSiblingFiles() throws Exception {
        Path root = temp.resolve("src");
        var watcher = new KotlinPollingWatcher();
        watcher.configure(Set.of(temp), path -> path.startsWith(root) || root.startsWith(path));
        Files.writeString(temp.resolve("notes.txt"), "irrelevant"); assertFalse(watcher.poll());
        Files.createDirectories(root.resolve("main/kotlin")); assertTrue(watcher.poll());
        watcher.configure(Set.of(temp, root.resolve("main/kotlin")),
                path -> path.startsWith(root) || root.startsWith(path));
        assertFalse(watcher.poll());
        Files.writeString(root.resolve("main/kotlin/New.kt"), "fun added() = 1"); assertTrue(watcher.poll());
    }

    @Test void repeatedConfigurationDoesNotSwallowSaveAndUnsubscribedTreesStopReporting() throws Exception {
        Path first = temp.resolve("first"), second = temp.resolve("second");
        Files.createDirectory(first); Files.createDirectory(second);
        Path source = first.resolve("Caller.kt"); Files.writeString(source, "old");
        var watcher = new KotlinPollingWatcher(); watcher.configure(Set.of(first), path -> true);
        Files.writeString(source, "new contents");
        watcher.configure(Set.of(first), path -> true); assertTrue(watcher.poll());
        watcher.configure(Set.of(second), path -> true);
        Files.writeString(source, "ignored"); assertFalse(watcher.poll());
        Files.writeString(second.resolve("Other.kt"), "new"); assertTrue(watcher.poll());
    }

    @Test void lostVisibilityAndRecoveryNotifyOnceEach() throws Exception {
        Files.writeString(temp.resolve("Caller.kt"), "value");
        var inaccessible = new java.util.concurrent.atomic.AtomicBoolean();
        var watcher = new KotlinPollingWatcher();
        watcher.configure(Set.of(temp), path -> {
            if (inaccessible.get()) throw new SecurityException("simulated denied access");
            return true;
        });
        inaccessible.set(true); assertTrue(watcher.poll()); assertFalse(watcher.poll());
        inaccessible.set(false); assertTrue(watcher.poll()); assertFalse(watcher.poll());
    }
}
