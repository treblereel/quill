package org.treblereel.mcp.kotlin;

import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Serialized by the analyzer. Owns every inserted snapshot and disposes it exactly once. */
final class SnapshotCache<K, V> {
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(4, .75f, true);
    private final int capacity;
    private final Consumer<V> dispose;
    private final BooleanSupplier pressure;
    private long evictions;

    SnapshotCache(int capacity, Consumer<V> dispose, BooleanSupplier pressure) {
        if (capacity < 1 || capacity > 4) throw new IllegalArgumentException("snapshot capacity must be 1..4");
        this.capacity = capacity; this.dispose = dispose; this.pressure = pressure;
    }
    V get(K key) { return entries.get(key); }
    void remove(K key) { V old = entries.remove(key); if (old != null) dispose.accept(old); }
    void prepare(K key) {
        remove(key);
        while (!entries.isEmpty() && (entries.size() >= capacity || pressure.getAsBoolean())) evict();
    }
    void put(K key, V value) { prepare(key); entries.put(key, value); }
    void pruneExcept(K keep) {
        get(keep); // The retained model is now most recently used.
        while (entries.size() > 1 && pressure.getAsBoolean()) evict();
    }
    private void evict() { K key = entries.keySet().iterator().next(); remove(key); evictions++; }
    int size() { return entries.size(); }
    long evictions() { return evictions; }
    void clear() { while (!entries.isEmpty()) remove(entries.keySet().iterator().next()); }
}
