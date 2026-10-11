package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;
import io.methvin.watcher.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KotlinDirectoryWatcherTest {
    @TempDir Path temp;

    @Test void rootsAreMinimalTraversalFiltersIrrelevantTreesAndLateEventsAreIgnored() throws Exception {
        Path root = temp.toRealPath(), source = root.resolve("src/Caller.kt"), ignored = root.resolve(".git/object");
        Files.createDirectories(source.getParent()); Files.writeString(source, "fun run() = 1");
        Files.createDirectories(ignored.getParent()); Files.writeString(ignored, "irrelevant");
        var listeners = new ArrayList<DirectoryChangeListener>(); var calls = new AtomicInteger();
        KotlinDirectoryWatcher.Factory factory = (roots, listener, visitor) -> {
            assertEquals(List.of(root), roots); listeners.add(listener);
            var visited = new HashSet<Path>(); visitor.recursiveVisitFiles(root, visited::add, visited::add);
            assertTrue(visited.contains(source)); assertFalse(visited.contains(ignored.getParent()));
            return DirectoryWatcher.builder().paths(roots).listener(listener).fileHashing(false)
                    .fileTreeVisitor(visitor).watchService(FileSystems.getDefault().newWatchService()).build();
        };
        try (var watcher = new KotlinDirectoryWatcher(factory, calls::incrementAndGet, () -> fail("Unexpected failure"))) {
            assertTrue(watcher.configure(Set.of(root, source.getParent()), path -> source.startsWith(path)));
            listeners.getFirst().onEvent(new DirectoryChangeEvent(DirectoryChangeEvent.EventType.MODIFY, false, ignored, null, 1, root));
            assertEquals(0, calls.get());
            listeners.getFirst().onEvent(new DirectoryChangeEvent(DirectoryChangeEvent.EventType.MODIFY, false, source, null, 1, root));
            assertEquals(1, calls.get());
            // Changing only the relevance filter does not restart an unchanged subscription.
            assertTrue(watcher.configure(Set.of(root), path -> source.startsWith(path)));
            assertEquals(1, listeners.size());
            watcher.close();
            listeners.getFirst().onEvent(new DirectoryChangeEvent(DirectoryChangeEvent.EventType.MODIFY, false, source, null, 1, root));
            assertEquals(1, calls.get());
            assertFalse(watcher.configure(Set.of(root), path -> true));
        }
    }

    @Test void terminatedEventLoopActivatesFallbackOnce() throws Exception {
        Path root = temp.toRealPath(); Files.writeString(root.resolve("Caller.kt"), "fun run() = 1");
        var failure = new CountDownLatch(1); var failures = new AtomicInteger();
        var subscription = new AtomicReference<DirectoryWatcher>();
        try (var watcher = new KotlinDirectoryWatcher((roots, listener, visitor) -> {
            var value = DirectoryWatcher.builder().paths(roots).listener(listener).fileHashing(false)
                    .watchService(FileSystems.getDefault().newWatchService()).build();
            subscription.set(value); return value;
        }, () -> {}, () -> { failures.incrementAndGet(); failure.countDown(); })) {
            assertTrue(watcher.configure(Set.of(root), path -> true));
            subscription.get().close(); assertTrue(failure.await(5, TimeUnit.SECONDS));
            assertFalse(watcher.active()); assertEquals(1, failures.get());
            assertFalse(watcher.configure(Set.of(root), path -> true)); assertEquals(1, failures.get());
        }
    }

    @Test void replacingRootsIgnoresOldOverflowAndDoesNotActivateFallback() throws Exception {
        Path first = temp.resolve("first"), second = temp.resolve("second");
        Files.createDirectory(first); Files.createDirectory(second);
        var listeners = new ArrayList<DirectoryChangeListener>(); var events = new AtomicInteger();
        try (var watcher = new KotlinDirectoryWatcher((roots, listener, visitor) -> {
            listeners.add(listener);
            return DirectoryWatcher.builder().paths(roots).listener(listener).fileHashing(false)
                    .watchService(FileSystems.getDefault().newWatchService()).build();
        }, events::incrementAndGet, () -> fail("Intentional replacement is not failure"))) {
            assertTrue(watcher.configure(Set.of(first), path -> true));
            assertTrue(watcher.configure(Set.of(second), path -> true));
            listeners.getFirst().onEvent(new DirectoryChangeEvent(DirectoryChangeEvent.EventType.OVERFLOW,
                    false, null, null, 1, first));
            assertEquals(0, events.get());
            listeners.getLast().onEvent(new DirectoryChangeEvent(DirectoryChangeEvent.EventType.OVERFLOW,
                    false, null, null, 1, second));
            assertEquals(1, events.get()); assertTrue(watcher.active());
        }
    }
}
