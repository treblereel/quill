package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/** Searches normalized configuration definitions and their consumers. */
final class ConfigurationReferenceQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS = Set.of("all", "property", "prefix", "persistence");

    String find(Jdbi jdbi, String key, String className, String kind, String module,
            int limit, int offset) {
        String normalizedKind = kind == null ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid kind: expected all, property, prefix, or persistence");
        }
        try {
            Filters filters = filters(key, className, normalizedKind, module);
            int definitionCount = count(jdbi, "configuration_definitions d",
                    filters.definitionWhere(), filters.bindings());
            int usageCount = count(jdbi, "configuration_usages u",
                    filters.usageWhere(), filters.bindings());
            int total = definitionCount + usageCount;
            List<Map<String, Object>> references = page(jdbi, filters, limit, offset);
            Map<ResolutionKey, Resolution> resolutions = resolveAll(jdbi, references, module);

            ObjectNode result = JSON.createObjectNode();
            putNullable(result, "key", key);
            putNullable(result, "class", className);
            result.put("kind", normalizedKind);
            putNullable(result, "module", module);
            ArrayNode values = result.putArray("references");
            for (Map<String, Object> reference : references) {
                ObjectNode node = values.addObject();
                String entryType = text(reference, "entry_type");
                node.put("entry_type", entryType);
                node.put("key", text(reference, "key"));
                node.put("kind", text(reference, "kind"));
                putNullable(node, "module", reference.get("module"));
                putNullable(node, "sourceSet", reference.get("source_set"));
                if (entryType.equals("definition")) {
                    node.put("file", text(reference, "file"));
                    node.put("line", number(reference, "line"));
                } else {
                    node.put("classId", number(reference, "class_id"));
                    node.put("className", text(reference, "class_name"));
                    putNullable(node, "member", reference.get("member"));
                    putNullable(node, "parameterIndex", reference.get("parameter_index"));
                    node.put("annotation", text(reference, "annotation"));
                    putNullable(node, "source", reference.get("source"));
                    String usageKind = text(reference, "kind");
                    boolean dynamic = usageKind.equals("dynamic_config_key");
                    Resolution resolution = dynamic ? new Resolution(0, List.of())
                            : resolutions.getOrDefault(
                                    new ResolutionKey(text(reference, "key"), usageKind),
                                    new Resolution(0, List.of()));
                    node.put("resolved", !dynamic && resolution.count() > 0);
                    node.put("resolution_status", dynamic ? "unknown"
                            : resolution.count() > 0 ? "resolved" : "unsatisfied");
                    node.put("resolution_strategy", dynamic ? "dynamic_programmatic_lookup"
                            : text(reference, "annotation").contains("#")
                                    ? "constant_programmatic_lookup" : "annotation_lookup");
                    node.put("confidence", dynamic ? 0.0 : 1.0);
                    node.put("definition_count", resolution.count());
                    ArrayNode files = node.putArray("definition_files");
                    resolution.files().forEach(files::add);
                }
            }
            result.put("definition_count", definitionCount);
            result.put("usage_count", usageCount);
            result.put("values_indexed", false);
            result.putArray("limitations")
                    .add("Configuration values are intentionally not stored")
                    .add("Consumers cover supported annotations and common programmatic APIs")
                    .add("Environment-variable and YAML list lookups are not resolved")
                    .add("Dynamic programmatic keys are reported as unknown without guessing their value");
            appendPage(result, references.size(), total, limit, offset);
            appendMeta(result, jdbi, 0);
            return result.toString();
        } catch (RuntimeException error) {
            return errorResponse("Could not query indexed configuration references");
        }
    }

    private static List<Map<String, Object>> page(
            Jdbi jdbi, Filters filters, int limit, int offset) {
        String sql = """
                SELECT entry_type, key, kind, file, line, module, source_set,
                       class_id, class_name, member, parameter_index, annotation, source
                FROM (
                    SELECT 'definition' AS entry_type, 0 AS entry_rank,
                           d.key, d.kind, d.file, d.line, d.module, d.source_set,
                           NULL AS class_id, NULL AS class_name, NULL AS member,
                           NULL AS parameter_index, NULL AS annotation, NULL AS source
                    FROM configuration_definitions d WHERE %s
                    UNION ALL
                    SELECT 'usage' AS entry_type, 1 AS entry_rank,
                           u.key, u.kind, NULL AS file, NULL AS line, u.module, u.source_set,
                           u.class_id, u.class_name, u.member, u.parameter_index,
                           u.annotation, u.source
                    FROM configuration_usages u WHERE %s
                ) references_page
                ORDER BY key, entry_rank, COALESCE(file, class_name),
                         COALESCE(member, ''), COALESCE(parameter_index, -1)
                LIMIT :limit OFFSET :offset
                """.formatted(filters.definitionWhere(), filters.usageWhere());
        Map<String, Object> bindings = new LinkedHashMap<>(filters.bindings());
        bindings.put("limit", limit);
        bindings.put("offset", offset);
        return jdbi.withHandle(handle -> handle.createQuery(sql)
                .bindMap(bindings).mapToMap().list());
    }

    private static int count(Jdbi jdbi, String table, String where,
            Map<String, Object> bindings) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT count(*) FROM " + table + " WHERE " + where)
                .bindMap(bindings).mapTo(Integer.class).one());
    }

    private static Map<ResolutionKey, Resolution> resolveAll(
            Jdbi jdbi, List<Map<String, Object>> references, String module) {
        Set<ResolutionKey> keys = new LinkedHashSet<>();
        for (Map<String, Object> reference : references) {
            if (!"usage".equals(text(reference, "entry_type"))) continue;
            String kind = text(reference, "kind");
            if (!kind.equals("dynamic_config_key")) {
                keys.add(new ResolutionKey(text(reference, "key"), kind));
            }
        }
        if (keys.isEmpty()) return Map.of();

        List<String> relations = new ArrayList<>();
        Map<String, Object> bindings = new LinkedHashMap<>();
        int index = 0;
        for (ResolutionKey key : keys) {
            String exact = "resolved_key_" + index;
            bindings.put(exact, key.key());
            if (key.kind().equals("config_prefix")) {
                String prefix = "resolved_prefix_" + index;
                bindings.put(prefix, escapeLike(key.key()) + ".%");
                relations.add("(d.key = :" + exact + " OR d.key LIKE :" + prefix
                        + " ESCAPE '!')");
            } else {
                relations.add("d.key = :" + exact);
            }
            index++;
        }
        String moduleFilter = "";
        if (module != null && !module.isBlank()) {
            moduleFilter = " AND d.module = :resolved_module";
            bindings.put("resolved_module", module);
        }
        String sql = "SELECT d.key, d.file FROM configuration_definitions d WHERE ("
                + String.join(" OR ", relations) + ")" + moduleFilter
                + " ORDER BY d.key, d.file";
        List<Map<String, Object>> definitions = jdbi.withHandle(handle -> handle.createQuery(sql)
                .bindMap(bindings).mapToMap().list());
        Map<ResolutionKey, Resolution> result = new LinkedHashMap<>();
        for (ResolutionKey key : keys) {
            int count = 0;
            Set<String> files = new LinkedHashSet<>();
            for (Map<String, Object> definition : definitions) {
                String definitionKey = text(definition, "key");
                boolean matches = definitionKey.equals(key.key())
                        || key.kind().equals("config_prefix")
                                && definitionKey.startsWith(key.key() + ".");
                if (matches) {
                    count++;
                    files.add(text(definition, "file"));
                }
            }
            result.put(key, new Resolution(count, List.copyOf(files)));
        }
        return result;
    }

    private static Filters filters(
            String key, String className, String kind, String module) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        String definitionKey = "1 = 1";
        String usageKey = "1 = 1";
        if (key != null && !key.isBlank()) {
            bindings.put("key_pattern", wildcardLike(key));
            int wildcard = key.indexOf('*');
            String literalPrefix = key.substring(0, wildcard < 0 ? key.length() : wildcard)
                    .strip();
            bindings.put("literal_prefix", literalPrefix);
            definitionKey = "d.key LIKE :key_pattern ESCAPE '!'";
            usageKey = "(u.key LIKE :key_pattern ESCAPE '!' OR "
                    + "(u.kind = 'config_prefix' AND "
                    + "(:literal_prefix = u.key OR :literal_prefix LIKE u.key || '.%')))";
        }
        String definitionModule = "1 = 1";
        String usageModule = "1 = 1";
        if (module != null && !module.isBlank()) {
            bindings.put("module", module);
            definitionModule = "d.module = :module";
            usageModule = "u.module = :module";
        }
        String usageClass = "1 = 1";
        if (className != null && !className.isBlank()) {
            bindings.put("class_name", className);
            bindings.put("class_suffix", "%." + escapeLike(className));
            usageClass = "(u.class_name = :class_name "
                    + "OR u.class_name LIKE :class_suffix ESCAPE '!')";
        }
        String definitionKind = switch (kind) {
            case "persistence" -> "d.kind = 'persistence_unit'";
            case "property", "prefix" -> "d.kind <> 'persistence_unit'";
            default -> "1 = 1";
        };
        String usageKind = switch (kind) {
            case "persistence" -> "u.kind = 'persistence_unit'";
            case "property" -> "u.kind IN ('config_key', 'dynamic_config_key')";
            case "prefix" -> "u.kind = 'config_prefix'";
            default -> "1 = 1";
        };
        String usageWhere = String.join(" AND ", usageKey, usageClass, usageModule, usageKind);
        String definitionWhere = String.join(" AND ", definitionKey, definitionModule,
                definitionKind);
        if ((className != null && !className.isBlank()) || kind.equals("prefix")) {
            String relatedUsage = usageWhere.replace("u.", "ru.");
            definitionWhere += " AND EXISTS (SELECT 1 FROM configuration_usages ru WHERE "
                    + relatedUsage + " AND (d.key = ru.key OR "
                    + "(ru.kind = 'config_prefix' AND d.key LIKE ru.key || '.%')))";
        }
        return new Filters(definitionWhere, usageWhere, bindings);
    }

    private static String wildcardLike(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character == '*') result.append('%');
            else if (character == '%' || character == '_' || character == '!') {
                result.append('!').append(character);
            } else result.append(character);
        }
        return result.toString();
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static void putNullable(ObjectNode node, String key, Object value) {
        if (value == null || value.toString().isBlank()) node.putNull(key);
        else if (value instanceof Number number) node.put(key, number.intValue());
        else node.put(key, value.toString());
    }

    private record Filters(
            String definitionWhere, String usageWhere, Map<String, Object> bindings) {}

    private record Resolution(int count, List<String> files) {}

    private record ResolutionKey(String key, String kind) {}
}
