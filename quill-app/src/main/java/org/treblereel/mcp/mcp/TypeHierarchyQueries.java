package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;

/** Builds paged ancestor and descendant paths for an indexed type. */
final class TypeHierarchyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String getTypeHierarchy(Jdbi jdbi, String target, String direction,
            int maxDepth, int limit, int offset) {
        String normalizedDirection = normalizeDirection(direction);
        if (normalizedDirection == null) {
            return errorResponse(
                    "Invalid direction: expected ancestors, descendants, or both");
        }
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord rootClass = lookup.cls();

        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi);
        Map<String, ClassRecord> byName = new HashMap<>();
        Map<String, List<ChildEdge>> children = new HashMap<>();
        for (ClassRecord cls : classes) {
            byName.put(cls.className(), cls);
            if (cls.superclass() != null) {
                children.computeIfAbsent(cls.superclass(), ignored -> new ArrayList<>())
                        .add(new ChildEdge(cls, "extends"));
            }
            for (String implemented : cls.interfaces()) {
                children.computeIfAbsent(implemented, ignored -> new ArrayList<>())
                        .add(new ChildEdge(cls, "implements"));
            }
        }
        children.values().forEach(values -> values.sort(
                Comparator.comparing(edge -> edge.child().className())));

        Map<String, HierarchyPath> paths = new LinkedHashMap<>();
        if (!"descendants".equals(normalizedDirection)) {
            collectAncestors(rootClass, byName, maxDepth, paths);
        }
        if (!"ancestors".equals(normalizedDirection)) {
            collectDescendants(rootClass, children, maxDepth, paths);
        }
        List<HierarchyPath> ordered = paths.values().stream()
                .sorted(Comparator.comparing(HierarchyPath::direction)
                        .thenComparingInt(HierarchyPath::depth)
                        .thenComparing(HierarchyPath::className))
                .toList();
        int from = Math.min(offset, ordered.size());
        int to = (int) Math.min((long) from + limit, ordered.size());
        List<HierarchyPath> page = ordered.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", rootClass.className());
        root.put("kind", rootClass.kind());
        root.put("source", rootClass.sourceFile() + ":" + rootClass.sourceLine());
        root.put("direction", normalizedDirection);
        root.put("max_depth", maxDepth);
        root.put("ancestor_count", paths.values().stream()
                .filter(path -> "ancestor".equals(path.direction())).count());
        root.put("descendant_count", paths.values().stream()
                .filter(path -> "descendant".equals(path.direction())).count());
        ArrayNode entries = root.putArray("hierarchy");
        int naiveTokens = rootClass.sourceTokens();
        for (HierarchyPath path : page) {
            ObjectNode node = entries.addObject();
            node.put("class", path.className());
            node.put("direction", path.direction());
            node.put("depth", path.depth());
            node.put("direct", path.depth() == 1);
            node.put("indexed", path.indexedClass() != null);
            node.set("path", JSON.valueToTree(path.path()));
            node.set("relations", JSON.valueToTree(path.relations()));
            if (path.indexedClass() != null) {
                ClassRecord cls = path.indexedClass();
                node.put("kind", cls.kind());
                node.put("source", cls.sourceFile() + ":" + cls.sourceLine());
                node.put("origin", cls.origin());
                if (cls.module() == null) node.putNull("module");
                else node.put("module", cls.module());
                if (cls.sourceSet() == null) node.putNull("source_set");
                else node.put("source_set", cls.sourceSet());
                naiveTokens += cls.sourceTokens();
            }
        }
        appendPage(root, page.size(), ordered.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static void collectAncestors(ClassRecord root, Map<String, ClassRecord> byName,
            int maxDepth, Map<String, HierarchyPath> result) {
        ArrayDeque<Traversal> queue = new ArrayDeque<>();
        enqueueParents(queue, root, 0, List.of(root.className()), List.of());
        Map<String, Integer> visited = new HashMap<>();
        while (!queue.isEmpty()) {
            Traversal current = queue.removeFirst();
            Integer previousDepth = visited.get(current.className());
            if (previousDepth != null && previousDepth <= current.depth()) continue;
            visited.put(current.className(), current.depth());
            ClassRecord indexed = byName.get(current.className());
            result.put("ancestor:" + current.className(), new HierarchyPath(
                    current.className(), "ancestor", current.depth(), current.path(),
                    current.relations(), indexed));
            if (indexed != null && current.depth() < maxDepth) {
                enqueueParents(queue, indexed, current.depth(),
                        current.path(), current.relations());
            }
        }
    }

    private static void enqueueParents(ArrayDeque<Traversal> queue, ClassRecord child,
            int childDepth, List<String> path, List<String> relations) {
        if (child.superclass() != null) {
            queue.addLast(new Traversal(child.superclass(), childDepth + 1,
                    append(path, child.superclass()), append(relations, "extends")));
        }
        for (String implemented : child.interfaces()) {
            queue.addLast(new Traversal(implemented, childDepth + 1,
                    append(path, implemented), append(relations, "implements")));
        }
    }

    private static void collectDescendants(ClassRecord root,
            Map<String, List<ChildEdge>> children, int maxDepth,
            Map<String, HierarchyPath> result) {
        ArrayDeque<Traversal> queue = new ArrayDeque<>();
        enqueueChildren(queue, children.get(root.className()), 0,
                List.of(root.className()), List.of());
        Map<String, Integer> visited = new HashMap<>();
        while (!queue.isEmpty()) {
            Traversal current = queue.removeFirst();
            Integer previousDepth = visited.get(current.className());
            if (previousDepth != null && previousDepth <= current.depth()) continue;
            visited.put(current.className(), current.depth());
            ChildEdge edge = current.edge();
            result.put("descendant:" + current.className(), new HierarchyPath(
                    current.className(), "descendant", current.depth(), current.path(),
                    current.relations(), edge.child()));
            if (current.depth() < maxDepth) {
                enqueueChildren(queue, children.get(current.className()), current.depth(),
                        current.path(), current.relations());
            }
        }
    }

    private static void enqueueChildren(ArrayDeque<Traversal> queue, List<ChildEdge> edges,
            int parentDepth, List<String> path, List<String> relations) {
        if (edges == null) return;
        for (ChildEdge edge : edges) {
            queue.addLast(new Traversal(edge.child().className(), parentDepth + 1,
                    append(path, edge.child().className()), append(relations, edge.relation()),
                    edge));
        }
    }

    private static String normalizeDirection(String direction) {
        if (direction == null || direction.isBlank()) return "both";
        return switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "ancestor", "ancestors", "up" -> "ancestors";
            case "descendant", "descendants", "down" -> "descendants";
            case "both" -> "both";
            default -> null;
        };
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private record ChildEdge(ClassRecord child, String relation) {}

    private record Traversal(
            String className,
            int depth,
            List<String> path,
            List<String> relations,
            ChildEdge edge) {
        Traversal(String className, int depth, List<String> path, List<String> relations) {
            this(className, depth, path, relations, null);
        }
    }

    private record HierarchyPath(
            String className,
            String direction,
            int depth,
            List<String> path,
            List<String> relations,
            ClassRecord indexedClass) {}
}
