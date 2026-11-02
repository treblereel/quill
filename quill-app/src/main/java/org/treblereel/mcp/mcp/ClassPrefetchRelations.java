package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * The CDI and test relations a class fact needs for a first-turn answer, one line per relation:
 * what the class injects, which injection points require or resolve to it, and which test
 * classes depend on it. Lines are indexed static evidence; runtime selection is not implied.
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
                ORDER BY ip.id""").bind("id", cls.id()).mapToMap().list()), ClassPrefetchRelations::injects);
        lines(fact, "injected_into", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT DISTINCT oc.class_name AS owner, ob.member_name AS owner_member, ip.kind,
                       ip.field_name, ip.target_type, ip.resolution_status
                FROM injection_points ip
                JOIN beans ob ON ob.id = ip.bean_id
                JOIN classes oc ON oc.id = COALESCE(ob.declaring_class_id, ob.class_id)
                WHERE ip.target_type = :name OR ip.target_type LIKE :generic
                   OR ip.resolved_bean_id IN (SELECT id FROM beans WHERE class_id = :id AND member_name IS NULL)
                ORDER BY oc.class_name, ip.field_name""")
                .bind("name", cls.className()).bind("generic", "%<" + cls.className() + ">%")
                .bind("id", cls.id()).mapToMap().list()), ClassPrefetchRelations::injectedInto);
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
                ORDER BY 1""").bind("name", cls.className()).bind("generic", "%<" + cls.className() + ">%")
                .bind("interface", "%\"" + cls.className() + "\"%").bind("id", cls.id()).mapToMap().list()),
                row -> String.valueOf(row.values().iterator().next()));
        lines(fact, "configuration", db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT key, kind, member, annotation, NULL AS file, NULL AS line FROM configuration_usages
                WHERE class_name = :name OR class_name LIKE :nested
                UNION ALL
                SELECT key, kind, NULL, NULL, file, line FROM configuration_definitions
                WHERE evidence LIKE :declared OR evidence LIKE :declaredNested
                ORDER BY 1""").bind("name", cls.className()).bind("nested", cls.className() + "$%")
                .bind("declared", "%\"declaring_class\":\"" + cls.className() + "\"%")
                .bind("declaredNested", "%\"declaring_class\":\"" + cls.className() + "$%")
                .mapToMap().list()), ClassPrefetchRelations::configuration);
        ConfigMappingKeys.append(db, fact, cls);
    }

    private static String configuration(Map<String, Object> row) {
        if (row.get("file") != null)
            return row.get("key") + " [" + row.get("kind") + " declared] " + row.get("file") + ":" + row.get("line");
        return row.get("key") + " [" + row.get("kind") + " read" + (row.get("member") == null ? "" : " by " + row.get("member"))
                + " via @" + row.get("annotation") + "]";
    }

    private static String injects(Map<String, Object> row) {
        String resolved = row.get("resolved_class") == null ? "unresolved"
                : row.get("resolved_class") + (row.get("resolved_member") == null ? ""
                        : "#" + row.get("resolved_member")) + " (" + row.get("resolved_kind") + ")";
        return row.get("kind") + " " + row.get("field_name") + ": " + row.get("target_type")
                + qualifiers(row) + " -> " + resolved + " [" + row.get("resolution_status") + "]";
    }

    private static String injectedInto(Map<String, Object> row) {
        String owner = row.get("owner") + (row.get("owner_member") == null ? "" : "#" + row.get("owner_member"));
        return owner + "#" + row.get("field_name") + " (" + row.get("kind") + " " + row.get("target_type")
                + ") [" + row.get("resolution_status") + "]";
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
