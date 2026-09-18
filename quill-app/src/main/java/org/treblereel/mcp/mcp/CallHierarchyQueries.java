package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.objectweb.asm.Type;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.MethodCallView;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;

/** Returns direct or bounded-transitive caller/callee edges captured from application bytecode. */
final class CallHierarchyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DIRECTIONS = Set.of("inbound", "outbound", "both");
    private static final int TRAVERSAL_EDGE_CAP = 5_000;

    String getCallHierarchy(Jdbi jdbi, String target, String method, String signature,
            String direction, boolean transitive, int maxDepth, int limit, int offset) {
        String normalizedDirection = direction == null
                ? "both" : direction.trim().toLowerCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalizedDirection)) {
            return errorResponse("Invalid direction: expected inbound, outbound, or both");
        }
        String normalizedMethod = method == null || method.isBlank() ? null : method.trim();
        String normalizedSignature = signature == null || signature.isBlank()
                ? null : signature.trim();
        if (normalizedMethod == null && normalizedSignature != null) {
            return errorResponse("A method name is required when signature is provided");
        }
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
        if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
        ClassRecord cls = lookup.cls();

        List<ClassMemberRecord> candidates = normalizedMethod == null ? List.of()
                : IndexReader.findClassMembers(jdbi, cls.id()).stream()
                        .filter(member -> member.kind().equals("METHOD")
                                || member.kind().equals("CONSTRUCTOR"))
                        .filter(member -> member.name().equals(normalizedMethod)
                                || (normalizedMethod.equals("<init>")
                                        && member.kind().equals("CONSTRUCTOR")))
                        .toList();
        List<ClassMemberRecord> matchingMembers = normalizedSignature == null
                ? candidates
                : candidates.stream().filter(member -> normalizedSignature.equals(member.signature())
                        || normalizedSignature.equals(JvmDescriptors.methodDescriptor(member)))
                        .toList();
        ClassMemberRecord selected = null;
        String descriptor = null;
        String bytecodeMethod = normalizedMethod;
        if (normalizedMethod != null) {
            if (matchingMembers.size() != 1) {
                return symbolSelectionError(jdbi, cls, normalizedMethod,
                        normalizedSignature, matchingMembers.isEmpty() ? candidates : matchingMembers,
                        matchingMembers.size() > 1
                                || (normalizedSignature == null && candidates.size() > 1));
            }
            selected = matchingMembers.getFirst();
            descriptor = JvmDescriptors.methodDescriptor(selected);
            if (descriptor == null) {
                ObjectNode error = JSON.createObjectNode();
                error.put("error", "Method descriptor is unavailable; rebuild the Quill index");
                appendCandidate(error.putArray("candidates"), selected);
                appendMeta(error, jdbi, 0);
                return error.toString();
            }
            if ("CONSTRUCTOR".equals(selected.kind())) bytecodeMethod = "<init>";
        }

        TraversalResult result = transitive
                ? traverse(jdbi, cls.id(), bytecodeMethod, descriptor,
                        normalizedDirection, maxDepth)
                : direct(jdbi, cls.id(), bytecodeMethod, descriptor,
                        normalizedDirection, limit, offset);
        List<TraversalEdge> page = transitive
                ? page(result.edges(), offset, limit) : result.edges();

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        if (normalizedMethod == null) root.putNull("method");
        else root.put("method", normalizedMethod);
        if (selected != null) {
            root.put("signature", selected.signature());
            root.put("descriptor", descriptor);
            root.put("member_kind", selected.kind().toLowerCase(Locale.ROOT));
        }
        root.put("direction", normalizedDirection);
        root.put("direct_only", !transitive);
        root.put("transitive", transitive);
        root.put("max_depth", transitive ? maxDepth : 1);
        root.put("traversal_edge_cap", TRAVERSAL_EDGE_CAP);
        root.put("traversal_truncated", result.truncated());
        if (normalizedMethod != null) {
            root.put("declared_method_match_count", selected == null ? 0 : 1);
            root.put("declared_method_found", selected != null);
        }
        ArrayNode limitations = root.putArray("limitations");
        limitations.add(transitive
                ? "Traversal is bounded by max_depth and traversal_edge_cap"
                : "Only direct calls present in indexed application bytecode are reported");
        limitations.add("Virtual/interface calls identify the bytecode-declared owner, not every runtime dispatch target");
        limitations.add("Reflection, generated-at-runtime calls, and framework callbacks are not inferred");

        ArrayNode edges = root.putArray("calls");
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = cls.sourceTokens();
        countedClasses.add(cls.id());
        for (TraversalEdge traversed : page) {
            MethodCallView call = traversed.call();
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
            if (transitive) {
                edge.put("depth", traversed.depth());
                edge.put("traversal_direction", traversed.direction());
                edge.set("path", JSON.valueToTree(traversed.path()));
            }
            if (countedClasses.add(call.fromClassId())) naiveTokens += call.fromSourceTokens();
            if (countedClasses.add(call.toClassId())) naiveTokens += call.toSourceTokens();
        }
        appendPage(root, page.size(), result.total(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static TraversalResult direct(Jdbi jdbi, int classId, String method,
            String descriptor, String direction, int limit, int offset) {
        List<TraversalEdge> edges = IndexReader.findMethodCalls(
                jdbi, classId, method, descriptor, direction, limit, offset).stream()
                .map(call -> new TraversalEdge(call, 1, direction, List.of()))
                .toList();
        int total = IndexReader.countMethodCalls(
                jdbi, classId, method, descriptor, direction);
        return new TraversalResult(edges, total, false);
    }

    private static TraversalResult traverse(Jdbi jdbi, int classId, String method,
            String descriptor, String direction, int maxDepth) {
        List<String> directions = direction.equals("both")
                ? List.of("inbound", "outbound") : List.of(direction);
        LinkedHashSet<MethodNode> roots = new LinkedHashSet<>();
        for (String traversalDirection : directions) {
            for (MethodCallView call : IndexReader.findAdjacentMethodCalls(
                    jdbi, classId, method, descriptor, traversalDirection)) {
                roots.add(endpoint(call, traversalDirection));
            }
        }

        ArrayDeque<NodePath> queue = new ArrayDeque<>();
        Set<MethodNode> visited = new HashSet<>();
        for (MethodNode root : roots) {
            visited.add(root);
            queue.addLast(new NodePath(root, 0, List.of(root.identity())));
        }

        LinkedHashMap<String, TraversalEdge> found = new LinkedHashMap<>();
        boolean truncated = false;
        traversal:
        while (!queue.isEmpty()) {
            NodePath current = queue.removeFirst();
            if (current.depth() >= maxDepth) continue;
            for (String traversalDirection : directions) {
                List<MethodCallView> adjacent = IndexReader.findAdjacentMethodCalls(
                        jdbi, current.node().classId(), current.node().method(),
                        current.node().descriptor(), traversalDirection);
                for (MethodCallView call : adjacent) {
                    MethodNode next = opposite(call, traversalDirection);
                    List<String> path = new ArrayList<>(current.path());
                    path.add(next.identity());
                    int depth = current.depth() + 1;
                    found.putIfAbsent(edgeKey(call),
                            new TraversalEdge(call, depth, traversalDirection, List.copyOf(path)));
                    if (found.size() >= TRAVERSAL_EDGE_CAP) {
                        truncated = true;
                        break traversal;
                    }
                    if (depth < maxDepth && visited.add(next)) {
                        queue.addLast(new NodePath(next, depth, List.copyOf(path)));
                    }
                }
            }
        }
        List<TraversalEdge> edges = found.values().stream()
                .sorted(Comparator.comparingInt(TraversalEdge::depth)
                        .thenComparing(edge -> edge.call().fromClass())
                        .thenComparing(edge -> edge.call().fromMethod())
                        .thenComparing(edge -> edge.call().fromDescriptor())
                        .thenComparing(edge -> edge.call().toClass())
                        .thenComparing(edge -> edge.call().toMethod())
                        .thenComparing(edge -> edge.call().toDescriptor()))
                .toList();
        return new TraversalResult(edges, edges.size(), truncated);
    }

    private static MethodNode endpoint(MethodCallView call, String direction) {
        return direction.equals("inbound")
                ? new MethodNode(call.toClassId(), call.toClass(), call.toMethod(), call.toDescriptor())
                : new MethodNode(call.fromClassId(), call.fromClass(), call.fromMethod(), call.fromDescriptor());
    }

    private static MethodNode opposite(MethodCallView call, String direction) {
        return direction.equals("inbound")
                ? new MethodNode(call.fromClassId(), call.fromClass(), call.fromMethod(), call.fromDescriptor())
                : new MethodNode(call.toClassId(), call.toClass(), call.toMethod(), call.toDescriptor());
    }

    private static String edgeKey(MethodCallView call) {
        return call.fromClassId() + "\u0000" + call.fromMethod() + "\u0000" + call.fromDescriptor()
                + "\u0000" + call.toClassId() + "\u0000" + call.toMethod() + "\u0000"
                + call.toDescriptor() + "\u0000" + call.invocationKind();
    }

    private static List<TraversalEdge> page(List<TraversalEdge> edges, int offset, int limit) {
        if (offset >= edges.size()) return List.of();
        return edges.subList(offset, Math.min(edges.size(), offset + limit));
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

    private static String symbolSelectionError(Jdbi jdbi, ClassRecord cls,
            String method, String signature, List<ClassMemberRecord> candidates,
            boolean ambiguous) {
        ObjectNode error = JSON.createObjectNode();
        error.put("error", ambiguous
                ? "Ambiguous method; provide signature or JVM descriptor"
                : "Method not found");
        error.put("target", cls.className());
        error.put("method", method);
        if (signature != null) error.put("signature", signature);
        ArrayNode values = error.putArray("candidates");
        candidates.forEach(candidate -> appendCandidate(values, candidate));
        appendMeta(error, jdbi, 0);
        return error.toString();
    }

    private static void appendCandidate(ArrayNode target, ClassMemberRecord candidate) {
        ObjectNode node = target.addObject();
        node.put("kind", candidate.kind().toLowerCase(Locale.ROOT));
        node.put("name", candidate.name());
        node.put("signature", candidate.signature());
        String descriptor = JvmDescriptors.methodDescriptor(candidate);
        if (descriptor != null) node.put("descriptor", descriptor);
    }

    private record MethodNode(int classId, String className, String method, String descriptor) {
        String identity() {
            return className + "#" + method + descriptor;
        }
    }

    private record NodePath(MethodNode node, int depth, List<String> path) {}

    private record TraversalEdge(
            MethodCallView call, int depth, String direction, List<String> path) {}

    private record TraversalResult(List<TraversalEdge> edges, int total, boolean truncated) {}
}
