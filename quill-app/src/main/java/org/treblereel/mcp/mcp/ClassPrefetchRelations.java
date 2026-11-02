package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * The CDI and test relations a class fact needs for a first-turn answer, one line per relation:
 * what the class injects, which injection points require or resolve to it, and which test
 * classes depend on it. Related source files are the production files that inject, implement or
 * call the class. Lines are indexed static evidence; runtime selection is not implied.
 */
final class ClassPrefetchRelations {

    private static final int LIMIT = 30;

    private ClassPrefetchRelations() {
    }

    static void append(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        lines(fact, "injects", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT ip.kind, ip.field_name, ip.target_type, ip.qualifiers, ip.resolution_status,
                       rc.class_name AS resolved_class, rb.member_name AS resolved_member, rb.kind AS resolved_kind
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id
                LEFT JOIN classes rc ON rc.id = COALESCE(rb.declaring_class_id, rb.class_id)
                WHERE ob.class_id = :id AND ob.member_name IS NULL
                ORDER BY ip.id""").bind("id", cls.id()).mapToMap().list()), row -> injects(db, row));
        lines(fact, "injected_into", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT DISTINCT oc.class_name AS owner, ob.member_name AS owner_member, ob.kind AS owner_kind, ip.kind,
                       ip.field_name, ip.target_type, ip.resolution_status
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE ip.target_type = :name OR ip.target_type LIKE :generic
                   OR ip.resolved_bean_id IN (SELECT id FROM beans WHERE class_id = :id AND member_name IS NULL)
                ORDER BY oc.class_name, ip.field_name""")
                .bind("name", cls.className()).bind("generic", "%<" + cls.className() + ">%")
                .bind("id", cls.id()).mapToMap().list()), ClassPrefetchRelations::injectedInto);
        lines(fact, "decorators", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name, b.priority FROM beans b JOIN classes c ON c.id = b.class_id
                WHERE b.kind = 'DECORATOR' AND b.bean_types LIKE :type AND c.id <> :id
                ORDER BY b.priority IS NULL, b.priority, c.class_name""")
                .bind("type", "%\"" + cls.className() + "\"%").bind("id", cls.id()).mapToMap().list()),
                row -> row.get("class_name") + " @Priority(" + (row.get("priority") == null ? "none" : row.get("priority")) + ")");
        if (fact.has("decorators"))
            fact.put("decorators_basis", "@Decorator beans with this bean type; a lower priority is invoked first, around the decorated bean");
        lines(fact, "test_classes", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT DISTINCT tc.source_file
                FROM dependencies d JOIN classes tc ON tc.id = d.from_class_id
                WHERE d.to_class_id = :id AND tc.source_set = 'test' AND tc.source_file IS NOT NULL
                ORDER BY tc.source_file""").bind("id", cls.id()).mapToMap().list()),
                row -> String.valueOf(row.get("source_file")));
        lines(fact, "related_source_files", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT DISTINCT oc.source_file
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE (ip.target_type = :name OR ip.target_type LIKE :generic
                   OR ip.resolved_bean_id IN (SELECT id FROM beans WHERE class_id = :id AND member_name IS NULL))
                  AND oc.source_file IS NOT NULL
                UNION
                SELECT source_file FROM classes
                WHERE lifecycle = 'current' AND source_file IS NOT NULL
                  AND (superclass = :name OR interfaces LIKE :interface)
                UNION
                SELECT c.source_file FROM method_calls mc JOIN classes c ON c.id = mc.from_class_id
                WHERE mc.to_class_id = :id AND mc.from_class_id <> :id AND c.lifecycle = 'current'
                  AND c.source_file IS NOT NULL AND c.source_set = 'main'
                ORDER BY 1""").bind("name", cls.className()).bind("generic", "%<" + cls.className() + ">%")
                .bind("interface", "%\"" + cls.className() + "\"%").bind("id", cls.id()).mapToMap().list()),
                row -> String.valueOf(row.values().iterator().next()));
        lines(fact, "configuration", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT key, kind, member, annotation, NULL AS file, NULL AS line FROM configuration_usages
                WHERE class_name = :name OR class_name LIKE :nested
                UNION ALL
                SELECT key, kind, NULL, json_extract(evidence, '$.declaring_class'), file, line FROM configuration_definitions
                WHERE evidence LIKE :declared OR evidence LIKE :declaredNested
                ORDER BY 1""").bind("name", cls.className()).bind("nested", cls.className() + "$%")
                .bind("declared", "%\"declaring_class\":\"" + cls.className() + "\"%")
                .bind("declaredNested", "%\"declaring_class\":\"" + cls.className() + "$%")
                .mapToMap().list()), ClassPrefetchRelations::configuration);
        ConfigMappingKeys.append(db, fact, cls);
        List<String> types = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT ip.target_type FROM injection_points ip JOIN beans ob ON ob.id = ip.bean_id
                WHERE ob.class_id = :id AND ob.member_name IS NULL ORDER BY ip.id""")
                .bind("id", cls.id()).mapTo(String.class).list());
        ArrayNode observers = JSON.createArrayNode();
        ArrayNode candidates = JSON.createArrayNode();
        for (String type : types) {
            String event = argument(type, EVENT);
            if (event != null) observers(db, event).forEach(observers::add);
            String instance = argument(type, INSTANCE);
            if (instance != null) typeCandidates(db, instance).forEach(candidates::add);
        }
        if (!observers.isEmpty()) fact.set("observers_of_injected_events", observers);
        if (!candidates.isEmpty()) fact.set("instance_candidates", candidates);
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EVENT = List.of("jakarta.enterprise.event.Event", "javax.enterprise.event.Event");
    private static final List<String> INSTANCE = List.of("jakarta.enterprise.inject.Instance",
            "javax.enterprise.inject.Instance", "io.quarkus.arc.InjectableInstance");
    private static final List<String> CONDITIONS = List.of("io.quarkus.arc.lookup.LookupIfProperty",
            "io.quarkus.arc.lookup.LookupUnlessProperty", "io.quarkus.arc.profile.IfBuildProfile",
            "io.quarkus.arc.profile.UnlessBuildProfile", "io.quarkus.arc.properties.IfBuildProperty",
            "io.quarkus.arc.properties.UnlessBuildProperty", "io.quarkus.arc.DefaultBean",
            "jakarta.enterprise.inject.Alternative", "jakarta.annotation.Priority");

    /** Candidates of an {@code Instance<T>} required type, or none for any other type. */
    static List<String> instanceCandidates(ReadQueryContext db, String requiredType) {
        String type = argument(requiredType, INSTANCE);
        return type == null ? List.of() : typeCandidates(db, type);
    }

    /** Observers of an {@code Event<T>} required type, or none for any other type. */
    static List<String> eventObservers(ReadQueryContext db, String requiredType) {
        String type = argument(requiredType, EVENT);
        return type == null ? List.of() : observers(db, type);
    }

    /** The type argument of {@code Wrapper<T>} when the wrapper is one of {@code wrappers}. */
    static String argument(String type, List<String> wrappers) {
        if (type == null) return null;
        int open = type.indexOf('<');
        if (open < 0 || !type.endsWith(">") || !wrappers.contains(type.substring(0, open))) return null;
        return type.substring(open + 1, type.length() - 1).trim();
    }

    /** Observer methods whose @Observes/@ObservesAsync parameter has exactly {@code event}'s type. */
    static List<String> observers(ReadQueryContext db, String event) {
        List<String> lines = new java.util.ArrayList<>();
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name, cm.name, cm.annotation_details FROM class_members cm
                JOIN classes c ON c.id = cm.class_id
                WHERE c.lifecycle = 'current' AND cm.annotation_details LIKE '%.event.Observes%'
                  AND (cm.annotation_details LIKE :binary OR cm.annotation_details LIKE :spelled)""")
                .bind("binary", "%\"parameterType\":\"" + event + "\"%")
                .bind("spelled", "%\"parameterType\":\"" + event.replace('.', '$') + "\"%")
                .mapToMap().list()).forEach(row -> {
                    try {
                        for (var annotation : JSON.readTree(String.valueOf(row.get("annotation_details")))) {
                            String name = annotation.path("annotationName").asText();
                            if (!name.endsWith(".event.Observes") && !name.endsWith(".event.ObservesAsync")) continue;
                            if (!sameType(annotation.path("parameterType").asText(), event)) continue;
                            String during = annotation.path("values").path("during").asText("");
                            lines.add(row.get("class_name") + "#" + row.get("name") + "(@" + name.substring(name.lastIndexOf('.') + 1)
                                    + " " + source(event) + (during.isEmpty() || "IN_PROGRESS".equals(during) ? "" : " during=" + during) + ")");
                        }
                    } catch (Exception unreadable) {
                        // An unreadable member row only drops that observer.
                    }
                });
        return lines;
    }

    /** Application beans whose bean types include {@code type}, with their lookup and enablement conditions. */
    static List<String> typeCandidates(ReadQueryContext db, String type) {
        List<String> lines = new java.util.ArrayList<>();
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT b.class_id, c.class_name, b.kind, b.scope, b.member_name, b.qualifiers
                FROM beans b JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE b.bean_types LIKE :type ORDER BY c.class_name, b.member_name""")
                .bind("type", "%\"" + type + "\"%").mapToMap().list()).forEach(row -> {
                    List<String> conditions = db.jdbi().withHandle(handle -> handle.createQuery("""
                            SELECT annotation_name, annotation_values FROM class_annotations
                            WHERE class_id = :id AND direct = 1 ORDER BY annotation_name""")
                            .bind("id", row.get("class_id")).mapToMap().list()).stream()
                            .filter(annotation -> CONDITIONS.contains(String.valueOf(annotation.get("annotation_name"))))
                            .map(annotation -> "@" + annotation.get("annotation_name") + values(annotation.get("annotation_values")))
                            .toList();
                    lines.add(row.get("kind") + " " + row.get("class_name") + (row.get("member_name") == null ? "" : "#" + row.get("member_name"))
                            + " " + row.get("scope") + " qualifiers " + row.get("qualifiers")
                            + (conditions.isEmpty() ? "" : " " + String.join(" ", conditions)));
                });
        return lines;
    }

    private static String values(Object raw) {
        if (raw == null) return "";
        try {
            var node = JSON.readTree(String.valueOf(raw));
            java.util.Set<String> defaults = new java.util.HashSet<>();
            node.path("$defaults").forEach(value -> defaults.add(value.asText()));
            List<String> set = new java.util.ArrayList<>();
            node.properties().forEach(entry -> {
                if (!entry.getKey().startsWith("$") && !defaults.contains(entry.getKey()))
                    set.add(entry.getKey() + "=" + (entry.getValue().isTextual() ? entry.getValue().asText() : entry.getValue()));
            });
            return set.isEmpty() ? "" : "(" + String.join(", ", set) + ")";
        } catch (Exception unreadable) {
            return "";
        }
    }

    private static boolean sameType(String indexed, String wanted) {
        return indexed.equals(wanted) || indexed.replace('$', '.').equals(wanted.replace('$', '.'));
    }

    /** Source spelling of a binary type name: Outer$Inner reads Outer.Inner; anonymous $1 stays. Types only. */
    static String source(Object type) {
        return type == null ? "null" : String.valueOf(type).replaceAll("\\$(?=[A-Za-z_])", ".");
    }

    private static String configuration(Map<String, Object> row) {
        if (row.get("file") != null)
            return row.get("key") + " [" + row.get("kind") + " declared"
                    + (row.get("annotation") == null ? "" : " by " + source(row.get("annotation"))) + "] "
                    + row.get("file") + ":" + row.get("line");
        return row.get("key") + " [" + row.get("kind") + " read" + (row.get("member") == null ? "" : " by " + row.get("member"))
                + " via @" + row.get("annotation") + "]";
    }

    private static String injects(ReadQueryContext db, Map<String, Object> row) {
        String resolved = row.get("resolved_class") == null ? "not resolved by the application index"
                + MethodPrefetchRelations.dependencyProviders(db, String.valueOf(row.get("target_type")), row.get("qualifiers"))
                : row.get("resolved_class") + (row.get("resolved_member") == null ? ""
                        : "#" + row.get("resolved_member")) + " (" + row.get("resolved_kind") + ")";
        return row.get("kind") + " " + row.get("field_name") + ": " + source(row.get("target_type"))
                + qualifiers(row) + " -> " + resolved + " [" + row.get("resolution_status") + "]";
    }

    private static String injectedInto(Map<String, Object> row) {
        String owner = row.get("owner") + (row.get("owner_member") == null ? "" : "#" + row.get("owner_member"));
        return owner + "#" + row.get("field_name") + " (" + row.get("kind") + " " + source(row.get("target_type"))
                + ") [" + row.get("resolution_status") + "]"
                + ("DECORATOR".equals(row.get("owner_kind")) ? " in a decorator: a delegate point resolves to the decorated bean" : "");
    }

    private static String qualifiers(Map<String, Object> row) {
        Object value = row.get("qualifiers");
        return value == null || "[]".equals(value) ? "" : " " + value;
    }

    private static void lines(ObjectNode fact, String field, List<Map<String, Object>> rows,
            java.util.function.Function<Map<String, Object>, String> line) {
        if (rows.isEmpty()) return;
        ArrayNode values = fact.putArray(field);
        rows.stream().limit(LIMIT).map(line).forEach(values::add);
        if (rows.size() > LIMIT) fact.putObject(field + "_truncated").put("shown", LIMIT).put("total", rows.size());
    }
}
