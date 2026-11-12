package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * Which qualifiers of an injection point and of its candidate beans are written in source and
 * which the container implies, with the annotations each declares. Agents asked about explicit
 * and implicit qualifiers otherwise read both sources to tell them apart.
 */
final class QualifierOrigin {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CANDIDATES = 4;

    private QualifierOrigin() {
    }

    static List<String> lines(ReadQueryContext db, String owner, JsonNode point) {
        List<String> lines = new ArrayList<>();
        String member = point.path("field").asText(point.path("member").asText());
        // Every injection point carries @Inject; naming it says nothing.
        List<String> pointAnnotations = memberAnnotations(db, owner, member).stream()
                .filter(name -> !name.equals("jakarta.inject.Inject") && !name.equals("javax.inject.Inject")).toList();
        lines.add(owner + "#" + member + ": " + origin(point.path("qualifiers"), pointAnnotations));
        Set<Integer> beans = new LinkedHashSet<>();
        for (JsonNode candidate : point.path("resolution_trace").path("candidates"))
            if (candidate.path("bean_id").isNumber()) beans.add(candidate.path("bean_id").asInt());
        if (beans.isEmpty()) db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT ip.resolved_bean_id FROM injection_points ip
                JOIN beans b ON b.id = ip.bean_id JOIN classes c ON c.id = COALESCE(b.declaring_class_id, b.class_id)
                WHERE c.class_name = :owner AND ip.field_name = :member AND ip.resolved_bean_id IS NOT NULL""")
                .bind("owner", owner).bind("member", member).mapTo(Integer.class).list()).forEach(beans::add);
        beans.stream().limit(MAX_CANDIDATES).forEach(id -> candidate(db, id, lines));
        Set<String> withValues = new LinkedHashSet<>();
        for (JsonNode qualifier : point.path("qualifiers"))
            if (qualifier.asText().contains("(")) withValues.add(qualifier.asText().replaceFirst("^@", "").replaceFirst("\\(.*", ""));
        for (String type : withValues) {
            String members = memberBinding(db, type);
            if (members != null) lines.add("@" + type + " members: " + members);
        }
        return lines;
    }

    private static final String NONBINDING = "jakarta.enterprise.util.Nonbinding";

    /**
     * Which members of a qualifier type the container compares: all but those marked @Nonbinding.
     * Read from the index for application annotations and from the jar for dependency ones.
     */
    static String memberBinding(ReadQueryContext db, String type) {
        List<String[]> members = new ArrayList<>();
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT m.name, m.annotations FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.class_name = :type AND m.kind = 'METHOD' ORDER BY m.rowid""").bind("type", type).mapToMap().list())
                .forEach(row -> members.add(new String[] {String.valueOf(row.get("name")),
                        String.valueOf(row.get("annotations")).contains(NONBINDING) ? "nonbinding" : "binding"}));
        if (members.isEmpty()) {
            String projectRoot = db.index.getMetadata().get("project_root");
            if (projectRoot == null) return null;
            try {
                JsonNode details = JSON.readTree(new ExternalSymbolQueries().details(db, java.nio.file.Path.of(projectRoot), type, 50, 0, "summary"));
                if (details.has("error_code")) return null;
                for (JsonNode member : details.path("members")) {
                    String signature = member.path("signature").asText();
                    if (!signature.contains("(") || signature.contains("<init>")) continue;
                    String name = signature.substring(0, signature.indexOf('(')).replaceFirst(".*\\s", "");
                    members.add(new String[] {name, member.path("annotations").toString().contains("Nonbinding") ? "nonbinding" : "binding"});
                }
            } catch (Exception unreadable) {
                return null;
            }
        }
        if (members.isEmpty()) return null;
        List<String> compared = members.stream().filter(m -> m[1].equals("binding")).map(m -> m[0]).toList();
        List<String> ignored = members.stream().filter(m -> m[1].equals("nonbinding")).map(m -> m[0]).toList();
        return (compared.isEmpty() ? "none compared" : String.join(", ", compared) + " compared")
                + (ignored.isEmpty() ? " (no @Nonbinding member)" : "; " + String.join(", ", ignored) + " ignored (@Nonbinding)");
    }

    private static void candidate(ReadQueryContext db, int id, List<String> lines) {
        db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT b.kind, b.qualifiers, b.member_name, c.class_name, d.class_name AS declaring
                FROM beans b JOIN classes c ON c.id = b.class_id LEFT JOIN classes d ON d.id = b.declaring_class_id
                WHERE b.id = :id""").bind("id", id).mapToMap().findOne()).ifPresent(bean -> {
            String declaring = (String) bean.get("declaring");
            String memberName = (String) bean.get("member_name");
            boolean producer = declaring != null && memberName != null && !memberName.startsWith("synthetic:");
            String label = producer ? declaring + "#" + memberName : String.valueOf(bean.get("class_name"));
            List<String> annotations = producer ? memberAnnotations(db, declaring, memberName)
                    : classAnnotations(db, String.valueOf(bean.get("class_name")));
            String kind = String.valueOf(bean.get("kind"));
            lines.add(label + (kind.equals("CLASS") ? "" : " (" + kind + ")") + ": "
                    + origin(parse(bean.get("qualifiers")), annotations));
        });
    }

    /** A qualifier is declared when an annotation of the same simple name is written on the element. */
    private static String origin(JsonNode qualifiers, List<String> annotations) {
        Set<String> simple = new LinkedHashSet<>();
        for (String annotation : annotations) simple.add(annotation.substring(annotation.lastIndexOf('.') + 1));
        List<String> declared = new ArrayList<>();
        List<String> implied = new ArrayList<>();
        for (JsonNode qualifier : qualifiers) {
            String text = qualifier.asText();
            String name = text.replaceFirst("^@", "").replaceFirst("\\(.*", "");
            (simple.contains(name.substring(name.lastIndexOf('.') + 1)) ? declared : implied).add(text);
        }
        return "declared " + (declared.isEmpty() ? "none" : String.join(", ", declared))
                + "; implied " + (implied.isEmpty() ? "none" : String.join(", ", implied))
                + (annotations.isEmpty() ? "" : "; annotations " + String.join(", ", annotations.stream().map(a -> "@" + a).toList()));
    }

    private static List<String> memberAnnotations(ReadQueryContext db, String owner, String member) {
        String raw = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT m.annotations FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.class_name = :owner AND m.name = :member ORDER BY m.kind = 'FIELD' DESC LIMIT 1""")
                .bind("owner", owner).bind("member", member).mapTo(String.class).findOne().orElse("[]"));
        List<String> result = new ArrayList<>();
        parse(raw).forEach(node -> result.add(node.asText()));
        return result;
    }

    private static List<String> classAnnotations(ReadQueryContext db, String className) {
        return db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT a.annotation_name FROM class_annotations a JOIN classes c ON c.id = a.class_id
                WHERE c.class_name = :name AND a.direct = 1 ORDER BY a.annotation_name""")
                .bind("name", className).mapTo(String.class).list());
    }

    private static JsonNode parse(Object raw) {
        try {
            return raw == null ? JSON.createArrayNode() : JSON.readTree(raw.toString());
        } catch (Exception unreadable) {
            return JSON.createArrayNode();
        }
    }
}
