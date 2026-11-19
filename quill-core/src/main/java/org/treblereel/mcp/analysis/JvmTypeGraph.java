package org.treblereel.mcp.analysis;

import java.util.*;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.concurrent.atomic.AtomicLong;

import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ClassMemberRecord;

/**
 * Immutable indexed type evidence with bounded derived results.
 */
public final class JvmTypeGraph {
    public final List<ClassRecord> classes;
    public final Map<String, List<ClassRecord>> owners;
    public final Map<Integer, List<ClassMemberRecord>> members;
    public final Set<String> ambiguous;
    public final Map<Integer, List<ClassMemberRecord>> sourceMembers;
    private final Map<Integer, List<ClassRecord>> children;
    public final int memberCount;
    private final long baseWeight;
    private final AtomicLong derivedWeight = new AtomicLong();
    private final Map<String, List<Integer>> aliases = bounded(256, List::size);
    private final Map<String, JvmMethodResolver.Resolution> resolutions = bounded(1024, value -> 1L + value.path().size());
    private final Map<Integer, Map<Integer, List<String>>> ancestry = bounded(16, value -> value.values().stream().mapToLong(path -> 1L + path.size()).sum());
    private final Map<Integer, List<ClassRecord>> descendants = bounded(64, List::size);
    private final Map<String, String> dispatch = bounded(256, value -> 1L + value.length() / 16);

    public JvmTypeGraph(List<ClassRecord> classes, Map<Integer, List<ClassMemberRecord>> members, Set<String> ambiguous) {
        this.classes = List.copyOf(classes);
        Map<String, List<ClassRecord>> ownerMap = new HashMap<>();
        classes.forEach(c -> ownerMap.computeIfAbsent(c.className(), ignored -> new ArrayList<>()).add(c));
        ownerMap.replaceAll((k, v) -> List.copyOf(v));
        this.owners = Map.copyOf(ownerMap);
        Map<Integer, List<ClassMemberRecord>> immutable = new HashMap<>();
        members.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        this.members = Map.copyOf(immutable);
        this.ambiguous = Set.copyOf(ambiguous);
        Map<Integer, List<ClassMemberRecord>> source = new HashMap<>();
        this.members.forEach((id, values) -> source.put(id, values.stream().filter(m -> !JvmMethodResolver.flag(m, "bridge") && !JvmMethodResolver.flag(m, "synthetic")).toList()));
        sourceMembers = Map.copyOf(source);
        memberCount = members.values().stream().mapToInt(List::size).sum();
        Map<Integer, List<ClassRecord>> reversed = new HashMap<>();
        for (var child : classes) {
            if (owners.get(child.className()).size() != 1) continue;
            var parents = new ArrayList<>(child.interfaces());
            if (child.superclass() != null) parents.add(child.superclass());
            for (String parent : parents) {
                var matches = owners.getOrDefault(parent, List.of());
                if (matches.size() == 1)
                    reversed.computeIfAbsent(matches.getFirst().id(), ignored -> new ArrayList<>()).add(child);
            }
        }
        reversed.replaceAll((k, v) -> List.copyOf(v));
        children = Map.copyOf(reversed);
        baseWeight = 2L * this.classes.size() + 2L * memberCount + this.ambiguous.size() + this.classes.stream().mapToLong(c -> c.interfaces().size()).sum();
    }

    /**
     * Approximate retained units, including sparse inventories and derived cache contents.
     */
    public long retainedWeight() {
        return baseWeight + derivedWeight.get();
    }

