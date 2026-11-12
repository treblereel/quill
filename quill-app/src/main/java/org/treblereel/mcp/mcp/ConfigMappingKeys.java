package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Expands an indexed {@code @ConfigMapping} interface into the keys SmallRye Config derives
 * from it: each abstract no-argument method is a key named by {@code @WithName} or the
 * kebab-case method name (the default naming strategy), {@code @WithDefault} gives its default,
 * and a return type that is another indexed interface is a nested group. Only declared members
 * are read; a custom naming strategy or {@code @WithParentName} is reported, not applied.
 */
final class ConfigMappingKeys {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int LIMIT = 60;
    private static final int DEPTH = 4;
    private static final String MAIN = "; main ";

    private ConfigMappingKeys() {
    }

    static void append(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        String prefix = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT key FROM configuration_usages
                WHERE class_id = :id AND kind = 'config_prefix' AND annotation = 'io.smallrye.config.ConfigMapping'
                LIMIT 1""").bind("id", cls.id()).mapTo(String.class).findOne().orElse(null));
        if (prefix == null) return;
        ArrayNode keys = JSON.createArrayNode();
        ArrayNode groups = JSON.createArrayNode();
        ArrayNode unsupported = JSON.createArrayNode();
        expand(db, new ConfigurationSourceEvidence(db), cls.className(), prefix, 1, keys, groups, unsupported, new java.util.HashSet<>());
        ObjectNode mapping = fact.putObject("config_mapping");
        mapping.put("prefix", prefix);
        mapping.put("naming", "@io.smallrye.config.WithName value, else kebab-case of the method name");
        if (!groups.isEmpty()) mapping.set("groups", groups);
        // One properties file usually declares them all; naming it once keeps the keys short.
        java.util.Set<String> files = new java.util.TreeSet<>();
        keys.forEach(key -> { int at = key.asText().indexOf(MAIN); if (at >= 0) files.add(key.asText().substring(at + MAIN.length(), key.asText().lastIndexOf(':'))); });
        if (files.size() == 1) {
            String file = files.iterator().next();
            mapping.put("main_declarations_in", file);
            ArrayNode shortened = JSON.createArrayNode();
            keys.forEach(key -> shortened.add(key.asText().replace(MAIN + file + ":", "; line ")));
            keys = shortened;
        }
        mapping.set("keys", keys);
        if (!unsupported.isEmpty()) mapping.set("not_expanded", unsupported);
    }

    private static void expand(ReadQueryContext db, ConfigurationSourceEvidence values, String type, String prefix, int depth, ArrayNode keys,
            ArrayNode groups, ArrayNode unsupported, Set<String> seen) {
        if (!seen.add(type) || keys.size() >= LIMIT) return;
        List<Map<String, Object>> members = db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT cm.name, cm.type_name, cm.modifiers, cm.parameter_types, cm.annotation_details
                FROM class_members cm JOIN classes c ON c.id = cm.class_id
                WHERE c.class_name = :type AND c.lifecycle = 'current' AND cm.kind = 'METHOD'
                ORDER BY cm.name""").bind("type", type).mapToMap().list());
        for (Map<String, Object> member : members) {
            String modifiers = String.valueOf(member.get("modifiers"));
            if (!modifiers.contains("abstract") || modifiers.contains("static")
                    || !"[]".equals(String.valueOf(member.get("parameter_types")))) continue;
            Map<String, String> annotations = annotations(String.valueOf(member.get("annotation_details")));
            if (annotations.containsKey("io.smallrye.config.WithParentName")) {
                unsupported.add(member.get("name") + ": @WithParentName");
                continue;
            }
            String name = annotations.getOrDefault("io.smallrye.config.WithName", kebab(String.valueOf(member.get("name"))));
            String key = prefix + "." + name;
            String returnType = String.valueOf(member.get("type_name"));
            boolean group = depth < DEPTH && isInterface(db, returnType);
            if (group) {
                groups.add(key + " -> " + returnType.replace('$', '.'));
                expand(db, values, returnType, key, depth + 1, keys, groups, unsupported, seen);
                continue;
            }
            String line = key + ": " + returnType.replace('$', '.');
            if (annotations.containsKey("io.smallrye.config.WithDefault"))
                line += " default=" + annotations.get("io.smallrye.config.WithDefault");
            else if (returnType.startsWith("java.util.Optional")) line += " optional";
            if (keys.size() < LIMIT) keys.add(line + mainDeclaration(db, values, key));
        }
    }

    /** V41 CQ-037: a mapping without @WithDefault leaves its default to the application's own properties. */
    static String mainDeclaration(ReadQueryContext db, ConfigurationSourceEvidence values, String key) {
        return db.jdbi().withHandle(handle -> handle.createQuery("""
                SELECT file, line, kind, evidence FROM configuration_definitions
                WHERE key = :key AND kind IN ('property', 'yaml_property') AND COALESCE(source_set, 'main') = 'main'
                ORDER BY file, line LIMIT 1""").bind("key", key).mapToMap().findOne()).map(main -> {
            String declared = ClassPrefetchRelations.declaredValue(values, key, String.valueOf(main.get("file")),
                    ((Number) main.get("line")).intValue(), String.valueOf(main.get("kind")), String.valueOf(main.get("evidence")));
            return MAIN + main.get("file") + ":" + main.get("line") + (declared == null ? "" : "=" + declared);
        }).orElse("");
    }

    private static boolean isInterface(ReadQueryContext db, String type) {
        return db.jdbi().withHandle(handle -> handle.createQuery(
                "SELECT count(*) FROM classes WHERE class_name = :type AND kind = 'INTERFACE' AND lifecycle = 'current'")
                .bind("type", type).mapTo(Integer.class).one()) > 0;
    }

    private static Map<String, String> annotations(String details) {
        Map<String, String> values = new java.util.HashMap<>();
        try {
            for (JsonNode annotation : JSON.readTree(details)) {
                JsonNode value = annotation.path("values").path("value");
                values.put(annotation.path("annotationName").asText(), value.isMissingNode() ? "" : value.asText());
            }
        } catch (Exception unreadable) {
            // Member details are written by the indexer; an unreadable row keeps the method's default name.
        }
        return values;
    }

    static String kebab(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) out.append('-');
                out.append(Character.toLowerCase(ch));
            } else out.append(ch);
        }
        return out.toString();
    }
}
