package org.treblereel.mcp.mcp;

import io.methvin.watcher.*;
import io.methvin.watcher.hashing.FileHasher;
import io.methvin.watcher.visitor.FileTreeVisitor;
import io.methvin.watchservice.MacOSXListeningWatchService;
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
    private volatile DirectoryWatcher subscription;

    KotlinDirectoryWatcher(Factory factory, Runnable changed, Runnable unavailable) {
        this.factory = factory; this.changed = changed; this.unavailable = unavailable;
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
        closeSubscription(); roots = desired;
        try {
            var listener = new DirectoryChangeListener() {
                @Override public void onEvent(DirectoryChangeEvent event) {
                    if (closed || failed || generation != owner) return;
                    if (event.eventType() == DirectoryChangeEvent.EventType.OVERFLOW
                            || event.path() != null && relevant.test(event.path().toAbsolutePath().normalize())) changed.run();
                }
                @Override public void onException(Exception error) { throw new IllegalStateException("Input watcher failed", error); }
            };
            subscription = factory.create(minimal, listener, visitor());
            subscription.watchAsync(executor).whenComplete((ignored, error) -> fail(owner));
        } catch (Exception | LinkageError error) { fail(owner); }
        return !failed;
    }

    private void fail(long owner) {
        synchronized (this) {
            if (closed || failed || generation != owner) return;
            failed = true; closeSubscription();
        }
        // Never call the coordinator while holding a lock on an event thread.
        unavailable.run();
    }

    private FileTreeVisitor visitor() {
        return (start, onDirectory, onFile) -> Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                if (!relevant.test(path)) return FileVisitResult.SKIP_SUBTREE;
                onDirectory.call(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (relevant.test(path)) onFile.call(path); return FileVisitResult.CONTINUE;
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
            service = new MacOSXListeningWatchService(new MacOSXListeningWatchService.Config() {
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
    }
}
