package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ModuleClasspathRecord;

/** Describes project-module dependencies and transitive classpath visibility. */
final class ModuleGraphQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DIRECTIONS = Set.of("inbound", "outbound", "both");

    String getModuleGraph(Jdbi jdbi, String module, String direction,
            int depth, int limit, int offset) {
        String normalizedDirection = direction == null
                ? "both" : direction.strip().toLowerCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalizedDirection)) {
            return errorResponse("Invalid direction: expected inbound, outbound, or both");
        }

        List<ModuleClasspathRecord> classpath = IndexReader.findAllModuleClasspath(jdbi);
        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi);
        Set<String> available = availableModules(classpath, classes);
        String selected = resolveModule(module, available);
        if (module != null && selected == null) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "Module not found: " + module);
            error.set("available_modules", JSON.valueToTree(available));
            appendMeta(error, jdbi, 0);
            return error.toString();
        }

        List<Relation> relations = relations(
                classpath, selected, normalizedDirection, depth);
        int from = Math.min(offset, relations.size());
        int to = (int) Math.min((long) from + limit, relations.size());
        List<Relation> page = relations.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        if (selected == null) root.putNull("module");
        else root.put("module", selected);
        root.put("direction", normalizedDirection);
        root.put("max_depth", depth);
        root.put("module_count", available.size());
        root.put("edge_semantics",
                "distance_1_is_declared_project_dependency; distance_greater_than_1_is_transitive_classpath_visibility");
        root.put("direct_relations", relations.stream()
                .filter(relation -> relation.distance() == 1).count());
        root.put("transitive_relations", relations.stream()
                .filter(relation -> relation.distance() > 1).count());

        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        ObjectNode discovery = root.putObject("discovery");
        discovery.put("scope", metadata.getOrDefault("module_discovery_scope", "unknown"));
        discovery.put("complete", Boolean.parseBoolean(
                metadata.getOrDefault("module_discovery_complete", "false")));
        discovery.put("stored_classpath_entries", classpath.size());

        ArrayNode limitations = root.putArray("limitations");
        limitations.add("The graph covers project modules discovered from compiled output directories; external libraries are not module nodes");
        limitations.add("Maven edges include compile/runtime project dependencies; Gradle edges are inferred statically from project(...) references and may include other configurations");
        limitations.add("Dynamic Gradle dependency declarations and build-plugin mutations may be absent because Quill does not run the target build");

        ArrayNode edges = root.putArray("relations");
        for (Relation relation : page) {
            ObjectNode edge = edges.addObject();
            edge.put("from", relation.from());
            edge.put("to", relation.to());
            edge.put("distance", relation.distance());
            edge.put("kind", relation.distance() == 1
                    ? "direct_project_dependency" : "transitive_classpath_visibility");
            edge.put("direction_from_selected", relation.direction());
            edge.put("indexed_relation", relation.indexedRelation());
        }

        Set<String> pageModules = new LinkedHashSet<>();
        if (selected != null) pageModules.add(selected);
        page.forEach(relation -> {
            pageModules.add(relation.from());
            pageModules.add(relation.to());
        });
        if (selected == null && page.isEmpty()) pageModules.addAll(available);
        Map<String, ModuleSummary> summaries = summarize(classes, available);
        ArrayNode nodes = root.putArray("nodes");
        pageModules.stream().sorted().forEach(name -> appendNode(nodes, name, summaries.get(name)));

        int naiveTokens = pageModules.stream()
                .map(summaries::get).filter(summary -> summary != null)
                .mapToInt(ModuleSummary::sourceTokens).sum();
        appendPage(root, page.size(), relations.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static List<Relation> relations(List<ModuleClasspathRecord> classpath,
            String selected, String direction, int depth) {
        List<Relation> result = new ArrayList<>();
        for (ModuleClasspathRecord entry : classpath) {
            if (entry.distance() == 0 || entry.distance() > depth) continue;
            if (selected == null) {
                if (entry.distance() == 1) {
                    result.add(new Relation(entry.applicationModule(), entry.visibleModule(),
                            entry.distance(), "graph", entry.relation()));
                }
                continue;
            }
            if (!"inbound".equals(direction) && entry.applicationModule().equals(selected)) {
                result.add(new Relation(entry.applicationModule(), entry.visibleModule(),
                        entry.distance(), "outbound", entry.relation()));
            }
            if (!"outbound".equals(direction) && entry.visibleModule().equals(selected)) {
                result.add(new Relation(entry.applicationModule(), entry.visibleModule(),
                        entry.distance(), "inbound", entry.relation()));
            }
        }
        return result.stream().distinct()
                .sorted(Comparator.comparingInt(Relation::distance)
                        .thenComparing(Relation::direction)
                        .thenComparing(Relation::from)
                        .thenComparing(Relation::to))
                .toList();
    }

    private static Set<String> availableModules(List<ModuleClasspathRecord> classpath,
            List<ClassRecord> classes) {
        Set<String> result = new java.util.TreeSet<>();
        for (ModuleClasspathRecord entry : classpath) {
            result.add(entry.applicationModule());
            result.add(entry.visibleModule());
        }
        classes.stream().map(ClassRecord::module)
                .filter(value -> value != null && !value.isBlank()).forEach(result::add);
        return result;
    }

    private static String resolveModule(String requested, Set<String> available) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = requested.strip().replace('\\', '/');
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.isEmpty()) normalized = ".";
        if (available.contains(normalized)) return normalized;
        String candidate = normalized;
        List<String> caseInsensitive = available.stream()
                .filter(value -> value.equalsIgnoreCase(candidate)).toList();
        if (caseInsensitive.size() == 1) return caseInsensitive.getFirst();
        String suffix = "/" + candidate;
        List<String> byLeaf = available.stream()
                .filter(value -> value.endsWith(suffix)).toList();
        return byLeaf.size() == 1 ? byLeaf.getFirst() : null;
    }

    private static Map<String, ModuleSummary> summarize(
            List<ClassRecord> classes, Set<String> available) {
        Map<String, MutableSummary> mutable = new LinkedHashMap<>();
        available.forEach(name -> mutable.put(name, new MutableSummary()));
        for (ClassRecord cls : classes) {
            if (cls.module() == null || cls.module().isBlank()) continue;
            MutableSummary summary = mutable.computeIfAbsent(
                    cls.module(), ignored -> new MutableSummary());
            summary.classes++;
            summary.sourceTokens += cls.sourceTokens();
            if (cls.isBean()) summary.beans++;
            if ("generated".equals(cls.origin())) summary.generated++;
            else summary.source++;
            if ("test".equals(cls.sourceSet())) summary.tests++;
        }
        Map<String, ModuleSummary> result = new LinkedHashMap<>();
        mutable.forEach((name, value) -> result.put(name, new ModuleSummary(
                value.classes, value.source, value.generated, value.tests,
                value.beans, value.sourceTokens)));
        return result;
    }

    private static void appendNode(
            ArrayNode nodes, String name, ModuleSummary summary) {
        ObjectNode node = nodes.addObject();
        node.put("module", name);
        if (summary == null) summary = new ModuleSummary(0, 0, 0, 0, 0, 0);
        node.put("classes", summary.classes());
        node.put("source_classes", summary.sourceClasses());
        node.put("generated_classes", summary.generatedClasses());
        node.put("test_classes", summary.testClasses());
        node.put("beans", summary.beans());
        node.put("source_tokens", summary.sourceTokens());
    }

    private record Relation(String from, String to, int distance,
                            String direction, String indexedRelation) {}

    private record ModuleSummary(int classes, int sourceClasses, int generatedClasses,
                                 int testClasses, int beans, int sourceTokens) {}

    private static final class MutableSummary {
        private int classes;
        private int source;
        private int generated;
        private int tests;
        private int beans;
        private int sourceTokens;
    }
}
