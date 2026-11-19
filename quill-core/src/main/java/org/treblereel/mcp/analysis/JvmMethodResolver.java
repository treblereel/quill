package org.treblereel.mcp.analysis;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Resolves a symbolic method reference to an indexed declaration, never a runtime receiver.
 */
public final class JvmMethodResolver {
    private static final int CAP = 5000;

    public record Resolution(String status, ClassRecord owner, ClassMemberRecord member, List<String> path) {
        public boolean resolved() {
            return owner != null && member != null;
        }
    }

    private final Map<String, List<ClassRecord>> owners;
    private final Map<Integer, List<ClassMemberRecord>> members;
    private final Function<Collection<Integer>, Map<Integer, List<ClassMemberRecord>>> loadMembers;
    private final BiFunction<String, String, List<Map.Entry<Integer, String>>> loadCandidates;
    private final JvmTypeGraph graph;
    private final Set<String> ambiguousOccurrences;

    public JvmMethodResolver(JvmTypeGraph graph) {
        this(graph, graph.members);
    }

    public JvmMethodResolver(JvmTypeGraph graph, Map<Integer, List<ClassMemberRecord>> members) {
        this(graph, members, ids -> Map.of(), (name, descriptor) -> List.of());
    }

    public JvmMethodResolver(JvmTypeGraph graph, Map<Integer, List<ClassMemberRecord>> members, Function<Collection<Integer>, Map<Integer, List<ClassMemberRecord>>> loadMembers, BiFunction<String, String, List<Map.Entry<Integer, String>>> loadCandidates) {
        this.graph = graph;
        this.loadMembers = loadMembers;
        this.loadCandidates = loadCandidates;
        this.ambiguousOccurrences = graph.ambiguous;
        this.owners = graph.owners;
        this.members = new HashMap<>(members);
    }

    private Resolution unknown(String status) {
        return new Resolution(status, null, null, List.of());
    }

    public Resolution resolve(String owner, String name, String descriptor) {
        prepareMembers(List.of(owner));
        return graph.resolution(owner + ":" + name + descriptor, () -> lookup(owner, name, descriptor));
    }

