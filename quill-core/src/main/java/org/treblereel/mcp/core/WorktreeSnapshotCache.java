package org.treblereel.mcp.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Short-lived, single-flight cache for expensive live Git worktree inspection. */
public final class WorktreeSnapshotCache {

    static final Duration DEFAULT_TTL = Duration.ofMillis(500);
    private static final Executor REFRESH_EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "quill-worktree-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private static final WorktreeSnapshotCache SHARED = new WorktreeSnapshotCache(
            DEFAULT_TTL, WorktreeInspector::inspect, System::nanoTime, REFRESH_EXECUTOR);

    private record Entry(WorktreeInspector.Snapshot snapshot, long expiresAtNanos,
            AtomicBoolean refreshing) {
        private Entry(WorktreeInspector.Snapshot snapshot, long expiresAtNanos) {
            this(snapshot, expiresAtNanos, new AtomicBoolean());
        }
    }

    private final long ttlNanos;
    private final Function<Path, WorktreeInspector.Snapshot> inspector;
    private final LongSupplier clock;
    private final Executor refreshExecutor;
    private final ConcurrentHashMap<Path, Entry> entries = new ConcurrentHashMap<>();

    WorktreeSnapshotCache(Duration ttl,
            Function<Path, WorktreeInspector.Snapshot> inspector, LongSupplier clock) {
        this(ttl, inspector, clock, Runnable::run);
    }

    WorktreeSnapshotCache(Duration ttl, Function<Path, WorktreeInspector.Snapshot> inspector,
            LongSupplier clock, Executor refreshExecutor) {
        this.ttlNanos = Objects.requireNonNull(ttl, "ttl").toNanos();
        if (ttlNanos <= 0) throw new IllegalArgumentException("ttl must be positive");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshExecutor = Objects.requireNonNull(refreshExecutor, "refreshExecutor");
    }

    public static WorktreeSnapshotCache shared() {
        return SHARED;
    }

    public WorktreeInspector.Snapshot get(Path projectRoot) {
        Path key = projectRoot.toAbsolutePath().normalize();
        long now = clock.getAsLong();
        if (entries.size() > 64) {
            entries.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtNanos());
        }
        Entry cached = entries.get(key);
        if (cached != null && now < cached.expiresAtNanos()) return cached.snapshot();
        if (cached != null) {
            refreshAsync(key, cached);
            return cached.snapshot();
        }

        return entries.compute(key, (ignored, current) -> {
            long refreshedAt = clock.getAsLong();
            if (current != null) return current;
            WorktreeInspector.Snapshot snapshot = inspector.apply(key);
            return new Entry(snapshot, clock.getAsLong() + ttlNanos);
        }).snapshot();
    }

    private void refreshAsync(Path key, Entry stale) {
        if (!stale.refreshing().compareAndSet(false, true)) return;
        try {
            refreshExecutor.execute(() -> {
                try {
                    WorktreeInspector.Snapshot snapshot = inspector.apply(key);
                    entries.replace(key, stale,
                            new Entry(snapshot, clock.getAsLong() + ttlNanos));
                } catch (RuntimeException ignored) {
                    // Keep serving the last good snapshot and retry after another request.
                } finally {
                    stale.refreshing().set(false);
                }
            });
        } catch (RuntimeException rejected) {
            stale.refreshing().set(false);
        }
    }

    public void invalidate(Path projectRoot) {
        if (projectRoot != null) entries.remove(projectRoot.toAbsolutePath().normalize());
    }
}
