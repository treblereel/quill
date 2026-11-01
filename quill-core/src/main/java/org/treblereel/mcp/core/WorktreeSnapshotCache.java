package org.treblereel.mcp.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Short-lived, single-flight cache for expensive live Git worktree inspection. */
public final class WorktreeSnapshotCache {

    static final Duration DEFAULT_TTL = Duration.ofMillis(500);
    private static final WorktreeSnapshotCache SHARED = new WorktreeSnapshotCache(
            DEFAULT_TTL, WorktreeInspector::inspect, System::nanoTime);
    private record Entry(WorktreeInspector.Snapshot snapshot, long expiresAtNanos) {}

    private final long ttlNanos;
    private final Function<Path, WorktreeInspector.Snapshot> inspector;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Path, Entry> entries = new ConcurrentHashMap<>();

    WorktreeSnapshotCache(Duration ttl,
            Function<Path, WorktreeInspector.Snapshot> inspector, LongSupplier clock) {
        this.ttlNanos = Objects.requireNonNull(ttl, "ttl").toNanos();
        if (ttlNanos <= 0) throw new IllegalArgumentException("ttl must be positive");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static WorktreeSnapshotCache shared() {
        return SHARED;
    }

    public WorktreeInspector.Snapshot get(Path projectRoot) {
        Path key = canonical(projectRoot);
        long now = clock.getAsLong();
        if (entries.size() > 64) {
            entries.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtNanos());
        }
        Entry cached = entries.get(key);
        if (cached != null && now < cached.expiresAtNanos()) return cached.snapshot();
        return entries.compute(key, (ignored, current) -> {
            long refreshedAt = clock.getAsLong();
            if (current != null && refreshedAt < current.expiresAtNanos()) return current;
            WorktreeInspector.Snapshot snapshot = inspector.apply(key);
            return new Entry(snapshot, clock.getAsLong() + ttlNanos);
        }).snapshot();
    }

    /** Refreshes synchronously for correctness-sensitive workflow gates. */
    public WorktreeInspector.Snapshot refresh(Path projectRoot) {
        Path key = canonical(projectRoot);
        WorktreeInspector.Snapshot snapshot = inspector.apply(key);
        entries.put(key, new Entry(snapshot, clock.getAsLong() + ttlNanos));
        return snapshot;
    }

    private static Path canonical(Path root) {
        try {return root.toRealPath();}catch(java.io.IOException missing){return root.toAbsolutePath().normalize();}
    }

    public void invalidate(Path projectRoot) {
        if (projectRoot != null) entries.remove(canonical(projectRoot));
    }
}