    private void prepareMembers(Collection<String> roots) {
        var pending = new ArrayDeque<>(roots);
        var seen = new HashSet<String>();
        var missing = new HashSet<Integer>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!seen.add(name)) continue;
            var matches = owners.getOrDefault(name, List.of());
            if (matches.size() != 1) continue;
            var owner = matches.getFirst();
            if (!members.containsKey(owner.id())) missing.add(owner.id());
            if (owner.superclass() != null) pending.add(owner.superclass());
            pending.addAll(owner.interfaces());
        }
        if (missing.isEmpty()) return;
        var loaded = loadMembers.apply(missing);
        missing.forEach(id -> {
            if (loaded.containsKey(id)) members.put(id, loaded.get(id));
        });
    }

    public List<Integer> symbolicOwners(int classId, String method, String descriptor, String direction) {
        if (method == null || descriptor == null || "outbound".equals(direction)) return List.of(classId);
        return graph.aliases(classId + ":" + method + descriptor, () -> {
            var candidates = loadCandidates.apply(method, descriptor);
            prepareMembers(candidates.stream().map(Map.Entry::getValue).toList());
            return symbolicOwners(classId, method, descriptor, candidates);
        });
    }

    private Resolution lookup(String owner, String name, String descriptor) {
        var path = new ArrayList<String>();
        var seen = new HashSet<String>();
        var interfaces = new LinkedHashSet<String>();
        String cursor = owner;
        boolean interfaceOwner = false;
        while (cursor != null && !cursor.isBlank()) {
            if (!seen.add(cursor)) return unknown("hierarchy_cycle");
            if (seen.size() > CAP) return unknown("hierarchy_limit");
            path.add(cursor);
            if (ambiguousOccurrences.contains(cursor)) return unknown("owner_ambiguous");
            var matches = owners.getOrDefault(cursor, List.of());
            // java.lang.Object can be absent from the application index. It has no static
            // methods inherited by interfaces; Object instance methods require dependency evidence.
            if (matches.size() != 1) {
                if (cursor.equals("java.lang.Object") && !interfaces.isEmpty() && !Set.of("toString", "hashCode", "equals", "getClass", "clone", "finalize", "wait", "notify", "notifyAll").contains(name))
                    break;
                return unknown(matches.isEmpty() ? "ancestor_not_indexed" : "owner_ambiguous");
            }
            var cls = matches.getFirst();
            if (path.size() == 1) interfaceOwner = "INTERFACE".equals(cls.kind());
            if (!members.containsKey(cls.id())) return unknown("member_evidence_not_loaded");
            var declarations = declared(cls, name, descriptor);
            if (declarations.size() > 1) return unknown("declaration_ambiguous");
            if (declarations.size() == 1)
                return new Resolution(path.size() == 1 ? "declared" : "inherited_class_declaration", cls, declarations.getFirst(), List.copyOf(path));
            if ("<init>".equals(name) || "<clinit>".equals(name)) return unknown("declaration_not_indexed");
            interfaces.addAll(cls.interfaces());
            cursor = interfaceOwner ? null : cls.superclass();
        }
        // Collect all interface declarations, then discard candidates shadowed by a subinterface.
        var pending = new ArrayDeque<>(interfaces);
        var visited = new HashSet<String>();
        var candidates = new LinkedHashMap<String, ClassMemberRecord>();
        var graph = new HashMap<String, List<String>>();
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (!visited.add(next)) continue;
            if (seen.size() + visited.size() > CAP) return unknown("hierarchy_limit");
            if (ambiguousOccurrences.contains(next)) return unknown("owner_ambiguous");
            var matches = owners.getOrDefault(next, List.of());
            if (matches.size() != 1) return unknown(matches.isEmpty() ? "ancestor_not_indexed" : "owner_ambiguous");
            var cls = matches.getFirst();
            if (!"INTERFACE".equals(cls.kind())) return unknown("invalid_interface_hierarchy");
            if (!members.containsKey(cls.id())) return unknown("member_evidence_not_loaded");
            for (var m : declared(cls, name, descriptor))
                if (!flag(m, "static") && !flag(m, "private")) candidates.put(next, m);
            graph.put(next, cls.interfaces());
            pending.addAll(cls.interfaces());
        }
        var degrees = new HashMap<String, Integer>();
        graph.keySet().forEach(key -> degrees.put(key, 0));
        graph.values().forEach(parents -> parents.forEach(parent -> degrees.merge(parent, 1, Integer::sum)));
        var ready = new ArrayDeque<String>();
        degrees.forEach((key, value) -> {
            if (value == 0) ready.add(key);
        });
        int processed = 0;
        while (!ready.isEmpty()) {
            String next = ready.removeFirst();
            processed++;
            for (String parent : graph.getOrDefault(next, List.of()))
                if (degrees.merge(parent, -1, Integer::sum) == 0) ready.add(parent);
        }
        if (processed != graph.size()) return unknown("hierarchy_cycle");
        var maximal = candidates.keySet().stream().filter(base -> candidates.keySet().stream().noneMatch(child -> !base.equals(child) && subinterface(child, base, new HashSet<>()))).toList();
        if (maximal.size() != 1)
            return unknown(maximal.isEmpty() ? "declaration_not_indexed" : "interface_declaration_ambiguous");
        String selected = maximal.getFirst();
        return new Resolution("inherited_interface_declaration", owners.get(selected).getFirst(), candidates.get(selected), List.of(owner, selected));
    }

    private boolean subinterface(String child, String parent, Set<String> seen) {
        var pending = new ArrayDeque<String>();
        pending.add(child);
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (!seen.add(next)) continue;
            var cls = owners.getOrDefault(next, List.of());
            if (cls.size() != 1) continue;
            if (cls.getFirst().interfaces().contains(parent)) return true;
            pending.addAll(cls.getFirst().interfaces());
        }
        return false;
    }

    private List<ClassMemberRecord> declared(ClassRecord cls, String name, String descriptor) {
        return members.getOrDefault(cls.id(), List.of()).stream().filter(m -> ("METHOD".equals(m.kind()) || "CONSTRUCTOR".equals(m.kind())) && ("CONSTRUCTOR".equals(m.kind()) ? "<init>" : m.name()).equals(name) && m.descriptor().equals(descriptor)).toList();
    }

    public static boolean flag(ClassMemberRecord member, String flag) {
        return member.modifiers() != null && Arrays.asList(member.modifiers().strip().split("\\s+")).contains(flag);
    }

    /**
     * Expand only references resolving to the selected exact JVM declaration. Overrides stay separate.
     */
    public List<Integer> symbolicOwners(int declarationId, String name, String descriptor, List<Map.Entry<Integer, String>> candidates) {
        var ids = new TreeSet<Integer>();
        ids.add(declarationId);
        if ("<init>".equals(name) || "<clinit>".equals(name)) return List.copyOf(ids);
        for (var candidate : candidates) {
            var resolution = resolve(candidate.getValue(), name, descriptor);
            if (resolution.resolved() && resolution.owner().id() == declarationId) ids.add(candidate.getKey());
        }
        return List.copyOf(ids);
    }
}
