package org.treblereel.mcp.kotlin;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class SnapshotCacheTest {
    @Test void lruReplacementAndRepeatedClearDisposeExactlyOnce() {
        var disposed = new ArrayList<String>();
        var cache = new SnapshotCache<String, String>(2, disposed::add, () -> false);
        cache.put("a", "a1"); cache.put("b", "b1"); assertEquals("a1", cache.get("a"));
        cache.put("c", "c1"); assertNull(cache.get("b")); assertEquals(List.of("b1"), disposed);
        cache.put("a", "a2"); assertEquals(List.of("b1", "a1"), disposed);
        cache.clear(); cache.clear(); assertEquals(Set.of("a1", "a2", "b1", "c1"), new HashSet<>(disposed));
        assertEquals(4, disposed.size()); assertEquals(1, cache.evictions());
    }
    @Test void heapPressureRetainsOnlyActiveModelAndEvictsBeforeBuild() {
        var disposed = new ArrayList<String>(); var pressure = new AtomicBoolean();
        var cache = new SnapshotCache<String, String>(2, disposed::add, pressure::get);
        cache.put("a", "a"); cache.put("b", "b"); pressure.set(true);
        cache.pruneExcept("a"); assertNull(cache.get("b")); assertEquals("a", cache.get("a"));
        cache.prepare("c"); assertEquals(0, cache.size()); assertEquals(List.of("b", "a"), disposed);
        cache.put("c", "c"); cache.pruneExcept("c"); assertEquals("c", cache.get("c"));
    }
    @Test void capacityBoundsAndSingleEntryMode() {
        assertThrows(IllegalArgumentException.class, () -> new SnapshotCache<>(0, x -> {}, () -> false));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotCache<>(5, x -> {}, () -> false));
        var disposed = new ArrayList<Integer>(); var cache = new SnapshotCache<String, Integer>(1, disposed::add, () -> false);
        cache.put("a", 1); cache.put("b", 2); assertEquals(List.of(1), disposed); assertEquals(1, cache.size());
    }
}
