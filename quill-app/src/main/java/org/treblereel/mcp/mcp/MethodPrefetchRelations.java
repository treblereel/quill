package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * What a method fact needs beyond its callers for a first-turn answer, one line per relation:
 * the method's annotations with their set values, what it calls two levels down (lambda bodies
 * count as the method that declares them), and for a producer method the bean it produces, its
 * parameters and the injection points it resolves. Static bytecode evidence only.
 */
final class MethodPrefetchRelations {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 40;
    private static final int DEPTH = 2;

    private MethodPrefetchRelations() {
    }

    /**
     * Callers become one line per class (class, file, calling methods) and coverage keeps its
     * verdict and gaps: per-call evidence lines fit poorly in a hook and stay one tool call away.
     */
    static void compactImpact(ObjectNode fact) {
        for (String field : List.of("production_callers", "test_callers", "unclassified_callers",
                "indirect_production")) {
            if (!(fact.get(field) instanceof ArrayNode callers)) continue;
            ArrayNode lines = JSON.createArrayNode();
            for (JsonNode caller : callers) {
                Set<String> methods = new LinkedHashSet<>();
                caller.path("methods").forEach(method -> methods.add(method.path("method").asText()
                        + (method.has("depth") ? "@" + method.path("depth").asInt() : "")));
                lines.add(caller.path("class").asText() + " " + caller.path("source").asText()
                        + (methods.isEmpty() ? "" : " via " + String.join(", ", methods)));
            }
            fact.set(field, lines);
        }
        if (fact.get("coverage") instanceof ObjectNode coverage) {
            ObjectNode compact = JSON.createObjectNode();
            for (String field : List.of("scan_complete", "incomplete_reasons", "not_covered"))
                if (coverage.has(field)) compact.set(field, coverage.get(field));
            fact.set("coverage", compact);
        }
        if (fact.get("override_evidence") instanceof ObjectNode overrides) {
            overrides.remove(List.of("selected_declarations", "limitations", "showing", "has_more"));
        }
        fact.remove(List.of("reference_page_counts", "evidence_detail"));
    }

    static void append(ReadQueryContext db, ObjectNode fact) throws Exception {
        String owner = fact.path("class").asText(null);
        String name = fact.path("name").asText(null);
        String descriptor = fact.path("descriptor").asText(null);
        if (owner == null || name == null || descriptor == null) return;
        Integer classId = db.jdbi().withHandle(handle -> handle.createQuery(
                "SELECT id FROM classes WHERE class_name = :name AND lifecycle = 'current' LIMIT 1")
                .bind("name", owner).mapTo(Integer.class).findOne().orElse(null));
        if (classId == null) return;
        annotations(db, fact, classId, name, descriptor);
        callees(db, fact, classId, name, descriptor);
        producer(db, fact, classId, name);
    }

