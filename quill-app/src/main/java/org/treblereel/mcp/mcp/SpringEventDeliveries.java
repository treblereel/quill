package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.MemberAnnotationRecord;

/**
 * Who receives the Spring application events a method publishes. A call graph stops at
 * {@code ApplicationEventPublisher#publishEvent}; in Apollo AQ-028 claude found the listener with grep and left out
 * the multicaster and transaction facts that decide delivery. The publish call, the event classes the publishing
 * method builds and the {@code @EventListener}/{@code ApplicationListener} methods are all indexed already.
 */
final class SpringEventDeliveries {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> PUBLISHERS = Set.of("org.springframework.context.ApplicationEventPublisher",
            "org.springframework.context.ApplicationContext", "org.springframework.context.ConfigurableApplicationContext",
            "org.springframework.context.support.AbstractApplicationContext");
    private static final String APPLICATION_EVENT = "org.springframework.context.ApplicationEvent";
    private static final String EVENT_LISTENER = "org.springframework.context.event.EventListener";
    private static final String TX_EVENT_LISTENER = "org.springframework.transaction.event.TransactionalEventListener";
    private static final String APPLICATION_LISTENER = "org.springframework.context.ApplicationListener";
    private static final String ASYNC = "org.springframework.scheduling.annotation.Async";
    private static final Set<String> TRANSACTIONAL = Set.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional", "javax.transaction.Transactional");
    private static final int DEPTH = 3, NODE_CAP = 200;

    private SpringEventDeliveries() {}

    private record Method(int classId, String name, String descriptor, List<String> path) {}

    /** Null when nothing reachable within {@value #DEPTH} application calls publishes an event. */
    static ObjectNode find(ReadQueryContext db, ClassRecord cls, String method, String descriptor) {
        Deque<Method> queue = new ArrayDeque<>(List.of(new Method(cls.id(), method, descriptor, List.of())));
        Set<String> seen = new HashSet<>();
        List<ObjectNode> publications = new ArrayList<>();
        Map<String, Set<String>> eventsByPublisher = new LinkedHashMap<>();
        while (!queue.isEmpty() && seen.size() < NODE_CAP) {
            Method current = queue.removeFirst();
            if (!seen.add(current.classId() + "#" + current.name() + current.descriptor())) continue;
            String label = className(db, current.classId()) + "#" + current.name();
            List<String> path = new ArrayList<>(current.path());
            path.add(label);
            String publishLine = publishCall(db, current);
            List<Map<String, Object>> calls = db.jdbi().withHandle(handle -> handle.createQuery("""
                    SELECT mc.to_class_id, mc.to_method, mc.to_descriptor, c.class_name FROM method_calls mc
                    JOIN classes c ON c.id = mc.to_class_id
                    WHERE mc.from_class_id = :id AND mc.from_method = :name AND mc.from_descriptor = :descriptor""")
                    .bind("id", current.classId()).bind("name", current.name()).bind("descriptor", current.descriptor())
                    .mapToMap().list());
            if (publishLine != null) {
                Set<String> events = new LinkedHashSet<>();
                for (Map<String, Object> call : calls) {
                    String type = String.valueOf(call.get("class_name"));
                    if (isEvent(db, type)) events.add(type);
                }
                eventsByPublisher.put(label, events);
                ObjectNode publication = JSON.createObjectNode().put("publisher_method", label).put("publish_call", publishLine);
                publication.set("event_types", JSON.valueToTree(events));
                publication.set("call_path", JSON.valueToTree(path));
                publications.add(publication);
            }
            if (path.size() > DEPTH) continue;
            for (Map<String, Object> call : calls)
                queue.add(new Method(((Number) call.get("to_class_id")).intValue(), String.valueOf(call.get("to_method")),
                        String.valueOf(call.get("to_descriptor")), path));
        }
        if (publications.isEmpty()) return null;
        ObjectNode result = JSON.createObjectNode()
                .put("scope", "Spring application events published within " + DEPTH + " application calls; event types are "
                        + "the event classes the publishing method builds; listeners matched by parameter type and its supertypes");
        ArrayNode published = result.putArray("publications");
        for (ObjectNode publication : publications) {
            ArrayNode listeners = publication.putArray("listeners");
            for (JsonNode event : publication.path("event_types")) listeners(db, event.asText()).forEach(listeners::add);
            published.add(publication);
        }
        result.put("multicaster", multicaster(db));
        result.set("transactional_publishing_path", JSON.valueToTree(transactional(db, publications)));
        result.put("transaction_note", "@EventListener runs immediately inside publishEvent; only @TransactionalEventListener waits "
                + "for a transaction phase, and without an active transaction it is skipped unless fallbackExecution=true");
        return result;
    }

