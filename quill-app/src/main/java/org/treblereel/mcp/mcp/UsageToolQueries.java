package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.treblereel.mcp.db.UsageRepository;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;

/** Queries that explain where and how an indexed class is used. */
final class UsageToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> USAGE_KINDS = Set.of(
            "annotation", "constructor_call", "field_access", "inheritance", "injection",
            "method_call", "service_loader", "type_reference", "callback_reference", "method_handle_reference", "field_handle_reference", "bootstrap_reference");

    String findUsages(ReadQueryContext reader, String target, String usageKind,
            String module, int limit, int offset) {
        return findUsages(reader, target, usageKind, module, limit, offset, true);
    }

    String findUsages(ReadQueryContext reader, String target, String usageKind,
            String module, int limit, int offset, boolean includeLiveMeta) {
        String normalizedKind = normalizeKind(usageKind);
        if (normalizedKind != null && !USAGE_KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid usage_kind: expected one of "
                    + USAGE_KINDS.stream().sorted().toList());
        }
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, target);
        if (lookup.error() != null) {
            // The workspace caller (includeLiveMeta false) resolves a miss through its own external type index.
            String dependency = includeLiveMeta ? DependencyTypeAnswers.referencedType(reader, lookup, target) : null;
            if (dependency != null) return DependencyTypeAnswers.usages(reader, dependency, normalizedKind);
            return includeLiveMeta ? classLookupError(reader, lookup, target)
                    : ClassTargetResolver.errorResponse(JSON, lookup, target).toString();
        }
        ClassRecord targetClass = lookup.cls();

        List<Usage> usages = collectUsages(reader, targetClass);
        Set<Integer> callerIds = new HashSet<>();
        usages.forEach(usage -> callerIds.add(usage.caller().id()));
        Map<Integer, List<ClassOccurrenceRecord>> occurrences =
                reader.index.findClassOccurrencesByClassIds(callerIds);
        usages = usages.stream()
                .filter(usage -> normalizedKind == null
                        || normalizedKind.equals(usage.usageKind()))
                .filter(usage -> matchesModule(
                        usage.caller(), occurrences.get(usage.caller().id()), module))
                .sorted(Comparator.comparing(Usage::usageKind)
                        .thenComparing(usage -> usage.caller().className())
                        .thenComparing(Usage::indexedKind))
                .toList();

        int totalOccurrences = usages.stream().mapToInt(Usage::occurrenceCount).sum();
        int from = Math.min(offset, usages.size());
        int to = (int) Math.min((long) from + limit, usages.size());
        List<Usage> page = usages.subList(from, to);
        Map<Integer, List<UsageRepository.InvokedMemberView>> members = reader.usages.findInvokedMembers(
                targetClass.id(), page.stream()
                        .filter(usage -> "method_call".equals(usage.usageKind()))
                        .map(usage -> usage.caller().id()).toList());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", targetClass.className());
        root.put("granularity", "class");
        if (normalizedKind == null) root.putNull("usage_kind");
        else root.put("usage_kind", normalizedKind);
        if (module == null) root.putNull("module_filter");
        else root.put("module_filter", module);
        root.set("supported_usage_kinds", JSON.valueToTree(USAGE_KINDS.stream().sorted().toList()));
        root.put("usage_group_count", usages.size());
        root.put("usage_occurrence_count", totalOccurrences);
        root.putArray("limitations").add("Class-level references are aggregated; use find_symbol_usages for exact member identities");

        ArrayNode values = root.putArray("usages");
        int naiveTokens = targetClass.sourceTokens();
        for (Usage usage : page) {
            ObjectNode node = values.addObject();
            node.put("class", usage.caller().className());
            node.put("usage_kind", usage.usageKind());
            node.put("indexed_kind", usage.indexedKind());
            node.put("occurrences", usage.occurrenceCount());
            List<UsageRepository.InvokedMemberView> invoked = "method_call".equals(usage.usageKind())
                    ? members.getOrDefault(usage.caller().id(), List.of()) : List.of();
            if (!invoked.isEmpty()) {
                node.put("invoked_member_groups", invoked.getFirst().groupCount());
                node.put("invoked_members_truncated", invoked.getFirst().groupCount() > invoked.size());
                ArrayNode names = node.putArray("invoked_members");
                for (var member : invoked) {
                    ObjectNode value = names.addObject();
                    value.put("name", member.name());
                    value.put("descriptor", member.descriptor());
                    value.put("invocation_kind", member.invocationKind());
                    value.put("instruction_occurrences", member.instructionOccurrences());
                }
            }
            node.put("source", (usage.caller().sourceLine() > 0 ? usage.caller().sourceFile() + ":" + usage.caller().sourceLine() : usage.caller().sourceFile()));
            node.put("origin", usage.caller().origin());
            appendContext(node, usage.caller());
            if (!usage.evidenceLines().isEmpty()) {
                node.put("evidence_file", usage.caller().sourceFile());
                node.set("evidence_lines", JSON.valueToTree(usage.evidenceLines()));
            }
            appendOccurrences(node, occurrences.get(usage.caller().id()));
            naiveTokens += usage.caller().sourceTokens();
        }
        appendPage(root, page.size(), usages.size(), limit, offset);
        UsageCoverage.append(root,reader,targetClass.id());
        if (includeLiveMeta) appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    private List<Usage> collectUsages(ReadQueryContext reader, ClassRecord target) {
        Map<UsageKey, Usage> result = new LinkedHashMap<>();
        List<DependencyRecord> dependencies =
                reader.usages.findDependencies(target.id(), "inbound");
        Map<Integer, ClassRecord> callers = reader.symbols.findClassesByIds(dependencies.stream().map(DependencyRecord::fromClassId).distinct().toList());
        for (DependencyRecord dependency : dependencies) {
            ClassRecord caller = callers.get(dependency.fromClassId());
            if (caller == null) continue;
            add(result, new Usage(caller, usageKind(dependency.kind()), dependency.kind(),
                    dependency.occurrenceCount(), dependency.evidenceLines()));
        }

        for (ClassRecord candidate : reader.symbols.findAllClasses()) {
            if (Objects.equals(candidate.superclass(), target.className())) {
                add(result, new Usage(candidate, "inheritance", "EXTENDS", 1, List.of()));
            }
            if (candidate.interfaces().contains(target.className())) {
                String kind = "INTERFACE".equals(candidate.kind())
                        ? "EXTENDS_INTERFACE" : "IMPLEMENTS";
                add(result, new Usage(candidate, "inheritance", kind, 1, List.of()));
            }
        }

        List<ClassRecord> annotated = reader.symbols.findAnnotatedClasses(target.className(), true, Integer.MAX_VALUE, 0);
        Map<Integer, List<ClassAnnotationRecord>> annotations =
                reader.symbols.findClassAnnotations(target.className(),
                        annotated.stream().map(ClassRecord::id).toList(), true);
        for (ClassRecord candidate : annotated) {
            List<ClassAnnotationRecord> matches = annotations.getOrDefault(
                    candidate.id(), List.of());
            boolean direct = matches.stream().anyMatch(ClassAnnotationRecord::direct);
            add(result, new Usage(candidate, "annotation",
                    direct ? "ANNOTATED_WITH" : "META_ANNOTATED_WITH", 1, List.of()));
        }
        return new ArrayList<>(result.values());
    }

    private static void add(Map<UsageKey, Usage> result, Usage usage) {
        UsageKey key = new UsageKey(
                usage.caller().id(), usage.usageKind(), usage.indexedKind());
        Usage previous = result.get(key);
        if (previous == null) {
            result.put(key, usage);
            return;
        }
        Set<Integer> lines = new java.util.TreeSet<>(previous.evidenceLines());
        lines.addAll(usage.evidenceLines());
        result.put(key, new Usage(previous.caller(), previous.usageKind(), previous.indexedKind(),
                previous.occurrenceCount() + usage.occurrenceCount(), List.copyOf(lines)));
    }

    private static String usageKind(String indexedKind) {
        return switch (indexedKind) {
            case "CONSTRUCTS" -> "constructor_call";
            case "CALLS" -> "method_call";
            case "CALLBACK_REFERENCE" -> "callback_reference";
            case "METHOD_HANDLE_REFERENCE" -> "method_handle_reference";
            case "FIELD_HANDLE_REFERENCE" -> "field_handle_reference";
            case "BOOTSTRAP_REFERENCE" -> "bootstrap_reference";
            case "FIELD_ACCESS" -> "field_access";
            case "CDI_INJECT", "SPRING_INJECT" -> "injection";
            case "SERVICE_PROVIDES", "SERVICE_CONSUMES" -> "service_loader";
            default -> "type_reference";
        };
    }

    private static String normalizeKind(String usageKind) {
        if (usageKind == null || usageKind.isBlank() || "all".equalsIgnoreCase(usageKind)) {
            return null;
        }
        String normalized = usageKind.trim().toLowerCase(java.util.Locale.ROOT);
        return "di".equals(normalized) ? "injection" : normalized;
    }

    private static boolean matchesModule(ClassRecord caller,
            List<ClassOccurrenceRecord> occurrences, String module) {
        if (module == null) return true;
        if (Objects.equals(module, caller.module())) return true;
        return occurrences != null && occurrences.stream()
                .anyMatch(occurrence -> Objects.equals(module, occurrence.module()));
    }

    private static void appendContext(ObjectNode node, ClassRecord cls) {
        if (cls.module() == null) node.putNull("module");
        else node.put("module", cls.module());
        if (cls.sourceSet() == null) node.putNull("source_set");
        else node.put("source_set", cls.sourceSet());
    }

    private static void appendOccurrences(
            ObjectNode node, List<ClassOccurrenceRecord> occurrences) {
        if (occurrences == null || occurrences.size() < 2) return;
        node.put("class_occurrence_count", occurrences.size());
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

    private record Usage(
            ClassRecord caller,
            String usageKind,
            String indexedKind,
            int occurrenceCount,
            List<Integer> evidenceLines) {}

    private record UsageKey(int callerId, String usageKind, String indexedKind) {}
}
