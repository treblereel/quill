package org.treblereel.mcp.db;

import org.jdbi.v3.core.locator.ClasspathSqlLocator;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Handle;
import org.treblereel.mcp.model.*;

/** Read-only symbol queries; the caller owns the handle and transaction. */
public final class SymbolRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Handle handle;
    private List<ClassRecord> allClasses;

    public SymbolRepository(Handle handle) { this(handle, null); }

    void useClasses(List<ClassRecord> prepared) { allClasses = prepared; }

    SymbolRepository(Handle handle, List<ClassRecord> classes) {
        this.handle = Objects.requireNonNull(handle);
        allClasses = classes;
    }

    public List<KotlinDeclarationRecord> findKotlinDeclarations(int classId) {
        return handle.createQuery("""
                        SELECT class_id, kind, name, jvm_name, descriptor, semantic_model,
                               is_suspend, is_extension, has_default_parameters,
                               is_mutable, is_lateinit, is_delegated, is_synthetic, value_parameters, declaration_types
                        FROM kotlin_declarations
                        WHERE class_id = :classId
                        ORDER BY CASE kind WHEN 'FUNCTION' THEN 1 WHEN 'PROPERTY' THEN 2 WHEN 'CONSTRUCTOR' THEN 3 ELSE 0 END,
                                 name, descriptor
                        """)
                .bind("classId", classId)
                .map((row, context) -> new KotlinDeclarationRecord(
                        row.getInt("class_id"), row.getString("kind"), row.getString("name"),
                        row.getString("jvm_name"), row.getString("descriptor"),
                        row.getString("semantic_model"), row.getInt("is_suspend") == 1,
                        row.getInt("is_extension") == 1,
                        row.getInt("has_default_parameters") == 1,
                        row.getInt("is_mutable") == 1, row.getInt("is_lateinit") == 1,
                        row.getInt("is_delegated") == 1, row.getInt("is_synthetic") == 1,
                        kotlinParametersFromJson(row.getString("value_parameters")),
                        kotlinTypesFromJson(row.getString("declaration_types"))))
                .list();
    }

    public static KotlinDeclarationTypes kotlinTypesFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readValue(json, KotlinDeclarationTypes.class); }
        catch (RuntimeException | JsonProcessingException invalid) { return null; }
    }

    public static List<KotlinValueParameter> kotlinParametersFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            var tree = JSON.readTree(json);
            if (!tree.isArray()) return null;
            Set<String> names = new HashSet<>();
            int varargs = 0;
            for (var value : tree) {
                if (!value.path("name").isTextual() || value.path("name").asText().isBlank()
                        || !names.add(value.path("name").asText())
                        || !value.path("hasDefault").isBoolean() || !value.path("vararg").isBoolean()) return null;
                if (value.path("vararg").asBoolean() && ++varargs > 1) return null;
            }
            return JSON.readValue(json, new TypeReference<List<KotlinValueParameter>>() {});
        } catch (JsonProcessingException error) {
            // Older/incomplete metadata must not turn heuristic navigation into a false certainty.
            return null;
        }
    }

    public record SymbolSearchResult(
            int classId,
            String className,
            String classKind,
            String symbolKind,
            String symbolName,
            String signature,
            String descriptor,
            String typeName,
            List<String> parameterTypes,
            String modifiers,
            List<String> annotations,
            String sourceFile,
            int sourceLine,
            String origin,
            String module,
            String sourceSet,
            int sourceTokens,
            String language,
            String semanticKind,
            String semanticName,
            boolean isSuspend,
            boolean extension,
            boolean hasDefaultParameters,
            boolean mutable,
            boolean lateinit,
            boolean delegated) {}

    public record AnnotatedSymbolResult(
            int classId,
            String className,
            String symbolKind,
            String declarationKind,
            String symbolName,
            String signature,
            String descriptor,
            String typeName,
            List<String> parameterTypes,
            String modifiers,
            String match,
            String viaAnnotation,
            String annotationTarget,
            Integer parameterIndex,
            String parameterName,
            String parameterType,
            String sourceFile,
            int sourceLine,
            String origin,
            String module,
            String sourceSet,
            int sourceTokens,
            Map<String, Object> annotationValues) {}

    public List<ClassRecord> findAllClasses() {
        if (allClasses == null) allClasses = List.copyOf(readAllClasses());
        return allClasses;
    }

    private List<ClassRecord> readAllClasses() {
        return handle.createQuery("SELECT * FROM classes WHERE lifecycle = 'current' "
                                + "AND origin != 'orphan_output' ORDER BY id")
                        .map((rs, ctx) -> mapClass(rs))
                        .list();
    }

    public List<String> findAnnotationNames(String target) {
        String normalized = target.startsWith("@") ? target.substring(1) : target;
        String suffix = "%." + normalized;
        return handle.createQuery("""
                        SELECT DISTINCT annotation_name FROM class_annotations
                        WHERE annotation_name = :name OR annotation_name LIKE :suffix
                        ORDER BY CASE WHEN annotation_name = :name THEN 0 ELSE 1 END,
                                 annotation_name""")
                .bind("name", normalized)
                .bind("suffix", suffix)
                .mapTo(String.class)
                .list();
    }

    public List<String> findSymbolAnnotationNames(String target) {
        String normalized = target.startsWith("@") ? target.substring(1) : target;
        String suffix = "%." + normalized;
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(SymbolRepository.class, "findSymbolAnnotationNames"))
                .bind("name", normalized)
                .bind("suffix", suffix)
                .mapTo(String.class)
                .list();
    }

    public List<AnnotatedSymbolResult> findAnnotatedSymbols(String annotationName, String symbolKind,
            boolean includeMetaAnnotations, int limit, int offset) {
        String sql = annotatedSymbolsCte() + """
                SELECT * FROM symbols
                ORDER BY CASE symbol_kind WHEN 'CLASS' THEN 0 WHEN 'INTERFACE' THEN 0
                         WHEN 'ENUM' THEN 0 WHEN 'ANNOTATION' THEN 0
                         WHEN 'RECORD' THEN 0 WHEN 'FIELD' THEN 1
                         WHEN 'CONSTRUCTOR' THEN 2 ELSE 3 END,
                         class_name, symbol_name, signature
                LIMIT :limit OFFSET :offset""";
        return handle.createQuery(sql)
                .bind("annotation", annotationName)
                .bind("includeMeta", includeMetaAnnotations ? 1 : 0)
                .bind("kind", symbolKind)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> new AnnotatedSymbolResult(
                        rs.getInt("class_id"), rs.getString("class_name"),
                        rs.getString("symbol_kind"), rs.getString("declaration_kind"),
                        rs.getString("symbol_name"),
                        rs.getString("signature"), rs.getString("descriptor"),
                        rs.getString("type_name"),
                        fromJson(rs.getString("parameter_types")),
                        rs.getString("modifiers"), rs.getString("match"),
                        rs.getString("via_annotation"), rs.getString("annotation_target"),
                        nullableInteger(rs, "parameter_index"),
                        rs.getString("parameter_name"), rs.getString("parameter_type"),
                        rs.getString("source_file"),
                        rs.getInt("source_line"), rs.getString("origin"),
                        rs.getString("module"), rs.getString("source_set"),
                        rs.getInt("source_tokens"),
                        annotationValuesFromJson(rs.getString("annotation_values"))))
                .list();
    }

    public int countAnnotatedSymbols(String annotationName,
            String symbolKind, boolean includeMetaAnnotations) {
        return handle.createQuery(
                        annotatedSymbolsCte() + "SELECT count(*) FROM symbols")
                .bind("annotation", annotationName)
                .bind("includeMeta", includeMetaAnnotations ? 1 : 0)
                .bind("kind", symbolKind)
                .mapTo(Integer.class).one();
    }

    private static String annotatedSymbolsCte() {
        return ClasspathSqlLocator.findSqlOnClasspath(SymbolRepository.class, "annotatedSymbolsCte");
    }

    public List<ClassRecord> findAnnotatedClasses(String annotationName,
            boolean includeMetaAnnotations, int limit, int offset) {
        return handle.createQuery("""
                        SELECT c.* FROM classes c
                        WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                          AND EXISTS (
                              SELECT 1 FROM class_annotations a
                              WHERE a.class_id = c.id
                                AND a.annotation_name = :annotation
                                AND (:includeMeta = 1 OR a.direct = 1))
                        ORDER BY c.class_name, c.id
                        LIMIT :limit OFFSET :offset""")
                .bind("annotation", annotationName)
                .bind("includeMeta", includeMetaAnnotations ? 1 : 0)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> mapClass(rs))
                .list();
    }

    public int countAnnotatedClasses(String annotationName,
            boolean includeMetaAnnotations) {
        return handle.createQuery("""
                        SELECT count(*) FROM classes c
                        WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                          AND EXISTS (
                              SELECT 1 FROM class_annotations a
                              WHERE a.class_id = c.id
                                AND a.annotation_name = :annotation
                                AND (:includeMeta = 1 OR a.direct = 1))""")
                .bind("annotation", annotationName)
                .bind("includeMeta", includeMetaAnnotations ? 1 : 0)
                .mapTo(Integer.class)
                .one();
    }

    public Map<String, Integer> countAnnotatedClassesByOrigin(String annotationName, boolean includeMetaAnnotations) {
        
            Map<String, Integer> result = new LinkedHashMap<>();
            handle.createQuery("""
                            SELECT c.origin, count(*) AS class_count FROM classes c
                            WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                              AND EXISTS (
                                  SELECT 1 FROM class_annotations a
                                  WHERE a.class_id = c.id
                                    AND a.annotation_name = :annotation
                                    AND (:includeMeta = 1 OR a.direct = 1))
                            GROUP BY c.origin ORDER BY c.origin""")
                    .bind("annotation", annotationName)
                    .bind("includeMeta", includeMetaAnnotations ? 1 : 0)
                    .map((rs, ctx) -> Map.entry(
                            rs.getString("origin"), rs.getInt("class_count")))
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        
    }

    public Map<Integer, List<ClassAnnotationRecord>> findClassAnnotations(String annotationName, Collection<Integer> classIds,
            boolean includeMetaAnnotations) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        
            Map<Integer, List<ClassAnnotationRecord>> result = new LinkedHashMap<>();
            handle.createQuery("""
                            SELECT class_id, annotation_name, direct, via_annotation, annotation_values
                            FROM class_annotations
                            WHERE annotation_name = :annotation
                              AND class_id IN (<classIds>)
                              AND (:includeMeta = 1 OR direct = 1)
                            ORDER BY class_id, direct DESC, via_annotation""")
                    .bind("annotation", annotationName)
                    .bindList("classIds", classIds)
                    .bind("includeMeta", includeMetaAnnotations ? 1 : 0)
                    .map((rs, ctx) -> new ClassAnnotationRecord(
                            rs.getInt("class_id"), rs.getString("annotation_name"),
                            rs.getBoolean("direct"), rs.getString("via_annotation"),
                            annotationValuesFromJson(rs.getString("annotation_values"))))
                    .forEach(annotation -> result
                            .computeIfAbsent(annotation.classId(), ignored -> new ArrayList<>())
                            .add(annotation));
            return result;
        
    }

    public List<ClassAnnotationRecord> findClassAnnotations(int classId) {
        return handle.createQuery("""
                        SELECT class_id, annotation_name, direct, via_annotation, annotation_values
                        FROM class_annotations WHERE class_id = :classId
                        ORDER BY direct DESC, annotation_name, via_annotation""")
                .bind("classId", classId)
                .map((rs, ctx) -> new ClassAnnotationRecord(
                            rs.getInt("class_id"), rs.getString("annotation_name"),
                            rs.getBoolean("direct"), rs.getString("via_annotation"),
                            annotationValuesFromJson(rs.getString("annotation_values"))))
                .list();
    }

    public Map<Integer, List<ClassAnnotationRecord>> findClassAnnotations(Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        
            Map<Integer, List<ClassAnnotationRecord>> result = new LinkedHashMap<>();
            List<Integer> ids = List.copyOf(classIds);
            for (int from = 0; from < ids.size(); from += 500) {
                List<Integer> batch = ids.subList(from, Math.min(from + 500, ids.size()));
                handle.createQuery("""
                                SELECT class_id, annotation_name, direct, via_annotation, annotation_values
                                FROM class_annotations WHERE class_id IN (<classIds>)
                                ORDER BY class_id, direct DESC, annotation_name, via_annotation""")
                        .bindList("classIds", batch)
                        .map((rs, ctx) -> new ClassAnnotationRecord(
                            rs.getInt("class_id"), rs.getString("annotation_name"),
                            rs.getBoolean("direct"), rs.getString("via_annotation"),
                            annotationValuesFromJson(rs.getString("annotation_values"))))
                        .forEach(annotation -> result
                                .computeIfAbsent(annotation.classId(), ignored -> new ArrayList<>())
                                .add(annotation));
            }
            return result;
        
    }

    public List<ClassMemberRecord> findClassMembers(int classId) {
        return findJvmClassMembers(classId).stream()
                .filter(m -> !m.modifiers().contains("bridge") && !m.modifiers().contains("synthetic")).toList();
    }

    public List<ClassMemberRecord> findJvmClassMembers(int classId) {
        return handle.createQuery("""
                        SELECT class_id, kind, name, signature, descriptor, type_name, parameter_types,
                               modifiers, annotations, annotation_details
                        FROM class_members WHERE class_id = :classId
                        ORDER BY CASE kind WHEN 'FIELD' THEN 0 WHEN 'CONSTRUCTOR' THEN 1 ELSE 2 END,
                                 name, signature""")
                .bind("classId", classId)
                .map((rs, ctx) -> mapClassMember(rs))
                .list();
    }

    public Map<Integer,List<ClassMemberRecord>> findClassMembers(Collection<Integer> classIds) {
        return findClassMembers(classIds,false);
    }

    public Map<Integer,List<ClassMemberRecord>> findJvmClassMembers(Collection<Integer> classIds) {
        return findClassMembers(classIds,true);
    }

    private Map<Integer, List<ClassMemberRecord>> findClassMembers(Collection<Integer> classIds,boolean includeCompilerMethods) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        
            Map<Integer, List<ClassMemberRecord>> result = new LinkedHashMap<>();
            List<Integer> ids = List.copyOf(classIds);
            for (int from = 0; from < ids.size(); from += 500) {
                List<Integer> batch = ids.subList(from, Math.min(from + 500, ids.size()));
                handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(SymbolRepository.class, "findClassMembers"))
                        .bindList("classIds", batch).bind("compilerMethods",includeCompilerMethods)
                        .map((rs, ctx) -> mapClassMember(rs))
                        .forEach(member -> result
                                .computeIfAbsent(member.classId(), ignored -> new ArrayList<>())
                                .add(member));
            }
            ids.forEach(id -> result.putIfAbsent(id, List.of()));
            return result;
        
    }

    public List<SymbolSearchResult> searchSymbols(String namePattern,
            String symbolKind, int limit, int offset) {
        return searchSymbols(namePattern, symbolKind, null, limit, offset);
    }

    public List<SymbolSearchResult> searchSymbols(String namePattern,
            String symbolKind, String language, int limit, int offset) {
        String pattern = namePattern.replace("*", "%");
        if (!pattern.contains("%")) pattern = "%" + pattern + "%";
        String kindFilter = symbolKind == null ? "" : " AND symbol_kind = :kind";
        String languageFilter = language == null ? "" : " AND language = :language";
        String sql = ClasspathSqlLocator.findSqlOnClasspath(SymbolRepository.class, "searchSymbols") + kindFilter + languageFilter
                + " ORDER BY symbol_name, class_name, signature "
                + "LIMIT :limit OFFSET :offset";
        String finalPattern = pattern;
        
            var query = handle.createQuery(sql)
                    .bind("pattern", finalPattern)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (symbolKind != null) query.bind("kind", symbolKind);
            if (language != null) query.bind("language", language);
            return query.map((rs, ctx) -> new SymbolSearchResult(
                    rs.getInt("class_id"), rs.getString("class_name"),
                    rs.getString("class_kind"), rs.getString("symbol_kind"),
                    rs.getString("symbol_name"), rs.getString("signature"),
                    rs.getString("descriptor"), rs.getString("type_name"),
                    fromJson(rs.getString("parameter_types")),
                    rs.getString("modifiers"), fromJson(rs.getString("annotations")),
                    rs.getString("source_file"), rs.getInt("source_line"),
                    rs.getString("origin"), rs.getString("module"),
                    rs.getString("source_set"), rs.getInt("source_tokens"),
                    rs.getString("language"), rs.getString("semantic_kind"),
                    rs.getString("semantic_name"), rs.getInt("is_suspend") == 1,
                    rs.getInt("is_extension") == 1,
                    rs.getInt("has_default_parameters") == 1,
                    rs.getInt("is_mutable") == 1, rs.getInt("is_lateinit") == 1,
                    rs.getInt("is_delegated") == 1)).list();
        
    }

    public int countSymbols(String namePattern, String symbolKind) {
        return countSymbols(namePattern, symbolKind, null);
    }

    public int countSymbols(String namePattern, String symbolKind, String language) {
        String pattern = namePattern.replace("*", "%");
        if (!pattern.contains("%")) pattern = "%" + pattern + "%";
        String kindFilter = symbolKind == null ? "" : " AND symbol_kind = :kind";
        String languageFilter = language == null ? "" : " AND language = :language";
        String sql = ClasspathSqlLocator.findSqlOnClasspath(SymbolRepository.class, "countSymbols") + kindFilter + languageFilter;
        String finalPattern = pattern;
        
            var query = handle.createQuery(sql).bind("pattern", finalPattern);
            if (symbolKind != null) query.bind("kind", symbolKind);
            if (language != null) query.bind("language", language);
            return query.mapTo(Integer.class).one();
        
    }

    static List<Integer> parseIntList(String value) {
        if (value == null || value.length() < 2) return List.of();
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) return List.of();
        return java.util.Arrays.stream(body.split(","))
                .map(String::trim).map(Integer::valueOf).toList();
    }

    public Optional<ClassRecord> findClassByName(String className) {
        return findClassesByName(className).stream().findFirst();
    }

    public List<ClassRecord> findClassesByName(String className) {
        return handle.createQuery("SELECT * FROM classes WHERE class_name = :name "
                                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                                + "ORDER BY module, source_set, id")
                        .bind("name", className)
                        .map((rs, ctx) -> mapClass(rs))
                        .list();
    }

    public Optional<ClassRecord> findClassByPath(String path) {
        String normalized = path.replace('\\', '/');
        return handle.createQuery("""
                        SELECT c.* FROM classes c
                        LEFT JOIN files f ON f.id = c.file_id
                        WHERE (c.source_file = :path
                           OR f.project_path = :path
                           OR f.repository_path = :path)
                          AND c.lifecycle = 'current' AND c.origin != 'orphan_output'
                        ORDER BY CASE WHEN c.lifecycle = 'current' THEN 0 ELSE 1 END, c.class_name
                        LIMIT 1""")
                .bind("path", normalized)
                .map((rs, ctx) -> mapClass(rs))
                .findFirst();
    }

    public List<ClassRecord> findClassesByShortName(String shortName) {
        return handle.createQuery("SELECT * FROM classes WHERE "
                                + "(class_name = :shortName OR class_name LIKE :name) "
                                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                                + "ORDER BY class_name")
                        .bind("shortName", shortName)
                        .bind("name", "%." + shortName)
                        .map((rs, ctx) -> mapClass(rs))
                        .list();
    }

    public List<ClassRecord> searchClasses(String namePattern, int limit) {
        return searchClasses(namePattern, null, null, limit);
    }

    public List<ClassRecord> searchClasses(String namePattern,
            String module, String sourceSet, int limit) {
        return searchClasses(namePattern, module, sourceSet, limit, 0);
    }

    public List<ClassRecord> searchClasses(String namePattern,
            String module, String sourceSet, int limit, int offset) {
        String sql = "SELECT * FROM classes WHERE class_name LIKE :pattern "
                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                + (module != null ? "AND (EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id AND co.module = :module) OR "
                        + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id) AND classes.module = :module)) " : "")
                + (sourceSet != null ? "AND (EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id AND co.source_set = :sourceSet) OR "
                        + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id) AND classes.source_set = :sourceSet)) " : "")
                + "ORDER BY class_name LIMIT :limit OFFSET :offset";
        String pattern = namePattern.replace("*", "%");
        if (!pattern.contains("%")) {
            pattern = "%" + pattern + "%";
        }
        String finalPattern = pattern;
        
                    var query = handle.createQuery(sql)
                            .bind("pattern", finalPattern)
                            .bind("limit", limit)
                            .bind("offset", offset);
                    if (module != null) query.bind("module", module);
                    if (sourceSet != null) query.bind("sourceSet", sourceSet);
                    return query.map((rs, ctx) -> mapClass(rs)).list();
                
    }

    public int countClasses(String namePattern,
            String module, String sourceSet) {
        String sql = "SELECT COUNT(*) FROM classes WHERE class_name LIKE :pattern "
                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                + (module != null ? "AND (EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id AND co.module = :module) OR "
                        + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id) AND classes.module = :module)) " : "")
                + (sourceSet != null ? "AND (EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id AND co.source_set = :sourceSet) OR "
                        + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                        + "WHERE co.class_id = classes.id) AND classes.source_set = :sourceSet)) " : "");
        String pattern = namePattern.replace("*", "%");
        if (!pattern.contains("%")) pattern = "%" + pattern + "%";
        String finalPattern = pattern;
        
            var query = handle.createQuery(sql).bind("pattern", finalPattern);
            if (module != null) query.bind("module", module);
            if (sourceSet != null) query.bind("sourceSet", sourceSet);
            return query.mapTo(Integer.class).one();
        
    }

    public Optional<org.treblereel.mcp.core.KotlinSourceDebug.Info> findKotlinSourceDebug(int classId) {
        return handle.createQuery("SELECT data FROM kotlin_source_debug WHERE class_id=:id")
                .bind("id", classId).mapTo(String.class).findOne().flatMap(data -> {
            try { return Optional.of(JSON.readValue(data, org.treblereel.mcp.core.KotlinSourceDebug.Info.class)); }
            catch (Exception invalid) { return Optional.empty(); }
        });
    }

    public Optional<ClassRecord> findClassById(int id) {
        return handle.createQuery("SELECT * FROM classes WHERE id = :id")
                        .bind("id", id)
                        .map((rs, ctx) -> mapClass(rs))
                        .findFirst();
    }

    public Map<Integer, ClassRecord> findClassesByIds(Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        
            Map<Integer, ClassRecord> result = new LinkedHashMap<>();
            handle.createQuery("SELECT * FROM classes WHERE id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(ids))
                    .map((rs, ctx) -> mapClass(rs))
                    .forEach(record -> result.put(record.id(), record));
            return result;
        
    }

    static ClassRecord mapClass(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClassRecord(
                rs.getInt("id"), rs.getString("class_name"), rs.getString("kind"),
                rs.getString("superclass"), fromJson(rs.getString("interfaces")),
                rs.getString("source_file"), rs.getInt("source_line"),
                rs.getInt("is_bean") == 1, rs.getInt("source_tokens"),
                rs.getObject("file_id") != null ? rs.getInt("file_id") : null,
                rs.getString("origin"), rs.getString("lifecycle"),
                rs.getString("module"), rs.getString("source_set"));
    }

    static ClassMemberRecord mapClassMember(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ClassMemberRecord(
                rs.getInt("class_id"), rs.getString("kind"), rs.getString("name"),
                rs.getString("signature"), rs.getString("descriptor"),
                rs.getString("type_name"),
                fromJson(rs.getString("parameter_types")), rs.getString("modifiers"),
                fromJson(rs.getString("annotations")),
                annotationDetailsFromJson(rs.getString("annotation_details")));
    }

    static Integer nullableInteger(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public int countClasses() {
        return countQuery("SELECT COUNT(*) FROM classes "
                + "WHERE lifecycle = 'current' AND origin != 'orphan_output'");
    }

    public int sumSourceTokens() {
        return countQuery("SELECT COALESCE(SUM(source_tokens), 0) FROM classes "
                + "WHERE lifecycle = 'current' AND origin != 'orphan_output'");
    }

    private int countQuery(String sql) {
        return handle.createQuery(sql).mapTo(Integer.class).one();
    }

    private Map<String, Integer> groupCountQuery(String sql) {
        
            Map<String, Integer> result = new LinkedHashMap<>();
            handle.createQuery(sql)
                    .map((rs, ctx) -> Map.entry(rs.getString(1), rs.getInt(2)))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        
    }

    static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> annotationValuesFromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castValues(Map<?, ?> values) {
        return (Map<String, Object>) values;
    }

    private static List<MemberAnnotationRecord> annotationDetailsFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> values = JSON.readValue(json, new TypeReference<>() {});
            return values.stream().map(value -> {
                Number index = (Number) value.get("parameterIndex");
                Integer parameterIndex = index == null || index.intValue() < 0
                        ? null : index.intValue();
                return new MemberAnnotationRecord(
                        Objects.toString(value.get("annotationName"), ""),
                        Objects.toString(value.get("targetKind"), ""),
                        parameterIndex,
                        emptyToNull(Objects.toString(value.get("parameterName"), "")),
                        emptyToNull(Objects.toString(value.get("parameterType"), "")),
                        value.get("values") instanceof Map<?, ?> memberValues
                                ? castValues(memberValues) : Map.of());
            }).toList();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
    public record IndexedScope(String module, String sourceSet, int classes) {}
    public List<IndexedScope> findIndexedScopes() {
        return handle.createQuery("""
                SELECT module, source_set, COUNT(*) AS classes FROM classes
                WHERE lifecycle='current' AND origin NOT IN ('dependency','orphan_output')
                GROUP BY module,source_set ORDER BY COALESCE(module,''),COALESCE(source_set,'')
                """).map((rs, ctx) -> new IndexedScope(rs.getString("module"),
                        rs.getString("source_set"), rs.getInt("classes"))).list();
    }

    public List<PositionDeclaration> findPositionDeclarations( String identifier, String path) {
        return handle.createQuery(ClasspathSqlLocator.findSqlOnClasspath(SymbolRepository.class, "findPositionDeclarations"))
                .bind("identifier", identifier).bind("path", path)
                .map((row, context) -> new PositionDeclaration(
                        row.getString("class_name"), row.getString("kind"),
                        row.getString("member_name"), row.getString("signature"),
                        row.getString("descriptor"),
                        sourceParameterCount(row.getString("parameter_types"),
                                row.getInt("is_suspend") == 1, row.getInt("is_extension") == 1,
                                row.getString("value_parameters")),
                        row.getString("source_file"), row.getInt("source_line"),
                        row.getInt("same_file") == 1,
                        row.getString("semantic_kind"), row.getString("semantic_name"),
                        row.getInt("has_defaults") == 1,
                        SymbolRepository.kotlinParametersFromJson(row.getString("value_parameters"))))
                .list();
    }
    public List<EnclosingClass> findEnclosingClasses( String path, int line) {
        return handle.createQuery("""
                        SELECT c.class_name, c.source_line
                        FROM classes c LEFT JOIN files f ON f.id = c.file_id
                        WHERE c.lifecycle = 'current' AND c.source_line <= :line
                          AND (c.source_file = :path OR f.project_path = :path
                               OR f.repository_path = :path)
                        ORDER BY c.source_line DESC LIMIT 10
                        """)
                .bind("line", line).bind("path", path)
                .map((row, context) -> new EnclosingClass(
                        row.getString("class_name"), row.getInt("source_line")))
                .list();
    }
    private static Integer parameterCount(String json) {
        if (json == null) return null;
        try {
            var parsed = JSON.readTree(json);
            return parsed.isArray() ? parsed.size() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
    private static Integer sourceParameterCount(String json, boolean suspend, boolean extension, String metadata) {
        var parameters = SymbolRepository.kotlinParametersFromJson(metadata);
        if (parameters != null) return parameters.size();
        Integer count = parameterCount(json);
        return count == null ? null : Math.max(0, count - (suspend ? 1 : 0) - (extension ? 1 : 0));
    }
    public record PositionDeclaration(String className, String kind, String memberName,
            String signature, String descriptor, Integer parameterCount, String sourceFile,
            int sourceLine, boolean sameFile,
            String semanticKind, String semanticName, boolean hasDefaults, List<KotlinValueParameter> valueParameters) {}
    public record EnclosingClass(String className, int sourceLine) {}
    public record ApiOwner(int id, String className, String kind, String superclass,
            String interfaces, String module, String sourceSet) {}
    public record ApiMember(int classId, String kind, String name, String descriptor, String signature,
            String modifiers, String annotations, String annotationDetails, String typeName, String parameterTypes) {}
    public record JvmApiRow(int classId, String evidence) {}

    public List<ApiOwner> findApiOwners() {
        return handle.createQuery("""
                SELECT id,class_name,kind,superclass,interfaces,module,source_set FROM classes
                WHERE lifecycle='current' AND origin!='orphan_output' AND (source_set='main' OR source_set IS NULL)
                ORDER BY class_name,module,source_set,id
                """).map((rs, ctx) -> apiOwner(rs)).list();
    }

    public List<Integer> findAmbiguousModuleOwners() {
        return handle.createQuery("SELECT class_id FROM class_occurrences GROUP BY class_id HAVING COUNT(DISTINCT module)>1")
                .mapTo(Integer.class).list();
    }

    public List<ApiMember> findApiMembers() {
        return handle.createQuery("SELECT class_id,kind,name,descriptor,signature,modifiers,annotations,annotation_details,type_name,parameter_types FROM class_members ORDER BY class_id,kind,name,descriptor,signature")
                .map((rs, ctx) -> new ApiMember(rs.getInt("class_id"),rs.getString("kind"),rs.getString("name"),
                        rs.getString("descriptor"),rs.getString("signature"),rs.getString("modifiers"),rs.getString("annotations"),
                        rs.getString("annotation_details"),rs.getString("type_name"),rs.getString("parameter_types"))).list();
    }

    public List<JvmApiRow> findJvmApiRows() {
        return handle.createQuery("SELECT class_id,evidence FROM jvm_api ORDER BY class_id")
                .map((rs, ctx) -> new JvmApiRow(rs.getInt("class_id"),rs.getString("evidence"))).list();
    }

    public List<ClassAnnotationRecord> findApiAnnotations() {
        return handle.createQuery("SELECT class_id,annotation_name,direct,via_annotation,annotation_values FROM class_annotations ORDER BY class_id,annotation_name,direct,via_annotation")
                .map((rs, ctx) -> new ClassAnnotationRecord(rs.getInt("class_id"),rs.getString("annotation_name"),rs.getInt("direct")==1,rs.getString("via_annotation"),annotationValuesFromJson(rs.getString("annotation_values")))).list();
    }

    public List<KotlinDeclarationRecord> findKotlinDeclarationRows() {
        return handle.createQuery("""
                SELECT class_id,kind,name,jvm_name,descriptor,semantic_model,is_suspend,is_extension,
                       has_default_parameters,is_mutable,is_lateinit,is_delegated,is_synthetic,value_parameters,declaration_types
                FROM kotlin_declarations ORDER BY class_id,kind,name,jvm_name,descriptor
                """).map((rs, ctx) -> new KotlinDeclarationRecord(rs.getInt("class_id"),rs.getString("kind"),rs.getString("name"),
                        rs.getString("jvm_name"),rs.getString("descriptor"),rs.getString("semantic_model"),rs.getInt("is_suspend")==1,
                        rs.getInt("is_extension")==1,rs.getInt("has_default_parameters")==1,rs.getInt("is_mutable")==1,
                        rs.getInt("is_lateinit")==1,rs.getInt("is_delegated")==1,rs.getInt("is_synthetic")==1,
                        kotlinParametersFromJson(rs.getString("value_parameters")),kotlinTypesFromJson(rs.getString("declaration_types")))).list();
    }

    public List<ApiOwner> findHierarchyRows() {
        return handle.createQuery("SELECT id,class_name,kind,module,source_set,superclass,interfaces FROM classes WHERE lifecycle='current' AND origin!='orphan_output' ORDER BY class_name,module,source_set,id")
                .map((rs, ctx) -> apiOwner(rs)).list();
    }

    private static ApiOwner apiOwner(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiOwner(rs.getInt("id"),rs.getString("class_name"),rs.getString("kind"),
                rs.getString("superclass"),rs.getString("interfaces"),rs.getString("module"),rs.getString("source_set"));
    }

    public boolean hasTestClasses(String module) {
        return handle.createQuery("SELECT EXISTS(SELECT 1 FROM classes WHERE module=:module AND source_set='test' AND lifecycle='current' AND origin NOT IN ('dependency','orphan_output'))")
                .bind("module", module).mapTo(Boolean.class).one();
    }

    public Set<String> findAmbiguousTypeNames() {
        return new HashSet<>(handle.createQuery("SELECT class_name FROM class_occurrences GROUP BY class_name HAVING count(DISTINCT output_directory)>1")
                .mapTo(String.class).list());
    }

    /** A field declared by a class: its type, modifiers and annotation names (a JSON array). */
    public record FieldDeclaration(String typeName, String modifiers, String annotations) {}
    /** A member carrying an {@code @Observes} parameter, with its raw annotation details. */
    public record ObserverMember(int classId, String className, String name, String descriptor, String annotationDetails) {}
    /** A direct class annotation with its raw values (JSON, may be null). */
    public record DirectClassAnnotation(String annotationName, String annotationValues) {}

    /** The id of the current class named {@code className}, whatever its origin. */
    public Optional<Integer> findCurrentClassId(String className) {
        return handle.createQuery("SELECT id FROM classes WHERE class_name = :name AND lifecycle = 'current' LIMIT 1")
                .bind("name", className).mapTo(Integer.class).findOne();
    }

    public Optional<String> findSuperclass(int classId) {
        return handle.createQuery("SELECT superclass FROM classes WHERE id = :id")
                .bind("id", classId).mapTo(String.class).findOne();
    }

    /** The raw annotation_details JSON of one member; empty when the member is unknown or has none. */
    public Optional<String> findMemberAnnotationDetails(int classId, String name, String descriptor) {
        return handle.createQuery("SELECT annotation_details FROM class_members WHERE class_id = :id AND name = :name AND descriptor = :descriptor")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor).mapTo(String.class).findOne();
    }

    /** Current members observing {@code eventType} through an {@code @Observes} parameter. */
    public List<ObserverMember> findObserverMembers(String eventType) {
        return handle.createQuery("""
                SELECT c.id AS class_id, c.class_name, m.name, m.descriptor, m.annotation_details FROM class_members m
                JOIN classes c ON c.id = m.class_id
                WHERE c.lifecycle = 'current' AND m.annotation_details LIKE '%.event.Observes"%' AND m.annotation_details LIKE :type""")
                .bind("type", "%\"parameterType\":\"" + eventType + "\"%")
                .map((rs, ctx) -> new ObserverMember(rs.getInt("class_id"), rs.getString("class_name"), rs.getString("name"),
                        rs.getString("descriptor"), rs.getString("annotation_details"))).list();
    }

    public Optional<FieldDeclaration> findField(int classId, String name) {
        return handle.createQuery("SELECT type_name, modifiers, annotations FROM class_members WHERE class_id = :id AND kind = 'FIELD' AND name = :name")
                .bind("id", classId).bind("name", name)
                .map((rs, ctx) -> new FieldDeclaration(rs.getString("type_name"), rs.getString("modifiers"), rs.getString("annotations")))
                .findOne();
    }

    /** Annotations written on the class itself, in index order. */
    public List<DirectClassAnnotation> findDirectClassAnnotations(int classId) {
        return handle.createQuery("SELECT annotation_name, annotation_values FROM class_annotations WHERE class_id = :id AND direct = 1")
                .bind("id", classId)
                .map((rs, ctx) -> new DirectClassAnnotation(rs.getString("annotation_name"), rs.getString("annotation_values"))).list();
    }

    public boolean hasMemberNamed(int classId, String name) {
        return handle.createQuery("SELECT count(*) FROM class_members WHERE class_id = :id AND name = :name")
                .bind("id", classId).bind("name", name).mapTo(Integer.class).one() > 0;
    }

    public boolean declaresMember(int classId, String name, String descriptor) {
        return handle.createQuery("SELECT count(*) FROM class_members WHERE class_id = :id AND name = :name AND descriptor = :descriptor")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor).mapTo(Integer.class).one() > 0;
    }

    /** Modifiers of each method overload named {@code name}; an empty descriptor matches every overload. */
    public List<String> findMethodModifiers(int classId, String name, String descriptor) {
        return handle.createQuery("""
                SELECT modifiers FROM class_members WHERE class_id = :id AND kind = 'METHOD' AND name = :name
                  AND (:descriptor = '' OR descriptor = :descriptor)""")
                .bind("id", classId).bind("name", name).bind("descriptor", descriptor).mapTo(String.class).list();
    }

    /** A field or method in declaration order: kind, name, type, modifiers and annotation names (JSON). */
    public record MemberOutline(String kind, String name, String typeName, String modifiers, String annotations) {}
    /** An overridable method: neither private, static, synthetic nor bridge. */
    public record InheritableMethod(String name, String descriptor, String signature, String modifiers) {}

    /** A class's fields and methods in declaration order. */
    public List<MemberOutline> findFieldsAndMethods(int classId) {
        return handle.createQuery("""
                SELECT kind, name, type_name, modifiers, annotations FROM class_members
                WHERE class_id = :id AND kind IN ('FIELD', 'METHOD') ORDER BY rowid""")
                .bind("id", classId)
                .map((rs, ctx) -> new MemberOutline(rs.getString("kind"), rs.getString("name"), rs.getString("type_name"),
                        rs.getString("modifiers"), rs.getString("annotations"))).list();
    }

    /** Members observing {@code event} (binary or source spelling) through {@code @Observes} or {@code @ObservesAsync}. */
    public List<ObserverMember> findEventObserverMembers(String event) {
        return handle.createQuery("""
                SELECT c.id AS class_id, c.class_name, cm.name, cm.descriptor, cm.annotation_details FROM class_members cm
                JOIN classes c ON c.id = cm.class_id
                WHERE c.lifecycle = 'current' AND cm.annotation_details LIKE '%.event.Observes%'
                  AND (cm.annotation_details LIKE :binary OR cm.annotation_details LIKE :spelled)""")
                .bind("binary", "%\"parameterType\":\"" + event + "\"%")
                .bind("spelled", "%\"parameterType\":\"" + event.replace('.', '$') + "\"%")
                .map((rs, ctx) -> new ObserverMember(rs.getInt("class_id"), rs.getString("class_name"), rs.getString("name"),
                        rs.getString("descriptor"), rs.getString("annotation_details"))).list();
    }

    /** Direct annotations of a class ordered by name; a null id has none. */
    public List<DirectClassAnnotation> findDirectClassAnnotationsByName(Integer classId) {
        return handle.createQuery("SELECT annotation_name, annotation_values FROM class_annotations WHERE class_id = :id AND direct = 1 ORDER BY annotation_name")
                .bind("id", classId)
                .map((rs, ctx) -> new DirectClassAnnotation(rs.getString("annotation_name"), rs.getString("annotation_values"))).list();
    }

    /** Overridable methods of the current class named {@code className}, by name and descriptor. */
    public List<InheritableMethod> findInheritableMethods(String className) {
        return handle.createQuery("""
                SELECT cm.name, cm.descriptor, cm.signature, cm.modifiers FROM class_members cm JOIN classes c ON c.id = cm.class_id
                WHERE c.class_name = :name AND c.lifecycle = 'current' AND cm.kind = 'METHOD'
                  AND COALESCE(cm.modifiers, '') NOT LIKE '%private%' AND COALESCE(cm.modifiers, '') NOT LIKE '%static%'
                  AND COALESCE(cm.modifiers, '') NOT LIKE '%synthetic%' AND COALESCE(cm.modifiers, '') NOT LIKE '%bridge%'
                ORDER BY cm.name, cm.descriptor""")
                .bind("name", className)
                .map((rs, ctx) -> new InheritableMethod(rs.getString("name"), rs.getString("descriptor"),
                        rs.getString("signature"), rs.getString("modifiers"))).list();
    }

    /** The superclass of each current class named {@code className}; an entry is null for a class without one. */
    public List<String> findCurrentSuperclasses(String className) {
        return handle.createQuery("SELECT superclass FROM classes WHERE class_name = :name AND lifecycle = 'current'")
                .bind("name", className).mapTo(String.class).list();
    }

    /** How many current classes one source set holds. */
    public record SourceSetCount(String sourceSet, int classes) {}

    /** Current classes directly in {@code packageName} per source set ({@code unknown} when unset). */
    public List<SourceSetCount> findPackageClassCounts(String packageName) {
        return handle.createQuery("""
                SELECT COALESCE(source_set, 'unknown') AS source_set, count(*) AS classes FROM classes
                WHERE class_name LIKE :prefix AND lifecycle = 'current'
                  AND instr(substr(class_name, length(:package) + 2), '.') = 0
                GROUP BY 1 ORDER BY 1""")
                .bind("prefix", packageName + ".%").bind("package", packageName)
                .map((rs, ctx) -> new SourceSetCount(rs.getString("source_set"), rs.getInt("classes"))).list();
    }

    /** Current non-orphan classes per module ({@code .} when unset) and source set ({@code unknown}). */
    public List<IndexedScope> findCurrentClassCounts() {
        return handle.createQuery("""
                SELECT COALESCE(module, '.') AS module, COALESCE(source_set, 'unknown') AS source_set, count(*) AS classes
                FROM classes WHERE lifecycle = 'current' AND origin <> 'orphan_output'
                GROUP BY 1, 2 ORDER BY 1, 2""")
                .map((rs, ctx) -> new IndexedScope(rs.getString("module"), rs.getString("source_set"), rs.getInt("classes"))).list();
    }

    /** Every module a class of any lifecycle is indexed in. */
    public List<String> findClassModules() {
        return handle.createQuery("SELECT DISTINCT module FROM classes WHERE module IS NOT NULL ORDER BY module").mapTo(String.class).list();
    }

    /** The source file of the current class named {@code className}, whatever its origin. */
    public Optional<String> findCurrentSourceFile(String className) {
        return handle.createQuery("SELECT source_file FROM classes WHERE class_name = :name AND lifecycle = 'current' LIMIT 1")
                .bind("name", className).mapTo(String.class).findOne();
    }

    public Optional<String> findSourceFile(int classId) {
        return handle.createQuery("SELECT source_file FROM classes WHERE id = :id")
                .bind("id", classId).mapTo(String.class).findOne();
    }

    public List<String> findFieldNames(int classId) {
        return handle.createQuery("SELECT name FROM class_members WHERE class_id = :id AND kind = 'FIELD'")
                .bind("id", classId).mapTo(String.class).list();
    }

    /** A member's name and annotation names (JSON). */
    public record MemberAnnotations(String name, String annotations) {}

    /** Methods of every class named {@code className}, in declaration order. */
    public List<MemberAnnotations> findMethodAnnotations(String className) {
        return handle.createQuery("""
                SELECT m.name, m.annotations FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.class_name = :type AND m.kind = 'METHOD' ORDER BY m.rowid""")
                .bind("type", className)
                .map((rs, ctx) -> new MemberAnnotations(rs.getString("name"), rs.getString("annotations"))).list();
    }

    /** Annotation names (JSON) of a member of any class named {@code className}, a field before a method. */
    public Optional<String> findMemberAnnotations(String className, String member) {
        return handle.createQuery("""
                SELECT m.annotations FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.class_name = :owner AND m.name = :member ORDER BY m.kind = 'FIELD' DESC LIMIT 1""")
                .bind("owner", className).bind("member", member).mapTo(String.class).findOne();
    }

    /** Names of the annotations written on any class named {@code className}, sorted. */
    public List<String> findDirectAnnotationNames(String className) {
        return handle.createQuery("""
                SELECT a.annotation_name FROM class_annotations a JOIN classes c ON c.id = a.class_id
                WHERE c.class_name = :name AND a.direct = 1 ORDER BY a.annotation_name""")
                .bind("name", className).mapTo(String.class).list();
    }

    /** A class's observer methods and producer members, in declaration order. */
    public List<MemberAnnotations> findObserverAndProducerMembers(int classId) {
        return handle.createQuery("""
                SELECT name, annotations FROM class_members WHERE class_id = :id AND kind IN ('METHOD','FIELD')
                AND (annotations LIKE '%.event.Observes%' OR annotations LIKE '%.inject.Produces"%') ORDER BY rowid""")
                .bind("id", classId)
                .map((rs, ctx) -> new MemberAnnotations(rs.getString("name"), rs.getString("annotations"))).list();
    }

    /** A current method that may receive Spring application events, with its class's raw interfaces JSON. */
    public record ListenerMethod(int classId, String className, String interfaces, String name) {}

    /** Whether any member annotated with an EventListener takes a {@code type} parameter. */
    public boolean hasEventListenerFor(String type) {
        return handle.createQuery("SELECT 1 FROM class_members WHERE annotations LIKE :listener AND parameter_types LIKE :type LIMIT 1")
                .bind("listener", "%EventListener%").bind("type", "%\"" + type + "\"%").mapTo(Integer.class).findOne().isPresent();
    }

    /** Current methods annotated with an EventListener or named onApplicationEvent, one per class and name. */
    public List<ListenerMethod> findListenerMethods() {
        return handle.createQuery("""
                SELECT c.id, c.class_name, c.interfaces, m.name FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.lifecycle = 'current' AND m.kind = 'METHOD'
                  AND (m.annotations LIKE '%EventListener%' OR m.name = 'onApplicationEvent')
                GROUP BY c.id, m.name ORDER BY c.class_name, m.name""")
                .map((rs, ctx) -> new ListenerMethod(rs.getInt("id"), rs.getString("class_name"), rs.getString("interfaces"),
                        rs.getString("name"))).list();
    }

    /** A class with its raw interfaces JSON. */
    public record ClassInterfaces(String className, String interfaces) {}

    /** Annotation names (JSON) of each method overload of any class named {@code className}; an empty descriptor matches all. */
    public List<String> findMethodAnnotationNames(String className, String method, String descriptor) {
        return handle.createQuery("""
                SELECT m.annotations FROM class_members m JOIN classes c ON c.id=m.class_id
                WHERE c.class_name=:owner AND m.kind='METHOD' AND m.name=:method AND (:descriptor='' OR m.descriptor=:descriptor)""")
                .bind("owner", className).bind("method", method).bind("descriptor", descriptor).mapTo(String.class).list();
    }

    /** Main source classes implementing any interface, by name. */
    public List<ClassInterfaces> findMainSourceClassInterfaces() {
        return handle.createQuery("SELECT class_name, interfaces FROM classes WHERE origin='source' AND source_set='main' AND interfaces IS NOT NULL ORDER BY class_name")
                .map((rs, ctx) -> new ClassInterfaces(rs.getString("class_name"), rs.getString("interfaces"))).list();
    }

    /** {@code Class#member} of every source member annotated {@code @io.quarkus.security.PermissionChecker}, sorted. */
    public List<String> findPermissionCheckers() {
        return handle.createQuery("""
                SELECT c.class_name || '#' || m.name FROM class_members m JOIN classes c ON c.id=m.class_id
                WHERE c.origin='source' AND m.annotations LIKE '%"io.quarkus.security.PermissionChecker"%' ORDER BY 1""")
                .mapTo(String.class).list();
    }

    /** A method as a config mapping reads it: return type, modifiers, parameter types and annotation details (JSON). */
    public record MappingMethod(String name, String typeName, String modifiers, String parameterTypes, String annotationDetails) {}

    /** Methods of the current class named {@code className}, by name. */
    public List<MappingMethod> findMappingMethods(String className) {
        return handle.createQuery("""
                SELECT cm.name, cm.type_name, cm.modifiers, cm.parameter_types, cm.annotation_details
                FROM class_members cm JOIN classes c ON c.id = cm.class_id
                WHERE c.class_name = :type AND c.lifecycle = 'current' AND cm.kind = 'METHOD'
                ORDER BY cm.name""")
                .bind("type", className)
                .map((rs, ctx) -> new MappingMethod(rs.getString("name"), rs.getString("type_name"), rs.getString("modifiers"),
                        rs.getString("parameter_types"), rs.getString("annotation_details"))).list();
    }

    public boolean isCurrentInterface(String className) {
        return handle.createQuery("SELECT count(*) FROM classes WHERE class_name = :type AND kind = 'INTERFACE' AND lifecycle = 'current'")
                .bind("type", className).mapTo(Integer.class).one() > 0;
    }

    /** Raw values (JSON, null when none) of each direct {@code annotationName} annotation on a class. */
    public List<String> findDirectClassAnnotationValues(int classId, String annotationName) {
        return handle.createQuery("SELECT annotation_values FROM class_annotations WHERE class_id = :id AND direct = 1 AND annotation_name = :annotation")
                .bind("id", classId).bind("annotation", annotationName).mapTo(String.class).list();
    }

    /** The jvm_api evidence JSON of a class. */
    public Optional<String> findJvmApiEvidence(int classId) {
        return handle.createQuery("SELECT evidence FROM jvm_api WHERE class_id=:id").bind("id", classId).mapTo(String.class).findOne();
    }

    /** A class annotation by name. */
    public record NamedClassAnnotation(String className, String annotationName) {}
    /** A member with its raw annotation details (JSON). */
    public record AnnotatedMember(String className, String name, String annotationDetails) {}

    /** LIKE pattern for annotation values naming {@code key} as {@code "name":"key"}, escaped with {@code !}. */
    private static String namedKeyPattern(String key) {
        return "%\"name\":\"" + key.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "\"%";
    }

    /** Class annotations whose values name {@code key}, by class. */
    public List<NamedClassAnnotation> findClassAnnotationsNamingKey(String key) {
        return handle.createQuery("""
                SELECT c.class_name, a.annotation_name FROM class_annotations a JOIN classes c ON c.id = a.class_id
                WHERE a.annotation_values LIKE :needle ESCAPE '!' ORDER BY c.class_name""")
                .bind("needle", namedKeyPattern(key))
                .map((rs, ctx) -> new NamedClassAnnotation(rs.getString("class_name"), rs.getString("annotation_name"))).list();
    }

    /** Members whose annotation values name {@code key}, by class and member. */
    public List<AnnotatedMember> findMembersNamingKey(String key) {
        return handle.createQuery("""
                SELECT c.class_name, m.name, m.annotation_details FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE m.annotation_details LIKE :needle ESCAPE '!' ORDER BY c.class_name, m.name""")
                .bind("needle", namedKeyPattern(key))
                .map((rs, ctx) -> new AnnotatedMember(rs.getString("class_name"), rs.getString("name"), rs.getString("annotation_details"))).list();
    }

    /** {@code Class#member} names of current members annotated with {@code annotation}, by class and member. */
    public List<String> findMembersAnnotatedWith(String annotation) {
        return handle.createQuery("""
                SELECT c.class_name, m.name FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE m.annotations LIKE :quoted AND c.lifecycle = 'current' ORDER BY c.class_name, m.name""")
                .bind("quoted", "%\"" + annotation + "\"%")
                .map((rs, ctx) -> rs.getString("class_name") + "#" + rs.getString("name")).list();
    }

    /** An annotation value that may hold an expression: on a member ({@code member} set) or on a class. */
    public record AnnotationExpressionSite(String className, String sourceFile, String module, String sourceSet, String member, String details) {}

    /** Current member annotation details and direct class annotation values matching a LIKE pattern, by class and member. */
    public List<AnnotationExpressionSite> findAnnotationValuesLike(String like) {
        return handle.createQuery("""
                SELECT c.class_name, c.source_file, c.module, c.source_set, m.name AS member, m.annotation_details AS details
                FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.lifecycle = 'current' AND m.annotation_details LIKE :like
                UNION ALL
                SELECT c.class_name, c.source_file, c.module, c.source_set, NULL, a.annotation_name || '=' || a.annotation_values
                FROM class_annotations a JOIN classes c ON c.id = a.class_id
                WHERE c.lifecycle = 'current' AND a.direct = 1 AND a.annotation_values LIKE :like
                ORDER BY 1, 5""")
                .bind("like", like)
                .map((rs, ctx) -> new AnnotationExpressionSite(rs.getString("class_name"), rs.getString("source_file"),
                        rs.getString("module"), rs.getString("source_set"), rs.getString("member"), rs.getString("details"))).list();
    }
}
