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
import org.treblereel.mcp.model.ResolutionCandidate;
import org.treblereel.mcp.model.ResolutionStatus;

/** Structural code and dependency graph queries. */
final class StructureToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GRAPH_NODES = 200;

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        return searchClasses(jdbi, pattern, null, null, limit);
    }

    String searchClasses(Jdbi jdbi, String pattern, String module, String sourceSet, int limit) {
        List<ClassRecord> classes = IndexReader.searchClasses(
                jdbi, pattern, module, sourceSet, limit + 1);
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
            appendContext(node, value);
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
        return getBeans(jdbi, className, scope, kind, profile, qualifier, null, null, limit);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet, int limit) {
        Map<String, String> filter = new HashMap<>();
        if (className != null) filter.put("class_name", className);
        if (scope != null) filter.put("scope", scope);
        if (kind != null) filter.put("kind", kind);
        if (profile != null) filter.put("profile", profile);
        if (qualifier != null) filter.put("qualifier", qualifier);
        if (module != null) filter.put("module", module);
        if (sourceSet != null) filter.put("source_set", sourceSet);

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
                appendContext(node, beanClass);
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
        appendContext(root, cls);
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
                    appendContext(child, value);
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
                    appendContext(child, value);
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
        appendContext(root, cls);
        List<InjectionPointRecord> injectionPoints = IndexReader.findInjectionPoints(
                jdbi, bean.get().id());
        Set<Integer> referencedBeanIds = new HashSet<>();
        injectionPoints.stream().map(InjectionPointRecord::resolvedBeanId)
                .filter(Objects::nonNull).forEach(referencedBeanIds::add);
        injectionPoints.stream().flatMap(point -> point.resolutionTrace().candidates().stream())
                .map(ResolutionCandidate::beanId).filter(Objects::nonNull)
                .forEach(referencedBeanIds::add);
        injectionPoints.stream().flatMap(point -> point.resolutionTrace().candidates().stream())
                .map(ResolutionCandidate::relatedBeanId).filter(Objects::nonNull)
                .forEach(referencedBeanIds::add);
        Map<Integer, BeanRecord> resolvedBeans = IndexReader.findBeansByIds(
                jdbi, referencedBeanIds);
        Map<Integer, ClassRecord> resolvedClasses = IndexReader.findClassesByIds(jdbi,
                resolvedBeans.values().stream().map(BeanRecord::classId).toList());
        ArrayNode result = root.putArray("injection_points");
        ArrayNode unsatisfied = root.putArray("unsatisfied");
        ArrayNode ambiguous = root.putArray("ambiguous");
        ArrayNode contextRequired = root.putArray("context_required");
        ArrayNode unknown = root.putArray("unknown");
        ArrayNode unsupported = root.putArray("unsupported_mechanism");
        for (InjectionPointRecord point : injectionPoints) {
            ObjectNode node = result.addObject();
            node.put("kind", point.kind());
            node.put("field", point.fieldName());
            node.put("required_type", point.targetType());
            node.set("qualifiers", JSON.valueToTree(point.qualifiers()));
            node.put("resolution", point.resolutionStatus().name().toLowerCase());
            node.put("resolution_strategy", point.resolutionStrategy());
            node.put("reason", point.resolutionReason());
            node.put("confidence", point.resolutionConfidence().name().toLowerCase());
            node.set("limitations", JSON.valueToTree(point.limitations()));
            appendResolutionTrace(node, point, resolvedBeans, resolvedClasses);
            if (point.resolutionStatus() == ResolutionStatus.AMBIGUOUS) {
                node.putNull("resolved_to");
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
            } else {
                node.putNull("resolved_to");
                switch (point.resolutionStatus()) {
                    case UNSATISFIED -> unsatisfied.add(point.fieldName());
                    case CONTEXT_REQUIRED -> contextRequired.add(point.fieldName());
                    case UNKNOWN -> unknown.add(point.fieldName());
                    case UNSUPPORTED_MECHANISM -> unsupported.add(point.fieldName());
                    default -> { }
                }
            }
        }
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    private static void appendResolutionTrace(ObjectNode node, InjectionPointRecord point,
            Map<Integer, BeanRecord> beans, Map<Integer, ClassRecord> classes) {
        ObjectNode trace = node.putObject("resolution_trace");
        trace.set("applied_rules", JSON.valueToTree(point.resolutionTrace().appliedRules()));
        trace.set("unsupported_rules",
                JSON.valueToTree(point.resolutionTrace().unsupportedRules()));
        ArrayNode candidates = trace.putArray("candidates");
        for (ResolutionCandidate candidate : point.resolutionTrace().candidates()) {
            ObjectNode candidateNode = candidates.addObject();
            if (candidate.beanId() == null) candidateNode.putNull("bean_id");
            else candidateNode.put("bean_id", candidate.beanId());
            BeanRecord bean = candidate.beanId() == null ? null : beans.get(candidate.beanId());
            ClassRecord candidateClass = bean == null ? null : classes.get(bean.classId());
            String className = candidateClass == null
                    ? candidate.className() : candidateClass.className();
            if (className == null) candidateNode.putNull("class");
            else candidateNode.put("class", className);
            if (candidateClass != null) {
                candidateNode.put("file", candidateClass.sourceFile());
                candidateNode.put("origin", candidateClass.origin());
                candidateNode.put("lifecycle", candidateClass.lifecycle());
                appendContext(candidateNode, candidateClass);
            }
            if (bean != null) {
                candidateNode.put("kind", bean.kind());
                if (bean.memberName() != null) candidateNode.put("member", bean.memberName());
                candidateNode.set("qualifiers", JSON.valueToTree(bean.qualifiers()));
            }
            candidateNode.put("disposition", candidate.disposition().name().toLowerCase());
            candidateNode.put("reason", candidate.reason());
            if (candidate.relatedBeanId() != null) {
                candidateNode.put("related_bean_id", candidate.relatedBeanId());
                BeanRecord related = beans.get(candidate.relatedBeanId());
                ClassRecord relatedClass = related == null ? null : classes.get(related.classId());
                if (relatedClass != null) {
                    candidateNode.put("related_class", relatedClass.className());
                }
            }
            candidateNode.set("rules", JSON.valueToTree(candidate.rules()));
        }
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

    private static void appendContext(ObjectNode node, ClassRecord cls) {
        if (cls.module() == null) node.putNull("module");
        else node.put("module", cls.module());
        if (cls.sourceSet() == null) node.putNull("source_set");
        else node.put("source_set", cls.sourceSet());
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
