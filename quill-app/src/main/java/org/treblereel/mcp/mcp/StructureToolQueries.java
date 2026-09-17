package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;
import static org.treblereel.mcp.mcp.ToolResponseSupport.isProducer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
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
        return searchClasses(jdbi, pattern, null, null, limit, 0);
    }

    String searchClasses(Jdbi jdbi, String pattern, String module, String sourceSet, int limit) {
        return searchClasses(jdbi, pattern, module, sourceSet, limit, 0);
    }

    String searchClasses(Jdbi jdbi, String pattern, String module, String sourceSet,
            int limit, int offset) {
        List<ClassRecord> classes = IndexReader.searchClasses(
                jdbi, pattern, module, sourceSet, limit, offset);
        int total = IndexReader.countClasses(jdbi, pattern, module, sourceSet);
        List<ClassRecord> limited = classes;
        Map<Integer, BeanRecord> beansByClass = IndexReader.findBeansByClassIds(
                jdbi, limited.stream().map(ClassRecord::id).toList());
        Map<Integer, List<ClassOccurrenceRecord>> occurrencesByClass =
                IndexReader.findClassOccurrencesByClassIds(
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
            appendOccurrences(node, occurrencesByClass.get(value.id()));
            naiveTokens += value.sourceTokens();
        }
        appendPage(root, limited.size(), total, limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, int limit) {
        return getBeans(jdbi, className, scope, kind, profile, qualifier, null, null, limit);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet, int limit) {
        return getBeans(jdbi, className, scope, kind, profile, qualifier,
                module, sourceSet, limit, 0);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet,
            int limit, int offset) {
        if (className != null && !className.contains("*")) {
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, className);
            if (lookup.error() != null) return classLookupError(jdbi, lookup, className);
            className = lookup.cls().className();
        }
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
        int from = Math.min(offset, beans.size());
        int to = (int) Math.min((long) from + limit, beans.size());
        List<BeanRecord> limited = beans.subList(from, to);
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(
                jdbi, limited.stream().map(BeanRecord::classId).toList());
        Map<Integer, List<ClassOccurrenceRecord>> occurrencesByClass =
                IndexReader.findClassOccurrencesByClassIds(
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
            if (bean.isDefault()) node.put("default_bean", true);
            node.set("qualifiers", JSON.valueToTree(bean.qualifiers()));
            node.set("bean_types", JSON.valueToTree(bean.beanTypes()));
            node.set("profiles", JSON.valueToTree(bean.profiles()));
            if (beanClass != null) {
                node.put("source", beanClass.sourceFile() + ":" + beanClass.sourceLine());
                appendContext(node, beanClass);
                appendOccurrences(node, occurrencesByClass.get(beanClass.id()));
                naiveTokens += beanClass.sourceTokens();
            }
        }
        appendPage(root, limited.size(), total, limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        return getDependencies(jdbi, target, direction, depth,
                true, MAX_GRAPH_NODES, 0, null);
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth,
            boolean includeNodes, int limit, int offset, String cursor) {
        if (!Set.of("inbound", "outbound", "both").contains(direction)) {
            return errorResponse("Invalid direction: expected inbound, outbound, or both");
        }
        var lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();
        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("is_bean", cls.isBean());
        root.put("origin", cls.origin());
        root.put("lifecycle", cls.lifecycle());
        appendContext(root, cls);
        appendOccurrences(root, IndexReader.findClassOccurrencesByClassIds(
                jdbi, List.of(cls.id())).get(cls.id()));
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
        if (!includeNodes) {
            root.put("nodes_included", false);
            appendMeta(root, jdbi, cls.sourceTokens());
            return root.toString();
        }
        if (depth == 1) {
            if (cursor != null && !cursor.isBlank()) {
                return errorResponse("cursor is only supported when depth > 1");
            }
            List<GraphRelation> relations = relationsFor(jdbi, cls.id(), direction, 1);
            int from = Math.min(offset, relations.size());
            int to = (int) Math.min((long) from + limit, relations.size());
            List<GraphRelation> page = relations.subList(from, to);
            int naiveTokens = cls.sourceTokens() + renderDepthOne(jdbi, root, page);
            root.put("pagination", "offset");
            appendPage(root, page.size(), relations.size(), limit, offset);
            appendMeta(root, jdbi, naiveTokens);
            return root.toString();
        }
        if (offset != 0) {
            return errorResponse("offset is only supported when depth = 1; use cursor for deeper graphs");
        }
        List<GraphRelation> graph = breadthFirstRelations(jdbi, cls.id(), direction, depth);
        int cursorOffset = decodeCursor(jdbi, cursor, cls.id(), direction, depth);
        if (cursorOffset < 0) return errorResponse("Invalid or expired dependency cursor");
        int from = Math.min(cursorOffset, graph.size());
        int to = (int) Math.min((long) from + limit, graph.size());
        List<GraphRelation> page = graph.subList(from, to);
        int naiveTokens = cls.sourceTokens() + renderBreadthFirst(jdbi, root, page);
        boolean hasMore = to < graph.size();
        root.put("pagination", "cursor");
        root.put("showing", page.size());
        root.put("total", graph.size());
        root.put("limit", limit);
        root.put("has_more", hasMore);
        root.put("truncated", cursorOffset > 0 || hasMore);
        if (hasMore) {
            root.put("next_cursor", encodeCursor(jdbi, cls.id(), direction, depth, to));
        }
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private List<GraphRelation> breadthFirstRelations(
            Jdbi jdbi, int rootClassId, String direction, int maxDepth) {
        List<GraphRelation> graph = new ArrayList<>();
        ArrayDeque<Frontier> frontier = new ArrayDeque<>();
        Set<Integer> expanded = new HashSet<>();
        Set<Integer> scheduled = new HashSet<>();
        Set<DependencyKey> seen = new HashSet<>();
        frontier.add(new Frontier(rootClassId, 0));
        scheduled.add(rootClassId);
        while (!frontier.isEmpty()) {
            Frontier current = frontier.removeFirst();
            if (current.depth() >= maxDepth || !expanded.add(current.classId())) continue;
            for (GraphRelation relation : relationsFor(
                    jdbi, current.classId(), direction, current.depth() + 1)) {
                if (seen.add(DependencyKey.of(relation.dependency()))) graph.add(relation);
                if (scheduled.add(relation.related().id())) {
                    frontier.addLast(new Frontier(relation.related().id(), relation.depth()));
                }
            }
        }
        return graph;
    }

    private List<GraphRelation> relationsFor(
            Jdbi jdbi, int classId, String direction, int relationDepth) {
        List<DependencyRecord> dependencies = IndexReader.findDependencies(jdbi, classId, direction);
        Set<Integer> classIds = new HashSet<>();
        classIds.add(classId);
        for (DependencyRecord dependency : dependencies) {
            classIds.add(dependency.fromClassId());
            classIds.add(dependency.toClassId());
        }
        Map<Integer, ClassRecord> classes = IndexReader.findClassesByIds(jdbi, List.copyOf(classIds));
        ClassRecord current = classes.get(classId);
        List<GraphRelation> relations = new ArrayList<>();
        for (DependencyRecord dependency : dependencies) {
            boolean outbound = !"inbound".equals(direction)
                    && dependency.fromClassId() == classId;
            int relatedId = outbound ? dependency.toClassId() : dependency.fromClassId();
            ClassRecord related = classes.get(relatedId);
            if (current != null && related != null) {
                relations.add(new GraphRelation(current, related, dependency,
                        outbound ? "outbound" : "inbound", relationDepth));
            }
        }
        relations.sort(Comparator
                .comparing((GraphRelation value) -> value.related().className())
                .thenComparing(GraphRelation::relationDirection)
                .thenComparing(value -> value.dependency().kind())
                .thenComparingInt(value -> value.dependency().fromClassId())
                .thenComparingInt(value -> value.dependency().toClassId()));
        return relations;
    }

    private int renderDepthOne(Jdbi jdbi, ObjectNode root, List<GraphRelation> relations) {
        int tokens = 0;
        Map<Integer, List<ClassOccurrenceRecord>> occurrences = occurrences(jdbi, relations);
        for (GraphRelation relation : relations) {
            String arrayName = "outbound".equals(relation.relationDirection())
                    ? "depends_on" : "depended_by";
            ObjectNode child = array(root, arrayName).addObject();
            renderRelation(child, relation, occurrences.get(relation.related().id()));
            tokens += relation.related().sourceTokens();
        }
        return tokens;
    }

    private int renderBreadthFirst(Jdbi jdbi, ObjectNode root, List<GraphRelation> relations) {
        int tokens = 0;
        ArrayNode graph = root.putArray("graph");
        Map<Integer, List<ClassOccurrenceRecord>> occurrences = occurrences(jdbi, relations);
        for (GraphRelation relation : relations) {
            ObjectNode child = graph.addObject();
            child.put("depth", relation.depth());
            child.put("parent", relation.current().className());
            child.put("relation_direction", relation.relationDirection());
            renderRelation(child, relation, occurrences.get(relation.related().id()));
            tokens += relation.related().sourceTokens();
        }
        return tokens;
    }

    private Map<Integer, List<ClassOccurrenceRecord>> occurrences(
            Jdbi jdbi, List<GraphRelation> relations) {
        return IndexReader.findClassOccurrencesByClassIds(jdbi,
                relations.stream().map(value -> value.related().id()).distinct().toList());
    }

    private void renderRelation(ObjectNode child, GraphRelation relation,
            List<ClassOccurrenceRecord> occurrences) {
        ClassRecord related = relation.related();
        DependencyRecord dependency = relation.dependency();
        child.put("class", related.className());
        child.put("kind", dependency.kind());
        if (dependency.occurrenceCount() > 1) {
            child.put("occurrences", dependency.occurrenceCount());
        }
        ClassRecord caller = "outbound".equals(relation.relationDirection())
                ? relation.current() : related;
        appendEvidence(child, caller, dependency);
        appendContext(child, related);
        appendOccurrences(child, occurrences);
    }

    private String encodeCursor(
            Jdbi jdbi, int classId, String direction, int depth, int offset) {
        ObjectNode value = JSON.createObjectNode();
        value.put("v", 1);
        value.put("index", IndexReader.getMetadata(jdbi).getOrDefault("index_id", ""));
        value.put("class_id", classId);
        value.put("direction", direction);
        value.put("depth", depth);
        value.put("offset", offset);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private int decodeCursor(
            Jdbi jdbi, String cursor, int classId, String direction, int depth) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor),
                    StandardCharsets.UTF_8);
            var value = JSON.readTree(decoded);
            String indexId = IndexReader.getMetadata(jdbi).getOrDefault("index_id", "");
            if (value.path("v").asInt() != 1
                    || !Objects.equals(value.path("index").asText(), indexId)
                    || value.path("class_id").asInt() != classId
                    || !Objects.equals(value.path("direction").asText(), direction)
                    || value.path("depth").asInt() != depth
                    || value.path("offset").asInt(-1) < 0) return -1;
            return value.path("offset").asInt();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static void appendEvidence(
            ObjectNode child, ClassRecord caller, DependencyRecord dependency) {
        if (dependency.evidenceLines().isEmpty()) return;
        child.put("evidence_file", caller.sourceFile());
        child.set("evidence_lines", JSON.valueToTree(dependency.evidenceLines()));
    }

    private static ArrayNode array(ObjectNode node, String name) {
        return node.has(name) ? (ArrayNode) node.get(name) : node.putArray(name);
    }

    private static void appendOccurrences(
            ObjectNode node, List<ClassOccurrenceRecord> occurrences) {
        if (occurrences == null || occurrences.size() < 2) return;
        node.put("occurrence_count", occurrences.size());
        ArrayNode values = node.putArray("class_occurrences");
        for (ClassOccurrenceRecord occurrence : occurrences) {
            ObjectNode value = values.addObject();
            value.put("module", occurrence.module());
            value.put("source_set", occurrence.sourceSet());
            value.put("origin", occurrence.origin());
            value.put("output_directory", occurrence.outputDirectory());
            value.put("class_file", occurrence.classFile());
            if (occurrence.sourceFile() != null) {
                value.put("source_file", occurrence.sourceFile());
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

    private record Frontier(int classId, int depth) {}

    private record GraphRelation(
            ClassRecord current,
            ClassRecord related,
            DependencyRecord dependency,
            String relationDirection,
            int depth) {}

    private record DependencyKey(
            int fromClassId, int toClassId, String kind, Integer injectionPointId) {

        private static DependencyKey of(DependencyRecord dependency) {
            return new DependencyKey(dependency.fromClassId(), dependency.toClassId(),
                    dependency.kind(), dependency.injectionPointId());
        }
    }
}
