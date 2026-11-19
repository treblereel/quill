package org.treblereel.mcp.mcp.project;

import io.methvin.watcher.*;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watcher.visitor.FileTreeVisitor;
import io.methvin.watchservice.MacOSXListeningWatchService;
import io.methvin.watchservice.QuillMacOSWatchService;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.slf4j.helpers.NOPLogger;
import org.treblereel.mcp.diagnostics.QuillLog;

/** Event subscriptions only; the coordinator owns debounce and recovery. */
final class KotlinDirectoryWatcher implements AutoCloseable {
    interface Factory {
        DirectoryWatcher create(
                List<Path> roots,
                DirectoryChangeListener listener,
                FileTreeVisitor visitor,
                java.util.function.Consumer<Runnable> deliveryBarrier)
                throws IOException;
    }

    static final Factory DEFAULT = KotlinDirectoryWatcher::create;
    private final Factory factory;
    private final java.util.function.Consumer<Path> changed;
    private final Runnable unavailable;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(
                    task -> {
                        var thread = new Thread(task, "quill-k2-directory-events");
                        thread.setDaemon(true);
                        return thread;
                    });
    private volatile Predicate<Path> relevant = path -> false;
    private volatile boolean closed, failed;
    private volatile long generation;
    private Set<Path> roots = Set.of();
    static final long HEALTH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

    /**
     * How long a heartbeat may stay undelivered before the stream counts as dead. A loaded
     * fseventsd delivered events 1.8 s late at the median and 5.2 s at worst without losing any,
     * and replacing the subscription only adds delay, so this sits well above real latency, not
     * near the usual one.
     */
    static final long HEALTH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