    private <K, V> Map<K, V> bounded(int maximum, ToLongFunction<V> weight) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(16, .75f, true) {
            @Override
            public V put(K key, V value) {
                V previous = super.put(key, value);
                long delta = weight.applyAsLong(value) - (previous == null ? 0 : weight.applyAsLong(previous));
                if (size() > maximum) {
                    var eldest = entrySet().iterator().next();
                    delta -= weight.applyAsLong(eldest.getValue());
                    super.remove(eldest.getKey());
                }
                derivedWeight.addAndGet(delta);
                return previous;
            }
        });
    }

    public List<Integer> aliases(String key, Supplier<List<Integer>> loader) {
        var cached = aliases.get(key);
        if (cached != null) return cached;
        var value = List.copyOf(loader.get());
        synchronized (aliases) {
            cached = aliases.get(key);
            if (cached != null) return cached;
            if (value.size() <= 512) aliases.put(key, value);
        }
        return value;
    }

    public JvmMethodResolver.Resolution resolution(String key, Supplier<JvmMethodResolver.Resolution> loader) {
        var cached = resolutions.get(key);
        if (cached != null) return cached;
        var value = loader.get();
        synchronized (resolutions) {
            cached = resolutions.get(key);
            if (cached != null) return cached;
            if (!"member_evidence_not_loaded".equals(value.status()) && value.path().size() <= 256)
                resolutions.put(key, value);
        }
        return value;
    }

    public String dispatch(String key, Supplier<String> loader) {
        var cached = dispatch.get(key);
        if (cached != null) return cached;
        var value = loader.get();
        synchronized (dispatch) {
            cached = dispatch.get(key);
            if (cached != null) return cached;
            if (value.length() <= 8192) dispatch.put(key, value);
        }
        return value;
    }

    public List<ClassRecord> descendants(ClassRecord owner) {
        synchronized (descendants) {
            var cached = descendants.get(owner.id());
            if (cached != null) return cached;
            if (owners.getOrDefault(owner.className(), List.of()).size() != 1) return List.of();
            var pending = new ArrayDeque<ClassRecord>();
            pending.add(owner);
            var seen = new HashSet<Integer>();
            var values = new ArrayList<ClassRecord>();
            while (!pending.isEmpty() && seen.size() < 5000) {
                var next = pending.removeFirst();
                if (!seen.add(next.id())) continue;
                values.add(next);
                pending.addAll(children.getOrDefault(next.id(), List.of()));
            }
            values.sort(Comparator.comparing(ClassRecord::className).thenComparingInt(ClassRecord::id));
            var value = List.copyOf(values);
            descendants.put(owner.id(), value);
            return value;
        }
    }

    public Map<Integer, List<String>> ancestry(ClassRecord owner) {
        synchronized (ancestry) {
            var cached = ancestry.get(owner.id());
            if (cached != null) return cached;
            int id = owner.id();
            if (owners.getOrDefault(owner.className(), List.of()).size() != 1) return Map.of();
            record Step(ClassRecord cls, List<String> path) {
            }
            var pending = new ArrayDeque<Step>();
            pending.add(new Step(owner, List.of(owner.className())));
            Map<Integer, List<String>> paths = new HashMap<>();
            while (!pending.isEmpty() && paths.size() < 5000) {
                var next = pending.removeFirst();
                if (paths.putIfAbsent(next.cls().id(), next.path()) != null) continue;
                var parents = new ArrayList<>(next.cls().interfaces());
                if (next.cls().superclass() != null) parents.add(next.cls().superclass());
                for (String parent : parents) {
                    var matches = owners.getOrDefault(parent, List.of());
                    if (matches.size() != 1) continue;
                    var path = new ArrayList<>(next.path());
                    path.add(parent);
                    pending.addLast(new Step(matches.getFirst(), List.copyOf(path)));
                }
            }
            var value = Map.copyOf(paths);
            if (paths.values().stream().mapToLong(List::size).sum() <= 4096) ancestry.put(owner.id(), value);
            return value;
        }
    }

    public List<String> path(ClassRecord child, ClassRecord ancestor) {
        if (owners.getOrDefault(ancestor.className(), List.of()).size() != 1) return List.of();
        return ancestry(child).getOrDefault(ancestor.id(), List.of());
    }
}
