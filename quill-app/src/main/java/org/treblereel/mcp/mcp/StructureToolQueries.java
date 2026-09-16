package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;
import static org.treblereel.mcp.mcp.ToolResponseSupport.isProducer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntConsumer;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

/** Structural code and dependency graph queries. */
final class StructureToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GRAPH_NODES = 200;

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        List<ClassRecord> classes = IndexReader.searchClasses(jdbi, pattern, limit + 1);
        boolean hasMore = classes.size() > limit;
        List<ClassRecord> limited = hasMore ? classes.subList(0, limit) : classes;
        Map<Integer, BeanRecord> beansByClass = IndexReader.findBeansByClassIds(
                jdbi, limited.stream().map(ClassRecord::id).toList());

        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("classes");
        int naiveTokens = 0;
        for (ClassRecord value : limited) {
            ObjectNode node = arr.addObject();
            node.put("class", value.className());
            node.put("source", value.sourceFile() + ":" + value.sourceLine());
            node.put("origin", value.origin());
            node.put("lifecycle", value.lifecycle());
            node.put("is_bean", value.isBean());
            BeanRecord bean = beansByClass.get(value.id());
            if (bean != null) node.put("scope", bean.scope());
            node.put("source_tokens", value.sourceTokens());
            naiveTokens += value.sourceTokens();
        }
        root.put("showing", limited.size());
        if (hasMore) root.put("total", ">" + limit + " (use a more specific pattern)");
        else root.put("total", limited.size());
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, int limit) {
        Map<String, String> filter = new HashMap<>();
        if (className != null) filter.put("class_name", className);
        if (scope != null) filter.put("scope", scope);
        if (kind != null) filter.put("kind", kind);
        if (profile != null) filter.put("profile", profile);
        if (qualifier != null) filter.put("qualifier", qualifier);

        List<BeanRecord> beans = IndexReader.findBeans(jdbi, filter.isEmpty() ? null : filter);
        int total = beans.size();
        List<BeanRecord> limited = beans.size() > limit ? beans.subList(0, limit) : beans;
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(
                jdbi, limited.stream().map(BeanRecord::classId).toList());
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("beans");
        int naiveTokens = 0;
        for (BeanRecord bean : limited) {
            ObjectNode node = arr.addObject();
            ClassRecord beanClass = classesById.get(bean.classId());
            node.put("class", beanClass != null ? beanClass.className() : "unknown");
            if (bean.memberName() != null) node.put("member", bean.memberName());
            if (isProducer(bean.kind()) && bean.beanTypes() != null
                    && !bean.beanTypes().isEmpty()) {
                node.put("produced_type", bean.beanTypes().getFirst());
            }
            node.put("kind", bean.kind());
            node.put("scope", bean.scope());
            node.set("qualifiers", JSON.valueToTree(bean.qualifiers()));
            node.set("bean_types", JSON.valueToTree(bean.beanTypes()));
            node.set("profiles", JSON.valueToTree(bean.profiles()));
            if (beanClass != null) {
                node.put("source", beanClass.sourceFile() + ":" + beanClass.sourceLine());
                naiveTokens += beanClass.sourceTokens();
            }
        }
        root.put("showing", limited.size());
        root.put("total", total);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();
        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("is_bean", cls.isBean());
        root.put("origin", cls.origin());
        root.put("lifecycle", cls.lifecycle());
        ObjectNode metrics = root.putObject("metrics");
        metrics.put("fan_in", IndexReader.countDependents(jdbi, cls.id()));
        metrics.put("incoming_edges", IndexReader.countDependencyEdges(jdbi, cls.id(), true));
        metrics.put("fan_out", IndexReader.countDependencies(jdbi, cls.id()));
        metrics.put("outgoing_edges", IndexReader.countDependencyEdges(jdbi, cls.id(), false));
        writeDependencyBreakdown(metrics.putObject("fan_in_breakdown"),
                IndexReader.dependencyBreakdown(jdbi, cls.id(), true));
        writeDependencyBreakdown(metrics.putObject("fan_out_breakdown"),
                IndexReader.dependencyBreakdown(jdbi, cls.id(), false));
        if (cls.isBean()) {
            IndexReader.findBeanByClassId(jdbi, cls.id())
                    .ifPresent(bean -> root.put("scope", bean.scope()));
        }
        int[] naiveTokens = {cls.sourceTokens()};
        Set<Integer> visited = new HashSet<>();
        visited.add(cls.id());
        GraphBudget budget = new GraphBudget(MAX_GRAPH_NODES);
        expandDependencies(jdbi, cls.id(), direction, depth, root, visited,
                tokens -> naiveTokens[0] += tokens, budget);
        if (budget.truncated) {
            root.put("truncated", true);
            root.put("node_limit", MAX_GRAPH_NODES);
        }
        appendMeta(root, jdbi, naiveTokens[0]);
        return root.toString();
    }

    private void expandDependencies(Jdbi jdbi, int classId, String direction, int depth,
            ObjectNode node, Set<Integer> visited, IntConsumer tokenAccum, GraphBudget budget) {
        List<DependencyRecord> dependencies = IndexReader.findDependencies(
                jdbi, classId, direction);
        ArrayNode dependsOn = node.putArray("depends_on");
        ArrayNode dependedBy = node.putArray("depended_by");
        for (DependencyRecord dependency : dependencies) {
            if (dependency.fromClassId() == classId) {
                if (!budget.claim()) break;
                IndexReader.findClassById(jdbi, dependency.toClassId()).ifPresent(value -> {
                    ObjectNode child = dependsOn.addObject();
                    child.put("class", value.className());
                    child.put("kind", dependency.kind());
                    child.put("occurrences", dependency.occurrenceCount());
                    tokenAccum.accept(value.sourceTokens());
                    if (depth > 1 && visited.add(value.id())) {
                        expandDependencies(jdbi, value.id(), direction, depth - 1, child,
                                visited, tokenAccum, budget);
                    }
                });
            }
            if (dependency.toClassId() == classId) {
                if (!budget.claim()) break;
                IndexReader.findClassById(jdbi, dependency.fromClassId()).ifPresent(value -> {
                    ObjectNode child = dependedBy.addObject();
                    child.put("class", value.className());
                    child.put("kind", dependency.kind());
                    child.put("occurrences", dependency.occurrenceCount());
                    tokenAccum.accept(value.sourceTokens());
                    if (depth > 1 && visited.add(value.id())) {
                        expandDependencies(jdbi, value.id(), direction, depth - 1, child,
                                visited, tokenAccum, budget);
                    }
                });
            }
        }
    }

    String getInjectionPoints(Jdbi jdbi, String target) {
        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();
        var bean = IndexReader.findBeanByClassId(jdbi, cls.id());
        if (bean.isEmpty()) return errorResponse("Not a bean: " + target);
        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        List<InjectionPointRecord> injectionPoints = IndexReader.findInjectionPoints(
                jdbi, bean.get().id());
        Map<Integer, BeanRecord> resolvedBeans = IndexReader.findBeansByIds(jdbi,
                injectionPoints.stream().map(InjectionPointRecord::resolvedBeanId)
                        .filter(Objects::nonNull).toList());
        Map<Integer, ClassRecord> resolvedClasses = IndexReader.findClassesByIds(jdbi,
                resolvedBeans.values().stream().map(BeanRecord::classId).toList());
        ArrayNode result = root.putArray("injection_points");
        ArrayNode unsatisfied = root.putArray("unsatisfied");
        ArrayNode ambiguous = root.putArray("ambiguous");
        for (InjectionPointRecord point : injectionPoints) {
            ObjectNode node = result.addObject();
            node.put("kind", point.kind());
            node.put("field", point.fieldName());
            node.put("required_type", point.targetType());
            node.set("qualifiers", JSON.valueToTree(point.qualifiers()));
            if (point.isAmbiguous()) {
                node.putNull("resolved_to");
                node.put("resolution", "ambiguous");
                ambiguous.add(point.fieldName());
            } else if (point.resolvedBeanId() != null) {
                BeanRecord resolved = resolvedBeans.get(point.resolvedBeanId());
                if (resolved != null) {
                    ClassRecord resolvedClass = resolvedClasses.get(resolved.classId());
                    if (resolvedClass != null) node.put("resolved_to", resolvedClass.className());
                    if (resolved.memberName() != null) {
                        node.put("resolved_member", resolved.memberName());
                    }
                    if (isProducer(resolved.kind()) && resolved.beanTypes() != null
                            && !resolved.beanTypes().isEmpty()) {
                        node.put("resolved_produced_type", resolved.beanTypes().getFirst());
                    }
                }
                node.put("resolution", "unique");
            } else {
                node.putNull("resolved_to");
                node.put("resolution", "unsatisfied");
                unsatisfied.add(point.fieldName());
            }
        }
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    private static void writeDependencyBreakdown(ObjectNode result,
            List<IndexReader.DependencyBreakdown> breakdown) {
        for (IndexReader.DependencyBreakdown entry : breakdown) {
            ObjectNode origin = result.putObject(entry.origin());
            origin.put("classes", entry.classes());
            origin.put("edges", entry.edges());
            if (entry.origin().equals("orphan_output")) origin.put("excluded_from_score", true);
        }
    }

    private static final class GraphBudget {
        private int remaining;
        private boolean truncated;

        private GraphBudget(int limit) {
            remaining = limit;
        }

        private boolean claim() {
            if (remaining == 0) {
                truncated = true;
                return false;
            }
            remaining--;
            return true;
        }
    }
}
