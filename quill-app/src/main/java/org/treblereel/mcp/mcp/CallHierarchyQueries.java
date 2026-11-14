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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.UsageRepository;
import org.treblereel.mcp.db.UsageRepository.MethodCallView;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.KotlinDeclarationRecord;

/** Returns direct or bounded-transitive caller/callee edges captured from application bytecode. */
final class CallHierarchyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DIRECTIONS = Set.of("inbound", "outbound", "both");
    private static final Set<String> SCOPES = Set.of("all", "cross_class", "cross_package");
    private static final int TRAVERSAL_EDGE_CAP = 5_000;

    String getCallHierarchy(ReadQueryContext reader, String target, String method, String signature,
            String direction, boolean transitive, int maxDepth, int limit, int offset) {
        return getCallHierarchy(reader, target, method, signature, direction, transitive,
                maxDepth, "all", limit, offset);
    }

    String getCallHierarchy(ReadQueryContext reader, String target, String method, String signature,
            String direction, boolean transitive, int maxDepth, String scope,
            int limit, int offset) {
        try {
            SymbolContract.Reference reference = SymbolContract.parse(target).orElse(null);
            if (reference != null) {
                if (!"METHOD".equals(reference.kind())
                        && !"CONSTRUCTOR".equals(reference.kind())) {
                    return errorResponse("INVALID_SYMBOL_KIND",
                            "symbol_id must identify a method or constructor");
                }
                method = reference.jvmName();
                signature = reference.descriptor();
            }
        } catch (SymbolContract.ParseException error) {
            return errorResponse(error.errorCode(), error.getMessage());
        }
        String normalizedDirection = direction == null
                ? "both" : ArgumentAliases.graphDirection(direction);
        if (!DIRECTIONS.contains(normalizedDirection)) {
            return errorResponse("INVALID_ARGUMENT",
                    "Invalid direction: expected inbound, outbound, or both");
        }
        String normalizedScope = scope == null ? "all" : scope.trim().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(normalizedScope)) {
            return errorResponse("INVALID_ARGUMENT",
                    "Invalid scope: expected all, cross_class, or cross_package");
        }
        String normalizedMethod = method == null || method.isBlank() ? null : method.trim();
        String normalizedSignature = signature == null || signature.isBlank()
                ? null : signature.trim();
        if (normalizedMethod == null && normalizedSignature != null) {
            return errorResponse("MISSING_METHOD",
                    "A method name is required when signature is provided");
        }
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, target);
        if (lookup.error() != null) return classLookupError(reader, lookup, target);
        ClassRecord cls = lookup.cls();
        List<KotlinDeclarationRecord> kotlinDeclarations =
                reader.symbols.findKotlinDeclarations(cls.id());

        List<ClassMemberRecord> candidates = normalizedMethod == null ? List.of()
                : (normalizedSignature == null ? reader.symbols.findClassMembers(cls.id()) : reader.symbols.findJvmClassMembers(cls.id())).stream()
                        .filter(member -> member.kind().equals("METHOD")
                                || member.kind().equals("CONSTRUCTOR"))
                        .filter(member -> KotlinMemberNames.matches(
                                normalizedMethod, member, kotlinDeclarations)
                                || (normalizedMethod.equals("<init>")
                                        && member.kind().equals("CONSTRUCTOR")))
                        .toList();
        List<ClassMemberRecord> matchingMembers = normalizedSignature == null
                ? candidates
                : candidates.stream().filter(member -> JvmDescriptors.matchesSignature(member, normalizedSignature))
                        .toList();
        ClassMemberRecord selected = null;
        String descriptor = null;
        String bytecodeMethod = normalizedMethod;
        KotlinDeclarationRecord kotlinDeclaration = null;
        String lambdaDeclarer = null;
        if (normalizedMethod != null && matchingMembers.isEmpty() && normalizedMethod.startsWith("lambda$")) {
            // A synthetic lambda body is no declared member, but call evidence names it.
            descriptor = lambdaDescriptor(reader, cls.id(), normalizedMethod, normalizedSignature);
            if (descriptor != null) {
                int end = normalizedMethod.indexOf('$', 7);
                lambdaDeclarer = end > 7 ? normalizedMethod.substring(7, end) : null;
            }
        }
        if (normalizedMethod != null && lambdaDeclarer == null) {
            if (matchingMembers.size() != 1) {
                return symbolSelectionError(reader, cls, normalizedMethod,
                        normalizedSignature, matchingMembers.isEmpty() ? candidates : matchingMembers,
                        matchingMembers.size() > 1
                                || (normalizedSignature == null && candidates.size() > 1));
            }
            selected = matchingMembers.getFirst();
            kotlinDeclaration = KotlinMemberNames
                    .declaration(selected, kotlinDeclarations).orElse(null);
            descriptor = JvmDescriptors.methodDescriptor(selected);
            if (descriptor == null) {
                ObjectNode error = JSON.createObjectNode();
                appendError(error, "DESCRIPTOR_UNAVAILABLE",
                        "Method descriptor is unavailable; rebuild the Quill index");
                appendCandidate(error.putArray("candidates"), cls, selected);
                appendMeta(error, reader, 0);
                return error.toString();
            }
            bytecodeMethod = "CONSTRUCTOR".equals(selected.kind()) ? "<init>" : selected.name();
        }

        TraversalResult unfiltered = transitive
                ? traverse(reader, cls.id(), bytecodeMethod, descriptor,
                        normalizedDirection, maxDepth)
                : direct(reader, cls.id(), bytecodeMethod, descriptor,
                        normalizedDirection, TRAVERSAL_EDGE_CAP, 0);
        List<TraversalEdge> filtered = unfiltered.edges().stream()
                .filter(edge -> inScope(edge.call(), normalizedScope)).toList();
        List<TraversalEdge> page = page(filtered, offset, limit);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        if (normalizedMethod == null) root.putNull("method");
        else root.put("method", normalizedMethod);
        if (lambdaDeclarer != null) {
            root.put("descriptor", descriptor);
            root.put("lambda_declared_by", lambdaDeclarer);
        }
        if (selected != null) {
            root.put("signature", selected.signature());
            root.put("descriptor", descriptor);
            root.put("member_kind", selected.kind().toLowerCase(Locale.ROOT));
            SymbolContract.appendMember(root, cls, selected, kotlinDeclarations);
            if (kotlinDeclaration != null) {
                root.put("kotlin_name", kotlinDeclaration.name());
                if (kotlinDeclaration.isSuspend()) root.put("suspend", true);
                if (kotlinDeclaration.extension()) root.put("extension", true);
                if (kotlinDeclaration.hasDefaultParameters()) {
                    root.put("default_parameters", true);
                }
            }
        }
        root.put("direction", normalizedDirection);
        if (selected != null && !kotlinDeclarations.isEmpty() && !normalizedDirection.equals("outbound")) {
            var sourceCallers = KotlinUsageNormalizer.inbound(reader, cls.id(), bytecodeMethod, descriptor);
            root.put("source_caller_count", sourceCallers.usages().size());
            root.put("source_caller_normalization_truncated", sourceCallers.truncated());
            var callers = root.putArray("source_callers");
            for (var usage : sourceCallers.usages().stream().skip(offset).limit(limit).toList()) {
                var caller = callers.addObject();
                var call = usage.call();
                appendEndpoint(caller.putObject("caller"), call.fromClass(), call.fromMethod(), call.fromDescriptor(),
                        call.fromSource(), call.fromSourceLine(), call.fromOrigin(), call.fromModule());
                caller.put("occurrence_count", call.occurrenceCount());
                caller.set("evidence_lines", JSON.valueToTree(call.evidenceLines()));
                SymbolUsageQueries.appendPath(caller, usage);
                KotlinSourceLocations.append(reader, caller, call.fromClassId(), call.evidenceLines());
            }
        }
        root.put("direct_only", !transitive);
        root.put("transitive", transitive);
        root.put("max_depth", transitive ? maxDepth : 1);
        root.put("scope", normalizedScope);
        root.put("suppressed_call_count", unfiltered.edges().size() - filtered.size());
        root.put("traversal_edge_cap", TRAVERSAL_EDGE_CAP);
        root.put("traversal_truncated", unfiltered.truncated());
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
        ObjectNode expressions = selected != null && "METHOD".equals(selected.kind()) && !normalizedDirection.equals("outbound")
                ? ExpressionReferences.find(reader, cls, selected.name()) : null;
        if (expressions != null) {
            root.set("expression_references", expressions);
            limitations.add("Calls written in annotation expressions (SpEL) are listed in expression_references, not in calls");
        }

        root.putObject("edge_defaults").put("edge_kind", "call")
                .put("execution_semantics", "invocation_instruction");
        JavaDispatchEvidence.declareDefaults(root);
        ArrayNode edges = root.putArray("calls");
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = cls.sourceTokens();
        countedClasses.add(cls.id());
        JavaSourceLocations javaLocations = new JavaSourceLocations(reader);
        String dynamicBasis = "2".equals(reader.index.getMetadata().get("invokedynamic_evidence_protocol"))
                ? "lambda_metafactory" : "legacy_unclassified";
        JavaDispatchEvidence dispatch = new JavaDispatchEvidence(reader);
        if (selected != null && kotlinDeclarations.isEmpty() && !normalizedDirection.equals("outbound"))
            dispatch.appendPossibleCallers(root, cls, selected, limit, offset);
        java.util.Map<Integer, ClassRecord> callees = new java.util.HashMap<>();
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
            // Ordinary calls use the edge_defaults declared once on the root.
            if ("dynamic".equals(call.invocationKind())) {
                edge.put("edge_kind", "lambda_metafactory".equals(dynamicBasis)
                        ? "callback_reference" : "dynamic_handle_reference");
                edge.put("execution_semantics", "lambda_metafactory".equals(dynamicBasis)
                        ? "callback_creation" : "bootstrap_reference_unknown");
                edge.put("reference_basis", dynamicBasis);
            }
            if (call.fromSource() != null && call.fromSource().endsWith(".java")) dispatch.appendTarget(edge, call);
            edge.put("occurrence_count", call.occurrenceCount());
            edge.set("evidence_lines", JSON.valueToTree(call.evidenceLines()));
            ClassRecord callee = callees.computeIfAbsent(call.toClassId(), id -> reader.symbols.findClassById(id).orElse(null));
            ClassRecord caller = callees.computeIfAbsent(call.fromClassId(), id -> reader.symbols.findClassById(id).orElse(null));
            if(callee!=null) ((ObjectNode)edge.get("callee")).put("symbol_id",SymbolContract.id(callee,
                    "<init>".equals(call.toMethod()) ? "CONSTRUCTOR" : "METHOD",call.toMethod(),call.toDescriptor()));
            if(caller!=null) ((ObjectNode)edge.get("caller")).put("symbol_id",SymbolContract.id(caller,
                    "<init>".equals(call.fromMethod()) ? "CONSTRUCTOR" : "METHOD",call.fromMethod(),call.fromDescriptor()));
            if (callee != null) javaLocations.append(edge, call.fromSource(), call.fromClass(), call.fromMethod(), call.fromDescriptor(),
                    call.toClass(), call.toMethod(), call.toDescriptor(), "call", SymbolContract.id(callee,
                            "<init>".equals(call.toMethod()) ? "CONSTRUCTOR" : "METHOD", call.toMethod(), call.toDescriptor()), call.evidenceLines());
            if (transitive) {
                edge.put("depth", traversed.depth());
                edge.put("traversal_direction", traversed.direction());
                edge.set("path", JSON.valueToTree(traversed.path()));
            }
            if (countedClasses.add(call.fromClassId())) naiveTokens += call.fromSourceTokens();
            if (countedClasses.add(call.toClassId())) naiveTokens += call.toSourceTokens();
        }
        dispatch.writeTargets(root);
        javaLocations.writeDefaults(root);
        appendPage(root, page.size(), filtered.size(), limit, offset);
        UsageCoverage.append(root,reader,cls.id());
        appendMeta(root, reader, naiveTokens, cls.sourceFile(), cls.module());
        return root.toString();
    }

    private static boolean inScope(MethodCallView call, String scope) {
        if ("all".equals(scope)) return true;
        if (call.fromClass().equals(call.toClass())) return false;
        return !"cross_package".equals(scope)
                || !packageName(call.fromClass()).equals(packageName(call.toClass()));
    }

    private static String packageName(String className) {
        int nested = className.indexOf('$');
        String owner = nested < 0 ? className : className.substring(0, nested);
        int separator = owner.lastIndexOf('.');
        return separator < 0 ? "" : owner.substring(0, separator);
    }

    private static TraversalResult direct(ReadQueryContext reader, int classId, String method,
            String descriptor, String direction, int limit, int offset) {
        List<TraversalEdge> edges = reader.usages.findMethodCalls(classId, method, descriptor, direction, limit, offset, reader.methods().symbolicOwners(classId, method, descriptor, direction)).stream()
                .map(call -> new TraversalEdge(call, 1, direction, List.of()))
                .toList();
        int total = reader.usages.countMethodCalls(classId, method, descriptor, direction, reader.methods().symbolicOwners(classId, method, descriptor, direction));
        return new TraversalResult(edges, total, total > edges.size());
    }

    private static TraversalResult traverse(ReadQueryContext reader, int classId, String method,
            String descriptor, String direction, int maxDepth) {
        List<String> directions = direction.equals("both")
                ? List.of("inbound", "outbound") : List.of(direction);
        LinkedHashSet<MethodNode> roots = new LinkedHashSet<>();
        for (String traversalDirection : directions) {
            for (MethodCallView call : reader.usages.findAdjacentMethodCalls(classId, method, descriptor, traversalDirection, reader.methods().symbolicOwners(classId, method, descriptor, traversalDirection))) {
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
                List<MethodCallView> adjacent = reader.usages.findAdjacentMethodCalls(current.node().classId(), current.node().method(), current.node().descriptor(), traversalDirection, reader.methods().symbolicOwners(current.node().classId(), current.node().method(), current.node().descriptor(), traversalDirection));
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
        // Parameter and return types are derivable from the JVM descriptor; repeating them on
        // both endpoints of every edge roughly doubled the endpoint payload.
        node.put("class", className);
        node.put("method", method);
        node.put("descriptor", descriptor);
        node.put("source", sourceLine > 0 ? source + ":" + sourceLine : source);
        node.put("origin", origin);
        if (module == null) node.putNull("module");
        else node.put("module", module);
    }

    /** The one bytecode descriptor of a synthetic lambda in call evidence, matching a given descriptor if any. */
    private static String lambdaDescriptor(ReadQueryContext reader, int classId, String method, String signature) {
        List<String> descriptors = reader.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT from_descriptor FROM method_calls WHERE from_class_id = :id AND from_method = :name
                UNION SELECT to_descriptor FROM method_calls WHERE to_class_id = :id AND to_method = :name""")
                .bind("id", classId).bind("name", method).mapTo(String.class).list());
        List<String> matching = signature == null ? descriptors
                : descriptors.stream().filter(signature::equals).toList();
        return matching.size() == 1 ? matching.getFirst() : null;
    }

    private static String symbolSelectionError(ReadQueryContext reader, ClassRecord cls,
            String method, String signature, List<ClassMemberRecord> candidates,
            boolean ambiguous) {
        ObjectNode error = JSON.createObjectNode();
        appendError(error, ambiguous ? "AMBIGUOUS_METHOD" : "METHOD_NOT_FOUND",
                ambiguous ? "Ambiguous method; provide signature or JVM descriptor"
                        : "Method not found");
        error.put("target", cls.className());
        error.put("method", method);
        if (signature != null) error.put("signature", signature);
        ArrayNode values = error.putArray("candidates");
        candidates.forEach(candidate -> appendCandidate(values, cls, candidate));
        if (candidates.isEmpty()) {
            List<String> names = reader.symbols.findClassMembers(cls.id()).stream()
                    .filter(member -> member.kind().equals("METHOD")).map(ClassMemberRecord::name)
                    .filter(name -> !name.startsWith("lambda$")).distinct().toList();
            if (!names.isEmpty()) error.set("similar_methods", JSON.valueToTree(NameSimilarity.closest(method, names, 5)));
        }
        if (ambiguous) {
            appendRetryWith(error, "signature",
                    "Use one candidate's exact signature, descriptor, or symbol_id");
        }
        appendMeta(error, reader, 0);
        return error.toString();
    }

    private static void appendCandidate(
            ArrayNode target, ClassRecord cls, ClassMemberRecord candidate) {
        ObjectNode node = target.addObject();
        String descriptor = JvmDescriptors.methodDescriptor(candidate);
        node.put("symbol_id", SymbolContract.id(cls, candidate.kind(),
                candidate.name(), descriptor));
        node.put("kind", candidate.kind().toLowerCase(Locale.ROOT));
        node.put("name", candidate.name());
        node.put("signature", candidate.signature());
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
