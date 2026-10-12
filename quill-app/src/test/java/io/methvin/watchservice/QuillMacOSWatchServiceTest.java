package io.methvin.watchservice;

import static java.nio.file.StandardWatchEventKinds.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.NativeLong;
import io.methvin.watchservice.jna.CarbonAPI;
import io.methvin.watchservice.jna.FSEventStreamRef;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuillMacOSWatchServiceTest {
    @TempDir Path temp;
    private static final List<WatchEvent.Kind<?>> EVENTS = List.of(ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
    private static final class Fake implements QuillMacOSWatchService.Platform {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<CarbonAPI.FSEventStreamCallback> callbacks = new ArrayList<>();
        boolean startResult = true, missingStream, missingQueue;
        RuntimeException startFailure;
        Runnable duringStart;
        CountDownLatch starting, allowStart, draining, allowDrain;
        public Pointer queue() { calls.add("queue"); return missingQueue ? null : new Pointer(1); }
        public FSEventStreamRef create(Path root, CarbonAPI.FSEventStreamCallback callback, Pointer queue) {
            calls.add("create"); callbacks.add(callback);
            return missingStream ? null : new FSEventStreamRef();
        }
        public boolean start(FSEventStreamRef stream) {
            calls.add("start"); pause(starting, allowStart);
            if (duringStart != null) duringStart.run();
            if (startFailure != null) throw startFailure;
            return startResult;
        }
        public void stop(FSEventStreamRef stream) { calls.add("stop"); }
        public void invalidate(FSEventStreamRef stream) { calls.add("invalidate"); }
        public void drain(Pointer queue) { calls.add("drain"); pause(draining, allowDrain); }
        public void release(FSEventStreamRef stream) { calls.add("release"); }
        public void releaseQueue(Pointer queue) { calls.add("releaseQueue"); }
        private static void pause(CountDownLatch entered, CountDownLatch proceed) {
            if (entered == null) return;
            entered.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }
    private QuillMacOSWatchService service(Fake platform) {
        return new QuillMacOSWatchService(new MacOSXListeningWatchService.Config() {}, platform);
    }
    @Test void registrationWaitsForStartAndCloseCannotOvertakeIt() throws Exception {
        var platform = new Fake(); platform.starting = new CountDownLatch(1); platform.allowStart = new CountDownLatch(1);
        var service = service(platform);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var registered = executor.submit(() -> service.register(new WatchablePath(temp), EVENTS));
            assertTrue(platform.starting.await(5, TimeUnit.SECONDS)); assertFalse(registered.isDone());
            var closeEntered = new CountDownLatch(1);
            var closed = executor.submit(() -> { closeEntered.countDown(); service.close(); });
            assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
            assertFalse(platform.calls.contains("release")); assertFalse(closed.isDone());
            platform.allowStart.countDown();
            assertNotNull(registered.get(5, TimeUnit.SECONDS)); closed.get(5, TimeUnit.SECONDS);
            assertEquals(List.of("queue", "create", "start", "stop", "invalidate", "drain", "release", "releaseQueue"), platform.calls);
        } finally { platform.allowStart.countDown(); service.close(); }
    }
    @Test void closeWaitsForCallbacksBeforeReleasingStreamsAndQueue() throws Exception {
        var platform = new Fake(); var service = service(platform);
        var key = service.register(new WatchablePath(temp.toRealPath()), EVENTS);
        platform.draining = new CountDownLatch(1); platform.allowDrain = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var closed = executor.submit(() -> { service.close(); });
            assertTrue(platform.draining.await(5, TimeUnit.SECONDS));
            assertFalse(closed.isDone()); assertFalse(platform.calls.contains("release"));
            // A queued callback can still enqueue events while close is waiting at the barrier.
            key.signalEvent(ENTRY_MODIFY, temp.resolve("Caller.kt"));
            platform.allowDrain.countDown(); closed.get(5, TimeUnit.SECONDS);
            assertThrows(ClosedWatchServiceException.class, service::poll);
            assertThrows(ClosedWatchServiceException.class, () -> service.register(new WatchablePath(temp), EVENTS));
            service.close(); assertEquals(1, Collections.frequency(platform.calls, "releaseQueue"));
        } finally { platform.allowDrain.countDown(); service.close(); }
    }
    @Test void failedStartReleasesStreamAndAllowsRetry() throws Exception {
        var platform = new Fake(); platform.startResult = false;
        try (var service = service(platform)) {
            assertThrows(IOException.class, () -> service.register(new WatchablePath(temp), EVENTS));
            assertEquals(List.of("queue", "create", "start", "invalidate", "drain", "release"), platform.calls);
            platform.startResult = true;
            assertNotNull(service.register(new WatchablePath(temp), EVENTS));
        }
        assertEquals(2, Collections.frequency(platform.calls, "release"));
        assertEquals(1, Collections.frequency(platform.calls, "releaseQueue"));
    }
    @Test void thrownStartFailureIsPreservedAndResourcesReleased() throws Exception {
        var platform = new Fake(); platform.startFailure = new IllegalStateException("start failure");
        try (var service = service(platform)) {
            assertSame(platform.startFailure, assertThrows(IllegalStateException.class,
                    () -> service.register(new WatchablePath(temp), EVENTS)));
            assertTrue(platform.calls.contains("release"));
        }
    }
    @Test void missingNativeHandlesFailWithoutRegisteringStreams() throws Exception {
        var missingQueue = new Fake(); missingQueue.missingQueue = true;
        assertThrows(IllegalStateException.class, () -> service(missingQueue));
        var missingStream = new Fake(); missingStream.missingStream = true;
        try (var service = service(missingStream)) {
            assertThrows(IOException.class, () -> service.register(new WatchablePath(temp), EVENTS));
            assertFalse(missingStream.calls.contains("start"));
        }
        assertFalse(missingStream.calls.contains("release"));
        assertEquals(1, Collections.frequency(missingStream.calls, "releaseQueue"));
    }
    @Test void parentSubscriptionCoversChildrenAndAllIndependentRootsAreReleased() throws Exception {
        var platform = new Fake();
        Path parent = Files.createDirectory(temp.resolve("parent"));
        Path child = Files.createDirectory(parent.resolve("child"));
        Path other = Files.createDirectory(temp.resolve("other"));
        try (var service = service(platform)) {
            service.register(new WatchablePath(parent), EVENTS);
            service.register(new WatchablePath(parent), EVENTS);
            service.register(new WatchablePath(child), EVENTS);
            service.register(new WatchablePath(other), EVENTS);
            assertEquals(2, Collections.frequency(platform.calls, "create"));
        }
        assertEquals(2, Collections.frequency(platform.calls, "stop"));
        assertEquals(2, Collections.frequency(platform.calls, "invalidate"));
        assertEquals(2, Collections.frequency(platform.calls, "release"));
        assertEquals(1, Collections.frequency(platform.calls, "drain"));
    }
    @Test void nonexistentRootDoesNotAllocateNativeStream() throws Exception {
        var platform = new Fake();
        try (var service = service(platform)) {
            assertThrows(IOException.class, () -> service.register(new WatchablePath(temp.resolve("missing")), EVENTS));
            assertFalse(platform.calls.contains("create"));
        }
    }
    @Test void eventDuringStartIsQueuedBeforeRegistrationReturns() throws Exception {
        var platform = new Fake(); Path file = temp.toRealPath().resolve("Caller.kt");
        Files.writeString(file, "fun run() = 1");
        platform.duringStart = () -> {
            try { Files.writeString(file, "fun run() = 2"); }
            catch (IOException failure) { throw new AssertionError(failure); }
            platform.callbacks.getFirst().invoke(new FSEventStreamRef(), null, new NativeLong(1),
                    new StringArray(new String[]{file.toString()}), null, null);
        };
        try (var service = service(platform)) {
            var key = service.register(new WatchablePath(temp.toRealPath()), EVENTS);
            assertSame(key, service.poll());
            assertTrue(key.pollEvents().stream().anyMatch(event -> event.kind() == ENTRY_MODIFY && file.equals(event.context())));
        }
    }
    @Test void deletingRootInvalidatesAllKeysWithoutDrainingInsideCallback() throws Exception {
        var platform = new Fake();
        Path first = Files.createDirectory(temp.resolve("first")).toRealPath();
        Path second = Files.createDirectory(temp.resolve("second")).toRealPath();
        var config = new MacOSXListeningWatchService.Config() {
            public io.methvin.watcher.visitor.FileTreeVisitor fileTreeVisitor() {
                return (path, onDirectory, onFile) -> {
                    if (Files.exists(path)) io.methvin.watcher.visitor.FileTreeVisitor.DEFAULT_FILE_TREE_VISITOR
                            .recursiveVisitFiles(path, onDirectory, onFile);
                };
            }
        };
        try (var service = new QuillMacOSWatchService(config, platform)) {
            var firstKey = service.register(new WatchablePath(first), EVENTS);
            var secondKey = service.register(new WatchablePath(second), EVENTS);
            Files.delete(first);
            platform.callbacks.getFirst().invoke(new FSEventStreamRef(), null, new NativeLong(1),
                    new StringArray(new String[]{first.toString()}), null, null);
            assertFalse(firstKey.isValid()); assertFalse(secondKey.isValid());
            assertFalse(platform.calls.contains("drain")); assertFalse(platform.calls.contains("release"));
            assertThrows(IOException.class, () -> service.register(new WatchablePath(second), EVENTS));
        }
        assertEquals(2, Collections.frequency(platform.calls, "release"));
    }

}
