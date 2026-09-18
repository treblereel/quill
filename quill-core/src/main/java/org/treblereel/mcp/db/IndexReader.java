package org.treblereel.mcp.db;

import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.model.*;

public final class IndexReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IndexReader() {}

    public record DependencyBreakdown(String origin, int classes, int edges) {}

    public record ArchitectureHub(
            int classId,
            int totalDependents,
            int sourceDependents,
            int generatedDependents,
            int productionDependents,
            int testDependents) {}

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
            int sourceTokens) {}

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
            int sourceTokens) {}

    public record MethodCallView(
            int fromClassId,
            String fromClass,
            String fromSource,
            int fromSourceLine,
            String fromOrigin,
            String fromModule,
            int fromSourceTokens,
            String fromMethod,
            String fromDescriptor,
            int toClassId,
            String toClass,
            String toSource,
            int toSourceLine,
            String toOrigin,
            String toModule,
            int toSourceTokens,
            String toMethod,
            String toDescriptor,
            String invocationKind,
            int occurrenceCount,
            List<Integer> evidenceLines) {}

    public record MethodInboundUsage(
            int classId,
            String method,
            String descriptor,
            int callerClassCount,
            int occurrenceCount) {}

    public record FieldUsage(
            int classId,
            String fieldName,
            String descriptor,
            int readerClassCount,
            int readOccurrences,
            int writerClassCount,
            int writeOccurrences) {}

    public record FieldAccessView(
            int fromClassId,
            String fromClass,
            String fromSource,
            int fromSourceLine,
            String fromOrigin,
            String fromModule,
            int fromSourceTokens,
            String fromMethod,
            String fromDescriptor,
            String fieldName,
            String fieldDescriptor,
            String accessKind,
            int occurrenceCount,
            List<Integer> evidenceLines) {}

    public static List<ClassRecord> findAllClasses(Jdbi jdbi) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE lifecycle = 'current' "
                                + "AND origin != 'orphan_output' ORDER BY id")
                        .map((rs, ctx) -> mapClass(rs))
                        .list());
    }

    public static List<UnusedClassCandidate> findUnusedClassCandidates(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        WITH incoming AS (
                            SELECT d.to_class_id,
                                   COUNT(DISTINCT d.from_class_id) AS class_count,
                                   COALESCE(SUM(d.occurrence_count), 0) AS occurrence_count,
                                   COUNT(DISTINCT CASE
                                       WHEN COALESCE(source.source_set, 'main') = 'test'
                                       THEN source.id END) AS test_count,
                                   COUNT(DISTINCT CASE
                                       WHEN COALESCE(source.source_set, 'main') != 'test'
                                       THEN source.id END) AS production_count
                            FROM dependencies d
                            JOIN classes source ON source.id = d.from_class_id
                            WHERE d.from_class_id != d.to_class_id
                              AND source.lifecycle = 'current'
                              AND source.origin != 'orphan_output'
                            GROUP BY d.to_class_id
                        ), hierarchy_edges AS (
                            SELECT child.id AS child_id, child.superclass AS target_name
                            FROM classes child
                            WHERE child.superclass IS NOT NULL
                              AND child.lifecycle = 'current'
                              AND child.origin != 'orphan_output'
                            UNION ALL
                            SELECT child.id AS child_id, interface.value AS target_name
                            FROM classes child, json_each(child.interfaces) interface
                            WHERE child.lifecycle = 'current'
                              AND child.origin != 'orphan_output'
                        ), hierarchy AS (
                            SELECT target_name, COUNT(DISTINCT child_id) AS user_count
                            FROM hierarchy_edges
                            GROUP BY target_name
                        ), annotations AS (
                            SELECT class_id, COUNT(*) AS annotation_count
                            FROM class_annotations
                            WHERE direct = 1
                            GROUP BY class_id
                        ), main_methods AS (
                            SELECT DISTINCT class_id
                            FROM class_members
                            WHERE kind = 'METHOD' AND name = 'main'
                              AND modifiers LIKE '%static%'
                              AND parameter_types = '["java.lang.String[]"]'
                        )
                        SELECT c.*,
                               COALESCE(incoming.class_count, 0) AS inbound_class_count,
                               COALESCE(incoming.occurrence_count, 0) AS inbound_occurrence_count,
                               COALESCE(incoming.production_count, 0) AS production_inbound_count,
                               COALESCE(incoming.test_count, 0) AS test_inbound_count,
                               COALESCE(hierarchy.user_count, 0) AS hierarchy_user_count,
                               COALESCE(annotations.annotation_count, 0) AS annotation_count,
                               CASE WHEN main_methods.class_id IS NULL THEN 0 ELSE 1 END AS has_main
                        FROM classes c
                        LEFT JOIN incoming ON incoming.to_class_id = c.id
                        LEFT JOIN hierarchy ON hierarchy.target_name = c.class_name
                        LEFT JOIN annotations ON annotations.class_id = c.id
                        LEFT JOIN main_methods ON main_methods.class_id = c.id
                        WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                        ORDER BY c.class_name, c.module, c.source_set, c.id""")
                .map((rs, ctx) -> new UnusedClassCandidate(
                        mapClass(rs),
                        rs.getInt("inbound_class_count"),
                        rs.getInt("inbound_occurrence_count"),
                        rs.getInt("production_inbound_count"),
                        rs.getInt("test_inbound_count"),
                        rs.getInt("hierarchy_user_count"),
                        rs.getInt("annotation_count"),
                        rs.getBoolean("has_main")))
                .list());
    }

    public static List<MethodInboundUsage> findMethodInboundUsages(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT calls.to_class_id, calls.to_method, calls.to_descriptor,
                               COUNT(DISTINCT calls.from_class_id) AS caller_class_count,
                               SUM(calls.occurrence_count) AS occurrence_count
                        FROM method_calls calls
                        JOIN classes target ON target.id = calls.to_class_id
                        JOIN classes source ON source.id = calls.from_class_id
                        WHERE target.lifecycle = 'current'
                          AND target.origin != 'orphan_output'
                          AND source.lifecycle = 'current'
                          AND source.origin != 'orphan_output'
                        GROUP BY calls.to_class_id, calls.to_method, calls.to_descriptor
                        ORDER BY calls.to_class_id, calls.to_method, calls.to_descriptor""")
                .map((rs, ctx) -> new MethodInboundUsage(
                        rs.getInt("to_class_id"), rs.getString("to_method"),
                        rs.getString("to_descriptor"), rs.getInt("caller_class_count"),
                        rs.getInt("occurrence_count")))
                .list());
    }

    public static List<FieldUsage> findFieldUsages(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT access.to_class_id, access.field_name,
                               access.field_descriptor,
                               COUNT(DISTINCT CASE WHEN access.access_kind LIKE 'read%'
                                   THEN access.from_class_id END) AS reader_class_count,
                               COALESCE(SUM(CASE WHEN access.access_kind LIKE 'read%'
                                   THEN access.occurrence_count ELSE 0 END), 0) AS read_occurrences,
                               COUNT(DISTINCT CASE WHEN access.access_kind LIKE 'write%'
                                   THEN access.from_class_id END) AS writer_class_count,
                               COALESCE(SUM(CASE WHEN access.access_kind LIKE 'write%'
                                   THEN access.occurrence_count ELSE 0 END), 0) AS write_occurrences
                        FROM field_accesses access
                        JOIN classes target ON target.id = access.to_class_id
                        JOIN classes source ON source.id = access.from_class_id
                        WHERE target.lifecycle = 'current'
                          AND target.origin != 'orphan_output'
                          AND source.lifecycle = 'current'
                          AND source.origin != 'orphan_output'
                        GROUP BY access.to_class_id, access.field_name, access.field_descriptor
                        ORDER BY access.to_class_id, access.field_name, access.field_descriptor""")
                .map((rs, ctx) -> new FieldUsage(
                        rs.getInt("to_class_id"), rs.getString("field_name"),
                        rs.getString("field_descriptor"), rs.getInt("reader_class_count"),
                        rs.getInt("read_occurrences"), rs.getInt("writer_class_count"),
                        rs.getInt("write_occurrences")))
                .list());
    }

    public static List<String> findAnnotationNames(Jdbi jdbi, String target) {
        String normalized = target.startsWith("@") ? target.substring(1) : target;
        String suffix = "%." + normalized;
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT DISTINCT annotation_name FROM class_annotations
                        WHERE annotation_name = :name OR annotation_name LIKE :suffix
                        ORDER BY CASE WHEN annotation_name = :name THEN 0 ELSE 1 END,
                                 annotation_name""")
                .bind("name", normalized)
                .bind("suffix", suffix)
                .mapTo(String.class)
                .list());
    }

    public static List<String> findSymbolAnnotationNames(Jdbi jdbi, String target) {
        String normalized = target.startsWith("@") ? target.substring(1) : target;
        String suffix = "%." + normalized;
        return jdbi.withHandle(h -> h.createQuery("""
                        WITH annotation_names(name) AS (
                            SELECT a.annotation_name
                            FROM class_annotations a JOIN classes c ON c.id = a.class_id
                            WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                            UNION
                            SELECT CAST(annotation.value AS TEXT)
                            FROM class_members m
                            JOIN classes c ON c.id = m.class_id
                            JOIN json_each(m.annotations) annotation
                            WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                              AND json_array_length(m.annotation_details) = 0
                            UNION
                            SELECT json_extract(detail.value, '$.annotationName')
                            FROM class_members m
                            JOIN classes c ON c.id = m.class_id
                            JOIN json_each(m.annotation_details) detail
                            WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                        )
                        SELECT DISTINCT name FROM annotation_names
                        WHERE name = :name OR name LIKE :suffix
                        ORDER BY CASE WHEN name = :name THEN 0 ELSE 1 END, name""")
                .bind("name", normalized)
                .bind("suffix", suffix)
                .mapTo(String.class)
                .list());
    }

    public static List<AnnotatedSymbolResult> findAnnotatedSymbols(
            Jdbi jdbi, String annotationName, String symbolKind,
            boolean includeMetaAnnotations, int limit, int offset) {
        String sql = annotatedSymbolsCte() + """
                SELECT * FROM symbols
                ORDER BY CASE symbol_kind WHEN 'CLASS' THEN 0 WHEN 'INTERFACE' THEN 0
                         WHEN 'ENUM' THEN 0 WHEN 'ANNOTATION' THEN 0
                         WHEN 'RECORD' THEN 0 WHEN 'FIELD' THEN 1
                         WHEN 'CONSTRUCTOR' THEN 2 ELSE 3 END,
                         class_name, symbol_name, signature
                LIMIT :limit OFFSET :offset""";
        return jdbi.withHandle(handle -> handle.createQuery(sql)
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
                        rs.getInt("source_tokens")))
                .list());
    }

    public static int countAnnotatedSymbols(Jdbi jdbi, String annotationName,
            String symbolKind, boolean includeMetaAnnotations) {
        return jdbi.withHandle(handle -> handle.createQuery(
                        annotatedSymbolsCte() + "SELECT count(*) FROM symbols")
                .bind("annotation", annotationName)
                .bind("includeMeta", includeMetaAnnotations ? 1 : 0)
                .bind("kind", symbolKind)
                .mapTo(Integer.class).one());
    }

    private static String annotatedSymbolsCte() {
        return """
                WITH member_annotation_matches AS (
                    SELECT m.class_id, m.kind AS declaration_kind, m.name, m.signature,
                           m.descriptor, m.type_name, m.parameter_types, m.modifiers,
                           json_extract(detail.value, '$.annotationName') AS annotation_name,
                           json_extract(detail.value, '$.targetKind') AS annotation_target,
                           CASE WHEN json_extract(detail.value, '$.parameterIndex') >= 0
                                THEN json_extract(detail.value, '$.parameterIndex') END
                                AS parameter_index,
                           NULLIF(json_extract(detail.value, '$.parameterName'), '')
                                AS parameter_name,
                           NULLIF(json_extract(detail.value, '$.parameterType'), '')
                                AS parameter_type
                    FROM class_members m JOIN json_each(m.annotation_details) detail
                    UNION ALL
                    SELECT m.class_id, m.kind, m.name, m.signature, m.descriptor,
                           m.type_name, m.parameter_types, m.modifiers,
                           CAST(annotation.value AS TEXT), m.kind, NULL, NULL, NULL
                    FROM class_members m JOIN json_each(m.annotations) annotation
                    WHERE json_array_length(m.annotation_details) = 0
                ), symbols AS (
                    SELECT c.id AS class_id, c.class_name, c.kind AS symbol_kind,
                           c.kind AS declaration_kind, c.class_name AS symbol_name,
                           NULL AS signature,
                           NULL AS descriptor, c.class_name AS type_name,
                           '[]' AS parameter_types,
                           '' AS modifiers,
                           CASE WHEN MAX(a.direct) = 1 THEN 'direct' ELSE 'meta' END AS match,
                           MIN(CASE WHEN a.direct = 0 THEN a.via_annotation END) AS via_annotation,
                           'TYPE' AS annotation_target, NULL AS parameter_index,
                           NULL AS parameter_name, NULL AS parameter_type,
                           c.source_file, c.source_line, c.origin, c.module, c.source_set,
                           c.source_tokens
                    FROM classes c JOIN class_annotations a ON a.class_id = c.id
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                      AND a.annotation_name = :annotation
                      AND (:includeMeta = 1 OR a.direct = 1)
                      AND (:kind = 'ALL' OR :kind = 'TYPE')
                    GROUP BY c.id
                    UNION ALL
                    SELECT c.id, c.class_name,
                           CASE WHEN m.annotation_target = 'METHOD_PARAMETER'
                                THEN 'PARAMETER' ELSE m.declaration_kind END,
                           m.declaration_kind, m.name, m.signature,
                           m.descriptor, m.type_name, m.parameter_types, m.modifiers,
                           'direct', NULL,
                           m.annotation_target, m.parameter_index,
                           m.parameter_name, m.parameter_type,
                           c.source_file, c.source_line, c.origin, c.module, c.source_set,
                           c.source_tokens
                    FROM member_annotation_matches m
                    JOIN classes c ON c.id = m.class_id
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                      AND m.annotation_name = :annotation
                      AND (:kind = 'ALL'
                           OR (:kind = 'PARAMETER'
                               AND m.annotation_target = 'METHOD_PARAMETER')
                           OR (:kind != 'PARAMETER'
                               AND m.annotation_target != 'METHOD_PARAMETER'
                               AND m.declaration_kind = :kind))
                )
                """;
    }

    public static List<ClassRecord> findAnnotatedClasses(Jdbi jdbi, String annotationName,
            boolean includeMetaAnnotations, int limit, int offset) {
        return jdbi.withHandle(h -> h.createQuery("""
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
                .list());
    }

    public static int countAnnotatedClasses(Jdbi jdbi, String annotationName,
            boolean includeMetaAnnotations) {
        return jdbi.withHandle(h -> h.createQuery("""
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
                .one());
    }

    public static Map<String, Integer> countAnnotatedClassesByOrigin(
            Jdbi jdbi, String annotationName, boolean includeMetaAnnotations) {
        return jdbi.withHandle(h -> {
            Map<String, Integer> result = new LinkedHashMap<>();
            h.createQuery("""
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
        });
    }

    public static Map<Integer, List<ClassAnnotationRecord>> findClassAnnotations(
            Jdbi jdbi, String annotationName, Collection<Integer> classIds,
            boolean includeMetaAnnotations) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, List<ClassAnnotationRecord>> result = new LinkedHashMap<>();
            h.createQuery("""
                            SELECT class_id, annotation_name, direct, via_annotation
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
                            rs.getBoolean("direct"), rs.getString("via_annotation")))
                    .forEach(annotation -> result
                            .computeIfAbsent(annotation.classId(), ignored -> new ArrayList<>())
                            .add(annotation));
            return result;
        });
    }

    public static List<ClassAnnotationRecord> findClassAnnotations(
            Jdbi jdbi, int classId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT class_id, annotation_name, direct, via_annotation
                        FROM class_annotations WHERE class_id = :classId
                        ORDER BY direct DESC, annotation_name, via_annotation""")
                .bind("classId", classId)
                .map((rs, ctx) -> new ClassAnnotationRecord(
                        rs.getInt("class_id"), rs.getString("annotation_name"),
                        rs.getBoolean("direct"), rs.getString("via_annotation")))
                .list());
    }

    public static Map<Integer, List<ClassAnnotationRecord>> findClassAnnotations(
            Jdbi jdbi, Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        return jdbi.withHandle(handle -> {
            Map<Integer, List<ClassAnnotationRecord>> result = new LinkedHashMap<>();
            List<Integer> ids = List.copyOf(classIds);
            for (int from = 0; from < ids.size(); from += 500) {
                List<Integer> batch = ids.subList(from, Math.min(from + 500, ids.size()));
                handle.createQuery("""
                                SELECT class_id, annotation_name, direct, via_annotation
                                FROM class_annotations WHERE class_id IN (<classIds>)
                                ORDER BY class_id, direct DESC, annotation_name, via_annotation""")
                        .bindList("classIds", batch)
                        .map((rs, ctx) -> new ClassAnnotationRecord(
                                rs.getInt("class_id"), rs.getString("annotation_name"),
                                rs.getBoolean("direct"), rs.getString("via_annotation")))
                        .forEach(annotation -> result
                                .computeIfAbsent(annotation.classId(), ignored -> new ArrayList<>())
                                .add(annotation));
            }
            return result;
        });
    }

    public static List<ClassMemberRecord> findClassMembers(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT class_id, kind, name, signature, descriptor, type_name, parameter_types,
                               modifiers, annotations, annotation_details
                        FROM class_members WHERE class_id = :classId
                        ORDER BY CASE kind WHEN 'FIELD' THEN 0 WHEN 'CONSTRUCTOR' THEN 1 ELSE 2 END,
                                 name, signature""")
                .bind("classId", classId)
                .map((rs, ctx) -> mapClassMember(rs))
                .list());
    }

    public static Map<Integer, List<ClassMemberRecord>> findClassMembers(
            Jdbi jdbi, Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        return jdbi.withHandle(handle -> {
            Map<Integer, List<ClassMemberRecord>> result = new LinkedHashMap<>();
            List<Integer> ids = List.copyOf(classIds);
            for (int from = 0; from < ids.size(); from += 500) {
                List<Integer> batch = ids.subList(from, Math.min(from + 500, ids.size()));
                handle.createQuery("""
                                SELECT class_id, kind, name, signature, descriptor, type_name, parameter_types,
                                       modifiers, annotations, annotation_details
                                FROM class_members WHERE class_id IN (<classIds>)
                                ORDER BY class_id,
                                         CASE kind WHEN 'FIELD' THEN 0
                                                   WHEN 'CONSTRUCTOR' THEN 1 ELSE 2 END,
                                         name, signature""")
                        .bindList("classIds", batch)
                        .map((rs, ctx) -> mapClassMember(rs))
                        .forEach(member -> result
                                .computeIfAbsent(member.classId(), ignored -> new ArrayList<>())
                                .add(member));
            }
            return result;
        });
    }

    public static List<SymbolSearchResult> searchSymbols(Jdbi jdbi, String namePattern,
            String symbolKind, int limit, int offset) {
        String pattern = namePattern.replace("*", "%");
        if (!pattern.contains("%")) pattern = "%" + pattern + "%";
        String kindFilter = symbolKind == null ? "" : " AND symbol_kind = :kind";
        String sql = """
                WITH symbols AS (
                    SELECT c.id AS class_id, c.class_name, c.kind AS class_kind,
                           c.kind AS symbol_kind, c.class_name AS symbol_name,
                           NULL AS signature, NULL AS descriptor, NULL AS type_name,
                           '[]' AS parameter_types,
                           '' AS modifiers, '[]' AS annotations,
                           c.source_file, c.source_line, c.origin, c.module, c.source_set,
                           c.source_tokens
                    FROM classes c
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                      AND c.class_name LIKE :pattern
                    UNION ALL
                    SELECT c.id AS class_id, c.class_name, c.kind AS class_kind,
                           m.kind AS symbol_kind, m.name AS symbol_name,
                           m.signature, m.descriptor, m.type_name, m.parameter_types,
                           m.modifiers, m.annotations,
                           c.source_file, c.source_line, c.origin, c.module, c.source_set,
                           c.source_tokens
                    FROM class_members m JOIN classes c ON c.id = m.class_id
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                      AND (m.name LIKE :pattern OR m.signature LIKE :pattern)
                )
                SELECT * FROM symbols WHERE 1 = 1
                """ + kindFilter + " ORDER BY symbol_name, class_name, signature "
                + "LIMIT :limit OFFSET :offset";
        String finalPattern = pattern;
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql)
                    .bind("pattern", finalPattern)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (symbolKind != null) query.bind("kind", symbolKind);
            return query.map((rs, ctx) -> new SymbolSearchResult(
                    rs.getInt("class_id"), rs.getString("class_name"),
                    rs.getString("class_kind"), rs.getString("symbol_kind"),
                    rs.getString("symbol_name"), rs.getString("signature"),
                    rs.getString("descriptor"), rs.getString("type_name"),
                    fromJson(rs.getString("parameter_types")),
                    rs.getString("modifiers"), fromJson(rs.getString("annotations")),
                    rs.getString("source_file"), rs.getInt("source_line"),
                    rs.getString("origin"), rs.getString("module"),
                    rs.getString("source_set"), rs.getInt("source_tokens"))).list();
        });
    }

    public static int countSymbols(Jdbi jdbi, String namePattern, String symbolKind) {
        String pattern = namePattern.replace("*", "%");
        if (!pattern.contains("%")) pattern = "%" + pattern + "%";
        String kindFilter = symbolKind == null ? "" : " AND symbol_kind = :kind";
        String sql = """
                WITH symbols AS (
                    SELECT c.kind AS symbol_kind
                    FROM classes c
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                      AND c.class_name LIKE :pattern
                    UNION ALL
                    SELECT m.kind AS symbol_kind
                    FROM class_members m JOIN classes c ON c.id = m.class_id
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                      AND (m.name LIKE :pattern OR m.signature LIKE :pattern)
                )
                SELECT count(*) FROM symbols WHERE 1 = 1
                """ + kindFilter;
        String finalPattern = pattern;
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql).bind("pattern", finalPattern);
            if (symbolKind != null) query.bind("kind", symbolKind);
            return query.mapTo(Integer.class).one();
        });
    }

    public static List<MethodCallView> findMethodCalls(Jdbi jdbi, int classId,
            String method, String direction, int limit, int offset) {
        return findMethodCalls(jdbi, classId, method, null, direction, limit, offset);
    }

    public static List<MethodCallView> findMethodCalls(Jdbi jdbi, int classId,
            String method, String descriptor, String direction, int limit, int offset) {
        String predicate = methodCallPredicate(
                direction, method != null, descriptor != null);
        String sql = """
                SELECT mc.*, source.class_name AS from_class,
                       source.source_file AS from_source,
                       source.source_line AS from_source_line,
                       source.origin AS from_origin, source.module AS from_module,
                       source.source_tokens AS from_source_tokens,
                       target.class_name AS to_class,
                       target.source_file AS to_source,
                       target.source_line AS to_source_line,
                       target.origin AS to_origin, target.module AS to_module,
                       target.source_tokens AS to_source_tokens
                FROM method_calls mc
                JOIN classes source ON source.id = mc.from_class_id
                JOIN classes target ON target.id = mc.to_class_id
                WHERE
                """ + predicate + " ORDER BY from_class, from_method, from_descriptor, "
                + "to_class, to_method, to_descriptor LIMIT :limit OFFSET :offset";
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql)
                    .bind("classId", classId)
                    .bind("limit", limit)
                    .bind("offset", offset);
            if (method != null) query.bind("method", method);
            if (descriptor != null) query.bind("descriptor", descriptor);
            return query.map((rs, ctx) -> new MethodCallView(
                    rs.getInt("from_class_id"), rs.getString("from_class"),
                    rs.getString("from_source"), rs.getInt("from_source_line"),
                    rs.getString("from_origin"), rs.getString("from_module"),
                    rs.getInt("from_source_tokens"),
                    rs.getString("from_method"), rs.getString("from_descriptor"),
                    rs.getInt("to_class_id"), rs.getString("to_class"),
                    rs.getString("to_source"), rs.getInt("to_source_line"),
                    rs.getString("to_origin"), rs.getString("to_module"),
                    rs.getInt("to_source_tokens"),
                    rs.getString("to_method"), rs.getString("to_descriptor"),
                    rs.getString("invocation_kind"), rs.getInt("occurrence_count"),
                    parseIntList(rs.getString("evidence_lines")))).list();
        });
    }

    public static List<MethodCallView> findExactMethodUsages(Jdbi jdbi, int classId,
            String method, String descriptor, int limit, int offset) {
        String sql = """
                SELECT mc.*, source.class_name AS from_class,
                       source.source_file AS from_source,
                       source.source_line AS from_source_line,
                       source.origin AS from_origin, source.module AS from_module,
                       source.source_tokens AS from_source_tokens,
                       target.class_name AS to_class,
                       target.source_file AS to_source,
                       target.source_line AS to_source_line,
                       target.origin AS to_origin, target.module AS to_module,
                       target.source_tokens AS to_source_tokens
                FROM method_calls mc
                JOIN classes source ON source.id = mc.from_class_id
                JOIN classes target ON target.id = mc.to_class_id
                WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                  AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                  AND mc.to_class_id = :classId AND mc.to_method = :method
                  AND mc.to_descriptor = :descriptor
                ORDER BY from_class, from_method, from_descriptor, invocation_kind
                LIMIT :limit OFFSET :offset""";
        return jdbi.withHandle(handle -> handle.createQuery(sql)
                .bind("classId", classId)
                .bind("method", method)
                .bind("descriptor", descriptor)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((rs, ctx) -> mapMethodCall(rs)).list());
    }

    public static int countExactMethodUsages(
            Jdbi jdbi, int classId, String method, String descriptor) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT count(*) FROM method_calls mc
                        JOIN classes source ON source.id = mc.from_class_id
                        JOIN classes target ON target.id = mc.to_class_id
                        WHERE source.lifecycle = 'current'
                          AND source.origin != 'orphan_output'
                          AND target.lifecycle = 'current'
                          AND target.origin != 'orphan_output'
                          AND mc.to_class_id = :classId AND mc.to_method = :method
                          AND mc.to_descriptor = :descriptor""")
                .bind("classId", classId)
                .bind("method", method)
                .bind("descriptor", descriptor)
                .mapTo(Integer.class).one());
    }

    public static List<FieldAccessView> findExactFieldUsages(Jdbi jdbi, int classId,
            String field, String descriptor, String accessKind, int limit, int offset) {
        String accessPredicate = accessKind == null ? "" : " AND fa.access_kind LIKE :accessKind";
        String sql = """
                SELECT fa.*, source.class_name AS from_class,
                       source.source_file AS from_source,
                       source.source_line AS from_source_line,
                       source.origin AS from_origin, source.module AS from_module,
                       source.source_tokens AS from_source_tokens
                FROM field_accesses fa
                JOIN classes source ON source.id = fa.from_class_id
                JOIN classes target ON target.id = fa.to_class_id
                WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                  AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                  AND fa.to_class_id = :classId AND fa.field_name = :field
                  AND fa.field_descriptor = :descriptor
                """ + accessPredicate
                + " ORDER BY from_class, from_method, from_descriptor, access_kind"
                + " LIMIT :limit OFFSET :offset";
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql)
                    .bind("classId", classId).bind("field", field)
                    .bind("descriptor", descriptor).bind("limit", limit)
                    .bind("offset", offset);
            if (accessKind != null) query.bind("accessKind", accessKind + "_%");
            return query.map((rs, ctx) -> new FieldAccessView(
                    rs.getInt("from_class_id"), rs.getString("from_class"),
                    rs.getString("from_source"), rs.getInt("from_source_line"),
                    rs.getString("from_origin"), rs.getString("from_module"),
                    rs.getInt("from_source_tokens"), rs.getString("from_method"),
                    rs.getString("from_descriptor"), rs.getString("field_name"),
                    rs.getString("field_descriptor"), rs.getString("access_kind"),
                    rs.getInt("occurrence_count"),
                    parseIntList(rs.getString("evidence_lines")))).list();
        });
    }

    public static int countExactFieldUsages(Jdbi jdbi, int classId,
            String field, String descriptor, String accessKind) {
        String accessPredicate = accessKind == null ? "" : " AND fa.access_kind LIKE :accessKind";
        String sql = """
                SELECT count(*) FROM field_accesses fa
                JOIN classes source ON source.id = fa.from_class_id
                JOIN classes target ON target.id = fa.to_class_id
                WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                  AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                  AND fa.to_class_id = :classId AND fa.field_name = :field
                  AND fa.field_descriptor = :descriptor
                """ + accessPredicate;
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql)
                    .bind("classId", classId).bind("field", field)
                    .bind("descriptor", descriptor);
            if (accessKind != null) query.bind("accessKind", accessKind + "_%");
            return query.mapTo(Integer.class).one();
        });
    }

    public static int countMethodCalls(
            Jdbi jdbi, int classId, String method, String direction) {
        return countMethodCalls(jdbi, classId, method, null, direction);
    }

    public static int countMethodCalls(Jdbi jdbi, int classId, String method,
            String descriptor, String direction) {
        String sql = "SELECT count(*) FROM method_calls mc WHERE "
                + methodCallPredicate(direction, method != null, descriptor != null);
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql).bind("classId", classId);
            if (method != null) query.bind("method", method);
            if (descriptor != null) query.bind("descriptor", descriptor);
            return query.mapTo(Integer.class).one();
        });
    }

    public static List<MethodCallView> findAdjacentMethodCalls(Jdbi jdbi, int classId,
            String method, String descriptor, String direction) {
        String endpoint = "inbound".equals(direction) ? "to" : "from";
        StringBuilder predicate = new StringBuilder("mc.")
                .append(endpoint).append("_class_id = :classId");
        if (method != null) predicate.append(" AND mc.").append(endpoint)
                .append("_method = :method");
        if (descriptor != null) predicate.append(" AND mc.").append(endpoint)
                .append("_descriptor = :descriptor");
        String sql = """
                SELECT mc.*, source.class_name AS from_class,
                       source.source_file AS from_source,
                       source.source_line AS from_source_line,
                       source.origin AS from_origin, source.module AS from_module,
                       source.source_tokens AS from_source_tokens,
                       target.class_name AS to_class,
                       target.source_file AS to_source,
                       target.source_line AS to_source_line,
                       target.origin AS to_origin, target.module AS to_module,
                       target.source_tokens AS to_source_tokens
                FROM method_calls mc
                JOIN classes source ON source.id = mc.from_class_id
                JOIN classes target ON target.id = mc.to_class_id
                WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                  AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                  AND
                """ + predicate + " ORDER BY from_class, from_method, from_descriptor, "
                + "to_class, to_method, to_descriptor";
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql).bind("classId", classId);
            if (method != null) query.bind("method", method);
            if (descriptor != null) query.bind("descriptor", descriptor);
            return query.map((rs, ctx) -> new MethodCallView(
                    rs.getInt("from_class_id"), rs.getString("from_class"),
                    rs.getString("from_source"), rs.getInt("from_source_line"),
                    rs.getString("from_origin"), rs.getString("from_module"),
                    rs.getInt("from_source_tokens"),
                    rs.getString("from_method"), rs.getString("from_descriptor"),
                    rs.getInt("to_class_id"), rs.getString("to_class"),
                    rs.getString("to_source"), rs.getInt("to_source_line"),
                    rs.getString("to_origin"), rs.getString("to_module"),
                    rs.getInt("to_source_tokens"),
                    rs.getString("to_method"), rs.getString("to_descriptor"),
                    rs.getString("invocation_kind"), rs.getInt("occurrence_count"),
                    parseIntList(rs.getString("evidence_lines")))).list();
        });
    }

    private static String methodCallPredicate(
            String direction, boolean filterMethod, boolean filterDescriptor) {
        String inbound = "mc.to_class_id = :classId"
                + (filterMethod ? " AND mc.to_method = :method" : "")
                + (filterDescriptor ? " AND mc.to_descriptor = :descriptor" : "");
        String outbound = "mc.from_class_id = :classId"
                + (filterMethod ? " AND mc.from_method = :method" : "")
                + (filterDescriptor ? " AND mc.from_descriptor = :descriptor" : "");
        return switch (direction) {
            case "inbound" -> inbound;
            case "outbound" -> outbound;
            case "both" -> "((" + inbound + ") OR (" + outbound + "))";
            default -> throw new IllegalArgumentException("Unsupported call direction: " + direction);
        };
    }

    public static List<BeanRecord> findBeans(Jdbi jdbi, Map<String, String> filter) {
        return jdbi.withHandle(h -> {
            var sb = new StringBuilder("SELECT b.*, c.class_name FROM beans b "
                    + "JOIN classes c ON b.class_id = c.id "
                    + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'");

            if (filter != null) {
                if (filter.containsKey("class_name")) sb.append(" AND c.class_name LIKE :className");
                if (filter.containsKey("scope")) sb.append(" AND b.scope = :scope");
                if (filter.containsKey("kind")) sb.append(" AND b.kind = :kind");
                if (filter.containsKey("qualifier")) sb.append(" AND b.qualifiers LIKE :qualifier");
                if (filter.containsKey("module")) {
                    sb.append(" AND (EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id AND co.module = :module) OR "
                            + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id) AND c.module = :module))");
                }
                if (filter.containsKey("source_set")) {
                    sb.append(" AND (EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id AND co.source_set = :sourceSet) OR "
                            + "(NOT EXISTS (SELECT 1 FROM class_occurrences co "
                            + "WHERE co.class_id = c.id) AND c.source_set = :sourceSet))");
                }
            }

            var q = h.createQuery(sb.toString());
            if (filter != null) {
                if (filter.containsKey("class_name")) q.bind("className", filter.get("class_name").replace("*", "%"));
                if (filter.containsKey("scope")) q.bind("scope", filter.get("scope"));
                if (filter.containsKey("kind")) q.bind("kind", filter.get("kind"));
                if (filter.containsKey("qualifier")) q.bind("qualifier", "%" + filter.get("qualifier") + "%");
                if (filter.containsKey("module")) q.bind("module", filter.get("module"));
                if (filter.containsKey("source_set")) q.bind("sourceSet", filter.get("source_set"));
            }

            List<BeanRecord> results = q.map((rs, ctx) -> mapBean(rs)).list();

            if (filter != null && filter.containsKey("profile")) {
                String requestedProfile = filter.get("profile");
                results = results.stream()
                        .filter(b -> matchesProfile(b.profiles(), requestedProfile))
                        .toList();
            }

            return results;
        });
    }

    static boolean matchesProfile(List<String> beanProfiles, String requestedProfile) {
        if (beanProfiles == null || beanProfiles.isEmpty()) return false;
        for (String p : beanProfiles) {
            if (p.equals(requestedProfile)) return true;
        }
        return false;
    }

    public static List<InjectionPointRecord> findInjectionPoints(Jdbi jdbi, int beanId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM injection_points WHERE bean_id = :beanId")
                        .bind("beanId", beanId)
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list());
    }

    public static List<DependencyRecord> findDependencies(Jdbi jdbi, int classId, String direction) {
        String sql = switch (direction) {
            case "outbound" -> "SELECT * FROM dependencies WHERE from_class_id = :classId";
            case "inbound" -> "SELECT * FROM dependencies WHERE to_class_id = :classId";
            default -> "SELECT * FROM dependencies WHERE from_class_id = :classId OR to_class_id = :classId";
        };
        return jdbi.withHandle(h ->
                h.createQuery(sql)
                        .bind("classId", classId)
                        .map((rs, ctx) -> new DependencyRecord(
                                rs.getInt("from_class_id"), rs.getInt("to_class_id"),
                                rs.getString("kind"),
                                rs.getObject("injection_point_id") != null ? rs.getInt("injection_point_id") : null,
                                rs.getInt("occurrence_count"),
                                parseIntList(rs.getString("evidence_lines"))))
                        .list());
    }

    /** Returns class-level dependency edges whose endpoints are both current indexed classes. */
    public static List<DependencyRecord> findCurrentDependencyGraph(
            Jdbi jdbi, String module, boolean includeGenerated, boolean includeTests) {
        StringBuilder sql = new StringBuilder("""
                SELECT d.* FROM dependencies d
                JOIN classes source ON source.id = d.from_class_id
                JOIN classes target ON target.id = d.to_class_id
                WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                  AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                  AND source.id != target.id
                """);
        if (module != null && !module.isBlank()) {
            sql.append(" AND source.module = :module AND target.module = :module");
        }
        if (!includeGenerated) {
            sql.append(" AND source.origin != 'generated' AND target.origin != 'generated'");
        }
        if (!includeTests) {
            sql.append(" AND COALESCE(source.source_set, 'main') != 'test'");
            sql.append(" AND COALESCE(target.source_set, 'main') != 'test'");
        }
        sql.append(" ORDER BY d.from_class_id, d.to_class_id, d.kind");
        return jdbi.withHandle(h -> {
            var query = h.createQuery(sql.toString());
            if (module != null && !module.isBlank()) query.bind("module", module);
            return query.map((rs, ctx) -> new DependencyRecord(
                            rs.getInt("from_class_id"), rs.getInt("to_class_id"),
                            rs.getString("kind"),
                            rs.getObject("injection_point_id") != null
                                    ? rs.getInt("injection_point_id") : null,
                            rs.getInt("occurrence_count"),
                            parseIntList(rs.getString("evidence_lines"))))
                    .list();
        });
    }

    private static List<Integer> parseIntList(String value) {
        if (value == null || value.length() < 2) return List.of();
        String body = value.substring(1, value.length() - 1).trim();
        if (body.isEmpty()) return List.of();
        return java.util.Arrays.stream(body.split(","))
                .map(String::trim).map(Integer::valueOf).toList();
    }

    public static Map<String, String> getMetadata(Jdbi jdbi) {
        return jdbi.withHandle(h -> {
            Map<String, String> result = new LinkedHashMap<>();
            h.createQuery("SELECT key, value FROM metadata")
                    .map((rs, ctx) -> Map.entry(rs.getString("key"), rs.getString("value")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    public static Optional<ClassRecord> findClassByName(Jdbi jdbi, String className) {
        return findClassesByName(jdbi, className).stream().findFirst();
    }

    public static List<ClassRecord> findClassesByName(Jdbi jdbi, String className) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE class_name = :name "
                                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                                + "ORDER BY module, source_set, id")
                        .bind("name", className)
                        .map((rs, ctx) -> mapClass(rs))
                        .list());
    }

    public static Optional<ClassRecord> findClassByPath(Jdbi jdbi, String path) {
        String normalized = path.replace('\\', '/');
        return jdbi.withHandle(h -> h.createQuery("""
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
                .findFirst());
    }

    public static Optional<FileRecord> findFileByPath(Jdbi jdbi, String path) {
        String normalized = path.replace('\\', '/');
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT * FROM files
                        WHERE project_path = :path OR repository_path = :path
                        ORDER BY CASE WHEN lifecycle = 'current' THEN 0 ELSE 1 END
                        LIMIT 1""")
                .bind("path", normalized)
                .map((rs, ctx) -> mapFile(rs))
                .findFirst());
    }

    public static List<FileRecord> findFileCandidates(Jdbi jdbi, String target, int limit) {
        String normalized = target.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (basename.contains(".")) {
            String possibleClass = basename.endsWith(".java") || basename.endsWith(".kt")
                    ? basename.substring(0, basename.lastIndexOf('.'))
                    : basename.substring(basename.lastIndexOf('.') + 1);
            basename = possibleClass;
        }
        String javaPattern = "%/" + basename + ".java";
        String kotlinPattern = "%/" + basename + ".kt";
        List<FileRecord> matches = jdbi.withHandle(h -> h.createQuery("""
                        SELECT id, project_path, repository_path, kind, origin, lifecycle,
                               worktree_status, module, source_set, source_rank
                        FROM (
                            SELECT id, project_path, repository_path, kind, origin, lifecycle,
                                   worktree_status, module, source_set, 0 AS source_rank
                            FROM files
                            WHERE project_path LIKE :javaPattern OR repository_path LIKE :javaPattern
                               OR project_path LIKE :kotlinPattern OR repository_path LIKE :kotlinPattern
                            UNION ALL
                            SELECT 0 AS id, g.file_path AS project_path,
                                   g.file_path AS repository_path,
                                   CASE WHEN g.file_path LIKE '%.kt' THEN 'kotlin' ELSE 'java' END AS kind,
                                   'source' AS origin, 'historical' AS lifecycle,
                                   NULL AS worktree_status, NULL AS module, NULL AS source_set,
                                   1 AS source_rank
                            FROM git_file_stats g
                            WHERE g.file_path LIKE :javaPattern OR g.file_path LIKE :kotlinPattern
                        )
                        ORDER BY source_rank,
                                 CASE WHEN lifecycle = 'current' THEN 0 ELSE 1 END,
                                 repository_path""")
                .bind("javaPattern", javaPattern)
                .bind("kotlinPattern", kotlinPattern)
                .map((rs, ctx) -> mapFile(rs))
                .list());
        Map<String, FileRecord> unique = new LinkedHashMap<>();
        for (FileRecord match : matches) unique.putIfAbsent(match.repositoryPath(), match);
        return unique.values().stream().limit(limit).toList();
    }

    public static List<GitFileStats> findHistoricalPathCandidates(
            Jdbi jdbi, String target, int limit) {
        String normalized = target.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!basename.endsWith(".java") && !basename.endsWith(".kt")) {
            int dot = basename.lastIndexOf('.');
            if (dot >= 0) basename = basename.substring(dot + 1);
            basename += ".java";
        }
        String suffixPattern = "%/" + basename;
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT * FROM git_file_stats
                        WHERE file_path = :path OR file_path LIKE :suffix
                        ORDER BY CASE WHEN file_path = :path THEN 0 ELSE 1 END,
                                 commit_count DESC, file_path
                        LIMIT :limit""")
                .bind("path", normalized)
                .bind("suffix", suffixPattern)
                .bind("limit", limit)
                .map((rs, ctx) -> mapGitFileStats(rs))
                .list());
    }

    public static List<ClassRecord> findClassesByShortName(Jdbi jdbi, String shortName) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE "
                                + "(class_name = :shortName OR class_name LIKE :name) "
                                + "AND lifecycle = 'current' AND origin != 'orphan_output' "
                                + "ORDER BY class_name")
                        .bind("shortName", shortName)
                        .bind("name", "%." + shortName)
                        .map((rs, ctx) -> mapClass(rs))
                        .list());
    }

    public static List<ClassRecord> searchClasses(Jdbi jdbi, String namePattern, int limit) {
        return searchClasses(jdbi, namePattern, null, null, limit);
    }

    public static List<ClassRecord> searchClasses(Jdbi jdbi, String namePattern,
            String module, String sourceSet, int limit) {
        return searchClasses(jdbi, namePattern, module, sourceSet, limit, 0);
    }

    public static List<ClassRecord> searchClasses(Jdbi jdbi, String namePattern,
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
        return jdbi.withHandle(h ->
                {
                    var query = h.createQuery(sql)
                            .bind("pattern", finalPattern)
                            .bind("limit", limit)
                            .bind("offset", offset);
                    if (module != null) query.bind("module", module);
                    if (sourceSet != null) query.bind("sourceSet", sourceSet);
                    return query.map((rs, ctx) -> mapClass(rs)).list();
                });
    }

    public static int countClasses(Jdbi jdbi, String namePattern,
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
        return jdbi.withHandle(handle -> {
            var query = handle.createQuery(sql).bind("pattern", finalPattern);
            if (module != null) query.bind("module", module);
            if (sourceSet != null) query.bind("sourceSet", sourceSet);
            return query.mapTo(Integer.class).one();
        });
    }

    public static Optional<BeanRecord> findBeanByClassId(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM beans WHERE class_id = :classId")
                        .bind("classId", classId)
                        .map((rs, ctx) -> mapBean(rs))
                        .findFirst());
    }

    public static Optional<ClassRecord> findClassById(Jdbi jdbi, int id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM classes WHERE id = :id")
                        .bind("id", id)
                        .map((rs, ctx) -> mapClass(rs))
                        .findFirst());
    }

    public static Optional<BeanRecord> findBeanById(Jdbi jdbi, int id) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM beans WHERE id = :id")
                        .bind("id", id)
                        .map((rs, ctx) -> mapBean(rs))
                        .findFirst());
    }

    public static Map<Integer, ClassRecord> findClassesByIds(Jdbi jdbi, Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, ClassRecord> result = new LinkedHashMap<>();
            h.createQuery("SELECT * FROM classes WHERE id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(ids))
                    .map((rs, ctx) -> mapClass(rs))
                    .forEach(record -> result.put(record.id(), record));
            return result;
        });
    }

    public static Map<Integer, BeanRecord> findBeansByIds(Jdbi jdbi, Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, BeanRecord> result = new LinkedHashMap<>();
            h.createQuery("SELECT * FROM beans WHERE id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(ids))
                    .map((rs, ctx) -> mapBean(rs))
                    .forEach(record -> result.put(record.id(), record));
            return result;
        });
    }

    public static Map<Integer, BeanRecord> findBeansByClassIds(Jdbi jdbi, Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, BeanRecord> result = new LinkedHashMap<>();
            h.createQuery("SELECT * FROM beans WHERE class_id IN (<ids>) ORDER BY id")
                    .bindList("ids", new LinkedHashSet<>(classIds))
                    .map((rs, ctx) -> mapBean(rs))
                    .forEach(record -> result.putIfAbsent(record.classId(), record));
            return result;
        });
    }

    public static Map<Integer, List<ClassOccurrenceRecord>> findClassOccurrencesByClassIds(
            Jdbi jdbi, Collection<Integer> classIds) {
        if (classIds == null || classIds.isEmpty()) return Map.of();
        return jdbi.withHandle(h -> {
            Map<Integer, List<ClassOccurrenceRecord>> result = new LinkedHashMap<>();
            h.createQuery("SELECT * FROM class_occurrences WHERE class_id IN (<ids>) "
                            + "ORDER BY class_id, module, source_set, output_directory, class_file")
                    .bindList("ids", new LinkedHashSet<>(classIds))
                    .map((rs, ctx) -> mapClassOccurrence(rs))
                    .forEach(record -> result.computeIfAbsent(record.classId(),
                            ignored -> new ArrayList<>()).add(record));
            result.replaceAll((ignored, values) -> List.copyOf(values));
            return result;
        });
    }

    public static List<ModuleClasspathRecord> findModuleClasspath(
            Jdbi jdbi, String applicationModule) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT application_module, visible_module, distance, relation "
                                + "FROM module_classpath WHERE application_module = :application "
                                + "ORDER BY distance, visible_module")
                .bind("application", applicationModule)
                .map((rs, ctx) -> new ModuleClasspathRecord(
                        rs.getString("application_module"), rs.getString("visible_module"),
                        rs.getInt("distance"), rs.getString("relation")))
                .list());
    }

    public static List<ModuleClasspathRecord> findAllModuleClasspath(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery(
                        "SELECT application_module, visible_module, distance, relation "
                                + "FROM module_classpath "
                                + "ORDER BY application_module, distance, visible_module")
                .map((rs, ctx) -> new ModuleClasspathRecord(
                        rs.getString("application_module"), rs.getString("visible_module"),
                        rs.getInt("distance"), rs.getString("relation")))
                .list());
    }

    public static Set<String> findVisibleModules(Jdbi jdbi, String applicationModule) {
        return findModuleClasspath(jdbi, applicationModule).stream()
                .map(ModuleClasspathRecord::visibleModule)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static ClassRecord mapClass(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ClassRecord(
                rs.getInt("id"), rs.getString("class_name"), rs.getString("kind"),
                rs.getString("superclass"), fromJson(rs.getString("interfaces")),
                rs.getString("source_file"), rs.getInt("source_line"),
                rs.getInt("is_bean") == 1, rs.getInt("source_tokens"),
                rs.getObject("file_id") != null ? rs.getInt("file_id") : null,
                rs.getString("origin"), rs.getString("lifecycle"),
                rs.getString("module"), rs.getString("source_set"));
    }

    private static ClassMemberRecord mapClassMember(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ClassMemberRecord(
                rs.getInt("class_id"), rs.getString("kind"), rs.getString("name"),
                rs.getString("signature"), rs.getString("descriptor"),
                rs.getString("type_name"),
                fromJson(rs.getString("parameter_types")), rs.getString("modifiers"),
                fromJson(rs.getString("annotations")),
                annotationDetailsFromJson(rs.getString("annotation_details")));
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static MethodCallView mapMethodCall(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new MethodCallView(
                rs.getInt("from_class_id"), rs.getString("from_class"),
                rs.getString("from_source"), rs.getInt("from_source_line"),
                rs.getString("from_origin"), rs.getString("from_module"),
                rs.getInt("from_source_tokens"),
                rs.getString("from_method"), rs.getString("from_descriptor"),
                rs.getInt("to_class_id"), rs.getString("to_class"),
                rs.getString("to_source"), rs.getInt("to_source_line"),
                rs.getString("to_origin"), rs.getString("to_module"),
                rs.getInt("to_source_tokens"),
                rs.getString("to_method"), rs.getString("to_descriptor"),
                rs.getString("invocation_kind"), rs.getInt("occurrence_count"),
                parseIntList(rs.getString("evidence_lines")));
    }

    private static ClassOccurrenceRecord mapClassOccurrence(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ClassOccurrenceRecord(
                rs.getInt("id"), rs.getInt("class_id"), rs.getString("class_name"),
                rs.getString("module"), rs.getString("source_set"),
                rs.getString("output_directory"), rs.getString("class_file"),
                rs.getString("source_file"), rs.getString("origin"));
    }

    private static FileRecord mapFile(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FileRecord(rs.getInt("id"), rs.getString("project_path"),
                rs.getString("repository_path"), rs.getString("kind"),
                rs.getString("origin"), rs.getString("lifecycle"),
                rs.getString("worktree_status"), rs.getString("module"),
                rs.getString("source_set"));
    }

    private static BeanRecord mapBean(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BeanRecord(
                rs.getInt("id"), rs.getInt("class_id"), rs.getString("kind"),
                rs.getString("scope"), fromJson(rs.getString("qualifiers")),
                fromJson(rs.getString("stereotypes")), rs.getInt("is_alternative") == 1,
                rs.getInt("is_default") == 1,
                rs.getObject("priority") != null ? rs.getInt("priority") : null,
                fromJson(rs.getString("profiles")),
                rs.getObject("declaring_class_id") != null ? rs.getInt("declaring_class_id") : null,
                rs.getString("member_name"), fromJson(rs.getString("bean_types")));
    }

    private static InjectionPointRecord mapInjectionPoint(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InjectionPointRecord(
                rs.getInt("id"), rs.getInt("bean_id"), rs.getString("kind"),
                rs.getString("target_type"), fromJson(rs.getString("qualifiers")),
                rs.getString("field_name"),
                rs.getObject("resolved_bean_id") != null ? rs.getInt("resolved_bean_id") : null,
                ResolutionStatus.valueOf(rs.getString("resolution_status")),
                rs.getString("resolution_strategy"), rs.getString("resolution_reason"),
                ResolutionConfidence.valueOf(rs.getString("resolution_confidence")),
                fromJson(rs.getString("limitations")),
                new ResolutionTrace(
                        candidatesFromJson(rs.getString("resolution_candidates")),
                        fromJson(rs.getString("applied_rules")),
                        fromJson(rs.getString("unsupported_rules"))));
    }

    public static List<GitFileStats> findHotspots(Jdbi jdbi, int limit, String since) {
        return GitIndexReader.findHotspots(jdbi, limit, since);
    }

    public static List<GitCommitRecord> findFileHistory(Jdbi jdbi, int classId, int limit) {
        return findFileHistory(jdbi, classId, limit, 0);
    }

    public static List<GitCommitRecord> findFileHistory(
            Jdbi jdbi, int classId, int limit, int offset) {
        return GitIndexReader.findFileHistory(jdbi, classId, limit, offset);
    }

    public static List<GitCommitRecord> findFileHistoryByPath(
            Jdbi jdbi, String filePath, int limit) {
        return findFileHistoryByPath(jdbi, filePath, limit, 0);
    }

    public static List<GitCommitRecord> findFileHistoryByPath(
            Jdbi jdbi, String filePath, int limit, int offset) {
        return GitIndexReader.findFileHistoryByPath(jdbi, filePath, limit, offset);
    }

    public static List<CoChangeRecord> findCoChanges(Jdbi jdbi, int classId, int limit) {
        return GitIndexReader.findCoChanges(jdbi, classId, limit);
    }

    public static List<CoChangeRecord> findCoChangesByPath(
            Jdbi jdbi, String filePath, int limit) {
        return GitIndexReader.findCoChangesByPath(jdbi, filePath, limit);
    }

    public static List<GitCommitRecord> findRecentCommits(Jdbi jdbi, int limit) {
        return GitIndexReader.findRecentCommits(jdbi, limit);
    }

    public static List<GitCommitFile> findCommitFiles(Jdbi jdbi, int commitId) {
        return GitIndexReader.findCommitFiles(jdbi, commitId);
    }

    public static Map<Integer, List<GitCommitFile>> findCommitFiles(
            Jdbi jdbi, Collection<Integer> commitIds, int limit) {
        return GitIndexReader.findCommitFiles(jdbi, commitIds, limit);
    }

    public static Optional<GitFileStats> findFileStatsByClassId(Jdbi jdbi, int classId) {
        return GitIndexReader.findFileStatsByClassId(jdbi, classId);
    }

    public static Optional<GitFileStats> findFileStatsByPath(Jdbi jdbi, String filePath) {
        return GitIndexReader.findFileStatsByPath(jdbi, filePath);
    }

    public static int countClasses(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COUNT(*) FROM classes "
                + "WHERE lifecycle = 'current' AND origin != 'orphan_output'");
    }

    public static int sumSourceTokens(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COALESCE(SUM(source_tokens), 0) FROM classes "
                + "WHERE lifecycle = 'current' AND origin != 'orphan_output'");
    }

    public static int countBeans(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COUNT(*) FROM beans b JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'");
    }

    public static int countCommits(Jdbi jdbi) {
        return countQuery(jdbi, "SELECT COUNT(*) FROM git_commits");
    }

    public static Map<String, Integer> countBeansByScope(Jdbi jdbi) {
        return groupCountQuery(jdbi, "SELECT b.scope, COUNT(*) as cnt FROM beans b "
                + "JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output' "
                + "GROUP BY b.scope ORDER BY cnt DESC");
    }

    public static Map<String, Integer> countBeansByKind(Jdbi jdbi) {
        return groupCountQuery(jdbi, "SELECT b.kind, COUNT(*) as cnt FROM beans b "
                + "JOIN classes c ON c.id = b.class_id "
                + "WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output' "
                + "GROUP BY b.kind ORDER BY cnt DESC");
    }

    public static List<InjectionPointRecord> findUnsatisfiedInjectionPoints(Jdbi jdbi) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.resolution_status = 'UNSATISFIED' "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list());
    }

    public static List<InjectionPointRecord> findAmbiguousInjectionPoints(Jdbi jdbi) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.resolution_status = 'AMBIGUOUS' "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list());
    }

    public static List<InjectionPointRecord> findUnknownInjectionPoints(Jdbi jdbi) {
        return findInjectionPointsByStatus(jdbi, ResolutionStatus.UNKNOWN);
    }

    public static List<InjectionPointRecord> findContextRequiredInjectionPoints(Jdbi jdbi) {
        return findInjectionPointsByStatus(jdbi, ResolutionStatus.CONTEXT_REQUIRED);
    }

    public static List<InjectionPointRecord> findUnsupportedInjectionPoints(Jdbi jdbi) {
        return findInjectionPointsByStatus(jdbi, ResolutionStatus.UNSUPPORTED_MECHANISM);
    }

    private static List<InjectionPointRecord> findInjectionPointsByStatus(
            Jdbi jdbi, ResolutionStatus status) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT ip.* FROM injection_points ip "
                                + "JOIN beans b ON b.id = ip.bean_id "
                                + "JOIN classes c ON c.id = b.class_id "
                                + "WHERE ip.resolution_status = :status "
                                + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                        .bind("status", status.name())
                        .map((rs, ctx) -> mapInjectionPoint(rs))
                        .list());
    }

    public static List<Map.Entry<Integer, Integer>> findMostDependedOn(Jdbi jdbi, int limit) {
        return findArchitectureHubs(jdbi).stream()
                .limit(limit)
                .map(hub -> Map.entry(hub.classId(), hub.totalDependents()))
                .toList();
    }

    public static List<ArchitectureHub> findArchitectureHubs(Jdbi jdbi) {
        return jdbi.withHandle(h -> h.createQuery("""
                        SELECT d.to_class_id,
                               COUNT(DISTINCT source.id) AS total_count,
                               COUNT(DISTINCT CASE WHEN source.origin = 'source'
                                                   THEN source.id END) AS source_count,
                               COUNT(DISTINCT CASE WHEN source.origin = 'generated'
                                                   THEN source.id END) AS generated_count,
                               COUNT(DISTINCT CASE WHEN source.source_set IS NULL
                                                       OR source.source_set != 'test'
                                                   THEN source.id END) AS production_count,
                               COUNT(DISTINCT CASE WHEN source.source_set = 'test'
                                                   THEN source.id END) AS test_count
                        FROM dependencies d
                        JOIN classes source ON source.id = d.from_class_id
                        JOIN classes target ON target.id = d.to_class_id
                        WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                          AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                        GROUP BY d.to_class_id
                        ORDER BY total_count DESC, d.to_class_id""")
                .map((rs, ctx) -> new ArchitectureHub(
                        rs.getInt("to_class_id"), rs.getInt("total_count"),
                        rs.getInt("source_count"), rs.getInt("generated_count"),
                        rs.getInt("production_count"), rs.getInt("test_count")))
                .list());
    }

    public static int countDependents(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(DISTINCT d.from_class_id) FROM dependencies d
                        JOIN classes source ON source.id = d.from_class_id
                        WHERE d.to_class_id = :classId
                          AND source.lifecycle = 'current' AND source.origin != 'orphan_output'""")
                        .bind("classId", classId)
                        .mapTo(Integer.class)
                        .one());
    }

    public static int countDependencies(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT COUNT(DISTINCT d.to_class_id) FROM dependencies d
                        JOIN classes target ON target.id = d.to_class_id
                        WHERE d.from_class_id = :classId
                          AND target.lifecycle = 'current' AND target.origin != 'orphan_output'""")
                        .bind("classId", classId)
                        .mapTo(Integer.class)
                        .one());
    }

    public static int countDependencyEdges(Jdbi jdbi, int classId, boolean inbound) {
        String endpoint = inbound ? "to_class_id" : "from_class_id";
        String related = inbound ? "from_class_id" : "to_class_id";
        return jdbi.withHandle(h -> h.createQuery("SELECT COALESCE(SUM(d.occurrence_count), 0) "
                        + "FROM dependencies d JOIN classes c ON c.id = d." + related + " "
                        + "WHERE d." + endpoint + " = :classId "
                        + "AND c.lifecycle = 'current' AND c.origin != 'orphan_output'")
                .bind("classId", classId)
                .mapTo(Integer.class)
                .one());
    }

    public static List<DependencyBreakdown> dependencyBreakdown(
            Jdbi jdbi, int classId, boolean inbound) {
        String endpoint = inbound ? "to_class_id" : "from_class_id";
        String related = inbound ? "from_class_id" : "to_class_id";
        return jdbi.withHandle(h -> h.createQuery("SELECT c.origin, "
                        + "COUNT(DISTINCT d." + related + ") AS class_count, "
                        + "SUM(d.occurrence_count) AS edge_count "
                        + "FROM dependencies d JOIN classes c ON c.id = d." + related + " "
                        + "WHERE d." + endpoint + " = :classId AND c.lifecycle = 'current' "
                        + "AND c.origin != 'orphan_output' "
                        + "GROUP BY c.origin ORDER BY c.origin")
                .bind("classId", classId)
                .map((rs, ctx) -> new DependencyBreakdown(rs.getString("origin"),
                        rs.getInt("class_count"), rs.getInt("edge_count")))
                .list());
    }

    private static int countQuery(Jdbi jdbi, String sql) {
        return jdbi.withHandle(h ->
                h.createQuery(sql).mapTo(Integer.class).one());
    }

    private static Map<String, Integer> groupCountQuery(Jdbi jdbi, String sql) {
        return jdbi.withHandle(h -> {
            Map<String, Integer> result = new LinkedHashMap<>();
            h.createQuery(sql)
                    .map((rs, ctx) -> Map.entry(rs.getString(1), rs.getInt(2)))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    public static List<ExternalDepRecord> findExternalDeps(Jdbi jdbi, int classId) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT * FROM class_external_deps WHERE class_id = :classId ORDER BY usage_kind, external_type")
                        .bind("classId", classId)
                        .map((rs, ctx) -> new ExternalDepRecord(
                                rs.getInt("class_id"), rs.getString("external_type"), rs.getString("usage_kind")))
                        .list());
    }

    public static List<Map.Entry<String, Integer>> findExternalDepsByLibrary(Jdbi jdbi, int limit) {
        return jdbi.withHandle(h ->
                h.createQuery("""
                        SELECT substr(external_type, 1, instr(substr(external_type, instr(external_type, '.') + 1), '.') + instr(external_type, '.') - 1) as library,
                               COUNT(DISTINCT class_id) as class_count
                        FROM class_external_deps
                        GROUP BY library
                        ORDER BY class_count DESC
                        LIMIT :limit""")
                        .bind("limit", limit)
                        .map((rs, ctx) -> {
                            String lib = rs.getString("library");
                            return (lib != null && !lib.isEmpty()) ? Map.entry(lib, rs.getInt("class_count")) : null;
                        })
                        .list()
                        .stream().filter(Objects::nonNull).toList());
    }

    public static List<Map.Entry<Integer, String>> findClassesUsingType(Jdbi jdbi, String typePattern) {
        return jdbi.withHandle(h ->
                h.createQuery("SELECT DISTINCT ced.class_id, c.class_name FROM class_external_deps ced JOIN classes c ON ced.class_id = c.id WHERE ced.external_type LIKE :pattern ORDER BY c.class_name")
                        .bind("pattern", typePattern.replace("*", "%"))
                        .map((rs, ctx) -> Map.entry(rs.getInt("class_id"), rs.getString("class_name")))
                        .list());
    }

    public static boolean hasExternalDeps(Jdbi jdbi) {
        try {
            return countQuery(jdbi, "SELECT COUNT(*) FROM class_external_deps") > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<CdiProblem> findCdiProblems(Jdbi jdbi) {
        try {
            return jdbi.withHandle(h ->
                    h.createQuery("SELECT * FROM cdi_problems ORDER BY id")
                            .map((rs, ctx) -> new CdiProblem(
                                    rs.getInt("id"),
                                    rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                                    rs.getString("class_name"),
                                    rs.getString("problem_type"),
                                    rs.getString("message")))
                            .list());
        } catch (Exception e) {
            return List.of();
        }
    }

    public static boolean hasGitData(Jdbi jdbi) {
        try {
            return GitIndexReader.hasGitData(jdbi);
        } catch (Exception e) {
            return false;
        }
    }

    private static GitFileStats mapGitFileStats(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GitFileStats(
                rs.getInt("id"), rs.getString("file_path"),
                rs.getObject("class_id") != null ? rs.getInt("class_id") : null,
                rs.getInt("commit_count"), rs.getString("last_modified"),
                rs.getString("last_author"), rs.getString("first_commit"),
                rs.getInt("distinct_authors"));
    }

    static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
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
                        emptyToNull(Objects.toString(value.get("parameterType"), "")));
            }).toList();
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static List<ResolutionCandidate> candidatesFromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
