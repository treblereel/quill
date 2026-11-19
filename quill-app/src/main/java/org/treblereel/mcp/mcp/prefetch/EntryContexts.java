package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.UsageRepository.CallerMethod;
import org.treblereel.mcp.diagnostics.QuillLog;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Which entry points reach a class: its production callers followed inbound until an HTTP handler,
 * an annotation expression (SpEL such as {@code @PreAuthorize}), a non-request entry (event
 * listener, scheduler, async method, startup callback) or a method nothing indexed calls. Apollo
 * AQ-045 and Cryostat CQ-045: asked what request scope changes outside an HTTP request, Codex
 * walked the callers with find_usages and a transitive call hierarchy (about 180 KB of results).
 */
final class EntryContexts {

    static final String BASIS =
            "production calls followed inbound up to depth "
                    + EntryContexts.DEPTH
                    + " ("
                    + EntryContexts.NODES
                    + " methods), also through each class's direct supertypes and a lambda's enclosing "
                    + "method; a request scope is active only on an HTTP request's thread; runtime "
                    + "dispatch and framework callbacks without these markers are not proven";

    private static final int DEPTH = 6;
    private static final int NODES = 400;
    private static final int SHOWN = 4;

    /** Method annotations marking work that runs outside any HTTP request. */
    private static final List<String> NON_REQUEST =
            List.of(
                    "EventListener",
                    "TransactionalEventListener",
                    "Scheduled",
                    "Async",
                    "PostConstruct",
                    "PreDestroy",
                    "KafkaListener",
                    "RabbitListener",
                    "JmsListener",
                    "SqsListener",
                    "Incoming",
                    "Startup");

    /**
     * Supertypes whose methods the framework calls on each HTTP request (Quarkus authentication,
     * servlet and JAX-RS filters, Spring interceptors), so they run on the request's thread.
     */
    private static final List<String> REQUEST_CALLBACKS =
            List.of(
                    "io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism",
                    "jakarta.servlet.Filter",
                    "javax.servlet.Filter",
                    "org.springframework.web.filter.OncePerRequestFilter",
                    "org.springframework.web.filter.GenericFilterBean",
                    "jakarta.ws.rs.container.ContainerRequestFilter",
                    "jakarta.ws.rs.container.ContainerResponseFilter",
                    "org.springframework.web.servlet.HandlerInterceptor");

    private record Node(int classId, String className, String method, String descriptor) {}

    private EntryContexts() {}

