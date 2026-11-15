package org.treblereel.mcp.mcp.query;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ModuleClasspathRecord;
import org.treblereel.mcp.mcp.support.ClassTargetResolver;

/** Finds strongly connected components in indexed class and module dependency graphs. */
public final class CycleQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SCOPES = Set.of("class", "module");
    private static final int DETAIL_LIMIT = 100;

    public String findCycles(ReadQueryContext reader, String scope, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        String normalizedScope = scope == null
                ? "class" : scope.strip().toLowerCase(Locale.ROOT);
        ClassRecord through = null;
        if (!SCOPES.contains(normalizedScope)) {
            // Agents name the class they ask about as the scope: answer the class cycles through it.
            ClassTargetResolver.Lookup lookup = scope.contains(".") || scope.contains("#")
                    ? ClassTargetResolver.resolve(reader, scope.strip()) : null;
            if (lookup == null || lookup.error() != null) {
                return errorResponse("Invalid scope: expected class or module");
            }
            through = lookup.cls();
        }
        return "module".equals(normalizedScope)
                ? findModuleCycles(reader, limit, offset)
                : findClassCycles(reader, module, includeGenerated, includeTests, limit, offset, through);
    }

    private String findClassCycles(ReadQueryContext reader, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset, ClassRecord through) {
        List<DependencyRecord> edges = reader.usages.findCurrentDependencyGraph(module, includeGenerated, includeTests);
        Set<Integer> ids = new LinkedHashSet<>();
        edges.forEach(edge -> {
            ids.add(edge.fromClassId());
            ids.add(edge.toClassId());
        });
        Map<Integer, ClassRecord> classes = reader.symbols.findClassesByIds(List.copyOf(ids));
        List<ClassEdge> classEdges = collapseNestedClasses(edges, classes);
        Map<Integer, List<Integer>> graph = adjacency(classEdges.stream()
                .map(edge -> new Pair<>(edge.from(), edge.to())).toList());
        List<List<Integer>> components = new ArrayList<>(cyclicComponents(graph));
        if (through != null) {
            int owner = topLevelId(through, classes);
            components.removeIf(component -> !component.contains(owner));
        }
        components.sort(componentComparator(classes));

        ObjectNode root = base("class", components.size(), limit, offset);
        if (through != null) {
            root.put("class_filter", through.className());
            root.put("class_in_cycle", !components.isEmpty());
        }
        if (module == null || module.isBlank()) root.putNull("module");
        else root.put("module", module);
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("indexed_dependency_rows", edges.size());
        root.put("analyzed_edges", classEdges.size());
        root.put("cycle_semantics", "strongly_connected_component_with_at_least_two_top_level_classes");
        root.put("nested_class_handling", "collapsed_into_top_level_owner");
        ArrayNode values = root.putArray("cycles");
        for (List<Integer> component : page(components, limit, offset)) {
            appendClassCycle(values, component, classes, classEdges, graph);
        }
        appendMeta(root, reader, 0);
        return root.toString();
    }

    private String findModuleCycles(ReadQueryContext reader, int limit, int offset) {
        List<ModuleClasspathRecord> records = reader.index.findAllModuleClasspath();
        List<Pair<String>> edges = records.stream()
                .filter(record -> record.distance() == 1)
                .filter(record -> !record.applicationModule().equals(record.visibleModule()))
                .map(record -> new Pair<>(record.applicationModule(), record.visibleModule()))
                .distinct().toList();
        Map<String, List<String>> graph = adjacency(edges);
        List<List<String>> components = new ArrayList<>(cyclicComponents(graph));
        components.sort(Comparator.<List<String>>comparingInt(List::size).reversed()
                .thenComparing(component -> component.stream().min(String::compareTo).orElse("")));

        ObjectNode root = base("module", components.size(), limit, offset);
        root.put("analyzed_edges", edges.size());
        root.put("cycle_semantics", "strongly_connected_component_of_direct_project_dependencies");
        ArrayNode values = root.putArray("cycles");
        for (List<String> component : page(components, limit, offset)) {
            List<String> members = component.stream().sorted().toList();
            ObjectNode cycle = values.addObject();
            cycle.put("member_count", members.size());
            cycle.set("members", JSON.valueToTree(members.stream().limit(DETAIL_LIMIT).toList()));
            cycle.put("members_truncated", members.size() > DETAIL_LIMIT);
            cycle.set("representative_path", JSON.valueToTree(representativeCycle(component, graph)));
            List<Pair<String>> internal = edges.stream()
                    .filter(edge -> component.contains(edge.from()) && component.contains(edge.to()))
                    .sorted(pairComparator()).toList();
            cycle.put("internal_edge_count", internal.size());
            ArrayNode edgeNodes = cycle.putArray("edges");
            internal.stream().limit(DETAIL_LIMIT).forEach(edge -> {
                ObjectNode node = edgeNodes.addObject();
                node.put("from", edge.from());
                node.put("to", edge.to());
                node.put("kind", "direct_project_dependency");
            });
            cycle.put("edges_truncated", internal.size() > DETAIL_LIMIT);
        }
        root.putArray("limitations")
                .add("Only direct project dependencies discovered without running the target build are considered")
                .add("Dynamic Gradle declarations and build-plugin mutations may be absent");
        appendMeta(root, reader, 0);
        return root.toString();
    }

    private static ObjectNode base(String scope, int total, int limit, int offset) {
        ObjectNode root = JSON.createObjectNode();
        root.put("scope", scope);
        int showing = Math.max(0, Math.min(limit, total - Math.min(offset, total)));
        appendPage(root, showing, total, limit, offset);
        return root;
    }

    private static void appendClassCycle(ArrayNode target, List<Integer> component,
            Map<Integer, ClassRecord> classes, List<ClassEdge> edges,
            Map<Integer, List<Integer>> graph) {
        List<ClassRecord> members = component.stream().map(classes::get)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(ClassRecord::className)).toList();
        Set<Integer> memberIds = new HashSet<>(component);
        List<ClassEdge> internal = edges.stream()
                .filter(edge -> memberIds.contains(edge.from())
                        && memberIds.contains(edge.to()))
                .toList();
        ObjectNode cycle = target.addObject();
        cycle.put("member_count", members.size());
        cycle.put("internal_edge_count", internal.size());
        cycle.put("cross_module", members.stream().map(ClassRecord::module)
                .filter(java.util.Objects::nonNull).distinct().count() > 1);
        cycle.set("modules", JSON.valueToTree(members.stream().map(ClassRecord::module)
                .filter(java.util.Objects::nonNull).distinct().sorted().toList()));
        ArrayNode memberNodes = cycle.putArray("members");
        members.stream().limit(DETAIL_LIMIT).forEach(member -> {
            ObjectNode node = memberNodes.addObject();
            node.put("class", member.className());
            if (member.module() != null) node.put("module", member.module());
            if (member.sourceSet() != null) node.put("source_set", member.sourceSet());
            node.put("origin", member.origin());
            if (member.sourceFile() != null) node.put("source_file", member.sourceFile());
        });
        cycle.put("members_truncated", members.size() > DETAIL_LIMIT);
        cycle.set("representative_path", JSON.valueToTree(
                representativeCycle(component, graph,
                        Comparator.comparing(id -> classes.get(id).className())).stream()
                        .map(classes::get).filter(java.util.Objects::nonNull)
                        .map(ClassRecord::className).toList()));

        ArrayNode edgeNodes = cycle.putArray("edges");
        internal.stream().sorted(Comparator
                        .comparing((ClassEdge edge) -> classes.get(edge.from()).className())
                        .thenComparing(edge -> classes.get(edge.to()).className()))
                .limit(DETAIL_LIMIT).forEach(edge -> {
                    ClassRecord from = classes.get(edge.from());
                    ClassRecord to = classes.get(edge.to());
                    if (from == null || to == null) return;
                    ObjectNode node = edgeNodes.addObject();
                    node.put("from", from.className());
                    node.put("to", to.className());
                    node.set("kinds", JSON.valueToTree(edge.kinds()));
                });
        cycle.put("edges_truncated", internal.size() > DETAIL_LIMIT);
    }

    private static List<ClassEdge> collapseNestedClasses(
            List<DependencyRecord> edges, Map<Integer, ClassRecord> classes) {
        Map<String, Integer> idsByName = new HashMap<>();
        classes.values().forEach(cls -> idsByName.put(cls.className(), cls.id()));
        Map<Integer, Integer> canonical = new HashMap<>();
        classes.values().forEach(cls -> {
            int separator = cls.className().indexOf('$');
            // Boxed on both branches: an int branch would unbox a missing owner and throw.
            Integer owner = separator < 0 ? Integer.valueOf(cls.id())
                    : idsByName.get(cls.className().substring(0, separator));
            canonical.put(cls.id(), owner == null ? cls.id() : owner);
        });
        Map<Pair<Integer>, Set<String>> aggregate = new LinkedHashMap<>();
        for (DependencyRecord edge : edges) {
            int from = canonical.getOrDefault(edge.fromClassId(), edge.fromClassId());
            int to = canonical.getOrDefault(edge.toClassId(), edge.toClassId());
            if (from == to) continue;
            aggregate.computeIfAbsent(new Pair<>(from, to),
                    ignored -> new java.util.TreeSet<>()).add(edge.kind());
        }
        return aggregate.entrySet().stream()
                .map(entry -> new ClassEdge(entry.getKey().from(), entry.getKey().to(),
                        List.copyOf(entry.getValue())))
                .toList();
    }

    /** The id cycles list a class under: its top-level owner's when that owner is indexed. */
    private static int topLevelId(ClassRecord cls, Map<Integer, ClassRecord> classes) {
        int separator = cls.className().indexOf('$');
        if (separator < 0) return cls.id();
        String owner = cls.className().substring(0, separator);
        return classes.values().stream().filter(candidate -> candidate.className().equals(owner))
                .mapToInt(ClassRecord::id).findFirst().orElse(cls.id());
    }

    private static <T> Map<T, List<T>> adjacency(Collection<Pair<T>> edges) {
        Map<T, Set<T>> sets = new LinkedHashMap<>();
        for (Pair<T> edge : edges) {
            sets.computeIfAbsent(edge.from(), ignored -> new LinkedHashSet<>()).add(edge.to());
            sets.computeIfAbsent(edge.to(), ignored -> new LinkedHashSet<>());
        }
        Map<T, List<T>> result = new LinkedHashMap<>();
        sets.forEach((key, values) -> result.put(key, values.stream().sorted(
                Comparator.comparing(Object::toString)).toList()));
        return result;
    }

    private static <T> List<List<T>> cyclicComponents(Map<T, List<T>> graph) {
        List<T> finishOrder = finishingOrder(graph);
        Map<T, List<T>> reverse = reversed(graph);
        Set<T> assigned = new HashSet<>();
        List<List<T>> result = new ArrayList<>();
        for (int index = finishOrder.size() - 1; index >= 0; index--) {
            T root = finishOrder.get(index);
            if (!assigned.add(root)) continue;
            List<T> component = new ArrayList<>();
            Deque<T> pending = new ArrayDeque<>();
            pending.push(root);
            while (!pending.isEmpty()) {
                T node = pending.pop();
                component.add(node);
                List<T> neighbors = reverse.getOrDefault(node, List.of());
                for (int neighbor = neighbors.size() - 1; neighbor >= 0; neighbor--) {
                    T next = neighbors.get(neighbor);
                    if (assigned.add(next)) pending.push(next);
                }
            }
            if (component.size() > 1) result.add(component);
        }
        return result;
    }

    private static <T> List<T> finishingOrder(Map<T, List<T>> graph) {
        Set<T> visited = new HashSet<>();
        List<T> order = new ArrayList<>();
        List<T> roots = graph.keySet().stream()
                .sorted(Comparator.comparing(Object::toString)).toList();
        for (T root : roots) {
            if (!visited.add(root)) continue;
            Deque<Frame<T>> stack = new ArrayDeque<>();
            stack.push(new Frame<>(root, 0));
            while (!stack.isEmpty()) {
                Frame<T> frame = stack.pop();
                List<T> neighbors = graph.getOrDefault(frame.node(), List.of());
                if (frame.nextNeighbor() < neighbors.size()) {
                    stack.push(new Frame<>(frame.node(), frame.nextNeighbor() + 1));
                    T next = neighbors.get(frame.nextNeighbor());
                    if (visited.add(next)) stack.push(new Frame<>(next, 0));
                } else {
                    order.add(frame.node());
                }
            }
        }
        return order;
    }

    private static <T> Map<T, List<T>> reversed(Map<T, List<T>> graph) {
        List<Pair<T>> reverseEdges = new ArrayList<>();
        graph.forEach((from, targets) -> targets.forEach(to ->
                reverseEdges.add(new Pair<>(to, from))));
        return adjacency(reverseEdges);
    }

    private static <T> List<T> representativeCycle(
            List<T> component, Map<T, List<T>> graph) {
        return representativeCycle(component, graph, Comparator.comparing(Object::toString));
    }

    private static <T> List<T> representativeCycle(
            List<T> component, Map<T, List<T>> graph, Comparator<T> comparator) {
        Set<T> allowed = new HashSet<>(component);
        T start = component.stream().min(comparator).orElseThrow();
        for (T neighbor : graph.getOrDefault(start, List.of())) {
            if (!allowed.contains(neighbor)) continue;
            Map<T, T> parent = new HashMap<>();
            Deque<T> queue = new ArrayDeque<>();
            queue.add(neighbor);
            parent.put(neighbor, null);
            while (!queue.isEmpty()) {
                T current = queue.removeFirst();
                if (current.equals(start)) {
                    List<T> reverse = new ArrayList<>();
                    for (T node = current; node != null; node = parent.get(node)) reverse.add(node);
                    java.util.Collections.reverse(reverse);
                    List<T> path = new ArrayList<>();
                    path.add(start);
                    path.addAll(reverse);
                    return path;
                }
                for (T next : graph.getOrDefault(current, List.of())) {
                    if (allowed.contains(next) && !parent.containsKey(next)) {
                        parent.put(next, current);
                        queue.addLast(next);
                    }
                }
            }
        }
        return List.of();
    }

    private static Comparator<List<Integer>> componentComparator(Map<Integer, ClassRecord> classes) {
        return Comparator.<List<Integer>>comparingInt(List::size).reversed()
                .thenComparing(component -> component.stream().map(classes::get)
                        .filter(java.util.Objects::nonNull).map(ClassRecord::className)
                        .min(String::compareTo).orElse(""));
    }

    private static <T> Comparator<Pair<T>> pairComparator() {
        return Comparator.comparing((Pair<T> pair) -> pair.from().toString())
                .thenComparing(pair -> pair.to().toString());
    }

    private static <T> List<T> page(List<T> values, int limit, int offset) {
        int from = Math.min(offset, values.size());
        int to = (int) Math.min((long) from + limit, values.size());
        return values.subList(from, to);
    }

    private record Pair<T>(T from, T to) {}

    private record ClassEdge(int from, int to, List<String> kinds) {}

    private record Frame<T>(T node, int nextNeighbor) {}
}
