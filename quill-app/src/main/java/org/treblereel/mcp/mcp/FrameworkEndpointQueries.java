package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.MethodCallView;
import org.treblereel.mcp.model.ClassRecord;

/** Presents indexed Spring MVC and JAX-RS routes with direct call evidence. */
final class FrameworkEndpointQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FRAMEWORKS = Set.of("spring", "jax-rs");
    private static final int CALL_DETAIL_LIMIT = 20;

    String findFrameworkEndpoints(Jdbi jdbi, String framework, String httpMethod,
            String pathPrefix, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        String normalizedFramework = normalize(framework);
        if (normalizedFramework != null && !FRAMEWORKS.contains(normalizedFramework)) {
            return errorResponse("Invalid framework: expected spring, jax-rs, or all");
        }
        String normalizedMethod = httpMethod == null || httpMethod.isBlank()
                ? null : httpMethod.strip().toUpperCase(Locale.ROOT);
        String normalizedPrefix = pathPrefix == null || pathPrefix.isBlank()
                ? null : normalizePath(pathPrefix);
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        String detail = metadata.get("framework_endpoints_detail");
        if (detail == null) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "Framework endpoint metadata is unavailable; rebuild the Quill index");
            appendMeta(error, jdbi, 0);
            return error.toString();
        }

        Map<Integer, ClassRecord> classes = new HashMap<>();
        IndexReader.findAllClasses(jdbi).forEach(cls -> classes.put(cls.id(), cls));
        List<Endpoint> endpoints;
        try {
            endpoints = parse(detail).stream()
                    .filter(endpoint -> normalizedFramework == null
                            || normalizedFramework.equals(endpoint.framework()))
                    .filter(endpoint -> normalizedMethod == null
                            || endpoint.httpMethods().contains(normalizedMethod)
                            || endpoint.httpMethods().contains("ANY"))
                    .filter(endpoint -> normalizedPrefix == null
                            || endpoint.paths().stream().anyMatch(
                                    path -> path.startsWith(normalizedPrefix)))
                    .filter(endpoint -> inScope(classes.get(endpoint.classId()), module,
                            includeGenerated, includeTests))
                    .sorted(Comparator.comparing(Endpoint::framework)
                            .thenComparing(endpoint -> endpoint.paths().getFirst())
                            .thenComparing(Endpoint::className)
                            .thenComparing(Endpoint::signature))
                    .toList();
        } catch (Exception error) {
            return errorResponse("Framework endpoint metadata is invalid; rebuild the Quill index");
        }
        int from = Math.min(offset, endpoints.size());
        int to = (int) Math.min((long) from + limit, endpoints.size());
        List<Endpoint> page = endpoints.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        if (normalizedFramework == null) root.putNull("framework_filter");
        else root.put("framework_filter", normalizedFramework);
        if (normalizedMethod == null) root.putNull("http_method_filter");
        else root.put("http_method_filter", normalizedMethod);
        if (normalizedPrefix == null) root.putNull("path_prefix_filter");
        else root.put("path_prefix_filter", normalizedPrefix);
        if (module == null) root.putNull("module_filter");
        else root.put("module_filter", module);
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("call_detail_limit", CALL_DETAIL_LIMIT);
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Only constant Spring MVC and JAX-RS annotations retained in compiled bytecode are analyzed");
        limitations.add("Programmatic, XML, YAML, runtime-generated, and meta-annotated routes may be absent");
        limitations.add("Direct project calls are static bytecode targets, not guaranteed runtime service dispatches");

        ArrayNode values = root.putArray("endpoints");
        Set<Integer> countedClasses = new HashSet<>();
        int naiveTokens = 0;
        for (Endpoint endpoint : page) {
            ClassRecord cls = classes.get(endpoint.classId());
            ObjectNode node = values.addObject();
            node.put("framework", endpoint.framework());
            node.set("http_methods", JSON.valueToTree(endpoint.httpMethods()));
            node.set("paths", JSON.valueToTree(endpoint.paths()));
            node.put("class", endpoint.className());
            node.put("method", endpoint.methodName());
            node.put("signature", endpoint.signature());
            node.put("descriptor", endpoint.descriptor());
            node.set("annotations", JSON.valueToTree(endpoint.annotations()));
            if (cls != null) {
                if (cls.sourceFile() != null) node.put("source", cls.sourceLine() > 0
                        ? cls.sourceFile() + ":" + cls.sourceLine() : cls.sourceFile());
                node.put("origin", cls.origin());
                if (cls.module() != null) node.put("module", cls.module());
                if (cls.sourceSet() != null) node.put("source_set", cls.sourceSet());
                if (countedClasses.add(cls.id())) naiveTokens += cls.sourceTokens();
            }
            List<MethodCallView> calls = IndexReader.findAdjacentMethodCalls(jdbi,
                    endpoint.classId(), endpoint.methodName(), endpoint.descriptor(), "outbound");
            node.put("direct_project_call_count", calls.size());
            node.put("calls_truncated", calls.size() > CALL_DETAIL_LIMIT);
            ArrayNode callNodes = node.putArray("direct_project_calls");
            for (MethodCallView call : calls.stream().limit(CALL_DETAIL_LIMIT).toList()) {
                ObjectNode callNode = callNodes.addObject();
                callNode.put("class", call.toClass());
                callNode.put("method", call.toMethod());
                callNode.put("descriptor", call.toDescriptor());
                callNode.put("invocation_kind", call.invocationKind());
                callNode.put("occurrence_count", call.occurrenceCount());
                callNode.set("evidence_lines", JSON.valueToTree(call.evidenceLines()));
                if (call.toModule() != null) callNode.put("module", call.toModule());
                if (countedClasses.add(call.toClassId())) naiveTokens += call.toSourceTokens();
            }
        }
        appendPage(root, page.size(), endpoints.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static List<Endpoint> parse(String detail) throws Exception {
        List<Endpoint> result = new ArrayList<>();
        for (JsonNode node : JSON.readTree(detail)) {
            List<String> classPaths = strings(node.path("classPaths"));
            List<String> methodPaths = strings(node.path("methodPaths"));
            result.add(new Endpoint(node.path("classId").asInt(),
                    node.path("className").asText(), node.path("methodName").asText(),
                    node.path("signature").asText(), node.path("descriptor").asText(),
                    node.path("framework").asText(), strings(node.path("httpMethods")),
                    combinePaths(classPaths, methodPaths), strings(node.path("annotations"))));
        }
        return result;
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) return List.of();
        return array.valueStream().map(JsonNode::asText).toList();
    }

    private static List<String> combinePaths(List<String> classPaths, List<String> methodPaths) {
        List<String> owners = classPaths.isEmpty() ? List.of("") : classPaths;
        List<String> methods = methodPaths.isEmpty() ? List.of("") : methodPaths;
        Set<String> result = new LinkedHashSet<>();
        for (String owner : owners) {
            for (String method : methods) result.add(joinPath(owner, method));
        }
        return List.copyOf(result);
    }

    private static String joinPath(String first, String second) {
        String left = normalizePath(first);
        String right = normalizePath(second);
        if ("/".equals(left)) return right;
        if ("/".equals(right)) return left;
        return left + right;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.strip())) return "/";
        String value = path.strip();
        if (!value.startsWith("/")) value = "/" + value;
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean inScope(ClassRecord cls, String module,
            boolean includeGenerated, boolean includeTests) {
        if (cls == null) return false;
        if (module != null && !module.equals(cls.module())) return false;
        if (!includeGenerated && "generated".equals(cls.origin())) return false;
        return includeTests || !"test".equals(cls.sourceSet());
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) return null;
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private record Endpoint(int classId, String className, String methodName,
            String signature, String descriptor, String framework,
            List<String> httpMethods, List<String> paths, List<String> annotations) {}
}
