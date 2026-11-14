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
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Builds a compact, evidence-backed card for one indexed class. */
final class SymbolToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> MEMBER_KINDS = Set.of("FIELD", "CONSTRUCTOR", "METHOD");

    String getSymbolDetails(ReadQueryContext reader, String target, boolean includeMembers,
            String memberKind, int memberLimit, int memberOffset) {
        String normalizedKind = normalizeMemberKind(memberKind);
        if (normalizedKind != null && !MEMBER_KINDS.contains(normalizedKind)) {
            return errorResponse("INVALID_ARGUMENT",
                    "Invalid member_kind: expected field, constructor, or method");
        }
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, target);
        if (lookup.error() != null) {
            var generated = ArcGeneratedClasses.describe(reader, target);
            if (generated != null) {
                ToolResponseSupport.appendMeta(generated, reader, 0);
                return generated.toString();
            }
            String dependency = DependencyTypeAnswers.referencedType(reader, lookup, target);
            return dependency != null ? DependencyTypeAnswers.symbol(reader, dependency) : classLookupError(reader, lookup, target);
        }
        ClassRecord cls = lookup.cls();
        List<KotlinDeclarationRecord> kotlinDeclarations =
                reader.symbols.findKotlinDeclarations(cls.id());

        ObjectNode root = JSON.createObjectNode();
        root.put("class", cls.className());
        root.put("kind", cls.kind());
        if (cls.superclass() == null) root.putNull("superclass");
        else root.put("superclass", cls.superclass());
        root.set("interfaces", JSON.valueToTree(cls.interfaces()));
        root.put("source", (cls.sourceLine() > 0 ? cls.sourceFile() + ":" + cls.sourceLine() : cls.sourceFile()));
        root.put("origin", cls.origin());
        root.put("lifecycle", cls.lifecycle());
        root.put("source_tokens", cls.sourceTokens());
        SymbolContract.appendClass(root, cls, kotlinDeclarations);
        if (!kotlinDeclarations.isEmpty()) {
            var properties = root.putArray("properties");
            KotlinProperties.list(reader, cls).forEach(property -> property.append(properties.addObject()));
        }
        appendContext(root, cls);

        List<ClassAnnotationRecord> annotations = reader.symbols.findClassAnnotations(cls.id());
        root.set("annotations", JSON.valueToTree(annotations.stream()
                .filter(ClassAnnotationRecord::direct)
                .map(ClassAnnotationRecord::annotationName).distinct().sorted().toList()));
        root.set("meta_annotations", JSON.valueToTree(annotations.stream()
                .filter(annotation -> !annotation.direct())
                .map(ClassAnnotationRecord::annotationName).distinct().sorted().toList()));
        appendKotlinDeclarations(root, kotlinDeclarations);

        ObjectNode dependencyMetrics = root.putObject("dependency_metrics");
        dependencyMetrics.put("fan_in", reader.usages.countDependents(cls.id()));
        dependencyMetrics.put("incoming_occurrences",
                reader.usages.countDependencyEdges(cls.id(), true));
        dependencyMetrics.put("fan_out", reader.usages.countDependencies(cls.id()));
        dependencyMetrics.put("outgoing_occurrences",
                reader.usages.countDependencyEdges(cls.id(), false));

        reader.beans.findBeanByClassId(cls.id())
                .ifPresent(bean -> {
                    ObjectNode node = root.putObject("bean");
                    appendBean(node, bean);
                    // V62 CQ-014: get_context named @LookupIfProperty without its values or what Instance.get() then does.
                    BeanEvidence.appendConditions(node, bean, annotations, List.of());
                });
        appendImplementations(root, cls, reader.symbols.findAllClasses());
        appendExternalDependencies(root, reader.usages.findExternalDeps(cls.id()));
        appendOccurrences(root, reader.index.findClassOccurrencesByClassIds(List.of(cls.id())).get(cls.id()));

        if (!includeMembers) {
            root.put("members_included", false);
            appendMeta(root, reader, cls.sourceTokens());
            return root.toString();
        }
        List<ClassMemberRecord> members = reader.symbols.findClassMembers(cls.id()).stream()
                .filter(member -> normalizedKind == null || normalizedKind.equals(member.kind()))
                .toList();
        int from = Math.min(memberOffset, members.size());
        int to = (int) Math.min((long) from + memberLimit, members.size());
        List<ClassMemberRecord> page = members.subList(from, to);
        root.put("members_included", true);
        if (normalizedKind == null) root.putNull("member_kind");
        else root.put("member_kind", normalizedKind.toLowerCase(Locale.ROOT));
        // Members repeat no class-level identity; find_symbol_usages and get_call_hierarchy accept
        // the class with name and descriptor, or the id built from member_symbol_id_format.
        root.put("member_symbol_id_format", SymbolContract.memberIdFormat(cls));
        ArrayNode values = root.putArray("members");
        for (ClassMemberRecord member : page) {
            ObjectNode node = values.addObject();
            node.put("kind", member.kind().toLowerCase(Locale.ROOT));
            node.put("name", member.name());
            // A method signature already names its parameter and return types.
            if ("FIELD".equals(member.kind())) node.put("type", member.typeName());
            else node.put("signature", member.signature());
            node.put("descriptor", member.descriptor());
            if (member.modifiers() != null && !member.modifiers().isBlank()) node.put("modifiers", member.modifiers());
            if (!member.annotations().isEmpty()) node.set("annotations", JSON.valueToTree(member.annotations()));
            SymbolContract.appendListedMember(node, cls, member, kotlinDeclarations);
            ArrayNode details = JSON.createArrayNode();
            member.annotationDetails().stream().filter(annotation -> annotation.parameterIndex() != null)
                    .forEach(annotation -> details.addObject()
                            .put("annotation", annotation.annotationName())
                            .put("parameter_index", annotation.parameterIndex())
                            .put("parameter_name", annotation.parameterName())
                            .put("parameter_type", annotation.parameterType()));
            if (!details.isEmpty()) node.set("parameter_annotations", details);
        }
        appendPage(root, page.size(), members.size(), memberLimit, memberOffset);
        appendMeta(root, reader, cls.sourceTokens());
        return root.toString();
    }

    private static void appendKotlinDeclarations(
            ObjectNode root, List<KotlinDeclarationRecord> declarations) {
        if (declarations.isEmpty()) return;
        root.put("language", "kotlin");
        root.put("semantic_model", declarations.getFirst().semanticModel());
        ArrayNode values = root.putArray("kotlin_declarations");
        for (KotlinDeclarationRecord declaration : declarations) {
            ObjectNode node = values.addObject();
            node.put("kind", declaration.kind().toLowerCase(Locale.ROOT));
            node.put("name", declaration.name());
            if (declaration.jvmName() == null) node.putNull("jvm_name");
            else node.put("jvm_name", declaration.jvmName());
            if (!declaration.descriptor().isBlank()) {
                node.put("descriptor", declaration.descriptor());
            }
            if (declaration.declarationTypes() != null) node.set("declaration_types", JSON.valueToTree(declaration.declarationTypes()));
            if (declaration.valueParameters() != null) {
                node.set("value_parameters", JSON.valueToTree(declaration.valueParameters()));
            }
            if (declaration.isSuspend()) node.put("suspend", true);
            if (declaration.extension()) node.put("extension", true);
            if (declaration.hasDefaultParameters()) node.put("default_parameters", true);
            if (declaration.mutable()) node.put("mutable", true);
            if (declaration.lateinit()) node.put("lateinit", true);
            if (declaration.delegated()) node.put("delegated", true);
            if (declaration.synthetic()) node.put("synthetic", true);
        }
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
        String value = kind.trim().toUpperCase(Locale.ROOT);
        // Agents ask for "methods" as often as "method".
        return value.endsWith("S") && MEMBER_KINDS.contains(value.substring(0, value.length() - 1))
                ? value.substring(0, value.length() - 1) : value;
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
