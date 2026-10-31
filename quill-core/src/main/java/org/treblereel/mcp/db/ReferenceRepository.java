package org.treblereel.mcp.db;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

import java.util.*;
import org.jdbi.v3.core.Handle;

/** Reads indexed configuration and resource definitions together with their references. */
public final class ReferenceRepository {
    private final Handle handle;
    public ReferenceRepository(Handle handle) { this.handle = Objects.requireNonNull(handle); }
    public record ConfigurationPage(int definitionCount, int usageCount,
            List<ConfigurationReference> references, Map<ResolutionKey,Resolution> resolutions) {}
    public record ResourcePage(int definitionCount, int usageCount,
            List<ResourceReference> references, Map<ResourceKey,List<String>> definitions) {}
    public record ConfigurationReference(String entryType, String key, String kind, String file, Integer line,
            String module, String sourceSet, Integer classId, String className, String member,
            Integer parameterIndex, String annotation, String source) {}
    public record ResourceReference(String entryType, String resourcePath, String file, Integer classId,
            String className, String member, String api, String source, String module, String sourceSet, String kind) {}
    public record Resolution(int count, List<String> files) {}
    public record ResolutionKey(String key, String kind) {}
    public record ResourceKey(String path, String kind) {}

    public ConfigurationPage findConfiguration(String key, String className, String kind, String module, int limit, int offset) {
            Filters filters = filters(key, className, kind, module);
            int definitionCount = count("configuration_definitions d",
                    filters.definitionWhere(), filters.bindings());
            int usageCount = count("configuration_usages u",
                    filters.usageWhere(), filters.bindings());
            int total = definitionCount + usageCount;
            List<ConfigurationReference> references = page(filters, limit, offset);
            Map<ResolutionKey, Resolution> resolutions = resolveAll(references, module);

        return new ConfigurationPage(definitionCount, usageCount, references, resolutions);
    }

    private List<ConfigurationReference> page(
            Filters filters, int limit, int offset) {
        String sql = ClasspathSqlLocator.findSqlOnClasspath(ReferenceRepository.class, "page").formatted(filters.definitionWhere(), filters.usageWhere());
        Map<String, Object> bindings = new LinkedHashMap<>(filters.bindings());
        bindings.put("limit", limit);
        bindings.put("offset", offset);
        return handle.createQuery(sql)
                .bindMap(bindings).map((rs,ctx) -> new ConfigurationReference(rs.getString("entry_type"),rs.getString("key"),rs.getString("kind"),
                        rs.getString("file"),(Integer)rs.getObject("line"),rs.getString("module"),rs.getString("source_set"),
                        (Integer)rs.getObject("class_id"),rs.getString("class_name"),rs.getString("member"),
                        (Integer)rs.getObject("parameter_index"),rs.getString("annotation"),rs.getString("source"))).list();
    }

    private int count(String table, String where,
            Map<String, Object> bindings) {
        return handle.createQuery(
                        "SELECT count(*) FROM " + table + " WHERE " + where)
                .bindMap(bindings).mapTo(Integer.class).one();
    }

    private Map<ResolutionKey, Resolution> resolveAll(
            List<ConfigurationReference> references, String module) {
        Set<ResolutionKey> keys = new LinkedHashSet<>();
        for (ConfigurationReference reference : references) {
            if (!"usage".equals(reference.entryType())) continue;
            String kind = reference.kind();
            if (!kind.equals("dynamic_config_key")) {
                keys.add(new ResolutionKey(reference.key(), kind));
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
        List<Map<String, Object>> definitions = handle.createQuery(sql)
                .bindMap(bindings).mapToMap().list();
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

    private record Filters(String definitionWhere, String usageWhere, Map<String,Object> bindings) {}

    public ResourcePage findResources(String path, String className, String module, int limit, int offset) {
            String pattern = wildcardLike(path);
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
            int definitionCount = count("files f", definitionWhere, bindings);
            int usageCount = count("resource_usages u", usageWhere, bindings);
            Map<String, Object> pageBindings = new LinkedHashMap<>(bindings);
            pageBindings.put("limit", limit);
            pageBindings.put("offset", offset);
            List<ResourceReference> rows = handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(ReferenceRepository.class, "findResources").formatted(definitionWhere, usageWhere))
                    .bindMap(pageBindings).map((rs,ctx) -> new ResourceReference(rs.getString("entry_type"),rs.getString("resource_path"),
                            rs.getString("file"),(Integer)rs.getObject("class_id"),rs.getString("class_name"),rs.getString("member"),
                            rs.getString("api"),rs.getString("source"),rs.getString("module"),rs.getString("source_set"),rs.getString("kind"))).list();
            Map<ResourceKey, List<String>> definitions = definitions(rows);

        return new ResourcePage(definitionCount, usageCount, rows, definitions);
    }

    private Map<ResourceKey, List<String>> definitions(
            List<ResourceReference> rows) {
        Set<ResourceKey> keys = new LinkedHashSet<>();
        for (ResourceReference row : rows) {
            if (!"usage".equals(row.entryType())) continue;
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
        List<String> files = handle.createQuery(sql)
                .bindMap(bindings).mapTo(String.class).list();
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

    public static ResourceKey resourceKey(ResourceReference row) {
        return new ResourceKey(row.resourcePath(), row.kind());
    }
}
