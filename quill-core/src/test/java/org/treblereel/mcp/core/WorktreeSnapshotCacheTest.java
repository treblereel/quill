package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeSnapshotCacheTest {

    @TempDir Path tempDir;

    @Test
    void reusesSnapshotUntilTtlExpires() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger inspections = new AtomicInteger();
        WorktreeSnapshotCache cache = new WorktreeSnapshotCache(
                Duration.ofMillis(500), ignored -> {
                    inspections.incrementAndGet();
                    return WorktreeInspector.Snapshot.empty();
                }, clock::get);

        WorktreeInspector.Snapshot first = cache.get(tempDir);
        clock.set(Duration.ofMillis(499).toNanos());
        assertSame(first, cache.get(tempDir));
        assertEquals(1, inspections.get());

        clock.set(Duration.ofMillis(500).toNanos());
        cache.get(tempDir);
        assertEquals(2, inspections.get());
    }

    @Test
    void expiredSnapshotIsServedWhileOneRefreshRuns() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger inspections = new AtomicInteger();
        List<Runnable> refreshes = new ArrayList<>();
        WorktreeSnapshotCache cache = new WorktreeSnapshotCache(
                Duration.ofMillis(500), ignored -> snapshot("commit-" + inspections.incrementAndGet()),
                clock::get, refreshes::add);

        WorktreeInspector.Snapshot first = cache.get(tempDir);
        clock.set(Duration.ofMillis(500).toNanos());
        for (int i = 0; i < 16; i++) {
            assertSame(first, cache.get(tempDir));
        }

        assertEquals(1, inspections.get());
        assertEquals(1, refreshes.size());
        refreshes.getFirst().run();
        assertEquals("commit-2", cache.get(tempDir).currentCommit());
    }

    @Test
    void concurrentMissesShareOneInspection() throws Exception {
        AtomicInteger inspections = new AtomicInteger();
        CountDownLatch inspectionStarted = new CountDownLatch(1);
        CountDownLatch releaseInspection = new CountDownLatch(1);
        WorktreeSnapshotCache cache = new WorktreeSnapshotCache(
                Duration.ofSeconds(1), ignored -> {
                    inspections.incrementAndGet();
                    inspectionStarted.countDown();
                    try {
                        releaseInspection.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return WorktreeInspector.Snapshot.empty();
                }, System::nanoTime);

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<java.util.concurrent.Future<WorktreeInspector.Snapshot>> requests =
                    new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                requests.add(executor.submit(() -> cache.get(tempDir)));
            }
            inspectionStarted.await(1, TimeUnit.SECONDS);
            releaseInspection.countDown();
            for (var request : requests) request.get(2, TimeUnit.SECONDS);
        }

        assertEquals(1, inspections.get());
    }

    @Test
    void invalidationForcesRefresh() {
        AtomicInteger inspections = new AtomicInteger();
        WorktreeSnapshotCache cache = new WorktreeSnapshotCache(
                Duration.ofSeconds(1), ignored -> {
                    inspections.incrementAndGet();
                    return WorktreeInspector.Snapshot.empty();
                }, System::nanoTime);

        cache.get(tempDir);
        cache.invalidate(tempDir);
        cache.get(tempDir);

        assertEquals(2, inspections.get());
    }

    private static WorktreeInspector.Snapshot snapshot(String commit) {
        WorktreeInspector.Snapshot empty = WorktreeInspector.Snapshot.empty();
        return new WorktreeInspector.Snapshot(commit, null, List.of(), empty.fingerprint(),
                List.of(), empty.structuralFingerprint());
    }
}
