package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.mcp.config.ConfigurationSourceEvidence;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.diagnostics.QuillLog;

/**
 * Expands an indexed {@code @ConfigMapping} interface into the keys SmallRye Config derives
 * from it: each abstract no-argument method is a key named by {@code @WithName} or the
 * kebab-case method name (the default naming strategy), {@code @WithDefault} gives its default,
 * and a return type that is another indexed interface is a nested group. Only declared members
 * are read; a custom naming strategy or {@code @WithParentName} is reported, not applied.
 */
final class ConfigMappingKeys {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int LIMIT = 60;
    private static final int DEPTH = 4;
    private static final String MAIN = "; main ";

    private ConfigMappingKeys() {
    }

    static void append(ReadQueryContext db, ObjectNode fact, ClassRecord cls) {
        String prefix = db.references.findConfigMappingPrefix(cls.id()).orElse(null);
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
        var members = db.symbols.findMappingMethods(type);
        for (var member : members) {
            String modifiers = String.valueOf(member.modifiers());
            if (!modifiers.contains("abstract") || modifiers.contains("static")
                    || !"[]".equals(String.valueOf(member.parameterTypes()))) continue;
            Map<String, String> annotations = annotations(String.valueOf(member.annotationDetails()));
            if (annotations.containsKey("io.smallrye.config.WithParentName")) {
                unsupported.add(member.name() + ": @WithParentName");
                continue;
            }
            String name = annotations.getOrDefault("io.smallrye.config.WithName", kebab(String.valueOf(member.name())));
            String key = prefix + "." + name;
            String returnType = String.valueOf(member.typeName());
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
        // The first of the (at most two) main declarations the class prefetch lists.
        return db.references.findMainPropertyDeclarations(key).stream().findFirst().map(main -> {
            String declared = ClassPrefetchRelations.declaredValue(values, key, String.valueOf(main.file()),
                    main.line().intValue(), String.valueOf(main.kind()), String.valueOf(main.evidence()));
            return MAIN + main.file() + ":" + main.line() + (declared == null ? "" : "=" + declared);
        }).orElse("");
    }

    private static boolean isInterface(ReadQueryContext db, String type) {
        return db.symbols.isCurrentInterface(type);
    }

    private static Map<String, String> annotations(String details) {
        Map<String, String> values = new java.util.HashMap<>();
        try {
            for (JsonNode annotation : JSON.readTree(details)) {
                JsonNode value = annotation.path("values").path("value");
                values.put(annotation.path("annotationName").asText(), value.isMissingNode() ? "" : value.asText());
            }
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
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
