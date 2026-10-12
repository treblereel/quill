package org.treblereel.mcp.mcp;

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

/** Event subscriptions only; the coordinator owns debounce and the polling fallback. */
final class KotlinDirectoryWatcher implements AutoCloseable {
    interface Factory {
        DirectoryWatcher create(List<Path> roots, DirectoryChangeListener listener, FileTreeVisitor visitor) throws IOException;
    }
    static final Factory DEFAULT = KotlinDirectoryWatcher::create;
    private final Factory factory;
    private final Runnable changed, unavailable;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "quill-k2-directory-events"); thread.setDaemon(true); return thread;
    });
    private volatile Predicate<Path> relevant = path -> false;
    private volatile boolean closed, failed;
    private volatile long generation;
    private Set<Path> roots = Set.of();
    static final long HEALTH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);
    static final long HEALTH_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    static final long INITIAL_RETRY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long MAX_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
    private volatile Path healthDirectory, healthFile;
    private volatile boolean healthPending;
    private volatile long eventRevision;
    private long nextProbe, probeDeadline, retryAfter;
    private volatile long retryDelay = INITIAL_RETRY_NANOS;
    private final java.util.function.LongSupplier clock;
    private volatile DirectoryWatcher subscription;

    KotlinDirectoryWatcher(Factory factory, Runnable changed, Runnable unavailable) {
        this(factory, changed, unavailable, System::nanoTime);
    }

    KotlinDirectoryWatcher(Factory factory, Runnable changed, Runnable unavailable, java.util.function.LongSupplier clock) {
        this.factory = factory; this.changed = changed; this.unavailable = unavailable; this.clock = clock;
    }

    synchronized boolean configure(Set<Path> directories, Predicate<Path> filter) {
        relevant = filter;
        if (closed || failed) return false;
        var minimal = new ArrayList<Path>();
        directories.stream().sorted(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString))
                .forEach(path -> { if (minimal.stream().noneMatch(path::startsWith)) minimal.add(path); });
        Set<Path> desired = Set.copyOf(minimal);
        if (subscription != null && desired.equals(roots)) return true;
        long owner = ++generation;
        closeSubscription(); roots = desired; healthPending = false;
        try {
            var listener = new DirectoryChangeListener() {
                @Override public void onEvent(DirectoryChangeEvent event) {
                    if (closed || failed || generation != owner) return;
                    if (event.path() != null && healthDirectory != null
                            && event.path().toAbsolutePath().normalize().startsWith(healthDirectory)) {
                        if (event.path().toAbsolutePath().normalize().equals(healthFile)) {
                            healthPending = false; retryDelay = INITIAL_RETRY_NANOS;
                        }
                        return;
                    }
                    if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW
                            || event.path() != null && relevant.test(event.path().toAbsolutePath().normalize())) {
                        eventRevision++; changed.run();
                    }
                }
                @Override public void onException(Exception error) { throw new IllegalStateException("Input watcher failed", error); }
            };
            if (healthDirectory == null) {
                healthDirectory = Files.createTempDirectory("quill-watch-health-").toRealPath();
                healthFile = healthDirectory.resolve("heartbeat");
                Files.writeString(healthFile, "initial");
            }
            var paths = new ArrayList<>(minimal);
            if (paths.stream().noneMatch(healthDirectory::startsWith)) paths.add(healthDirectory);
            subscription = factory.create(paths, listener, visitor());
            nextProbe = clock.getAsLong() + HEALTH_INTERVAL_NANOS;
            subscription.watchAsync(executor).whenComplete((ignored, error) -> fail(owner));
        } catch (Exception | LinkageError error) { fail(owner); }
        return !failed;
    }

    private void fail(long owner) {
        synchronized (this) {
            if (closed || failed || generation != owner) return;
            failed = true; healthPending = false; closeSubscription();
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
            long now = clock.getAsLong(); owner = generation;
            if (!healthPending) {
                if (now < nextProbe) return;
                healthPending = true; probeDeadline = now + HEALTH_TIMEOUT_NANOS;
                nextProbe = now + HEALTH_INTERVAL_NANOS;
                try { Files.writeString(healthFile, Long.toString(now)); return; }
                catch (IOException | SecurityException failure) { /* Activate fallback below. */ }
            } else if (now < probeDeadline) return;
        }
        fail(owner);
    }

    void deliveryMissing() { fail(generation); }
    long eventRevision() { return eventRevision; }
    Path healthFile() { return healthFile; }
    private boolean included(Path path) {
        return healthDirectory != null && path.startsWith(healthDirectory) || relevant.test(path);
    }

    private FileTreeVisitor visitor() {
        return (start, onDirectory, onFile) -> Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (!included(path)) return FileVisitResult.SKIP_SUBTREE;
                onDirectory.call(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (included(path)) onFile.call(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) {
                // A delete/atomic save can race traversal; foreground analysis verifies content.
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static DirectoryWatcher create(List<Path> roots, DirectoryChangeListener listener, FileTreeVisitor visitor) throws IOException {
        WatchService service;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            service = new QuillMacOSWatchService(new MacOSXListeningWatchService.Config() {
                @Override public double latency() { return .02; }
                @Override public FileHasher fileHasher() { return null; }
                @Override public FileTreeVisitor fileTreeVisitor() { return visitor; }
            });
        } else service = FileSystems.getDefault().newWatchService();
        try {
            return DirectoryWatcher.builder().paths(roots).listener(listener).watchService(service)
                    .fileTreeVisitor(visitor).fileHashing(false).logger(NOPLogger.NOP_LOGGER).build();
        } catch (IOException | RuntimeException | LinkageError error) {
            service.close(); throw error;
        }
    }

    synchronized Set<Path> roots() { return roots; }
    boolean active() { return !closed && !failed && subscription != null; }
    String backend() {
        return active() ? System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                ? "directory_watcher_fsevents" : "directory_watcher_watch_service" : "metadata_poll_100ms";
    }
    private void closeSubscription() {
        if (subscription == null) return;
        try { subscription.close(); } catch (IOException | RuntimeException | LinkageError ignored) { }
        subscription = null;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; generation++; closeSubscription(); executor.shutdownNow();
        if (healthFile != null) try { Files.deleteIfExists(healthFile); } catch (IOException ignored) { }
        if (healthDirectory != null) try { Files.deleteIfExists(healthDirectory); } catch (IOException ignored) { }
    }
}
