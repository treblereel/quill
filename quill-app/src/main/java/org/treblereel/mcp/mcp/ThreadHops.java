package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * Where a method hands work to another thread (Mutiny runSubscriptionOn/emitOn, Reactor
 * subscribeOn/publishOn, executors, CompletableFuture *Async, Vert.x executeBlocking) and which
 * beans the code it hands off reaches: the lambdas it creates and what they call, three calls deep.
 * V55 CQ-025: asked whether an async path may use contextual beans, Codex read each class for its scope.
 */
final class ThreadHops {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEPTH = 3;
    private static final int LIMIT = 12;

    private ThreadHops() {
    }

    static boolean switchesThread(String owner, String method) {
        if (owner.startsWith("io.smallrye.mutiny.")) return method.equals("runSubscriptionOn") || method.equals("emitOn");
        if (owner.startsWith("reactor.core.publisher.")) return method.equals("subscribeOn") || method.equals("publishOn");
        if (owner.equals("java.util.concurrent.CompletableFuture")) return method.endsWith("Async");
        if (owner.startsWith("java.util.concurrent.") && (owner.endsWith("Executor") || owner.endsWith("ExecutorService")
                || owner.endsWith("ScheduledExecutorService")))
            return Set.of("execute", "submit", "schedule", "invokeAll", "invokeAny", "scheduleAtFixedRate", "scheduleWithFixedDelay").contains(method);
        if (owner.startsWith("org.eclipse.microprofile.context.ManagedExecutor"))
            return method.equals("execute") || method.equals("submit") || method.endsWith("Async");
        if (owner.contains(".vertx.") && (owner.endsWith(".Vertx") || owner.endsWith(".Context")))
            return method.startsWith("executeBlocking") || method.equals("runOnContext");
        return owner.equals("java.lang.Thread") && method.equals("start");
    }

    static ObjectNode of(ReadQueryContext db, int classId, String method, String descriptor) throws Exception {
        String external = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT caller_external_calls FROM method_evidence
                WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor).mapTo(String.class).findOne().orElse("[]"));
        List<String> switches = new ArrayList<>();
        String executor = null;
        for (JsonNode call : JSON.readTree(external)) {
            // ordinal|line|owner|method|descriptor|phase
            String[] parts = call.asText().split("\\|", -1);
            if (parts.length < 4) continue;
            if (parts[2].equals("io.smallrye.mutiny.infrastructure.Infrastructure") && parts[3].equals("getDefaultExecutor"))
                executor = "Infrastructure#getDefaultExecutor";
            if (switchesThread(parts[2], parts[3]))
                switches.add("line " + parts[1] + " " + parts[2] + "#" + parts[3] + (executor == null ? "" : "(" + executor + ")"));
        }
        if (switches.isEmpty()) return null;
        ObjectNode fact = JSON.createObjectNode();
        fact.set("switches", JSON.valueToTree(switches));
        Set<String> reached = new LinkedHashSet<>();
        Set<String> requestScoped = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (Map<String, Object> lambda : db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT to_class_id, to_method, to_descriptor FROM method_calls
                WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor AND to_method LIKE 'lambda$%'""")
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor).mapToMap().list()))
            queue.add(new Object[] {((Number) lambda.get("to_class_id")).intValue(), lambda.get("to_method"), lambda.get("to_descriptor"), 1});
        while (!queue.isEmpty() && reached.size() < LIMIT) {
            Object[] current = queue.removeFirst();
            int id = (int) current[0];
            String name = String.valueOf(current[1]), desc = String.valueOf(current[2]);
            int depth = (int) current[3];
            if (!seen.add(id + "#" + name + desc)) continue;
            String shown = name.replaceFirst("^lambda\\$(.+)\\$\\d+$", "$1 (lambda)");
            for (Map<String, Object> row : db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT a.field_name, rb.scope, rc.class_name AS resolved, ip.target_type
                    FROM field_accesses a
                    JOIN beans ob ON ob.class_id = a.to_class_id AND ob.member_name IS NULL
                    JOIN injection_points ip ON ip.bean_id = ob.id AND ip.field_name = a.field_name
                    LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                    LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                    WHERE a.from_class_id = :id AND a.from_method = :name AND a.from_descriptor = :descriptor
                      AND a.access_kind LIKE 'read%' ORDER BY a.field_name""")
                    .bind("id", id).bind("name", name).bind("descriptor", desc).mapToMap().list())) {
                String scope = row.get("scope") == null ? null : String.valueOf(row.get("scope"));
                String bean = row.get("resolved") == null ? String.valueOf(row.get("target_type")) : String.valueOf(row.get("resolved"));
                reached.add(bean + " " + (scope == null ? "unresolved" : scope) + ": " + row.get("field_name") + " in " + shown);
                if ("@RequestScoped".equals(scope) || "@SessionScoped".equals(scope)) requestScoped.add(bean);
            }
            if (depth >= DEPTH) continue;
            for (Map<String, Object> call : db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT to_class_id, to_method, to_descriptor FROM method_calls
                    WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor""")
                    .bind("id", id).bind("name", name).bind("descriptor", desc).mapToMap().list()))
                queue.add(new Object[] {((Number) call.get("to_class_id")).intValue(), call.get("to_method"), call.get("to_descriptor"),
                        String.valueOf(call.get("to_method")).startsWith("lambda$") ? depth : depth + 1});
        }
        ArrayNode beans = fact.putArray("beans_reached_after");
        // Application beans and their scopes answer the question; unresolved framework fields go last.
        reached.stream().filter(line -> !line.contains(" unresolved: ")).forEach(beans::add);
        reached.stream().filter(line -> line.contains(" unresolved: ")).forEach(beans::add);
        fact.set("request_scoped_after", JSON.valueToTree(requestScoped));
        fact.put("basis", "injected fields read by this method's lambdas and their callees, " + DEPTH
                + " deep; request/session scope needs its context on the other thread; propagation not tracked");
        return fact;
    }
}