    /** Up to five lines, or null when nothing in production calls the class. */
    static ArrayNode of(ReadQueryContext db, ClassRecord target) {
        Set<String> http = httpHandlers(db);
        Map<Integer, List<Integer>> supertypes = new HashMap<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        Map<Node, Integer> depth = new HashMap<>();
        for (int owner : withSupertypes(db, target.id(), supertypes))
            for (CallerMethod caller : db.usages.findProductionCallerMethods(owner, null, null))
                // A subclass constructor calling its supertype's is construction, not a call to it.
                if (caller.classId() != target.id()
                        && !(owner != target.id() && caller.method().equals("<init>")))
                    enqueue(queue, depth, node(caller), 1);
        Set<String> handlers = new LinkedHashSet<>();
        Set<String> callbacks = new LinkedHashSet<>();
        Set<String> expressions = new LinkedHashSet<>();
        Set<String> nonRequest = new LinkedHashSet<>();
        Set<String> uncalled = new LinkedHashSet<>();
        Set<Node> seen = new HashSet<>();
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (!seen.add(node)) continue;
            if (seen.size() > NODES) {
                truncated = true;
                break;
            }
            String label = simple(node.className()) + "#" + node.method();
            if (http.contains(node.className() + "#" + node.method())) {
                handlers.add(label);
                continue;
            }
            if (requestCallback(db, node)) {
                callbacks.add(label);
                continue;
            }
            String marker = nonRequestMarker(db, node);
            if (marker != null) {
                nonRequest.add(label + " @" + marker);
                continue;
            }
            List<CallerMethod> callers = callers(db, node, supertypes);
            if (callers.isEmpty()) {
                if (calledFromExpressions(db, node)) expressions.add(label);
                else uncalled.add(label);
                continue;
            }
            // A lambda's enclosing method is the same call, not a level further.
            int next = depth.get(node) + (node.method().startsWith("lambda$") ? 0 : 1);
            if (next > DEPTH) {
                truncated = true;
                continue;
            }
            for (CallerMethod caller : callers)
                if (caller.classId() != target.id()) enqueue(queue, depth, node(caller), next);
        }
        if (seen.isEmpty()) return null;
        ArrayNode lines = Json.MAPPER.createArrayNode();
        if (!handlers.isEmpty())
            lines.add("HTTP handlers: " + handlers.size() + " (" + shown(handlers) + ")");
        if (!callbacks.isEmpty())
            lines.add(
                    "HTTP request callbacks: "
                            + shown(callbacks)
                            + " (called by the framework on each request, on its thread)");
        if (!expressions.isEmpty())
            lines.add(
                    "annotation expressions: "
                            + shown(expressions)
                            + " called only from annotation values (SpEL), so they run where the annotated method runs");
        lines.add(
                "non-request entries: "
                        + (nonRequest.isEmpty() ? "none found" : shown(nonRequest))
                        + (truncated ? " (search truncated)" : ""));
        if (!uncalled.isEmpty())
            lines.add(
                    "no indexed caller: "
                            + shown(uncalled)
                            + " (framework callback, reflection or unused)");
        return lines;
    }

    private static void enqueue(
            ArrayDeque<Node> queue, Map<Node, Integer> depth, Node node, int level) {
        if (depth.putIfAbsent(node, level) == null) queue.add(node);
    }

    private static Node node(CallerMethod caller) {
        return new Node(caller.classId(), caller.className(), caller.method(), caller.descriptor());
    }

    /**
     * Callers of the method on its class and direct supertypes; a lambda's are its enclosing
     * method's.
     */
    private static List<CallerMethod> callers(
            ReadQueryContext db, Node node, Map<Integer, List<Integer>> supertypes) {
        List<CallerMethod> callers = new ArrayList<>();
        if (node.method().startsWith("lambda$")) {
            String[] parts = node.method().split("\\$");
            String enclosing = parts.length > 1 ? parts[1] : "";
            for (var member : db.symbols.findClassMembers(node.classId()))
                if ("METHOD".equals(member.kind()) && enclosing.equals(member.name()))
                    callers.add(
                            new CallerMethod(
                                    node.classId(),
                                    node.className(),
                                    member.name(),
                                    member.descriptor()));
            return callers;
        }
        // A constructor is invoked on its own class only; its supertype's constructor is not it.
        List<Integer> owners =
                node.method().equals("<init>")
                        ? List.of(node.classId())
                        : withSupertypes(db, node.classId(), supertypes);
        for (int owner : owners)
            callers.addAll(
                    db.usages.findProductionCallerMethods(owner, node.method(), node.descriptor()));
        return callers;
    }

    private static List<Integer> withSupertypes(
            ReadQueryContext db, int classId, Map<Integer, List<Integer>> cache) {
        return cache.computeIfAbsent(
                classId,
                id -> {
                    List<Integer> ids = new ArrayList<>(List.of(id));
                    db.symbols
                            .findClassById(id)
                            .ifPresent(
                                    cls -> {
                                        List<String> names = new ArrayList<>(cls.interfaces());
                                        if (cls.superclass() != null) names.add(cls.superclass());
                                        // Only application types: through java.lang.Object every
                                        // caller would match.
                                        for (String name : names)
                                            db.symbols
                                                    .findClassByName(name)
                                                    .filter(type -> "source".equals(type.origin()))
                                                    .ifPresent(type -> ids.add(type.id()));
                                    });
                    return ids;
                });
    }

    private static boolean requestCallback(ReadQueryContext db, Node node) {
        var cls = db.symbols.findClassById(node.classId()).orElse(null);
        if (cls == null || node.method().startsWith("lambda$") || node.method().startsWith("<"))
            return false;
        return cls.interfaces().stream().anyMatch(REQUEST_CALLBACKS::contains)
                || REQUEST_CALLBACKS.contains(cls.superclass());
    }

    private static String nonRequestMarker(ReadQueryContext db, Node node) {
        String annotations =
                String.join(
                        " ", db.symbols.findMethodAnnotationNames(node.classId(), node.method()));
        for (String marker : NON_REQUEST)
            if (annotations.contains("." + marker + "\"")) return marker;
        return null;
    }

    private static boolean calledFromExpressions(ReadQueryContext db, Node node) {
        var cls = db.symbols.findClassById(node.classId()).orElse(null);
        if (cls == null) return false;
        var references =
                org.treblereel.mcp.mcp.query.ExpressionReferences.find(db, cls, node.method());
        return references != null && references.path("total").asInt() > 0;
    }

    private static Set<String> httpHandlers(ReadQueryContext db) {
        Set<String> handlers = new HashSet<>();
        String detail = db.index.getMetadata().get("framework_endpoints_detail");
        if (detail == null) return handlers;
        try {
            for (JsonNode endpoint : Json.MAPPER.readTree(detail))
                handlers.add(
                        endpoint.path("className").asText()
                                + "#"
                                + endpoint.path("methodName").asText());
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
        }
        return handlers;
    }

    private static String shown(Set<String> labels) {
        List<String> list = new ArrayList<>(labels);
        String text = String.join(", ", list.subList(0, Math.min(SHOWN, list.size())));
        return list.size() > SHOWN ? text + ", +" + (list.size() - SHOWN) + " more" : text;
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }
}
