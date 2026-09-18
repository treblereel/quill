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
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/** Finds classpath resources and bytecode-visible consumers. */
final class ResourceReferenceQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String find(Jdbi jdbi, String path, String className, String module,
            int limit, int offset) {
        if (path == null || path.isBlank()) return errorResponse("Resource path is required");
        try {
            String pattern = wildcardLike(normalize(path));
            Map<String, Object> bindings = new LinkedHashMap<>();
            bindings.put("path_pattern", pattern);
            bindings.put("file_pattern", "%/resources/" + pattern);
            String moduleDefinition = "";
            String moduleUsage = "";
            if (module != null && !module.isBlank()) {
                bindings.put("module", module);
                moduleDefinition = " AND f.module = :module";
                moduleUsage = " AND u.module = :module";
            }
            String classFilter = "";
            if (className != null && !className.isBlank()) {
                bindings.put("class_name", className);
                bindings.put("class_suffix", "%." + escapeLike(className));
                classFilter = " AND (u.class_name = :class_name OR "
                        + "u.class_name LIKE :class_suffix ESCAPE '!')";
            }
            String definitionWhere = "f.lifecycle = 'current' AND "
                    + "f.project_path LIKE :file_pattern ESCAPE '!'" + moduleDefinition;
            String usageWhere = "u.resource_path LIKE :path_pattern ESCAPE '!'"
                    + moduleUsage + classFilter;
            int definitionCount = count(jdbi, "files f", definitionWhere, bindings);
            int usageCount = count(jdbi, "resource_usages u", usageWhere, bindings);
            Map<String, Object> pageBindings = new LinkedHashMap<>(bindings);
            pageBindings.put("limit", limit);
            pageBindings.put("offset", offset);
            List<Map<String, Object>> rows = jdbi.withHandle(handle -> handle.createQuery("""
                    SELECT entry_type, resource_path, file, class_id, class_name, member, api,
                           source, module, source_set, kind
                    FROM (
                      SELECT 'definition' entry_type, f.project_path resource_path,
                             f.project_path file, NULL class_id, NULL class_name, NULL member,
                             NULL api, NULL source, f.module, f.source_set, f.kind, 0 rank
                      FROM files f WHERE %s
                      UNION ALL
                      SELECT 'usage', u.resource_path, NULL, u.class_id, u.class_name, u.member,
                             u.api, u.source, u.module, u.source_set, u.kind, 1 rank
                      FROM resource_usages u WHERE %s
                    ) refs ORDER BY resource_path, rank, COALESCE(file, class_name)
                    LIMIT :limit OFFSET :offset
                    """.formatted(definitionWhere, usageWhere))
                    .bindMap(pageBindings).mapToMap().list());
            Map<ResourceKey, List<String>> definitions = definitions(jdbi, rows);

            ObjectNode result = JSON.createObjectNode();
            result.put("path", path);
            if (className == null || className.isBlank()) result.putNull("class");
            else result.put("class", className);
            if (module == null || module.isBlank()) result.putNull("module");
            else result.put("module", module);
            ArrayNode references = result.putArray("references");
            for (Map<String, Object> row : rows) appendReference(
                    references, row, definitions.getOrDefault(resourceKey(row), List.of()));
            result.put("definition_count", definitionCount);
            result.put("usage_count", usageCount);
            result.putArray("limitations")
                    .add("Only constant paths passed to supported classpath resource APIs are resolved")
                    .add("Dynamic paths are reported as unknown")
                    .add("Non-classpath Spring resources are reported as unsupported, not missing");
            appendPage(result, rows.size(), definitionCount + usageCount, limit, offset);
            appendMeta(result, jdbi, 0);
            return result.toString();
        } catch (RuntimeException error) {
            return errorResponse("Could not query indexed resource references");
        }
    }

    private static void appendReference(
            ArrayNode target, Map<String, Object> row, List<String> files) {
        ObjectNode node = target.addObject();
        String type = text(row, "entry_type");
        node.put("entry_type", type);
        node.put("path", text(row, "resource_path"));
        node.put("kind", text(row, "kind"));
        nullable(node, "module", row.get("module"));
        nullable(node, "sourceSet", row.get("source_set"));
        if (type.equals("definition")) {
            node.put("file", text(row, "file"));
            return;
        }
        node.put("classId", ((Number) row.get("class_id")).intValue());
        node.put("className", text(row, "class_name"));
        node.put("member", text(row, "member"));
        node.put("api", text(row, "api"));
        nullable(node, "source", row.get("source"));
        String kind = text(row, "kind");
        boolean dynamic = kind.equals("dynamic_resource");
        boolean external = kind.equals("external_resource");
        node.put("resolution_status", dynamic ? "unknown" : external
                ? "unsupported_mechanism" : files.isEmpty() ? "unsatisfied" : "resolved");
        node.put("resolution_strategy", dynamic ? "dynamic_resource_path" : external
                ? "external_resource" : kind.equals("resource_bundle")
                        ? "resource_bundle_family" : "classpath_resource");
        node.put("confidence", dynamic ? 0.0 : 1.0);
        ArrayNode definitions = node.putArray("definition_files");
        files.forEach(definitions::add);
    }

    private static Map<ResourceKey, List<String>> definitions(
            Jdbi jdbi, List<Map<String, Object>> rows) {
        Set<ResourceKey> keys = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            if (!"usage".equals(text(row, "entry_type"))) continue;
            ResourceKey key = resourceKey(row);
            if (!key.kind().equals("dynamic_resource")
                    && !key.kind().equals("external_resource")) keys.add(key);
        }
        if (keys.isEmpty()) return Map.of();

        List<String> predicates = new ArrayList<>();
        Map<String, Object> bindings = new LinkedHashMap<>();
        int index = 0;
        for (ResourceKey key : keys) {
            String exact = "resource_exact_" + index;
            bindings.put(exact, "%/resources/" + escapeLike(key.path()));
            String predicate = "project_path LIKE :" + exact + " ESCAPE '!'";
            if (key.kind().equals("resource_bundle") && key.path().endsWith(".properties")) {
                String localized = "resource_localized_" + index;
                String stem = key.path().substring(
                        0, key.path().length() - ".properties".length());
                bindings.put(localized, "%/resources/" + escapeLike(stem)
                        + "!_%.properties");
                predicate = "(" + predicate + " OR project_path LIKE :" + localized
                        + " ESCAPE '!')";
            }
            predicates.add(predicate);
            index++;
        }
        String sql = "SELECT project_path FROM files WHERE lifecycle = 'current' AND ("
                + String.join(" OR ", predicates) + ") ORDER BY project_path";
        List<String> files = jdbi.withHandle(handle -> handle.createQuery(sql)
                .bindMap(bindings).mapTo(String.class).list());
        Map<ResourceKey, List<String>> result = new LinkedHashMap<>();
        for (ResourceKey key : keys) {
            result.put(key, files.stream().filter(file -> matches(file, key)).toList());
        }
        return result;
    }

    private static boolean matches(String file, ResourceKey key) {
        String marker = "/resources/";
        int resourceStart = file.lastIndexOf(marker);
        if (resourceStart < 0) return false;
        String relative = file.substring(resourceStart + marker.length());
        if (relative.equals(key.path())) return true;
        if (!key.kind().equals("resource_bundle") || !key.path().endsWith(".properties")) {
            return false;
        }
        String stem = key.path().substring(0, key.path().length() - ".properties".length());
        return relative.startsWith(stem + "_") && relative.endsWith(".properties");
    }

    private static ResourceKey resourceKey(Map<String, Object> row) {
        return new ResourceKey(text(row, "resource_path"), text(row, "kind"));
    }

    private static int count(Jdbi jdbi, String table, String where,
            Map<String, Object> bindings) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        "SELECT count(*) FROM " + table + " WHERE " + where)
                .bindMap(bindings).mapTo(Integer.class).one());
    }

    private static String normalize(String value) {
        String result = value.strip().replace('\\', '/');
        if (result.startsWith("classpath:")) result = result.substring(10);
        while (result.startsWith("/")) result = result.substring(1);
        return result;
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

    private static void nullable(ObjectNode node, String key, Object value) {
        if (value == null || value.toString().isBlank()) node.putNull(key);
        else node.put(key, value.toString());
    }

    private record ResourceKey(String path, String kind) {}
}
