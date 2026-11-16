package org.treblereel.mcp.mcp.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.UsageRepository.MethodCallView;

/**
 * The proxy semantics of the methods a call hierarchy names: @Transactional (with
 * propagation), @Async, cache and retry annotations, on the method or its class, and calls inside
 * one class that bypass the proxy. Apollo AQ-027 (Codex): asked where the transaction on
 * ReleaseController#publish starts and ends, Codex read six services one by one for
 * their @Transactional.
 */
final class CallInterception {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Set<String> INTERCEPTING =
            Set.of("Transactional", "Async", "Cacheable", "CachePut", "CacheEvict", "Retryable");

    private CallInterception() {}

    private record Endpoint(int classId, String className, String method, String descriptor) {}

    static void append(ObjectNode root, ReadQueryContext reader, List<MethodCallView> calls) {
        Map<String, Endpoint> endpoints = new LinkedHashMap<>();
        Map<String, String> selfCallers = new LinkedHashMap<>();
        for (MethodCallView call : calls) {
            endpoints.putIfAbsent(
                    call.fromClass() + "#" + call.fromMethod(),
                    new Endpoint(
                            call.fromClassId(),
                            call.fromClass(),
                            call.fromMethod(),
                            call.fromDescriptor()));
            String callee = call.toClass() + "#" + call.toMethod();
            endpoints.putIfAbsent(
                    callee,
                    new Endpoint(
                            call.toClassId(),
                            call.toClass(),
                            call.toMethod(),
                            call.toDescriptor()));
            if (call.fromClassId() == call.toClassId()
                    && !call.fromMethod().equals(call.toMethod()))
                selfCallers.putIfAbsent(callee, call.fromMethod());
        }
        ObjectNode result = JSON.createObjectNode();
        Map<Integer, List<String>> classLevel = new java.util.HashMap<>();
        List<org.treblereel.mcp.model.ClassRecord> all = null;
        for (var entry : endpoints.entrySet()) {
            Endpoint endpoint = entry.getValue();
            if (endpoint.method().startsWith("<")) continue;
            List<String> found = memberAnnotations(reader, endpoint);
            if (found.isEmpty())
                found =
                        classLevel.computeIfAbsent(
                                endpoint.classId(), id -> classAnnotations(reader, id));
            if (found.isEmpty()) {
                if (all == null) all = reader.symbols.findAllClasses();
                found = implementationAnnotations(reader, endpoint, all);
            }
            if (found.isEmpty()) continue;
            ArrayNode lines = result.putArray(entry.getKey());
            found.forEach(lines::add);
            String caller = selfCallers.get(entry.getKey());
            if (caller != null)
                lines.add(
                        "self-invocation from "
                                + caller
                                + ": a call inside the class does not pass the proxy, so the annotation does not apply on that path");
        }
        if (result.isEmpty()) return;
        root.set("interception", result);
        root.put(
                "interception_basis",
                "annotations a Spring proxy applies when the method is called through another bean; "
                        + "Spring's default propagation is REQUIRED (join an existing transaction)");
    }

    private static List<String> memberAnnotations(ReadQueryContext reader, Endpoint endpoint) {
        List<String> details =
                reader.jdbi()
                        .withHandle(
                                handle ->
                                        handle.createQuery(
                                                        "SELECT annotation_details FROM class_members WHERE class_id = :id AND name = :name AND descriptor = :descriptor")
                                                .bind("id", endpoint.classId())
                                                .bind("name", endpoint.method())
                                                .bind("descriptor", endpoint.descriptor())
                                                .mapTo(String.class)
                                                .list());
        List<String> result = new ArrayList<>();
        for (String json : details) {
            try {
                for (JsonNode annotation : JSON.readTree(json)) {
                    if (!"METHOD".equals(annotation.path("targetKind").asText("METHOD"))) continue;
                    String name = annotation.path("annotationName").asText();
                    if (intercepting(name)) result.add(render(name, annotation.path("values"), ""));
                }
            } catch (Exception unreadable) {
                // Unreadable details state nothing.
            }
        }
        return result;
    }

    private static final int MAX_IMPLEMENTATIONS = 4;

    /**
     * For a call through an interface or abstract method, what its indexed implementations carry:
     * Apollo calls MessageSender#sendMessage, and DatabaseMessageSender#sendMessage
     * is @Transactional.
     */
    private static List<String> implementationAnnotations(
            ReadQueryContext reader,
            Endpoint endpoint,
            List<org.treblereel.mcp.model.ClassRecord> all) {
        List<String> result = new ArrayList<>();
        for (var implementation : all) {
            if (result.size() == MAX_IMPLEMENTATIONS) break;
            if (!implementation.interfaces().contains(endpoint.className())
                    && !endpoint.className().equals(implementation.superclass())) continue;
            List<String> own =
                    memberAnnotations(
                            reader,
                            new Endpoint(
                                    implementation.id(),
                                    implementation.className(),
                                    endpoint.method(),
                                    endpoint.descriptor()));
            for (String annotation : own)
                result.add("via " + implementation.className() + ": " + annotation);
        }
        return result;
    }

    private static List<String> classAnnotations(ReadQueryContext reader, int classId) {
        List<String> result = new ArrayList<>();
        for (var row :
                reader.jdbi()
                        .withHandle(
                                handle ->
                                        handle.createQuery(
                                                        "SELECT annotation_name, annotation_values FROM class_annotations WHERE class_id = :id AND direct = 1 ORDER BY annotation_name")
                                                .bind("id", classId)
                                                .mapToMap()
                                                .list())) {
            String name = String.valueOf(row.get("annotation_name"));
            if (!intercepting(name)) continue;
            JsonNode values;
            try {
                Object raw = row.get("annotation_values");
                values = raw == null ? JSON.createObjectNode() : JSON.readTree(raw.toString());
            } catch (Exception unreadable) {
                values = JSON.createObjectNode();
            }
            result.add(render(name, values, " on class"));
        }
        return result;
    }

    private static boolean intercepting(String name) {
        return INTERCEPTING.contains(name.substring(name.lastIndexOf('.') + 1));
    }

    private static String render(String name, JsonNode values, String suffix) {
        String simple = name.substring(name.lastIndexOf('.') + 1);
        Set<String> defaults = new java.util.HashSet<>();
        values.path("$defaults").forEach(item -> defaults.add(item.asText()));
        List<String> parts = new ArrayList<>();
        boolean spring = name.startsWith("org.springframework.");
        if (spring && simple.equals("Transactional"))
            parts.add("propagation=" + values.path("propagation").asText("REQUIRED"));
        Map<String, String> others = new TreeMap<>();
        values.fields()
                .forEachRemaining(
                        field -> {
                            String key = field.getKey();
                            if (key.equals("$defaults")
                                    || defaults.contains(key)
                                    || (spring && key.equals("propagation"))) return;
                            JsonNode value = field.getValue();
                            others.put(key, value.isTextual() ? value.asText() : value.toString());
                        });
        others.forEach((key, value) -> parts.add(key + "=" + value));
        return "@"
                + simple
                + (parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")")
                + suffix;
    }
}
