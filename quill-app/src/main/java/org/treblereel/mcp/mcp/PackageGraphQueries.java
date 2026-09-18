package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;

/** Aggregates indexed class dependencies into a compact package graph. */
final class PackageGraphQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DIRECTIONS = Set.of("inbound", "outbound", "both");

    String getPackageGraph(Jdbi jdbi, String requestedPackage, String direction,
            String module, boolean includeGenerated, boolean includeTests,
            int limit, int offset) {
        String normalizedDirection = direction == null
                ? "both" : direction.strip().toLowerCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalizedDirection)) {
            return errorResponse("Invalid direction: expected inbound, outbound, or both");
        }
        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi).stream()
                .filter(cls -> module == null || module.isBlank() || module.equals(cls.module()))
                .filter(cls -> includeGenerated || !"generated".equals(cls.origin()))
                .filter(cls -> includeTests || !"test".equals(cls.sourceSet()))
                .toList();
        Map<Integer, ClassRecord> classesById = new HashMap<>();
        Map<String, MutablePackage> summaries = new TreeMap<>();
        for (ClassRecord cls : classes) {
            classesById.put(cls.id(), cls);
            MutablePackage summary = summaries.computeIfAbsent(
                    packageName(cls.className()), ignored -> new MutablePackage());
            summary.classes++;
            summary.sourceTokens += cls.sourceTokens();
            if (cls.module() != null) summary.modules.add(cls.module());
            if ("generated".equals(cls.origin())) summary.generated++;
            if ("test".equals(cls.sourceSet())) summary.tests++;
        }

        String selected = resolvePackage(requestedPackage, summaries.keySet());
        if (requestedPackage != null && !requestedPackage.isBlank() && selected == null) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "Package not found: " + requestedPackage);
            error.set("candidates", JSON.valueToTree(packageCandidates(
                    requestedPackage.strip(), summaries.keySet())));
            appendMeta(error, jdbi, 0);
            return error.toString();
        }

        List<DependencyRecord> dependencies = IndexReader.findCurrentDependencyGraph(
                jdbi, module, includeGenerated, includeTests);
        Map<PackagePair, MutableEdge> aggregated = new LinkedHashMap<>();
        for (DependencyRecord dependency : dependencies) {
            ClassRecord fromClass = classesById.get(dependency.fromClassId());
            ClassRecord toClass = classesById.get(dependency.toClassId());
            if (fromClass == null || toClass == null) continue;
            String from = packageName(fromClass.className());
            String to = packageName(toClass.className());
            if (from.equals(to)) continue;
            MutableEdge edge = aggregated.computeIfAbsent(
                    new PackagePair(from, to), ignored -> new MutableEdge());
            edge.rows++;
            edge.occurrences += dependency.occurrenceCount();
            edge.classPairs.add(((long) dependency.fromClassId() << 32)
                    ^ (dependency.toClassId() & 0xffffffffL));
            edge.kinds.merge(dependency.kind(), dependency.occurrenceCount(), Integer::sum);
        }

        List<PackageEdge> relations = aggregated.entrySet().stream()
                .map(entry -> new PackageEdge(entry.getKey().from(), entry.getKey().to(),
                        entry.getValue().rows, entry.getValue().occurrences,
                        entry.getValue().classPairs.size(),
                        java.util.Collections.unmodifiableMap(
                                new TreeMap<>(entry.getValue().kinds))))
                .filter(edge -> selected == null || matches(edge, selected, normalizedDirection))
                .sorted(Comparator.comparingInt(PackageEdge::occurrences).reversed()
                        .thenComparing(PackageEdge::from).thenComparing(PackageEdge::to))
                .toList();
        int from = Math.min(offset, relations.size());
        int to = Math.min(relations.size(), from + limit);
        List<PackageEdge> page = relations.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        if (selected == null) root.putNull("package");
        else root.put("package", selected);
        root.put("direction", normalizedDirection);
        if (module == null || module.isBlank()) root.putNull("module");
        else root.put("module", module);
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("package_count", summaries.size());
        root.put("indexed_dependency_rows", dependencies.size());
        root.put("inter_package_edge_count", aggregated.size());
        root.put("edge_semantics", "aggregated_current_class_dependencies");

        ArrayNode edges = root.putArray("relations");
        for (PackageEdge relation : page) {
            ObjectNode edge = edges.addObject();
            edge.put("from", relation.from());
            edge.put("to", relation.to());
            edge.put("direction_from_selected", selected == null ? "graph"
                    : relation.from().equals(selected) ? "outbound" : "inbound");
            edge.put("class_pair_count", relation.classPairs());
            edge.put("dependency_row_count", relation.rows());
            edge.put("occurrence_count", relation.occurrences());
            edge.set("kinds", JSON.valueToTree(relation.kinds().keySet()));
            edge.set("occurrences_by_kind", JSON.valueToTree(relation.kinds()));
        }

        Set<String> visiblePackages = new TreeSet<>();
        if (selected != null) visiblePackages.add(selected);
        page.forEach(edge -> {
            visiblePackages.add(edge.from());
            visiblePackages.add(edge.to());
        });
        ArrayNode nodes = root.putArray("nodes");
        int naiveTokens = 0;
        for (String name : visiblePackages) {
            MutablePackage summary = summaries.get(name);
            ObjectNode node = nodes.addObject();
            node.put("package", name);
            node.put("classes", summary == null ? 0 : summary.classes);
            node.put("generated_classes", summary == null ? 0 : summary.generated);
            node.put("test_classes", summary == null ? 0 : summary.tests);
            node.set("modules", JSON.valueToTree(
                    summary == null ? Set.of() : summary.modules));
            node.put("source_tokens", summary == null ? 0 : summary.sourceTokens);
            if (summary != null) naiveTokens += summary.sourceTokens;
        }
        root.putArray("limitations")
                .add("The graph reflects static dependencies in compiled application bytecode")
                .add("Reflection, runtime-generated links, and dependencies only present in uncompiled source are absent")
                .add("Nested classes are attributed to the package of their binary owner name");
        appendPage(root, page.size(), relations.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static boolean matches(PackageEdge edge, String selected, String direction) {
        return switch (direction) {
            case "inbound" -> edge.to().equals(selected);
            case "outbound" -> edge.from().equals(selected);
            default -> edge.from().equals(selected) || edge.to().equals(selected);
        };
    }

    private static String resolvePackage(String requested, Set<String> available) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = requested.strip();
        if (available.contains(normalized)) return normalized;
        List<String> candidates = packageCandidates(normalized, available);
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static List<String> packageCandidates(String requested, Set<String> available) {
        String suffix = "." + requested;
        return available.stream().filter(value -> value.equalsIgnoreCase(requested)
                        || value.endsWith(suffix))
                .sorted().limit(20).toList();
    }

    private static String packageName(String className) {
        int nested = className.indexOf('$');
        String topLevel = nested < 0 ? className : className.substring(0, nested);
        int separator = topLevel.lastIndexOf('.');
        return separator < 0 ? "<default>" : topLevel.substring(0, separator);
    }

    private record PackagePair(String from, String to) {}

    private record PackageEdge(String from, String to, int rows, int occurrences,
                               int classPairs, Map<String, Integer> kinds) {}

    private static final class MutableEdge {
        private int rows;
        private int occurrences;
        private final Set<Long> classPairs = new LinkedHashSet<>();
        private final Map<String, Integer> kinds = new TreeMap<>();
    }

    private static final class MutablePackage {
        private int classes;
        private int generated;
        private int tests;
        private int sourceTokens;
        private final Set<String> modules = new TreeSet<>();
    }
}