    /** The publishEvent call this method makes, from its recorded external calls, or null. */
    private static String publishCall(ReadQueryContext db, Method method) {
        String raw = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT caller_external_calls FROM method_evidence
                WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor""")
                .bind("id", method.classId()).bind("name", method.name()).bind("descriptor", method.descriptor())
                .mapTo(String.class).findOne().orElse("[]"));
        try {
            for (JsonNode call : JSON.readTree(raw)) {
                String[] parts = call.asText().split("\\|", -1);
                if (parts.length >= 4 && parts[3].equals("publishEvent") && PUBLISHERS.contains(parts[2]))
                    return parts[2] + "#publishEvent" + (parts.length > 4 ? parts[4] : "") + " line " + parts[1];
            }
        } catch (Exception unreadable) {
            // Unreadable evidence names no publish call.
        }
        return null;
    }

    private static boolean isEvent(ReadQueryContext db, String type) {
        if (supertypes(db, type).contains(APPLICATION_EVENT)) return true;
        return Boolean.TRUE.equals(db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT 1 FROM class_members WHERE annotations LIKE :listener AND parameter_types LIKE :type LIMIT 1""")
                .bind("listener", "%EventListener%").bind("type", "%\"" + type + "\"%").mapTo(Integer.class).findOne().isPresent()));
    }

    /** {@code type} and its indexed superclasses and interfaces. */
    private static Set<String> supertypes(ReadQueryContext db, String type) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(type));
        while (!pending.isEmpty() && result.size() < 50) {
            String current = pending.removeFirst();
            if (!result.add(current)) continue;
            db.symbols.findClassByName(current).ifPresent(record -> {
                if (record.superclass() != null) pending.add(record.superclass());
                pending.addAll(record.interfaces());
            });
        }
        return result;
    }

    private static List<ObjectNode> listeners(ReadQueryContext db, String event) {
        Set<String> accepted = supertypes(db, event);
        List<ObjectNode> result = new ArrayList<>();
        List<Map<String, Object>> rows = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.id, c.class_name, c.interfaces, m.name FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.lifecycle = 'current' AND m.kind = 'METHOD'
                  AND (m.annotations LIKE '%EventListener%' OR m.name = 'onApplicationEvent')
                GROUP BY c.id, m.name ORDER BY c.class_name, m.name""").mapToMap().list());
        for (Map<String, Object> row : rows) {
            int classId = ((Number) row.get("id")).intValue();
            for (ClassMemberRecord member : db.symbols.findClassMembers(classId)) {
                if (!member.name().equals(row.get("name")) || member.parameterTypes().size() != 1) continue;
                String parameter = member.parameterTypes().getFirst();
                boolean annotated = member.annotations().contains(EVENT_LISTENER) || member.annotations().contains(TX_EVENT_LISTENER);
                boolean listenerInterface = member.name().equals("onApplicationEvent")
                        && String.valueOf(row.get("interfaces")).contains(APPLICATION_LISTENER) && !parameter.equals(APPLICATION_EVENT);
                if (!(annotated || listenerInterface) || !accepted.contains(parameter)) continue;
                ObjectNode listener = JSON.createObjectNode()
                        .put("listener", row.get("class_name") + "#" + member.name() + "(" + simple(parameter) + ")")
                        .put("mechanism", member.annotations().contains(TX_EVENT_LISTENER) ? "@TransactionalEventListener"
                                : annotated ? "@EventListener" : "ApplicationListener");
                for (MemberAnnotationRecord annotation : member.annotationDetails()) {
                    if (annotation.annotationName().equals(TX_EVENT_LISTENER)) {
                        listener.put("phase", String.valueOf(annotation.values().getOrDefault("phase", "AFTER_COMMIT (default)")));
                        if (annotation.values().containsKey("fallbackExecution"))
                            listener.put("fallback_execution", String.valueOf(annotation.values().get("fallbackExecution")));
                    }
                    Object condition = annotation.values().get("condition");
                    if (condition != null && !String.valueOf(condition).isBlank()) listener.put("condition", String.valueOf(condition));
                }
                boolean async = member.annotations().contains(ASYNC) || db.symbols.findClassAnnotations(classId).stream()
                        .anyMatch(annotation -> annotation.annotationName().equals(ASYNC));
                listener.put("delivery", async ? "@Async: handed to a task executor" : "synchronous unless the multicaster has an executor");
                if (!parameter.equals(event)) listener.put("receives_as", parameter);
                result.add(listener);
            }
        }
        return result;
    }

    private static String multicaster(ReadQueryContext db) {
        boolean custom = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT 1 FROM beans WHERE qualifiers LIKE '%"@Qualifier(\\"applicationEventMulticaster\\")"%' LIMIT 1""")
                .mapTo(Integer.class).findOne().isPresent());
        return custom ? "custom applicationEventMulticaster bean: delivery follows its configuration (check for a task executor)"
                : "no applicationEventMulticaster bean in the application index: Spring's default SimpleApplicationEventMulticaster "
                        + "without an executor calls non-@Async listeners on the publishing thread";
    }

    private static List<String> transactional(ReadQueryContext db, List<ObjectNode> publications) {
        Set<String> result = new LinkedHashSet<>();
        for (ObjectNode publication : publications) {
            for (JsonNode step : publication.path("call_path")) {
                String label = step.asText();
                int hash = label.lastIndexOf('#');
                db.symbols.findClassByName(label.substring(0, hash)).ifPresent(record -> {
                    boolean onClass = db.symbols.findClassAnnotations(record.id()).stream()
                            .anyMatch(annotation -> TRANSACTIONAL.contains(annotation.annotationName()));
                    boolean onMethod = db.symbols.findClassMembers(record.id()).stream()
                            .anyMatch(member -> member.name().equals(label.substring(hash + 1))
                                    && member.annotations().stream().anyMatch(TRANSACTIONAL::contains));
                    if (onClass || onMethod) result.add(label + (onMethod ? " (method)" : " (class)"));
                });
            }
        }
        return List.copyOf(result);
    }

    private static String className(ReadQueryContext db, int classId) {
        return db.symbols.findClassById(classId).map(ClassRecord::className).orElse("class#" + classId);
    }

    private static String simple(String type) {
        return type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1);
    }
}
