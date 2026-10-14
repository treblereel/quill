package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendRetryWith;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.FieldAccessView;
import org.treblereel.mcp.db.IndexReader.MethodCallView;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Finds exact bytecode usages of a selected method, constructor, or field declaration. */
final class SymbolUsageQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS = Set.of("METHOD", "CONSTRUCTOR", "FIELD", "PROPERTY");
    private static final Set<String> ACCESS_MODES = Set.of("ALL", "READ", "WRITE");

    String findSymbolUsages(Jdbi jdbi, String target, String name, String kind,
            String signature, String access, int limit, int offset) {
        try {
            SymbolContract.Reference reference = SymbolContract.parse(target).orElse(null);
            if (reference != null) {
                name = reference.jvmName();
                kind = reference.kind();
                signature = reference.descriptor();
            }
        } catch (SymbolContract.ParseException error) {
            return errorResponse(error.errorCode(), error.getMessage());
        }
        String normalizedKind = kind == null ? "" : kind.strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            return errorResponse("INVALID_ARGUMENT",
                    "Invalid kind: expected method, constructor, field, or property");
        }
        String normalizedAccess = access == null
                ? "ALL" : access.strip().toUpperCase(Locale.ROOT);
        if (!ACCESS_MODES.contains(normalizedAccess)) {
            return errorResponse("INVALID_ARGUMENT",
                    "Invalid access: expected all, read, or write");
        }
        if (!Set.of("FIELD", "PROPERTY").contains(normalizedKind) && !"ALL".equals(normalizedAccess)) {
            return errorResponse("INVALID_ARGUMENT", "The access filter applies only to fields or properties");
        }
        String requestedName = name;
        String requestedSignature = signature;
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();
        if ("PROPERTY".equals(normalizedKind)) {
            return propertyUsages(jdbi, cls, requestedName, requestedSignature,
                    normalizedAccess, limit, offset);
        }
        List<KotlinDeclarationRecord> kotlinDeclarations =
                IndexReader.findKotlinDeclarations(jdbi, cls.id());

        List<ClassMemberRecord> kindMembers = IndexReader.findClassMembers(jdbi, cls.id()).stream()
                .filter(member -> member.kind().equals(normalizedKind)).toList();
        List<ClassMemberRecord> namedMembers = kindMembers.stream()
                .filter(member -> matchesName(
                        member, requestedName, normalizedKind, kotlinDeclarations)).toList();
        List<ClassMemberRecord> matches = requestedSignature == null
                || requestedSignature.isBlank()
                ? namedMembers
                : namedMembers.stream().filter(member -> member.signature().equals(
                        requestedSignature.strip())
                        || member.descriptor().equals(requestedSignature.strip())).toList();
        if (matches.size() != 1) {
            ObjectNode error = JSON.createObjectNode();
            boolean ambiguous = !matches.isEmpty();
            appendError(error, ambiguous ? "AMBIGUOUS_SYMBOL" : "SYMBOL_NOT_FOUND",
                    ambiguous ? "Ambiguous symbol" : "Symbol not found");
            error.put("target", cls.className());
            error.put("kind", normalizedKind.toLowerCase(Locale.ROOT));
            error.put("name", requestedName);
            if (requestedSignature != null) error.put("signature", requestedSignature);
            List<ClassMemberRecord> candidates = matches.isEmpty() ? namedMembers : matches;
            if (candidates.isEmpty()) candidates = kindMembers;
            ArrayNode values = error.putArray("candidates");
            candidates.forEach(candidate -> appendCandidate(values, cls, candidate));
            if (ambiguous) {
                appendRetryWith(error, "signature",
                        "Use one candidate's exact signature, descriptor, or symbol_id");
            }
            appendMeta(error, jdbi, 0);
            return error.toString();
        }

        ClassMemberRecord selected = matches.getFirst();
        KotlinDeclarationRecord kotlinDeclaration = KotlinMemberNames
                .declaration(selected, kotlinDeclarations).orElse(null);
        if (selected.descriptor().isBlank()) {
            ObjectNode error = JSON.createObjectNode();
            appendError(error, "DESCRIPTOR_UNAVAILABLE",
                    "Symbol descriptor is unavailable; rebuild the Quill index");
            appendCandidate(error.putArray("candidates"), cls, selected);
            appendMeta(error, jdbi, 0);
            return error.toString();
        }
        return "FIELD".equals(normalizedKind)
                ? fieldUsages(jdbi, cls, selected, normalizedAccess, limit, offset)
                : methodUsages(jdbi, cls, selected, kotlinDeclaration, limit, offset);
    }

    private String methodUsages(Jdbi jdbi, ClassRecord cls, ClassMemberRecord selected,
            KotlinDeclarationRecord kotlinDeclaration, int limit, int offset) {
        String bytecodeName = "CONSTRUCTOR".equals(selected.kind()) ? "<init>" : selected.name();
        boolean kotlin = !IndexReader.findKotlinDeclarations(jdbi, cls.id()).isEmpty();
        var normalized = kotlin ? KotlinUsageNormalizer.inbound(jdbi, cls.id(), bytecodeName, selected.descriptor())
                : new KotlinUsageNormalizer.Result(IndexReader.findExactMethodUsages(jdbi, cls.id(),
                    bytecodeName, selected.descriptor(), limit, offset).stream()
                    .map(call -> new KotlinUsageNormalizer.Usage(call, List.of(call))).toList(), false);
        var page = kotlin ? normalized.usages().stream().skip(offset).limit(limit).toList() : normalized.usages();
        int total = kotlin ? normalized.usages().size() : IndexReader.countExactMethodUsages(
                jdbi, cls.id(), bytecodeName, selected.descriptor());
        ObjectNode root = base(cls, selected, kotlinDeclaration);
        root.put("usage_scope", "exact_indexed_bytecode_references");
        JavaDispatchEvidence dispatch = new JavaDispatchEvidence(jdbi);
        ArrayNode values = root.putArray("usages");
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = cls.sourceTokens();
        JavaSourceLocations javaLocations = new JavaSourceLocations(jdbi);
        String dynamicBasis = "2".equals(IndexReader.getMetadata(jdbi).get("invokedynamic_evidence_protocol"))
                ? "lambda_metafactory" : "legacy_unclassified";
        for (var resolved : page) {
            MethodCallView usage = resolved.call();
            ObjectNode node = values.addObject();
            appendCaller(node, usage.fromClass(), usage.fromMethod(), usage.fromDescriptor(),
                    usage.fromSource(), usage.fromSourceLine(), usage.fromOrigin(),
                    usage.fromModule());
            node.put("usage_kind", "dynamic".equals(usage.invocationKind())
                    ? "lambda_metafactory".equals(dynamicBasis) ? "callback_reference" : "dynamic_handle_reference" : "call");
            node.put("execution_semantics", "dynamic".equals(usage.invocationKind())
                    ? "lambda_metafactory".equals(dynamicBasis) ? "callback_creation" : "bootstrap_reference_unknown" : "invocation_instruction");
            if ("dynamic".equals(usage.invocationKind())) node.put("reference_basis",dynamicBasis);
            node.put("invocation_kind", usage.invocationKind());
            if (!kotlin) dispatch.appendTarget(node, usage);
            node.put("occurrence_count", usage.occurrenceCount());
            node.set("evidence_lines", JSON.valueToTree(usage.evidenceLines()));
            appendPath(node, resolved);
            KotlinSourceLocations.append(jdbi, node, usage.fromClassId(), usage.evidenceLines());
            if (!kotlin) javaLocations.append(node, usage.fromSource(), usage.fromClass(), usage.fromMethod(), usage.fromDescriptor(),
                    cls.className(), bytecodeName, selected.descriptor(), "call", root.path("symbol_id").asText(), usage.evidenceLines());
            if (countedClasses.add(usage.fromClassId())) naiveTokens += usage.fromSourceTokens();
        }
        limitations(root).add("Virtual/interface calls identify the bytecode-declared owner, not every runtime dispatch target");
        root.put("normalization_truncated", normalized.truncated());
        root.put("answer_complete", !normalized.truncated());
        if (!kotlin) dispatch.appendPossibleCallers(root, cls, selected, limit, offset);
        appendPage(root, page.size(), total, limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private String propertyUsages(Jdbi jdbi, ClassRecord cls, String name, String signature,
            String access, int limit, int offset) {
        var matches = KotlinProperties.list(jdbi, cls).stream()
                .filter(property -> property.name().equals(name))
                .filter(property -> signature == null || signature.isBlank()
                        || property.descriptor().equals(signature)).toList();
        if (matches.size() != 1) {
            ObjectNode error = JSON.createObjectNode();
            appendError(error, matches.isEmpty() ? "SYMBOL_NOT_FOUND" : "AMBIGUOUS_SYMBOL",
                    matches.isEmpty() ? "Property not found" : "Ambiguous property");
            var candidates = error.putArray("candidates");
            matches.forEach(property -> property.append(candidates.addObject()));
            appendMeta(error, jdbi, 0);
            return error.toString();
        }
        var property = matches.getFirst();
        ObjectNode root = JSON.createObjectNode();
        property.append(root); root.put("target", cls.className()); root.put("access", access.toLowerCase(Locale.ROOT));
        var usages = new java.util.ArrayList<ObjectNode>();
        boolean truncated = false;
        for (var binding : property.bindings()) {
            if (binding.field()) {
                for (var usage : IndexReader.findExactFieldUsages(jdbi, binding.owner().id(),
                        binding.name(), binding.descriptor(), "ALL".equals(access) ? null
                            : access.toLowerCase(Locale.ROOT), Integer.MAX_VALUE, 0)) {
                    var node = JSON.createObjectNode();
                    appendCaller(node, usage.fromClass(), usage.fromMethod(), usage.fromDescriptor(),
                            usage.fromSource(), usage.fromSourceLine(), usage.fromOrigin(), usage.fromModule());
                    node.put("usage_kind", usage.accessKind().startsWith("read_") ? "read" : "write");
                    node.put("access_kind", usage.accessKind());
                    node.put("jvm_member", binding.name()); node.put("occurrence_count", usage.occurrenceCount());
                    node.set("evidence_lines", JSON.valueToTree(usage.evidenceLines()));
                    // Internal accessor field access is implementation evidence, not another source usage.
                    boolean accessor = property.bindings().stream().anyMatch(other -> !other.field()
                            && other.owner().className().equals(usage.fromClass())
                            && other.name().equals(usage.fromMethod())
                            && other.descriptor().equals(usage.fromDescriptor()));
                    if (!accessor) usages.add(node);
                }
            } else if ("ALL".equals(access) || binding.write() == "WRITE".equals(access)) {
                var result = KotlinUsageNormalizer.inbound(jdbi, binding.owner().id(), binding.name(), binding.descriptor());
                truncated |= result.truncated();
                for (var resolved : result.usages()) {
                    var usage = resolved.call(); var node = JSON.createObjectNode();
                    appendCaller(node, usage.fromClass(), usage.fromMethod(), usage.fromDescriptor(),
                            usage.fromSource(), usage.fromSourceLine(), usage.fromOrigin(), usage.fromModule());
                    node.put("usage_kind", binding.write() ? "write" : "read");
                    node.put("access_kind", binding.write() ? "setter_call" : "getter_call");
                    node.put("jvm_member", binding.name()); node.put("occurrence_count", usage.occurrenceCount());
                    node.set("evidence_lines", JSON.valueToTree(usage.evidenceLines()));
                    appendPath(node, resolved);
                    KotlinSourceLocations.append(jdbi, node, usage.fromClassId(), usage.evidenceLines()); usages.add(node);
                }
            }
        }
        usages.sort(java.util.Comparator.comparing(ObjectNode::toString));
        var page = usages.stream().skip(offset).limit(limit).toList();
        root.set("usages", JSON.valueToTree(page)); root.put("normalization_truncated", truncated);
        root.put("answer_complete", !truncated);
        limitations(root); appendPage(root, page.size(), usages.size(), limit, offset);
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    static void appendPath(ObjectNode node, KotlinUsageNormalizer.Usage resolved) {
        if (resolved.path().size() <= 1) return;
        node.put("normalized", true);
        var path = node.putArray("bytecode_path");
        for (var edge : resolved.path()) {
            var item = path.addObject();
            item.put("caller_class", edge.fromClass()); item.put("caller_method", edge.fromMethod());
            item.put("caller_descriptor", edge.fromDescriptor());
            item.put("callee_class", edge.toClass()); item.put("callee_method", edge.toMethod());
            item.put("callee_descriptor", edge.toDescriptor());
            item.set("evidence_lines", JSON.valueToTree(edge.evidenceLines()));
        }
    }

    private String fieldUsages(Jdbi jdbi, ClassRecord cls, ClassMemberRecord selected,
            String access, int limit, int offset) {
        String accessFilter = "ALL".equals(access) ? null : access.toLowerCase(Locale.ROOT);
        List<FieldAccessView> usages = IndexReader.findExactFieldUsages(
                jdbi, cls.id(), selected.name(), selected.descriptor(),
                accessFilter, limit, offset);
        int total = IndexReader.countExactFieldUsages(
                jdbi, cls.id(), selected.name(), selected.descriptor(), accessFilter);
        ObjectNode root = base(cls, selected, null);
        root.put("access", access.toLowerCase(Locale.ROOT));
        ArrayNode values = root.putArray("usages");
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = cls.sourceTokens();
        JavaSourceLocations javaLocations = new JavaSourceLocations(jdbi);
        for (FieldAccessView usage : usages) {
            ObjectNode node = values.addObject();
            appendCaller(node, usage.fromClass(), usage.fromMethod(), usage.fromDescriptor(),
                    usage.fromSource(), usage.fromSourceLine(), usage.fromOrigin(),
                    usage.fromModule());
            node.put("usage_kind", usage.accessKind().startsWith("read_") ? "read" : "write");
            node.put("access_kind", usage.accessKind());
            node.put("occurrence_count", usage.occurrenceCount());
            node.set("evidence_lines", JSON.valueToTree(usage.evidenceLines()));
            javaLocations.append(node, usage.fromSource(), usage.fromClass(), usage.fromMethod(), usage.fromDescriptor(),
                    cls.className(), selected.name(), selected.descriptor(), usage.accessKind().startsWith("read_") ? "read" : "write",
                    root.path("symbol_id").asText(), usage.evidenceLines());
            if (countedClasses.add(usage.fromClassId())) naiveTokens += usage.fromSourceTokens();
        }
        limitations(root);
        appendPage(root, usages.size(), total, limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static ObjectNode base(ClassRecord cls, ClassMemberRecord selected,
            KotlinDeclarationRecord kotlinDeclaration) {
        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("kind", selected.kind().toLowerCase(Locale.ROOT));
        root.put("name", selected.name());
        root.put("signature", selected.signature());
        root.put("descriptor", selected.descriptor());
        SymbolContract.appendMember(root, cls, selected,
                kotlinDeclaration == null ? List.of() : List.of(kotlinDeclaration));
        if (kotlinDeclaration != null) {
            root.put("kotlin_name", kotlinDeclaration.name());
            if (kotlinDeclaration.isSuspend()) root.put("suspend", true);
            if (kotlinDeclaration.extension()) root.put("extension", true);
            if (kotlinDeclaration.hasDefaultParameters()) {
                root.put("default_parameters", true);
            }
        }
        return root;
    }

    private static ArrayNode limitations(ObjectNode root) {
        return root.putArray("limitations")
                .add("Only exact usages present in indexed application bytecode are reported")
                .add("Reflection, generated-at-runtime access, and source-only references are not inferred");
    }

    private static boolean matchesName(
            ClassMemberRecord member, String requested, String kind,
            List<KotlinDeclarationRecord> kotlinDeclarations) {
        if ("CONSTRUCTOR".equals(kind)) {
            return requested == null || requested.isBlank() || "<init>".equals(requested)
                    || member.name().equals(requested);
        }
        return requested != null && KotlinMemberNames.matches(
                requested.strip(), member, kotlinDeclarations);
    }

    private static void appendCandidate(
            ArrayNode target, ClassRecord cls, ClassMemberRecord candidate) {
        ObjectNode node = target.addObject();
        node.put("symbol_id", SymbolContract.id(cls, candidate.kind(),
                candidate.name(), candidate.descriptor()));
        node.put("name", candidate.name());
        node.put("signature", candidate.signature());
        node.put("descriptor", candidate.descriptor());
    }

    private static void appendCaller(ObjectNode node, String className, String method,
            String descriptor, String source, int sourceLine, String origin, String module) {
        ObjectNode caller = node.putObject("caller");
        caller.put("class", className);
        caller.put("method", method);
        caller.put("descriptor", descriptor);
        if (source != null) caller.put("source", sourceLine > 0 ? source + ":" + sourceLine : source);
        caller.put("origin", origin);
        if (module != null) caller.put("module", module);
    }
}
