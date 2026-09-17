package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.FieldUsage;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Finds private fields with no indexed reads, conservatively excluding runtime-managed fields. */
final class UnusedFieldQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String findUnusedFields(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, boolean includeWriteOnly, int limit, int offset) {
        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi);
        Map<Integer, List<ClassMemberRecord>> members = IndexReader.findClassMembers(
                jdbi, classes.stream().map(ClassRecord::id).toList());
        Map<FieldKey, FieldUsage> usages = new HashMap<>();
        for (FieldUsage usage : IndexReader.findFieldUsages(jdbi)) {
            usages.put(new FieldKey(usage.classId(), usage.fieldName(), usage.descriptor()), usage);
        }
        Set<String> serializableClasses = serializableClasses(classes);
        Map<String, Integer> excluded = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        int analyzedPrivateFields = 0;
        for (ClassRecord cls : classes) {
            if (!inRequestedScope(cls, module, includeGenerated, includeTests, excluded)) continue;
            for (ClassMemberRecord member : members.getOrDefault(cls.id(), List.of())) {
                if (!"FIELD".equals(member.kind()) || !hasModifier(member, "private")) continue;
                analyzedPrivateFields++;
                if (!member.annotations().isEmpty()) {
                    increment(excluded, "annotated_field");
                    continue;
                }
                if (hasModifier(member, "static") && hasModifier(member, "final")) {
                    increment(excluded, "static_final_constant");
                    continue;
                }
                if (serializableClasses.contains(cls.className())) {
                    increment(excluded, "serializable_state");
                    continue;
                }
                String descriptor = JvmDescriptors.fieldDescriptor(member);
                if (descriptor == null) {
                    increment(excluded, "descriptor_not_resolved");
                    continue;
                }
                FieldUsage usage = usages.get(new FieldKey(cls.id(), member.name(), descriptor));
                int reads = usage == null ? 0 : usage.readOccurrences();
                int writes = usage == null ? 0 : usage.writeOccurrences();
                if (reads > 0) {
                    increment(excluded, "indexed_read");
                    continue;
                }
                if (writes > 0 && !includeWriteOnly) {
                    increment(excluded, "write_only_not_requested");
                    continue;
                }
                String classification = writes > 0 ? "write_only" : "never_accessed";
                String confidence = writes > 0 || cls.isBean() ? "low" : "medium";
                candidates.add(new Candidate(cls, member, descriptor, usage,
                        classification, confidence));
            }
        }
        candidates.sort(Comparator
                .comparingInt((Candidate candidate) -> confidenceOrder(candidate.confidence()))
                .thenComparing(candidate -> candidate.owner().className())
                .thenComparing(candidate -> candidate.member().name()));

        int from = Math.min(offset, candidates.size());
        int to = (int) Math.min((long) from + limit, candidates.size());
        List<Candidate> page = candidates.subList(from, to);
        ObjectNode root = JSON.createObjectNode();
        root.put("classification", "private_field_candidates_not_proven_dead_code");
        root.put("visibility_scope", "private_only");
        if (module == null) root.putNull("module_filter");
        else root.put("module_filter", module);
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("include_write_only", includeWriteOnly);
        root.put("analyzed_private_fields", analyzedPrivateFields);
        root.set("excluded_reason_counts", JSON.valueToTree(excluded));
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Reflection, JNI, serialization, persistence, templates, configuration, and framework conventions can access private fields without bytecode instructions");
        limitations.add("Annotated fields, static-final constants, and fields on known Serializable classes are excluded conservatively");
        limitations.add("Write-only fields are lower-confidence candidates and are opt-in");
        limitations.add("Only the indexed compiled snapshot is analyzed; inspect freshness metadata before deleting code");

        ArrayNode values = root.putArray("candidates");
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = 0;
        for (Candidate candidate : page) {
            ClassRecord cls = candidate.owner();
            ClassMemberRecord member = candidate.member();
            FieldUsage usage = candidate.usage();
            ObjectNode node = values.addObject();
            node.put("class", cls.className());
            node.put("field", member.name());
            node.put("signature", member.signature());
            node.put("descriptor", candidate.descriptor());
            node.put("type", member.typeName());
            node.put("modifiers", member.modifiers());
            node.put("candidate_kind", candidate.classification());
            node.put("confidence", candidate.confidence());
            node.put("read_occurrences", usage == null ? 0 : usage.readOccurrences());
            node.put("reader_classes", usage == null ? 0 : usage.readerClassCount());
            node.put("write_occurrences", usage == null ? 0 : usage.writeOccurrences());
            node.put("writer_classes", usage == null ? 0 : usage.writerClassCount());
            if (cls.sourceFile() == null) node.putNull("source");
            else node.put("source", cls.sourceFile() + ":" + cls.sourceLine());
            node.put("origin", cls.origin());
            if (cls.module() == null) node.putNull("module");
            else node.put("module", cls.module());
            if (cls.sourceSet() == null) node.putNull("source_set");
            else node.put("source_set", cls.sourceSet());
            ArrayNode cautions = node.putArray("cautions");
            if (cls.isBean()) cautions.add("Declaring class is a DI bean");
            if ("write_only".equals(candidate.classification())) {
                cautions.add("The field is written by indexed bytecode but no indexed read was found");
            }
            if (countedClasses.add(cls.id())) naiveTokens += cls.sourceTokens();
        }
        appendPage(root, page.size(), candidates.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static Set<String> serializableClasses(List<ClassRecord> classes) {
        Set<String> result = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (ClassRecord cls : classes) {
                if (result.contains(cls.className())) continue;
                boolean serializable = cls.interfaces().contains("java.io.Serializable")
                        || result.contains(cls.superclass())
                        || cls.interfaces().stream().anyMatch(result::contains);
                if (serializable && result.add(cls.className())) changed = true;
            }
        } while (changed);
        return result;
    }

    private static boolean inRequestedScope(ClassRecord cls, String module,
            boolean includeGenerated, boolean includeTests, Map<String, Integer> excluded) {
        if (module != null && !module.equals(cls.module())) {
            increment(excluded, "module_filter");
            return false;
        }
        if ("dependency".equals(cls.origin())) {
            increment(excluded, "dependency_class");
            return false;
        }
        if (!includeGenerated && "generated".equals(cls.origin())) {
            increment(excluded, "generated_class");
            return false;
        }
        if (!includeTests && "test".equals(cls.sourceSet())) {
            increment(excluded, "test_class");
            return false;
        }
        return true;
    }

    private static boolean hasModifier(ClassMemberRecord member, String modifier) {
        return List.of(member.modifiers().split("\\s+")).contains(modifier);
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.merge(key, 1, Integer::sum);
    }

    private static int confidenceOrder(String confidence) {
        return "medium".equals(confidence) ? 0 : 1;
    }

    private record FieldKey(int classId, String fieldName, String descriptor) {}

    private record Candidate(
            ClassRecord owner,
            ClassMemberRecord member,
            String descriptor,
            FieldUsage usage,
            String classification,
            String confidence) {}
}
