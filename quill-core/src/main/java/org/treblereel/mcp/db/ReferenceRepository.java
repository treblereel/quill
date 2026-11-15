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
    /** A definition ({@code file}, {@code line}, {@code evidence}) or a usage ({@code class*}, {@code member}, ...). */
    public record ConfigurationReference(String entryType, String key, String kind, String file, Integer line,
            String module, String sourceSet, Integer classId, String className, String member,
            Integer parameterIndex, String annotation, String source, String evidence) {
        public boolean definition() { return "definition".equals(entryType); }
    }
    public record ResourceReference(String entryType, String resourcePath, String file, Integer classId,
            String className, String member, String api, String source, String module, String sourceSet, String kind) {}
    public record Resolution(int count, List<String> files) {
        public static final Resolution NONE = new Resolution(0, List.of());
    }
    public record ResolutionKey(String key, String kind) {}
    public record ResourceKey(String path, String kind) {}

    /** A definition's key without its {@code %profile.} prefix; persistence units are never profiled. */
    private static final String BASE_KEY = "(CASE WHEN d.kind <> 'persistence_unit' AND substr(d.key,1,1)='%' AND instr(d.key,'.')>1 "
            + "THEN substr(d.key,instr(d.key,'.')+1) ELSE d.key END)";

    /**
     * Configuration definitions and consumers matching a key glob ({@code *}), consumer class (exact or simple
     * name), kind ({@code all}, {@code property}, {@code prefix}, {@code persistence}) and module, one page of
     * them ordered by key, and for every usage on the page how many indexed definitions satisfy it.
     */
    public ConfigurationPage findConfiguration(String key, String className, String kind, String module, int limit, int offset) {
        Filters filters = filters(key, className, kind, module);
        int definitionCount = count("configuration_definitions d", filters.definitionWhere(), filters.bindings());
        int usageCount = count("configuration_usages u", filters.usageWhere(), filters.bindings());
        List<ConfigurationReference> references = page(filters, limit, offset);
        return new ConfigurationPage(definitionCount, usageCount, references, resolveAll(references, module));
    }

    /** Test-profile declarations the indexer saw but could not read, in one module or all. */
    public int unknownTestProfiles(String module) {
        boolean scoped = module != null && !module.isBlank();
        return handle.createQuery("SELECT count(*) FROM configuration_definitions WHERE kind='quarkus_test_profile_unknown'"
                        + (scoped ? " AND module=:profile_module" : ""))
                .bindMap(scoped ? Map.of("profile_module", module) : Map.of()).mapTo(Integer.class).one();
    }

    private List<ConfigurationReference> page(Filters filters, int limit, int offset) {
        String sql = ClasspathSqlLocator.findSqlOnClasspath(ReferenceRepository.class, "page").formatted(filters.definitionWhere(), filters.usageWhere());
        Map<String, Object> bindings = new LinkedHashMap<>(filters.bindings());
        bindings.put("limit", limit);
        bindings.put("offset", offset);
        return handle.createQuery(sql)
                .bindMap(bindings).map((rs,ctx) -> new ConfigurationReference(rs.getString("entry_type"),rs.getString("key"),rs.getString("kind"),
                        rs.getString("file"),(Integer)rs.getObject("line"),rs.getString("module"),rs.getString("source_set"),
                        (Integer)rs.getObject("class_id"),rs.getString("class_name"),rs.getString("member"),
                        (Integer)rs.getObject("parameter_index"),rs.getString("annotation"),rs.getString("source"),
                        rs.getString("evidence"))).list();
    }

    private int count(String table, String where, Map<String, Object> bindings) {
        return handle.createQuery("SELECT count(*) FROM " + table + " WHERE " + where)
                .bindMap(bindings).mapTo(Integer.class).one();
    }

    private Map<ResolutionKey, Resolution> resolveAll(List<ConfigurationReference> references, String module) {
        Set<ResolutionKey> keys = new LinkedHashSet<>();
        for (ConfigurationReference reference : references)
            if (!reference.definition() && !reference.kind().equals("dynamic_config_key"))
                keys.add(new ResolutionKey(reference.key(), reference.kind()));
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
                relations.add("(" + BASE_KEY + " = :" + exact + " OR " + BASE_KEY + " LIKE :" + prefix + " ESCAPE '!')");
            } else {
                relations.add((key.kind().equals("persistence_unit") ? "d.key" : BASE_KEY) + " = :" + exact);
            }
            index++;
        }
        String moduleFilter = "";
        if (module != null && !module.isBlank()) {
            moduleFilter = " AND d.module = :resolved_module";
            bindings.put("resolved_module", module);
        }
        String sql = "SELECT d.key, d.kind, d.file FROM configuration_definitions d WHERE d.kind NOT IN ('quarkus_test_profile','quarkus_test_profile_unknown') AND ("
                + String.join(" OR ", relations) + ")" + moduleFilter + " ORDER BY d.key, d.file";
        List<Map<String, Object>> definitions = handle.createQuery(sql).bindMap(bindings).mapToMap().list();
        Map<ResolutionKey, Resolution> result = new LinkedHashMap<>();
        for (ResolutionKey key : keys) {
            int count = 0;
            Set<String> files = new LinkedHashSet<>();
            for (Map<String, Object> definition : definitions) {
                String definitionKey = text(definition, "kind").equals("persistence_unit") ? text(definition, "key") : baseKey(text(definition, "key"));
                boolean matches = definitionKey.equals(key.key())
                        || key.kind().equals("config_prefix") && definitionKey.startsWith(key.key() + ".");
                if (matches) {
                    count++;
                    files.add(text(definition, "file"));
                }
            }
            result.put(key, new Resolution(count, List.copyOf(files)));
        }
        return result;
    }

    /** {@link #BASE_KEY} in Java: {@code %dev.app.name} is {@code app.name}. */
    public static String baseKey(String key) {
        if (key == null || !key.startsWith("%")) return key;
        int dot = key.indexOf('.');
        return dot > 1 ? key.substring(dot + 1) : key;
    }

    private static Filters filters(String key, String className, String kind, String module) {
        Map<String, Object> bindings = new LinkedHashMap<>();
        String definitionKey = "1 = 1";
        if (key != null && !key.isBlank()) {
            bindings.put("key_pattern", wildcardLike(key));
            String usagePattern = baseKey(key);
            bindings.put("usage_key_pattern", wildcardLike(usagePattern));
            int wildcard = usagePattern.indexOf('*');
            bindings.put("literal_prefix", usagePattern.substring(0, wildcard < 0 ? usagePattern.length() : wildcard).strip());
            definitionKey = "(d.key LIKE :key_pattern ESCAPE '!' OR " + BASE_KEY + " LIKE :key_pattern ESCAPE '!')";
        }
        String definitionModule = "1 = 1";
        if (module != null && !module.isBlank()) {
            bindings.put("module", module);
            definitionModule = "d.module = :module";
        }
        if (className != null && !className.isBlank()) {
            bindings.put("class_name", className);
            bindings.put("class_suffix", "%." + escapeLike(className));
        }
        String definitionKind = switch (kind) {
            case "persistence" -> "d.kind = 'persistence_unit'";
            case "property", "prefix" -> "d.kind <> 'persistence_unit'";
            default -> "1 = 1";
        };
        String usageWhere = usageWhere("u", key, className, kind, module);
        String definitionWhere = String.join(" AND ", definitionKey, definitionModule, definitionKind);
        if ((className != null && !className.isBlank()) || kind.equals("prefix")) {
            String relatedDefinition = "EXISTS (SELECT 1 FROM configuration_usages ru WHERE "
                    + usageWhere("ru", key, className, kind, module) + " AND (" + BASE_KEY + " = ru.key OR "
                    + "(ru.kind = 'config_prefix' AND " + BASE_KEY + " LIKE ru.key || '.%')))";
            if (className != null && !className.isBlank()) relatedDefinition += " OR (d.kind LIKE 'quarkus_test_profile%' AND "
                    + "(json_extract(d.evidence,'$.declaring_class')=:class_name OR json_extract(d.evidence,'$.declaring_class') LIKE :class_suffix ESCAPE '!' "
                    + "OR EXISTS (SELECT 1 FROM json_each(d.evidence,'$.profile_classes') p WHERE p.value=:class_name OR p.value LIKE :class_suffix ESCAPE '!')))";
            definitionWhere += " AND (" + relatedDefinition + ")";
        }
        return new Filters(definitionWhere, usageWhere, bindings);
    }

    /**
     * The usage predicate over configuration_usages aliased {@code u}: the same conditions serve the usage page
     * and, aliased {@code ru}, the definitions related to those usages. Bindings are added by {@link #filters}.
     */
    private static String usageWhere(String u, String key, String className, String kind, String module) {
        String usageKey = "1 = 1";
        if (key != null && !key.isBlank()) {
            usageKey = "(" + u + ".key LIKE :key_pattern ESCAPE '!' OR " + u + ".key LIKE :usage_key_pattern ESCAPE '!' OR "
                    + "(" + u + ".kind = 'config_prefix' AND (:literal_prefix = " + u + ".key OR :literal_prefix LIKE " + u + ".key || '.%')))";
        }
        String usageModule = module != null && !module.isBlank() ? u + ".module = :module" : "1 = 1";
        String usageClass = className != null && !className.isBlank()
                ? "(" + u + ".class_name = :class_name OR " + u + ".class_name LIKE :class_suffix ESCAPE '!')" : "1 = 1";
        String usageKind = switch (kind) {
            case "persistence" -> u + ".kind = 'persistence_unit'";
            case "property" -> u + ".kind IN ('config_key', 'dynamic_config_key')";
            case "prefix" -> u + ".kind = 'config_prefix'";
            default -> "1 = 1";
        };
        return String.join(" AND ", usageKey, usageClass, usageModule, usageKind);
    }

    private static String wildcardLike(String value) {
        StringBuilder result = new StringBuilder();
        for (char character : value.toCharArray()) {
            if (character == '*') result.append('%');
            else if (character == '%' || character == '_' || character == '!') result.append('!').append(character);
            else result.append(character);
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

    /** The configuration key a class member injects, if one is indexed. */
    public Optional<String> findMemberConfigKey(int classId, String member) {
        return handle.createQuery("SELECT key FROM configuration_usages WHERE class_id = :id AND member = :name AND kind = 'config_key' LIMIT 1")
                .bind("id", classId).bind("name", member).mapTo(String.class).findOne();
    }

    /**
     * A configuration key a class reads ({@code member}, {@code annotation}) or declares in a test profile
     * ({@code annotation} is the declaring class, with {@code file}, {@code line}, {@code evidence}).
     */
    public record ClassConfiguration(String key, String kind, String member, String annotation, String file,
            Integer line, String evidence) {}
    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /** A main-source property declaration. */
    public record PropertyDeclaration(String file, Integer line, String kind, String evidence) {}

    /** Keys the class or its nested classes read, and test-profile keys they declare, by key. */
    public List<ClassConfiguration> findClassConfiguration(String className) {
        return handle.createQuery("""
                SELECT key, kind, member, annotation, NULL AS file, NULL AS line, NULL AS evidence FROM configuration_usages
                WHERE class_name = :name OR class_name LIKE :nested
                UNION ALL
                SELECT key, kind, NULL, json_extract(evidence, '$.declaring_class'), file, line, evidence FROM configuration_definitions
                WHERE evidence LIKE :declared OR evidence LIKE :declaredNested
                ORDER BY 1""")
                .bind("name", className).bind("nested", className + "$%")
                .bind("declared", "%\"declaring_class\":\"" + className + "\"%")
                .bind("declaredNested", "%\"declaring_class\":\"" + className + "$%")
                .map((rs, ctx) -> new ClassConfiguration(rs.getString("key"), rs.getString("kind"), rs.getString("member"),
                        rs.getString("annotation"), rs.getString("file"), nullableInt(rs, "line"), rs.getString("evidence"))).list();
    }

    /** The first two main-source property or YAML declarations of {@code key}, by file and line. */
    public List<PropertyDeclaration> findMainPropertyDeclarations(String key) {
        return handle.createQuery("""
                SELECT file, line, kind, evidence FROM configuration_definitions
                WHERE key = :key AND kind IN ('property', 'yaml_property') AND COALESCE(source_set, 'main') = 'main'
                ORDER BY file, line LIMIT 2""")
                .bind("key", key)
                .map((rs, ctx) -> new PropertyDeclaration(rs.getString("file"), nullableInt(rs, "line"),
                        rs.getString("kind"), rs.getString("evidence"))).list();
    }

    /** Up to three {@code quarkus.arc.exclude-types} declarations (any profile) as {@code key file:line}. */
    public List<String> findArcExcludeTypesDeclarations() {
        return handle.createQuery("""
                SELECT key || ' ' || file || ':' || line FROM configuration_definitions
                WHERE key LIKE '%quarkus.arc.exclude-types' ORDER BY file, line LIMIT 3""").mapTo(String.class).list();
    }

    /** Up to three {@code quarkus.arc.remove-unused-beans} declarations (any profile) as {@code key file:line}. */
    public List<String> findArcRemoveUnusedBeansDeclarations() {
        return handle.createQuery("""
                SELECT key || ' ' || file || ':' || line FROM configuration_definitions
                WHERE key LIKE '%quarkus.arc.remove-unused-beans' ORDER BY source_set, file, line LIMIT 3""").mapTo(String.class).list();
    }

    /** Every indexed configuration key: non-persistence definitions and key or prefix usages. */
    public Set<String> findConfigurationKeys() {
        return new HashSet<>(handle.createQuery("SELECT key FROM configuration_definitions WHERE kind <> 'persistence_unit' UNION SELECT key FROM configuration_usages WHERE kind IN ('config_key','config_prefix')")
                .mapTo(String.class).list());
    }

    /** The {@code @ConfigMapping} prefix a class declares, if any. */
    public Optional<String> findConfigMappingPrefix(int classId) {
        return handle.createQuery("""
                SELECT key FROM configuration_usages
                WHERE class_id = :id AND kind = 'config_prefix' AND annotation = 'io.smallrye.config.ConfigMapping'
                LIMIT 1""").bind("id", classId).mapTo(String.class).findOne();
    }

    /** A declaration of a key, possibly under a {@code %profile.} prefix. */
    public record KeyDeclaration(String key, String kind, String file, Integer line, String evidence) {}

    /** Up to six property or YAML declarations of {@code key}, plain or profiled, by key, file and line. */
    public List<KeyDeclaration> findPropertyDeclarations(String key) {
        return handle.createQuery("""
                SELECT key, kind, file, line, evidence FROM configuration_definitions
                WHERE kind IN ('property','yaml_property') AND
                  (key = :key OR (substr(key,1,1) = '%' AND substr(key,instr(key,'.')+1) = :key))
                ORDER BY key, file, line LIMIT 6""")
                .bind("key", key).map((rs, ctx) -> keyDeclaration(rs)).list();
    }

    private static KeyDeclaration keyDeclaration(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KeyDeclaration(rs.getString("key"), rs.getString("kind"), rs.getString("file"), nullableInt(rs, "line"), rs.getString("evidence"));
    }

    /** An own field a method reads, with the configuration key and annotation injecting it, if any. */
    public record FieldConfigurationRead(String className, String member, String annotation, String key, String fieldDescriptor) {}

    /** Up to 17 own fields a method reads, each with the configuration usages injecting it. */
    public List<FieldConfigurationRead> findFieldConfigurationReads(int classId, String method, String descriptor) {
        return handle.createQuery("""
                SELECT DISTINCT c.class_name,a.field_name AS member,u.annotation,u.key,a.field_descriptor
                FROM field_accesses a JOIN classes c ON c.id=a.to_class_id
                LEFT JOIN configuration_usages u ON u.class_id=a.to_class_id AND u.member=a.field_name AND u.parameter_index IS NULL AND u.annotation NOT LIKE '%#%'
                WHERE a.from_class_id=:id AND a.from_method=:name AND a.from_descriptor=:descriptor
                  AND a.access_kind IN ('read_instance','read_static') AND a.from_class_id=a.to_class_id
                ORDER BY c.class_name,a.field_name,u.annotation,u.key LIMIT 17
                """)
                .bind("id", classId).bind("name", method).bind("descriptor", descriptor)
                .map((rs, ctx) -> new FieldConfigurationRead(rs.getString("class_name"), rs.getString("member"),
                        rs.getString("annotation"), rs.getString("key"), rs.getString("field_descriptor"))).list();
    }

    /** Up to nine property, YAML or test-profile declarations of {@code key}, plain or profiled. */
    public List<KeyDeclaration> findRelatedDeclarations(String key) {
        return handle.createQuery("""
                SELECT key,kind,file,line,evidence FROM configuration_definitions
                WHERE kind IN ('property','yaml_property','quarkus_test_profile') AND
                  (key=:key OR (substr(key,1,1)='%' AND substr(key,instr(key,'.')+1)=:key))
                ORDER BY key,file,line LIMIT 9
                """).bind("key", key).map((rs, ctx) -> keyDeclaration(rs)).list();
    }
}
