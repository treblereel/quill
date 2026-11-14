package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * What the application index holds about a dependency class it references: its injection points
 * (with the bean each resolves to), the application classes referencing or subtyping it. Usage,
 * bean and hierarchy questions about Base64 or jakarta Event are answered from these instead of
 * CLASS_NOT_FOUND.
 */
final class DependencyTypeAnswers {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> REFERENCE_KINDS = Map.of(
            "inheritance", Set.of("EXTENDS", "IMPLEMENTS"),
            "annotation", Set.of("ANNOTATION"),
            "type_reference", Set.of("FIELD", "METHOD"),
            "service_loader", Set.of("SERVICE_PROVIDES"));

    private DependencyTypeAnswers() {
    }

    /** The dependency class a failed lookup names, when the application references it; otherwise null. */
    static String referencedType(ReadQueryContext reader, ClassTargetResolver.Lookup lookup, String target) {
        if (lookup.error() == null || !"CLASS_NOT_FOUND".equals(lookup.errorCode()) || target == null
                || target.contains("/") || target.lastIndexOf('.') <= 0) return null;
        if (!ClassTargetResolver.errorResponse(JSON, lookup, target).path("candidates").isEmpty()) return null;
        if (!reader.symbols.searchClasses(target.substring(0, target.lastIndexOf('.')) + ".%", 1).isEmpty()) return null;
        boolean referenced = reader.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT EXISTS(SELECT 1 FROM class_external_deps WHERE external_type = :type)
                    OR EXISTS(SELECT 1 FROM injection_points WHERE target_type = :type OR target_type LIKE :generic)
                    OR EXISTS(SELECT 1 FROM class_members WHERE annotations LIKE :quoted)""")
                .bind("type", target).bind("generic", target + "<%").bind("quoted", "%\"" + target + "\"%")
                .mapTo(Boolean.class).one());
        return referenced ? target : null;
    }

    /** A stand-in record so subtype traversals keyed by class name can start from the dependency type. */
    static ClassRecord standIn(String type) {
        return new ClassRecord(-1, type, "dependency", null, List.of(), null, 0, false, 0, null,
                "dependency", "current", null, null);
    }

    static String usages(ReadQueryContext reader, String type, String usageKind, int limit) {
        ObjectNode root = header(type);
        root.put("granularity", "class");
        if (usageKind == null) root.putNull("usage_kind");
        else root.put("usage_kind", usageKind);
        if (usageKind == null || usageKind.equals("injection")) {
            root.set("injection_points", injectionPoints(reader, type));
            appendDependencyBeans(root, reader, type);
        }
        if (usageKind == null || !usageKind.equals("injection")) {
            ToolResponseSupport.appendApplicationReferences(root, reader, type, usageKind == null ? null : REFERENCE_KINDS.get(usageKind), limit);
            if (usageKind != null && !REFERENCE_KINDS.containsKey(usageKind))
                root.put("references_note", "Calls and field accesses into dependency classes are not indexed per member; "
                        + "these are all application classes referencing it");
        }
        root.put("basis", "Dependency class: application classes referencing it and its injection points from the "
                + "application index; its own bytecode is in get_external_symbol_details");
        ToolResponseSupport.appendMeta(root, reader, 0);
        return root.toString();
    }

    /** get_symbol_details: the dependency's own summary from its jar and where the application uses it. */
    static String symbol(ReadQueryContext reader, String type) {
        ObjectNode root = header(type);
        String projectRoot = reader.index.getMetadata().get("project_root");
        if (projectRoot != null) {
            try {
                var details = JSON.readTree(new ExternalSymbolQueries().details(reader, java.nio.file.Path.of(projectRoot), type, 10, 0, "summary"));
                if (details instanceof ObjectNode external && !external.has("error_code")) {
                    external.remove(List.of("bytecode_request", "_meta", "class_name"));
                    root.set("external", external);
                }
            } catch (Exception unavailable) {
                // Without the jar the application references still answer.
            }
        }
        ToolResponseSupport.appendApplicationReferences(root, reader, type, null);
        root.put("basis", "Dependency class: summary from its jar (get_external_symbol_details has members and bytecode) "
                + "and the application classes and members referencing it");
        ToolResponseSupport.appendMeta(root, reader, 0);
        return root.toString();
    }

    /** list_beans / list_injection_points: the application beans of the type and what its injection points resolve to. */
    static String beans(ReadQueryContext reader, String type) {
        ObjectNode root = header(type);
        ArrayNode beans = root.putArray("beans");
        reader.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT b.kind, b.scope, b.member_name, b.framework, c.class_name, d.class_name AS declaring_class
                FROM beans b JOIN classes c ON c.id = b.class_id LEFT JOIN classes d ON d.id = b.declaring_class_id
                WHERE b.bean_types LIKE :quoted AND c.lifecycle = 'current' ORDER BY c.class_name, b.member_name""")
                .bind("quoted", "%\"" + type + "\"%").mapToMap().list()).forEach(row -> beans.add(
                        (row.get("member_name") != null ? (row.get("declaring_class") != null ? row.get("declaring_class") : row.get("class_name"))
                                + "#" + row.get("member_name") : String.valueOf(row.get("class_name")))
                                + " (" + BeanTerms.kind(row.get("framework"), row.get("kind"))
                                + (row.get("scope") != null ? " " + BeanTerms.scope(row.get("framework"), row.get("scope")) : "") + ")"));
        ArrayNode points = injectionPoints(reader, type);
        root.set("injection_points", points);
        appendDependencyBeans(root, reader, type);
        root.put("answer", (beans.isEmpty() ? "No application bean has" : beans.size() + " application bean"
                + (beans.size() == 1 ? " has" : "s have")) + " dependency type " + type + "; "
                + (points.isEmpty() ? "nothing in the application injects it"
                        : "its " + points.size() + " injection points resolve as listed"));
        ToolResponseSupport.appendMeta(root, reader, 0);
        return root.toString();
    }

    private static ObjectNode header(String type) {
        return JSON.createObjectNode().put("target", type).put("origin", "dependency");
    }

    static ArrayNode injectionPoints(ReadQueryContext reader, String type) {
        ArrayNode lines = JSON.createArrayNode();
        reader.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT c.class_name AS owner, ip.field_name, ip.kind, ip.target_type, ip.resolution_status, ip.resolution_reason,
                       rb.kind AS bean_kind, rb.scope AS bean_scope, rb.member_name AS bean_member, rb.framework AS bean_framework,
                       rc.class_name AS bean_class, dc.class_name AS bean_declaring_class
                FROM injection_points ip JOIN beans b ON b.id = ip.bean_id JOIN classes c ON c.id = b.class_id
                LEFT JOIN beans rb ON rb.id = ip.resolved_bean_id LEFT JOIN classes rc ON rc.id = rb.class_id
                LEFT JOIN classes dc ON dc.id = rb.declaring_class_id
                WHERE (ip.target_type = :type OR ip.target_type LIKE :generic) AND c.lifecycle = 'current'
                ORDER BY c.class_name, ip.field_name, ip.target_type""")
                .bind("type", type).bind("generic", type + "<%").mapToMap().list()).forEach(row -> {
                    String point = row.get("owner") + "." + (row.get("field_name") != null ? row.get("field_name")
                            : String.valueOf(row.get("kind")).toLowerCase());
                    if (!type.equals(row.get("target_type"))) point += " (" + row.get("target_type") + ")";
                    lines.add(point + " -> " + resolution(row));
                });
        return lines;
    }

    /** Beans the dependencies' own bean archives declare with the type, such as VertxProducer#mutinyEventBus. */
    private static void appendDependencyBeans(ObjectNode root, ReadQueryContext reader, String type) {
        ArrayNode lines = JSON.createArrayNode();
        reader.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT class_name, kind, member_name, scope, artifact, framework FROM external_beans
                WHERE bean_types LIKE :quoted ORDER BY class_name, member_name""")
                .bind("quoted", "%\"" + type + "\"%").mapToMap().list()).forEach(row -> lines.add(
                        row.get("class_name") + (row.get("member_name") != null ? "#" + row.get("member_name") : "")
                                + " (" + BeanTerms.kind(row.get("framework"), row.get("kind"))
                                + (row.get("scope") != null ? " " + BeanTerms.scope(row.get("framework"), row.get("scope")) : "")
                                + (row.get("artifact") != null ? ", " + row.get("artifact") : "") + ")"));
        if (!lines.isEmpty()) root.set("dependency_beans", lines);
    }

    private static String resolution(Map<String, Object> row) {
        if (row.get("bean_kind") != null) {
            String owner = row.get("bean_declaring_class") != null ? String.valueOf(row.get("bean_declaring_class"))
                    : String.valueOf(row.get("bean_class"));
            String bean = row.get("bean_member") != null ? owner + "#" + row.get("bean_member") : String.valueOf(row.get("bean_class"));
            return bean + " (" + BeanTerms.kind(row.get("bean_framework"), row.get("bean_kind"))
                    + (row.get("bean_scope") != null ? " " + BeanTerms.scope(row.get("bean_framework"), row.get("bean_scope")) : "") + ")";
        }
        if ("BUILTIN_BEAN".equals(row.get("resolution_reason"))) return "built-in bean";
        if ("NO_CONTEXT_CANDIDATE".equals(row.get("resolution_reason"))) return "no application bean";
        return row.get("resolution_status") + " " + row.get("resolution_reason");
    }
}