    private static void annotations(ReadQueryContext db, ObjectNode fact, int classId, String name,
            String descriptor) throws Exception {
        String details = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT annotation_details FROM class_members
                WHERE class_id = :id AND name = :name AND descriptor = :descriptor""")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor)
                .mapTo(String.class).findOne().orElse("[]"));
        ArrayNode lines = JSON.createArrayNode();
        for (JsonNode annotation : JSON.readTree(details)) {
            if (annotation.path("parameterIndex").asInt(-1) >= 0) continue;
            Set<String> defaults = new LinkedHashSet<>();
            annotation.path("values").path("$defaults").forEach(value -> defaults.add(value.asText()));
            StringBuilder line = new StringBuilder("@").append(annotation.path("annotationName").asText());
            List<String> values = new java.util.ArrayList<>();
            annotation.path("values").properties().forEach(entry -> {
                if (!entry.getKey().startsWith("$") && !defaults.contains(entry.getKey()))
                    values.add(entry.getKey() + "=" + text(entry.getValue()));
            });
            if (!values.isEmpty()) line.append('(').append(String.join(", ", values)).append(')');
            lines.add(line.toString());
        }
        if (!lines.isEmpty()) fact.set("annotations", lines);
    }

    /** Callees breadth-first to {@link #DEPTH}; a lambda body is expanded at its declarer's depth. */
    private static void callees(ReadQueryContext db, ObjectNode fact, int classId, String name,
            String descriptor) {
        record Method(int classId, String name, String descriptor, int depth) {}
        Set<String> seen = new LinkedHashSet<>();
        Set<String> lines = new LinkedHashSet<>();
        ArrayDeque<Method> queue = new ArrayDeque<>(List.of(new Method(classId, name, descriptor, 1)));
        seen.add(classId + "#" + name + descriptor);
        while (!queue.isEmpty() && lines.size() < LIMIT * 2) {
            Method method = queue.removeFirst();
            for (Map<String, Object> call : db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT mc.to_class_id, c.class_name, mc.to_method, mc.to_descriptor, mc.invocation_kind
                    FROM method_calls mc JOIN classes c ON c.id = mc.to_class_id
                    WHERE mc.from_class_id = :id AND mc.from_method = :name AND mc.from_descriptor = :descriptor
                    ORDER BY mc.evidence_lines""").bind("id", method.classId()).bind("name", method.name())
                    .bind("descriptor", method.descriptor()).mapToMap().list())) {
                String callee = String.valueOf(call.get("to_method"));
                int calleeClass = ((Number) call.get("to_class_id")).intValue();
                String calleeDescriptor = String.valueOf(call.get("to_descriptor"));
                boolean lambda = callee.startsWith("lambda$");
                if (!lambda) lines.add(method.depth() + " " + call.get("class_name") + "#" + callee
                        + " [" + call.get("invocation_kind") + "]");
                int next = lambda ? method.depth() : method.depth() + 1;
                if (next <= DEPTH && seen.add(calleeClass + "#" + callee + calleeDescriptor))
                    queue.addLast(new Method(calleeClass, callee, calleeDescriptor, next));
            }
            String external = db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT caller_external_calls FROM method_evidence
                    WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor""")
                    .bind("id", method.classId()).bind("name", method.name()).bind("descriptor", method.descriptor())
                    .mapTo(String.class).findOne().orElse("[]"));
            try {
                for (JsonNode call : JSON.readTree(external)) {
                    // line|ordinal|owner|method|descriptor|...
                    String[] parts = call.asText().split("\\|", -1);
                    if (parts.length > 3) lines.add(method.depth() + " " + parts[2] + "#" + parts[3]
                            + (parts.length > 4 ? javaSignature(parts[4]) : "") + " [external]");
                }
            } catch (Exception unreadable) {
                // Evidence rows are written by the indexer; an unreadable row only drops its external calls.
            }
        }
        if (lines.isEmpty()) return;
        if (lines.stream().anyMatch(line -> line.contains(".event.Event#fire")))
            firedEventObservers(db, fact, classId);
        ArrayNode values = fact.putArray("calls");
        lines.stream().limit(LIMIT).forEach(values::add);
        fact.put("calls_basis", "depth, callee, invocation; depth 2 = called by a depth-1 callee");
        if (lines.size() > LIMIT) fact.putObject("calls_truncated").put("shown", LIMIT).put("total", lines.size());
    }

    /** The method fires an event: observers of each Event<T> its class injects (the fired one is among them). */
    private static void firedEventObservers(ReadQueryContext db, ObjectNode fact, int classId) {
        ArrayNode observers = JSON.createArrayNode();
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT ip.target_type FROM injection_points ip JOIN beans ob ON ob.id = ip.bean_id
                WHERE ob.class_id = :id ORDER BY ip.id""").bind("id", classId).mapTo(String.class).list())
                .forEach(type -> ClassPrefetchRelations.eventObservers(db, type).forEach(observer ->
                        observers.add(ClassPrefetchRelations.source(type) + " -> " + observer)));
        if (!observers.isEmpty()) fact.set("observers_of_class_events", observers);
    }

    private static void producer(ReadQueryContext db, ObjectNode fact, int classId, String name) {
        List<Map<String, Object>> beans = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT id, kind, scope, qualifiers, bean_types FROM beans
                WHERE declaring_class_id = :id AND member_name = :name AND kind = 'PRODUCER_METHOD'""")
                .bind("id", classId).bind("name", name).mapToMap().list());
        if (beans.isEmpty()) return;
        ArrayNode produces = fact.putArray("produces");
        ArrayNode parameters = JSON.createArrayNode();
        ArrayNode injectedInto = JSON.createArrayNode();
        for (Map<String, Object> bean : beans) {
            int id = ((Number) bean.get("id")).intValue();
            produces.add(bean.get("kind") + " " + bean.get("scope") + " qualifiers " + bean.get("qualifiers")
                    + " types " + bean.get("bean_types"));
            db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status, rc.class_name AS resolved
                    FROM injection_points ip
                    LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                    LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                    WHERE ip.bean_id = :id ORDER BY ip.id""").bind("id", id).mapToMap().list())
                    .forEach(row -> parameters.add(row.get("field_name") + ": " + row.get("target_type") + " "
                            + row.get("qualifiers") + " -> " + (row.get("resolved") == null
                                    ? "unresolved" + dependencyProviders(db, String.valueOf(row.get("target_type")))
                                    : row.get("resolved"))
                            + " [" + row.get("resolution_status") + "]"));
            db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT DISTINCT oc.class_name AS owner, ip.field_name, ip.qualifiers, ip.resolution_status, oc.source_file
                    FROM injection_points ip
                    JOIN beans ob ON ob.id = ip.bean_id
                    JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                    WHERE ip.resolved_bean_id = :id OR ip.resolution_candidates LIKE :candidate
                    ORDER BY oc.class_name, ip.field_name""").bind("id", id)
                    .bind("candidate", "%\"beanId\":" + id + ",%").mapToMap().list())
                    .forEach(row -> injectedInto.add(row.get("owner") + "#" + row.get("field_name") + " "
                            + row.get("qualifiers") + " [" + row.get("resolution_status") + "] " + row.get("source_file")));
        }
        if (!parameters.isEmpty()) fact.set("producer_parameters", parameters);
        if (!injectedInto.isEmpty()) fact.set("produced_bean_injected_into", injectedInto);
    }

    /** Dependency beans providing {@code type}, for a parameter the application index cannot resolve. */
    private static String dependencyProviders(ReadQueryContext db, String type) {
        List<String> providers = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT class_name || COALESCE('#' || member_name, '') || ' from ' || COALESCE(artifact, 'unknown')
                FROM external_beans WHERE bean_types LIKE :type ORDER BY artifact, class_name LIMIT 5""")
                .bind("type", "%\"" + type + "\"%").mapTo(String.class).list());
        return providers.isEmpty() ? "" : "; dependency providers: " + String.join(", ", providers);
    }

    /** (Ljava/nio/file/Path;[I)Z reads (java.nio.file.Path, int[]):boolean; malformed input reads as empty. */
    static String javaSignature(String descriptor) {
        int close = descriptor.indexOf(')');
        if (!descriptor.startsWith("(") || close < 0) return "";
        List<String> parameters = new java.util.ArrayList<>();
        int[] at = {1};
        while (at[0] < close) {
            String type = javaType(descriptor, at);
            if (type == null) return "";
            parameters.add(type);
        }
        at[0] = close + 1;
        String result = javaType(descriptor, at);
        return result == null ? "" : "(" + String.join(", ", parameters) + "):" + result;
    }

    private static String javaType(String descriptor, int[] at) {
        if (at[0] >= descriptor.length()) return null;
        char kind = descriptor.charAt(at[0]++);
        switch (kind) {
            case '[': { String element = javaType(descriptor, at); return element == null ? null : element + "[]"; }
            case 'L': {
                int end = descriptor.indexOf(';', at[0]);
                if (end < 0) return null;
                String name = descriptor.substring(at[0], end).replace('/', '.');
                at[0] = end + 1;
                return ClassPrefetchRelations.source(name);
            }
            case 'Z': return "boolean";
            case 'B': return "byte";
            case 'C': return "char";
            case 'S': return "short";
            case 'I': return "int";
            case 'J': return "long";
            case 'F': return "float";
            case 'D': return "double";
            case 'V': return "void";
            default: return null;
        }
    }

    private static String text(JsonNode value) {
        return value.isTextual() ? value.asText() : value.toString();
    }
}
