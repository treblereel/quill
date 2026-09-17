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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.objectweb.asm.Type;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.MethodInboundUsage;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Finds private methods with no matching inbound bytecode call. */
final class UnusedMethodQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CONVENTIONAL_CALLBACKS = Set.of(
            "readObject", "readObjectNoData", "readResolve",
            "writeObject", "writeReplace", "finalize");

    String findUnusedMethods(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi);
        Map<Integer, List<ClassMemberRecord>> members = IndexReader.findClassMembers(
                jdbi, classes.stream().map(ClassRecord::id).toList());
        Map<MethodKey, MethodInboundUsage> usages = new HashMap<>();
        for (MethodInboundUsage usage : IndexReader.findMethodInboundUsages(jdbi)) {
            usages.put(new MethodKey(usage.classId(), usage.method(), usage.descriptor()), usage);
        }

        Map<String, Integer> excluded = new java.util.LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        int analyzedPrivateMethods = 0;
        for (ClassRecord cls : classes) {
            if (!inRequestedScope(cls, module, includeGenerated, includeTests, excluded)) continue;
            for (ClassMemberRecord member : members.getOrDefault(cls.id(), List.of())) {
                if (!"METHOD".equals(member.kind()) || !hasModifier(member, "private")) continue;
                analyzedPrivateMethods++;
                if (!member.annotations().isEmpty()) {
                    increment(excluded, "annotated_method");
                    continue;
                }
                if (hasModifier(member, "native")) {
                    increment(excluded, "native_method");
                    continue;
                }
                if (CONVENTIONAL_CALLBACKS.contains(member.name())) {
                    increment(excluded, "conventional_runtime_callback");
                    continue;
                }
                String descriptor = descriptor(member);
                if (descriptor == null) {
                    increment(excluded, "descriptor_not_resolved");
                    continue;
                }
                MethodInboundUsage usage = usages.get(
                        new MethodKey(cls.id(), member.name(), descriptor));
                if (usage != null) {
                    increment(excluded, "inbound_bytecode_call");
                    continue;
                }
                candidates.add(new Candidate(cls, member, descriptor,
                        cls.isBean() ? "low" : "medium"));
            }
        }
        candidates.sort(Comparator
                .comparingInt((Candidate candidate) -> confidenceOrder(candidate.confidence()))
                .thenComparing(candidate -> candidate.owner().className())
                .thenComparing(candidate -> candidate.member().signature()));

        int from = Math.min(offset, candidates.size());
        int to = (int) Math.min((long) from + limit, candidates.size());
        List<Candidate> page = candidates.subList(from, to);
        ObjectNode root = JSON.createObjectNode();
        root.put("classification", "private_method_candidates_not_proven_dead_code");
        root.put("visibility_scope", "private_only");
        if (module == null) root.putNull("module_filter");
        else root.put("module_filter", module);
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("analyzed_private_methods", analyzedPrivateMethods);
        root.set("excluded_reason_counts", JSON.valueToTree(excluded));
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Reflection, JNI, serialization, configuration, and framework conventions can invoke private methods without a bytecode call");
        limitations.add("Annotated, native, and known Java serialization callback methods are excluded conservatively");
        limitations.add("Method identity is matched by owner, name, and erased JVM descriptor; unresolved generic descriptors are excluded");
        limitations.add("Only the indexed compiled snapshot is analyzed; inspect freshness metadata before deleting code");

        ArrayNode values = root.putArray("candidates");
        int naiveTokens = 0;
        Set<Integer> countedClasses = new HashSet<>();
        for (Candidate candidate : page) {
            ClassRecord cls = candidate.owner();
            ClassMemberRecord member = candidate.member();
            ObjectNode node = values.addObject();
            node.put("class", cls.className());
            node.put("method", member.name());
            node.put("signature", member.signature());
            node.put("descriptor", candidate.descriptor());
            node.set("parameters", JSON.valueToTree(member.parameterTypes()));
            node.put("return_type", member.typeName());
            node.put("modifiers", member.modifiers());
            node.put("confidence", candidate.confidence());
            node.put("reason", "no_matching_inbound_bytecode_call");
            if (cls.sourceFile() == null) node.putNull("source");
            else node.put("source", cls.sourceFile() + ":" + cls.sourceLine());
            node.put("origin", cls.origin());
            if (cls.module() == null) node.putNull("module");
            else node.put("module", cls.module());
            if (cls.sourceSet() == null) node.putNull("source_set");
            else node.put("source_set", cls.sourceSet());
            if (cls.isBean()) {
                node.putArray("cautions").add(
                        "Declaring class is a DI bean; framework lifecycle conventions may apply");
            } else {
                node.putArray("cautions");
            }
            if (countedClasses.add(cls.id())) naiveTokens += cls.sourceTokens();
        }
        appendPage(root, page.size(), candidates.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
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

    private static String descriptor(ClassMemberRecord member) {
        Type returnType = asmType(member.typeName());
        if (returnType == null) return null;
        Type[] parameters = new Type[member.parameterTypes().size()];
        for (int i = 0; i < parameters.length; i++) {
            parameters[i] = asmType(member.parameterTypes().get(i));
            if (parameters[i] == null) return null;
        }
        return Type.getMethodDescriptor(returnType, parameters);
    }

    private static Type asmType(String typeName) {
        if (typeName == null || typeName.isBlank()) return null;
        String erased = eraseGenerics(typeName.trim());
        int dimensions = 0;
        while (erased.endsWith("[]")) {
            dimensions++;
            erased = erased.substring(0, erased.length() - 2);
        }
        String descriptor = switch (erased) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> {
                if (!erased.contains(".") || erased.contains("?") || erased.contains(" ")) {
                    yield null;
                }
                yield "L" + erased.replace('.', '/') + ";";
            }
        };
        if (descriptor == null || (dimensions > 0 && "V".equals(descriptor))) return null;
        return Type.getType("[".repeat(dimensions) + descriptor);
    }

    private static String eraseGenerics(String typeName) {
        StringBuilder result = new StringBuilder(typeName.length());
        int depth = 0;
        for (int i = 0; i < typeName.length(); i++) {
            char value = typeName.charAt(i);
            if (value == '<') depth++;
            else if (value == '>') depth--;
            else if (depth == 0) result.append(value);
        }
        return result.toString();
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.merge(key, 1, Integer::sum);
    }

    private static int confidenceOrder(String confidence) {
        return "medium".equals(confidence) ? 0 : 1;
    }

    private record MethodKey(int classId, String method, String descriptor) {}

    private record Candidate(
            ClassRecord owner,
            ClassMemberRecord member,
            String descriptor,
            String confidence) {}
}
