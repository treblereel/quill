package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
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
import org.objectweb.asm.Type;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.MethodCallView;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Returns direct caller/callee edges captured from application bytecode. */
final class CallHierarchyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DIRECTIONS = Set.of("inbound", "outbound", "both");

    String getCallHierarchy(Jdbi jdbi, String target, String method,
            String direction, int limit, int offset) {
        String normalizedDirection = direction == null
                ? "both" : direction.trim().toLowerCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalizedDirection)) {
            return errorResponse("Invalid direction: expected inbound, outbound, or both");
        }
        String normalizedMethod = method == null || method.isBlank() ? null : method.trim();
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();

        List<MethodCallView> calls = IndexReader.findMethodCalls(
                jdbi, cls.id(), normalizedMethod, normalizedDirection, limit, offset);
        int total = IndexReader.countMethodCalls(
                jdbi, cls.id(), normalizedMethod, normalizedDirection);
        List<ClassMemberRecord> matchingMembers = normalizedMethod == null ? List.of()
                : IndexReader.findClassMembers(jdbi, cls.id()).stream()
                        .filter(member -> member.kind().equals("METHOD")
                                || member.kind().equals("CONSTRUCTOR"))
                        .filter(member -> member.name().equals(normalizedMethod)
                                || (normalizedMethod.equals("<init>")
                                        && member.kind().equals("CONSTRUCTOR")))
                        .toList();
        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        if (normalizedMethod == null) root.putNull("method");
        else root.put("method", normalizedMethod);
        root.put("direction", normalizedDirection);
        root.put("direct_only", true);
        if (normalizedMethod != null) {
            root.put("declared_method_match_count", matchingMembers.size());
            root.put("declared_method_found", !matchingMembers.isEmpty());
            root.set("declared_signatures", JSON.valueToTree(
                    matchingMembers.stream().map(ClassMemberRecord::signature).toList()));
        }
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Only direct calls present in indexed application bytecode are reported");
        limitations.add("Virtual/interface calls identify the bytecode-declared owner, not every runtime dispatch target");
        limitations.add("Reflection, generated-at-runtime calls, and framework callbacks are not inferred");

        ArrayNode edges = root.putArray("calls");
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = cls.sourceTokens();
        countedClasses.add(cls.id());
        for (MethodCallView call : calls) {
            ObjectNode edge = edges.addObject();
            appendEndpoint(edge.putObject("caller"), call.fromClass(), call.fromMethod(),
                    call.fromDescriptor(), call.fromSource(), call.fromSourceLine(),
                    call.fromOrigin(), call.fromModule());
            appendEndpoint(edge.putObject("callee"), call.toClass(), call.toMethod(),
                    call.toDescriptor(), call.toSource(), call.toSourceLine(),
                    call.toOrigin(), call.toModule());
            edge.put("invocation_kind", call.invocationKind());
            edge.put("occurrence_count", call.occurrenceCount());
            edge.set("evidence_lines", JSON.valueToTree(call.evidenceLines()));
            if (countedClasses.add(call.fromClassId())) {
                naiveTokens += call.fromSourceTokens();
            }
            if (countedClasses.add(call.toClassId())) {
                naiveTokens += call.toSourceTokens();
            }
        }
        appendPage(root, calls.size(), total, limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static void appendEndpoint(ObjectNode node, String className, String method,
            String descriptor, String source, int sourceLine, String origin, String module) {
        node.put("class", className);
        node.put("method", method);
        node.put("descriptor", descriptor);
        try {
            Type methodType = Type.getMethodType(descriptor);
            node.set("parameters", JSON.valueToTree(java.util.Arrays.stream(
                    methodType.getArgumentTypes()).map(Type::getClassName).toList()));
            node.put("return_type", methodType.getReturnType().getClassName());
        } catch (IllegalArgumentException ignored) {
            node.set("parameters", JSON.createArrayNode());
            node.putNull("return_type");
        }
        node.put("source", source + ":" + sourceLine);
        node.put("origin", origin);
        if (module == null) node.putNull("module");
        else node.put("module", module);
    }
}
