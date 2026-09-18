package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ExternalDepRecord;

/** Builds a compact, evidence-backed card for one indexed class. */
final class SymbolToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MEMBER_KINDS = Set.of("FIELD", "CONSTRUCTOR", "METHOD");

    String getSymbolDetails(Jdbi jdbi, String target, boolean includeMembers,
            String memberKind, int memberLimit, int memberOffset) {
        String normalizedKind = normalizeMemberKind(memberKind);
        if (normalizedKind != null && !MEMBER_KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid member_kind: expected field, constructor, or method");
        }
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();

        ObjectNode root = JSON.createObjectNode();
        root.put("class", cls.className());
        root.put("kind", cls.kind());
        if (cls.superclass() == null) root.putNull("superclass");
        else root.put("superclass", cls.superclass());
        root.set("interfaces", JSON.valueToTree(cls.interfaces()));
        root.put("source", cls.sourceFile() + ":" + cls.sourceLine());
        root.put("origin", cls.origin());
        root.put("lifecycle", cls.lifecycle());
        root.put("source_tokens", cls.sourceTokens());
        appendContext(root, cls);

        List<ClassAnnotationRecord> annotations = IndexReader.findClassAnnotations(jdbi, cls.id());
        root.set("annotations", JSON.valueToTree(annotations.stream()
                .filter(ClassAnnotationRecord::direct)
                .map(ClassAnnotationRecord::annotationName).distinct().sorted().toList()));
        root.set("meta_annotations", JSON.valueToTree(annotations.stream()
                .filter(annotation -> !annotation.direct())
                .map(ClassAnnotationRecord::annotationName).distinct().sorted().toList()));

        ObjectNode dependencyMetrics = root.putObject("dependency_metrics");
        dependencyMetrics.put("fan_in", IndexReader.countDependents(jdbi, cls.id()));
        dependencyMetrics.put("incoming_occurrences",
                IndexReader.countDependencyEdges(jdbi, cls.id(), true));
        dependencyMetrics.put("fan_out", IndexReader.countDependencies(jdbi, cls.id()));
        dependencyMetrics.put("outgoing_occurrences",
                IndexReader.countDependencyEdges(jdbi, cls.id(), false));

        IndexReader.findBeanByClassId(jdbi, cls.id())
                .ifPresent(bean -> appendBean(root.putObject("bean"), bean));
        appendImplementations(root, cls, IndexReader.findAllClasses(jdbi));
        appendExternalDependencies(root, IndexReader.findExternalDeps(jdbi, cls.id()));
        appendOccurrences(root, IndexReader.findClassOccurrencesByClassIds(
                jdbi, List.of(cls.id())).get(cls.id()));

        if (!includeMembers) {
            root.put("members_included", false);
            appendMeta(root, jdbi, cls.sourceTokens());
            return root.toString();
        }
        List<ClassMemberRecord> members = IndexReader.findClassMembers(jdbi, cls.id()).stream()
                .filter(member -> normalizedKind == null || normalizedKind.equals(member.kind()))
                .toList();
        int from = Math.min(memberOffset, members.size());
        int to = (int) Math.min((long) from + memberLimit, members.size());
        List<ClassMemberRecord> page = members.subList(from, to);
        root.put("members_included", true);
        if (normalizedKind == null) root.putNull("member_kind");
        else root.put("member_kind", normalizedKind.toLowerCase(Locale.ROOT));
        ArrayNode values = root.putArray("members");
        for (ClassMemberRecord member : page) {
            ObjectNode node = values.addObject();
            node.put("kind", member.kind().toLowerCase(Locale.ROOT));
            node.put("name", member.name());
            node.put("signature", member.signature());
            node.put("descriptor", member.descriptor());
            node.put("type", member.typeName());
            node.set("parameters", JSON.valueToTree(member.parameterTypes()));
            node.put("modifiers", member.modifiers());
            node.set("annotations", JSON.valueToTree(member.annotations()));
        }
        appendPage(root, page.size(), members.size(), memberLimit, memberOffset);
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    private static void appendBean(ObjectNode node, BeanRecord bean) {
        node.put("kind", bean.kind());
        if (bean.scope() == null) node.putNull("scope");
        else node.put("scope", bean.scope());
        node.set("qualifiers", JSON.valueToTree(bean.qualifiers()));
        node.set("stereotypes", JSON.valueToTree(bean.stereotypes()));
        node.set("bean_types", JSON.valueToTree(bean.beanTypes()));
        node.set("profiles", JSON.valueToTree(bean.profiles()));
        node.put("alternative", bean.isAlternative());
        if (bean.priority() != null) node.put("priority", bean.priority());
    }

    private static void appendImplementations(
            ObjectNode root, ClassRecord target, List<ClassRecord> classes) {
        List<String> implementations = classes.stream()
                .filter(candidate -> target.className().equals(candidate.superclass())
                        || candidate.interfaces().contains(target.className()))
                .map(ClassRecord::className).sorted().toList();
        root.put("direct_implementation_count", implementations.size());
        root.set("direct_implementations", JSON.valueToTree(
                implementations.stream().limit(20).toList()));
        root.put("direct_implementations_truncated", implementations.size() > 20);
    }

    private static void appendExternalDependencies(
            ObjectNode root, List<ExternalDepRecord> dependencies) {
        Map<String, Long> byKind = dependencies.stream().collect(java.util.stream.Collectors
                .groupingBy(ExternalDepRecord::usageKind, java.util.TreeMap::new,
                        java.util.stream.Collectors.counting()));
        List<String> types = dependencies.stream().map(ExternalDepRecord::externalType)
                .distinct().sorted().toList();
        root.put("external_dependency_reference_count", dependencies.size());
        root.put("external_dependency_type_count", types.size());
        root.set("external_dependency_breakdown", JSON.valueToTree(byKind));
        root.set("external_dependencies", JSON.valueToTree(types.stream().limit(20).toList()));
        root.put("external_dependencies_truncated", types.size() > 20);
    }

    private static String normalizeMemberKind(String kind) {
        if (kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind)) return null;
        return kind.trim().toUpperCase(Locale.ROOT);
    }

    private static void appendContext(ObjectNode node, ClassRecord cls) {
        if (cls.module() == null) node.putNull("module");
        else node.put("module", cls.module());
        if (cls.sourceSet() == null) node.putNull("source_set");
        else node.put("source_set", cls.sourceSet());
    }

    private static void appendOccurrences(
            ObjectNode node, List<ClassOccurrenceRecord> occurrences) {
        if (occurrences == null || occurrences.isEmpty()) return;
        ArrayNode values = node.putArray("class_occurrences");
        for (ClassOccurrenceRecord occurrence : occurrences) {
            ObjectNode value = values.addObject();
            value.put("module", occurrence.module());
            value.put("source_set", occurrence.sourceSet());
            value.put("origin", occurrence.origin());
            value.put("output_directory", occurrence.outputDirectory());
            value.put("class_file", occurrence.classFile());
        }
    }
}
