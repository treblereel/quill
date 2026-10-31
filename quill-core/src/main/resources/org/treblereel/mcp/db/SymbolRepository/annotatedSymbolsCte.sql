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
