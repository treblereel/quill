package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/** Searches declarations indexed from dependency bytecode without mixing application symbols. */
final class ExternalSymbolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS = Set.of(
            "CLASS", "INTERFACE", "ANNOTATION", "ENUM", "RECORD",
            "FIELD", "CONSTRUCTOR", "METHOD");

    String search(Jdbi jdbi, String pattern, String kind, String library,
            int limit, int offset) {
        if (pattern == null || pattern.isBlank()) return error("Search pattern must not be blank");
        String normalizedKind = normalizeKind(kind);
        if (normalizedKind != null && !KINDS.contains(normalizedKind)) {
            return error("Invalid kind: expected class, interface, annotation, enum, record, field, constructor, method, or all");
        }
        String like = wildcard(pattern);
        String libraryLike = library == null || library.isBlank()
                ? null : wildcard(library);
        String kindClause = normalizedKind == null ? "" : " AND symbol_kind = :kind";
        String libraryClause = libraryLike == null ? "" : " AND class_name LIKE :library";
        String cte = """
                WITH symbols AS (
                  SELECT c.id AS class_id, c.class_name, c.kind AS symbol_kind,
                         c.class_name AS symbol_name, NULL AS signature, NULL AS descriptor,
                         c.kind AS class_kind
                  FROM classes c
                  WHERE c.lifecycle = 'current' AND c.origin = 'dependency'
                    AND c.class_name LIKE :pattern
                  UNION ALL
                  SELECT c.id, c.class_name, m.kind, m.name, m.signature, m.descriptor, c.kind
                  FROM class_members m JOIN classes c ON c.id = m.class_id
                  WHERE c.lifecycle = 'current' AND c.origin = 'dependency'
                    AND (m.name LIKE :pattern OR m.signature LIKE :pattern)
                )
                """;
        String where = " WHERE 1=1" + kindClause + libraryClause;
        List<Symbol> matches = jdbi.withHandle(handle -> {
            var query = handle.createQuery(cte + "SELECT * FROM symbols" + where
                            + " ORDER BY symbol_name, class_name, signature LIMIT :limit OFFSET :offset")
                    .bind("pattern", like).bind("limit", limit).bind("offset", offset);
            if (normalizedKind != null) query.bind("kind", normalizedKind);
            if (libraryLike != null) query.bind("library", libraryLike);
            return query.map((row, context) -> new Symbol(
                    row.getString("class_name"), row.getString("class_kind"),
                    row.getString("symbol_kind"), row.getString("symbol_name"),
                    row.getString("signature"), row.getString("descriptor"))).list();
        });
        int total = jdbi.withHandle(handle -> {
            var query = handle.createQuery(cte + "SELECT count(*) FROM symbols" + where)
                    .bind("pattern", like);
            if (normalizedKind != null) query.bind("kind", normalizedKind);
            if (libraryLike != null) query.bind("library", libraryLike);
            return query.mapTo(Integer.class).one();
        });
        ObjectNode result = JSON.createObjectNode();
        result.put("pattern", pattern);
        result.put("total", total);
        result.put("showing", matches.size());
        result.put("offset", offset);
        result.put("has_more", (long) offset + matches.size() < total);
        ArrayNode symbols = result.putArray("symbols");
        matches.forEach(match -> {
            ObjectNode item = symbols.addObject();
            item.put("kind", match.kind().toLowerCase(Locale.ROOT));
            item.put("name", match.name());
            item.put("declaring_class", match.className());
            item.put("class_kind", match.classKind().toLowerCase(Locale.ROOT));
            item.put("library_package", libraryPackage(match.className()));
            if (match.signature() != null) item.put("signature", match.signature());
            if (match.descriptor() != null) item.put("descriptor", match.descriptor());
        });
        result.put("source", "dependency_bytecode_index");
        return result.toString();
    }

    String details(Jdbi jdbi, String className, int limit, int offset) {
        ExternalClass cls = jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT id, class_name, kind, superclass, interfaces
                        FROM classes WHERE lifecycle = 'current' AND origin = 'dependency'
                          AND (class_name = :name OR class_name LIKE '%.' || :name)
                        ORDER BY CASE WHEN class_name = :name THEN 0 ELSE 1 END, class_name
                        LIMIT 1
                        """).bind("name", className.strip())
                .map((row, context) -> new ExternalClass(
                        row.getInt("id"), row.getString("class_name"), row.getString("kind"),
                        row.getString("superclass"), row.getString("interfaces")))
                .findFirst().orElse(null));
        if (cls == null) return error("External class not found: " + className);
        int total = jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT count(*) FROM class_members WHERE class_id = :id")
                .bind("id", cls.id()).mapTo(Integer.class).one());
        List<Member> members = jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT kind, name, signature, descriptor, type_name, modifiers, annotations
                        FROM class_members WHERE class_id = :id
                        ORDER BY kind, name, signature LIMIT :limit OFFSET :offset
                        """).bind("id", cls.id()).bind("limit", limit).bind("offset", offset)
                .map((row, context) -> new Member(
                        row.getString("kind"), row.getString("name"),
                        row.getString("signature"), row.getString("descriptor"),
                        row.getString("type_name"), row.getString("modifiers"),
                        row.getString("annotations"))).list());
        ObjectNode result = JSON.createObjectNode();
        result.put("class_name", cls.className());
        result.put("kind", cls.kind().toLowerCase(Locale.ROOT));
        if (cls.superclass() != null) result.put("superclass", cls.superclass());
        result.set("interfaces", parseArray(cls.interfaces()));
        result.put("library_package", libraryPackage(cls.className()));
        result.put("source_available", false);
        result.put("source", "dependency_bytecode_index");
        result.put("total_members", total);
        result.put("showing", members.size());
        result.put("offset", offset);
        result.put("has_more", (long) offset + members.size() < total);
        ArrayNode listed = result.putArray("members");
        members.forEach(member -> {
            ObjectNode item = listed.addObject();
            item.put("kind", member.kind().toLowerCase(Locale.ROOT));
            item.put("name", member.name());
            item.put("signature", member.signature());
            item.put("descriptor", member.descriptor());
            item.put("type", member.type());
            item.put("modifiers", member.modifiers());
            item.set("annotations", parseArray(member.annotations()));
        });
        result.putArray("limitations")
                .add("Dependency source JARs are not indexed; declaration source lines are unavailable")
                .add("Use get_project_dependencies to inspect resolved artifact coordinates");
        return result.toString();
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank() || kind.equalsIgnoreCase("all")) return null;
        return kind.strip().toUpperCase(Locale.ROOT);
    }

    private static String wildcard(String value) {
        String pattern = value.strip().replace('*', '%');
        return pattern.contains("%") ? pattern : "%" + pattern + "%";
    }

    private static String libraryPackage(String className) {
        String[] parts = className.split("\\.");
        if (parts.length <= 3) return className.contains(".")
                ? className.substring(0, className.lastIndexOf('.')) : "";
        return String.join(".", parts[0], parts[1], parts[2]);
    }

    private static com.fasterxml.jackson.databind.JsonNode parseArray(String value) {
        try {
            var parsed = JSON.readTree(value == null ? "[]" : value);
            return parsed != null && parsed.isArray() ? parsed : JSON.createArrayNode();
        } catch (Exception ignored) {
            return JSON.createArrayNode();
        }
    }

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private record Symbol(String className, String classKind, String kind, String name,
            String signature, String descriptor) {}
    private record ExternalClass(int id, String className, String kind,
            String superclass, String interfaces) {}
    private record Member(String kind, String name, String signature, String descriptor,
            String type, String modifiers, String annotations) {}
}
