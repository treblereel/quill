package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.FieldAccessView;
import org.treblereel.mcp.db.IndexReader.MethodCallView;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Correlates bytecode evidence that describes the lifecycle of a state-carrying type. */
final class StateLifecycleQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PERSISTENCE = Set.of(
            "persist", "save", "store", "repository", "persistence", "entitymanager", "session", "dao");
    private static final Set<String> DISPATCH = Set.of(
            "dispatch", "submit", "publish", "send", "enqueue", "worker", "invoke", "execute");
    private static final Set<String> RECOVERY = Set.of(
            "recover", "restore", "resume", "replay", "retry", "timer", "schedule", "timeout");
    private static final Set<String> SERIALIZATION = Set.of(
            "serialize", "deserialize", "mapper", "codec", "json", "marshal", "unmarshal");

    String trace(Jdbi jdbi, String target, int evidenceLimit) {
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();
        List<ClassMemberRecord> members = IndexReader.findClassMembers(jdbi, cls.id());
        List<ClassMemberRecord> constructors = members.stream()
                .filter(member -> member.kind().equals("CONSTRUCTOR")).toList();
        List<ClassMemberRecord> fields = members.stream()
                .filter(member -> member.kind().equals("FIELD")).toList();

        List<Evidence> creations = new ArrayList<>();
        for (ClassMemberRecord constructor : constructors) {
            if (constructor.descriptor().isBlank()) continue;
            for (MethodCallView usage : IndexReader.findExactMethodUsages(jdbi, cls.id(),
                    "<init>", constructor.descriptor(), evidenceLimit, 0)) {
                creations.add(Evidence.call("creation", usage, null));
            }
        }

        List<Evidence> reads = new ArrayList<>();
        List<Evidence> writes = new ArrayList<>();
        for (ClassMemberRecord field : fields) {
            if (field.descriptor().isBlank()) continue;
            for (FieldAccessView usage : IndexReader.findExactFieldUsages(jdbi, cls.id(),
                    field.name(), field.descriptor(), null, evidenceLimit, 0)) {
                Evidence evidence = Evidence.field(usage, field.name());
                (usage.accessKind().startsWith("read_") ? reads : writes).add(evidence);
            }
        }

        List<Evidence> combined = new ArrayList<>();
        combined.addAll(creations);
        combined.addAll(reads);
        combined.addAll(writes);
        List<Evidence> persistence = matching(combined, PERSISTENCE);
        List<Evidence> dispatch = matching(combined, DISPATCH);
        List<Evidence> recovery = matching(combined, RECOVERY);
        List<Evidence> serialization = matching(combined, SERIALIZATION);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("source", cls.sourceFile());
        root.put("field_count", fields.size());
        root.put("constructor_count", constructors.size());
        root.put("evidence_limit_per_member", evidenceLimit);
        appendEvidence(root.putArray("creation_sites"), creations);
        appendEvidence(root.putArray("field_writes"), writes);
        appendEvidence(root.putArray("field_reads"), reads);
        ObjectNode boundaries = root.putObject("semantic_boundaries");
        appendEvidence(boundaries.putArray("persistence"), persistence);
        appendEvidence(boundaries.putArray("dispatch"), dispatch);
        appendEvidence(boundaries.putArray("recovery"), recovery);
        appendEvidence(boundaries.putArray("serialization"), serialization);

        Set<String> roles = new LinkedHashSet<>();
        if (!persistence.isEmpty()) roles.add("persisted_state_candidate");
        if (!dispatch.isEmpty()) roles.add("dispatch_input_or_output_candidate");
        if (!recovery.isEmpty()) roles.add("recovery_state_candidate");
        if (!serialization.isEmpty()) roles.add("serialized_contract_candidate");
        if (!persistence.isEmpty() && !dispatch.isEmpty()) roles.add("durable_execution_state_candidate");
        root.set("inferred_roles", JSON.valueToTree(roles));
        root.put("inference_confidence", confidence(roles, writes, reads));
        root.put("ordered_lifecycle_proven", false);
        root.putArray("limitations")
                .add("Semantic boundary labels are name-based classifications of exact bytecode evidence")
                .add("Lifecycle evidence can span caller methods and does not prove that persistence occurs before dispatch; use analyze_execution_order on a concrete orchestrator method")
                .add("Reflection, external-library internals, runtime-generated access, and source-only changes are absent")
                .add("Use evidence locations for targeted source verification before architectural decisions");
        int naiveTokens = cls.sourceTokens() + combined.stream()
                .mapToInt(Evidence::sourceTokens).sum();
        appendMeta(root, jdbi, naiveTokens, cls.sourceFile(), cls.module());
        return root.toString();
    }

    private static List<Evidence> matching(List<Evidence> evidence, Set<String> terms) {
        return evidence.stream().filter(item -> {
            String searchable = (item.className() + " " + item.method()).toLowerCase(Locale.ROOT);
            return terms.stream().anyMatch(searchable::contains);
        }).distinct().toList();
    }

    private static String confidence(Set<String> roles, List<Evidence> writes, List<Evidence> reads) {
        if (roles.contains("durable_execution_state_candidate")
                && roles.contains("recovery_state_candidate") && !writes.isEmpty() && !reads.isEmpty()) {
            return "high";
        }
        if (!roles.isEmpty() && (!writes.isEmpty() || !reads.isEmpty())) return "medium";
        return "low";
    }

    private static void appendEvidence(ArrayNode target, List<Evidence> evidence) {
        for (Evidence item : evidence) {
            ObjectNode node = target.addObject();
            node.put("kind", item.kind());
            node.put("class", item.className());
            node.put("method", item.method());
            if (item.member() != null) node.put("member", item.member());
            if (item.accessKind() != null) node.put("access_kind", item.accessKind());
            if (item.source() != null) node.put("source", item.source());
            node.set("evidence_lines", JSON.valueToTree(item.lines()));
            node.set("instruction_ordinals", JSON.valueToTree(item.instructionOrdinals()));
            node.put("occurrence_count", item.occurrences());
        }
    }

    private record Evidence(String kind, String className, String method, String member,
            String accessKind, String source, List<Integer> lines,
            List<Integer> instructionOrdinals, int occurrences, int sourceTokens) {
        static Evidence call(String kind, MethodCallView call, String member) {
            return new Evidence(kind, call.fromClass(), call.fromMethod(), member, null,
                    call.fromSource(), call.evidenceLines(), call.instructionOrdinals(),
                    call.occurrenceCount(), call.fromSourceTokens());
        }

        static Evidence field(FieldAccessView access, String member) {
            String kind = access.accessKind().startsWith("read_") ? "field_read" : "field_write";
            return new Evidence(kind, access.fromClass(), access.fromMethod(), member,
                    access.accessKind(), access.fromSource(), access.evidenceLines(),
                    access.instructionOrdinals(), access.occurrenceCount(),
                    access.fromSourceTokens());
        }
    }
}