    // Leave time within the default MCP tool budget for preparation and query work.
    static final long DELIVERY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);
    static final long INITIAL_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long MAX_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
    private volatile Path healthDirectory, healthFile, deliveryFile;
    private long deliverySequence;
    private long deliveryDeadline;
    private volatile boolean healthPending;
    private volatile long eventRevision;
    private volatile CompletableFuture<Void> delivery;
    private CompletableFuture<Void> nextDelivery;
    private long nextProbe, probeDeadline, retryAfter;
    private volatile long retryDelay = INITIAL_RETRY_NANOS;
    private final java.util.function.LongSupplier clock;
    private final long healthTimeoutNanos;
    private volatile DirectoryWatcher subscription;
    private Map<Path, Object> rootIdentities = Map.of();
    private Runnable nativeBarrier = () -> {};

    KotlinDirectoryWatcher(Factory factory, Runnable changed, Runnable unavailable) {
        this(factory, changed, unavailable, System::nanoTime);
    }

    KotlinDirectoryWatcher(
            Factory factory,
            Runnable changed,
            Runnable unavailable,
            java.util.function.LongSupplier clock) {
        this(factory, path -> changed.run(), unavailable, clock);
    }

    KotlinDirectoryWatcher(
            Factory factory, java.util.function.Consumer<Path> changed, Runnable unavailable) {
        this(factory, changed, unavailable, System::nanoTime);
    }

    KotlinDirectoryWatcher(
            Factory factory,
            Runnable changed,
            Runnable unavailable,
            java.time.Duration healthTimeout) {
        this(
                factory,
                path -> changed.run(),
                unavailable,
                System::nanoTime,
                healthTimeout.toNanos());
    }

    private KotlinDirectoryWatcher(
            Factory factory,
            java.util.function.Consumer<Path> changed,
            Runnable unavailable,
            java.util.function.LongSupplier clock) {
        this(factory, changed, unavailable, clock, HEALTH_TIMEOUT_NANOS);
    }

    private KotlinDirectoryWatcher(
            Factory factory,
            java.util.function.Consumer<Path> changed,
            Runnable unavailable,
            java.util.function.LongSupplier clock,
            long healthTimeoutNanos) {
        this.factory = factory;
        this.changed = changed;
        this.unavailable = unavailable;
        this.clock = clock;
        this.healthTimeoutNanos = healthTimeoutNanos;
    }

    synchronized boolean configure(Set<Path> directories, Predicate<Path> filter) {
        relevant = filter;
        if (closed || failed) return false;
        var minimal = new ArrayList<Path>();
        directories.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
                .forEach(
                        path -> {
                            if (minimal.stream().noneMatch(path::startsWith)) minimal.add(path);
                        });
        Set<Path> desired = Set.copyOf(minimal);
        if (subscription != null && desired.equals(roots)) return true;
        long owner = ++generation;
        closeSubscription();
        failDeliveries();
        roots = desired;
        healthPending = false;
        try {
            var listener =
                    new DirectoryChangeListener() {
                        @Override
                        public void onEvent(DirectoryChangeEvent event) {
                            if (closed || failed || generation != owner) return;
                            if (event.path() != null
                                    && healthDirectory != null
                                    && event.path()
                                            .toAbsolutePath()
                                            .normalize()
                                            .startsWith(healthDirectory)) {
                                CompletableFuture<Void> pending = null;
                                CompletableFuture<Void> next = null;
                                synchronized (KotlinDirectoryWatcher.this) {
                                    if (closed || failed || generation != owner) return;
                                    Path path = event.path().toAbsolutePath().normalize();
                                    if (path.equals(healthFile) || path.equals(deliveryFile)) {
                                        healthPending = false;
                                        retryDelay = INITIAL_RETRY_NANOS;
                                    }
                                    if (path.equals(deliveryFile)) {
                                        pending = delivery;
                                        clearDeliveryFile();
                                        next = nextDelivery;
                                        nextDelivery = null;
                                        delivery = next;
                                    }
                                }
                                if (pending != null) pending.complete(null);
                                if (next != null)
                                    synchronized (KotlinDirectoryWatcher.this) {
                                        // A slow flush for the next cohort must not delay the
                                        // acknowledged one.
                                        if (generation == owner && delivery == next)
                                            startDelivery(next);
                                    }
                                return;
                            }
                            if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW
                                    || event.path() != null
                                            && relevant.test(
                                                    event.path().toAbsolutePath().normalize())) {
                                eventRevision++;
                                changed.accept(
                                        event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW
                                                ? null
                                                : event.path().toAbsolutePath().normalize());
                            }
                            // Linux reports no event for a watched root itself, and a root
                            // recreated at once
                            // can reuse its inode; the deletions under it arrive after the root is
                            // gone.
                            if (event.eventType() == DirectoryChangeEvent.EventType.DELETE
                                    && desired.stream()
                                            .anyMatch(root -> !Files.isDirectory(root))) {
                                fail(owner, "a watched root was deleted");
                            }
                        }

                        @Override
                        public void onException(Exception error) {
                            throw new IllegalStateException("Input watcher failed", error);
                        }
                    };
            if (healthDirectory == null) {
                healthDirectory = Files.createTempDirectory("quill-watch-health-").toRealPath();
                healthFile = healthDirectory.resolve("heartbeat");
                Files.writeString(healthFile, "initial");
            }
            var paths = new ArrayList<>(minimal);
            if (paths.stream().noneMatch(healthDirectory::startsWith)) paths.add(healthDirectory);
            subscription =
                    factory.create(paths, listener, visitor(), barrier -> nativeBarrier = barrier);
            rootIdentities = identities(desired);
            nextProbe = clock.getAsLong() + HEALTH_INTERVAL_NANOS;
            subscription
                    .watchAsync(executor)
                    .whenComplete(
                            (ignored, error) ->
                                    fail(
                                            owner,
                                            "subscription ended"
                                                    + (error == null
                                                            ? ""
                                                            : ": " + rootCause(error))));
        } catch (Exception | LinkageError error) {
            fail(owner, "subscription failed to start: " + error);
        }
        return !failed;
    }

    /** Flushes ordinary queued events before a correctness-sensitive prepared read. */
    synchronized CompletableFuture<Void> awaitDelivery() {
        if (!active())
            return CompletableFuture.failedFuture(new IOException("Input watcher unavailable"));
        if (delivery != null) {
            // The in-flight marker may predate these reads. All of them can use the next marker.
            if (nextDelivery == null) nextDelivery = new CompletableFuture<>();
            return nextDelivery.copy();
        }
        var pending = new CompletableFuture<Void>();
        startDelivery(pending);
        return pending.copy();
    }

    private void startDelivery(CompletableFuture<Void> pending) {
        delivery = pending;
        try {
            // A heartbeat on a separate FSEvents stream cannot order the project streams.
            nativeBarrier.run();
            deliveryFile = healthDirectory.resolve("read-" + ++deliverySequence);
            Files.writeString(deliveryFile, "barrier");
            deliveryDeadline = clock.getAsLong() + DELIVERY_TIMEOUT_NANOS;
        } catch (IOException | RuntimeException | LinkageError failure) {
            failDeliveries(failure);
        }
    }

    private void fail(long owner, String reason) {
        synchronized (this) {
            if (closed || failed || generation != owner) return;
            QuillLog.info(
                    "Input watcher unavailable ("
                            + reason
                            + "); retrying in "
                            + TimeUnit.NANOSECONDS.toMillis(retryDelay)
                            + " ms");
            failed = true;
            healthPending = false;
            closeSubscription();
            failDeliveries();
            retryAfter = clock.getAsLong() + retryDelay;
            retryDelay = Math.min(MAX_RETRY_NANOS, retryDelay * 2);
        }
        // Never call the coordinator while holding a lock on an event thread.
        unavailable.run();
    }

    // Called with the coordinator state held, using the same lock order as configure.
    synchronized boolean recover() {
        if (closed || !failed || clock.getAsLong() < retryAfter) return false;
        failed = false;
        return configure(roots, relevant);
    }

    void checkHealth() {
        long owner;
        synchronized (this) {
            if (!active()) return;
            long now = clock.getAsLong();
            owner = generation;
            // An individual marker can be lost even while heartbeats arrive.
            if (deliveryFile != null && now >= deliveryDeadline) {
                failDeliveries(new TimeoutException("Input read barrier expired"));
            }
            // Linux inotify watches an inode, not a path: a watched root deleted and recreated
            // leaves
            // a live-looking subscription that reports nothing, while the heartbeat directory still
            // answers. FSEvents follows the path, so this only re-subscribes on macOS.
            if (!rootIdentities.equals(identities(rootIdentities.keySet()))) {
                fail(owner, "a watched root was removed or replaced");
                return;
            }
            if (!healthPending) {
                if (now < nextProbe) return;
                healthPending = true;
                probeDeadline = now + healthTimeoutNanos;
                nextProbe = now + HEALTH_INTERVAL_NANOS;
                try {
                    Files.writeString(healthFile, Long.toString(now));
                    return;
                } catch (IOException | SecurityException failure) {
                    fail(owner, "heartbeat not written: " + failure);
                    return;
                }
            } else if (now < probeDeadline) return;
        }
        fail(
                owner,
                "heartbeat not delivered within "
                        + TimeUnit.NANOSECONDS.toMillis(healthTimeoutNanos)
                        + " ms");
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    long eventRevision() {
        return eventRevision;
    }

    Path healthFile() {
        return healthFile;
    }

    Path deliveryFile() {
        return deliveryFile;
    }

    private void clearDeliveryFile() {
        Path path = deliveryFile;
        deliveryFile = null;
        if (path != null)
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
    }

    private void failDeliveries() {
        failDeliveries(new IOException("Input watcher subscription ended"));
    }

    private void failDeliveries(Throwable failure) {
        var pending = delivery;
        var next = nextDelivery;
        delivery = null;
        nextDelivery = null;
        clearDeliveryFile();
        if (pending != null) pending.completeExceptionally(failure);
        if (next != null) next.completeExceptionally(failure);
    }

    private boolean included(Path path) {
        return healthDirectory != null && path.startsWith(healthDirectory) || relevant.test(path);
    }

    private FileTreeVisitor visitor() {
        return (start, onDirectory, onFile) ->
                Files.walkFileTree(
                        start,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(
                                    Path path, BasicFileAttributes attributes) throws IOException {
                                if (!included(path)) return FileVisitResult.SKIP_SUBTREE;
                                onDirectory.call(path);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(
                                    Path path, BasicFileAttributes attributes) throws IOException {
                                if (included(path)) onFile.call(path);
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path path, IOException error) {
                                // A delete/atomic save can race traversal; foreground analysis
                                // verifies content.
                                return FileVisitResult.CONTINUE;
                            }
                        });
    }

    private static DirectoryWatcher create(
            List<Path> roots,
            DirectoryChangeListener listener,
            FileTreeVisitor visitor,
            java.util.function.Consumer<Runnable> deliveryBarrier)
            throws IOException {
        WatchService service;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            service =
                    new QuillMacOSWatchService(
                            new MacOSXListeningWatchService.Config() {
                                @Override
                                public double latency() {
                                    return .02;
                                }

                                @Override
                                public FileHasher fileHasher() {
                                    return null;
                                }

                                @Override
                                public FileTreeVisitor fileTreeVisitor() {
                                    return visitor;
                                }
                            });
        } else service = FileSystems.getDefault().newWatchService();
        deliveryBarrier.accept(
                service instanceof QuillMacOSWatchService mac ? mac::flushPendingEvents : () -> {});
        try {
            return DirectoryWatcher.builder()
                    .paths(roots)
                    .listener(listener)
                    .watchService(service)
                    .fileTreeVisitor(visitor)
                    .fileHashing(false)
                    .logger(NOPLogger.NOP_LOGGER)
                    .build();
        } catch (IOException | RuntimeException | LinkageError error) {
            service.close();
            throw error;
        }
    }

    /**
     * The file key of each root, or its absence; some file systems have no key, so creation time
     * stands in.
     */
    private static Map<Path, Object> identities(Collection<Path> paths) {
        var result = new HashMap<Path, Object>();
        for (Path path : paths) {
            Object identity;
            try {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                identity =
                        attributes.fileKey() != null
                                ? attributes.fileKey()
                                : attributes.creationTime();
            } catch (IOException | SecurityException missing) {
                identity = "missing";
            }
            result.put(path, identity);
        }
        return Map.copyOf(result);
    }

    synchronized Set<Path> roots() {
        return roots;
    }

    boolean active() {
        return !closed && !failed && subscription != null;
    }

    String backend() {
        return active()
                ? System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                        ? "directory_watcher_fsevents"
                        : "directory_watcher_watch_service"
                : "unavailable";
    }

    private void closeSubscription() {
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (IOException | RuntimeException | LinkageError ignored) {
        }
        subscription = null;
        nativeBarrier = () -> {};
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        closeSubscription();
        executor.shutdownNow();
        failDeliveries();
        if (healthFile != null)
            try {
                Files.deleteIfExists(healthFile);
            } catch (IOException ignored) {
            }
        if (healthDirectory != null)
            try {
                Files.deleteIfExists(healthDirectory);
            } catch (IOException ignored) {
            }
    }
}
