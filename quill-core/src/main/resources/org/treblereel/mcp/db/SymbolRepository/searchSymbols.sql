WITH kotlin_types AS (
    SELECT class_id, kind, name
    FROM kotlin_declarations
    WHERE kind NOT IN ('FUNCTION', 'PROPERTY')
), symbols AS (
    SELECT c.id AS class_id, c.class_name, c.kind AS class_kind,
           c.kind AS symbol_kind, c.class_name AS symbol_name,
           NULL AS signature, NULL AS descriptor, NULL AS type_name,
           '[]' AS parameter_types,
           '' AS modifiers, '[]' AS annotations,
           c.source_file, c.source_line, c.origin, c.module, c.source_set,
           c.source_tokens,
           CASE WHEN kt.class_id IS NOT NULL OR lower(c.source_file) LIKE '%.kt'
                THEN 'kotlin' ELSE 'java' END AS language,
           kt.kind AS semantic_kind, kt.name AS semantic_name,
           0 AS is_suspend, 0 AS is_extension, 0 AS has_default_parameters,
           0 AS is_mutable, 0 AS is_lateinit, 0 AS is_delegated
    FROM classes c
    LEFT JOIN kotlin_types kt ON kt.class_id = c.id
    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
      AND c.class_name LIKE :pattern
    UNION ALL
    SELECT c.id AS class_id, c.class_name, c.kind AS class_kind,
           m.kind AS symbol_kind, m.name AS symbol_name,
           m.signature, m.descriptor, m.type_name, m.parameter_types,
           m.modifiers, m.annotations,
           c.source_file, c.source_line, c.origin, c.module, c.source_set,
           c.source_tokens,
           CASE WHEN kd.class_id IS NOT NULL OR lower(c.source_file) LIKE '%.kt'
                THEN 'kotlin' ELSE 'java' END AS language,
           kd.kind AS semantic_kind, kd.name AS semantic_name,
           COALESCE(kd.is_suspend, 0) AS is_suspend,
           COALESCE(kd.is_extension, 0) AS is_extension,
           COALESCE(kd.has_default_parameters, 0) AS has_default_parameters,
           COALESCE(kd.is_mutable, 0) AS is_mutable,
           COALESCE(kd.is_lateinit, 0) AS is_lateinit,
           COALESCE(kd.is_delegated, 0) AS is_delegated
    FROM class_members m JOIN classes c ON c.id = m.class_id
    LEFT JOIN kotlin_declarations kd ON kd.class_id = m.class_id
      AND ((kd.kind = 'FUNCTION' AND kd.jvm_name = m.name
            AND kd.descriptor = m.descriptor)
           OR (kd.kind = 'PROPERTY' AND kd.jvm_name = m.name
               AND (kd.descriptor = '' OR kd.descriptor = m.descriptor)))
    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
      AND m.modifiers NOT LIKE '%bridge%' AND m.modifiers NOT LIKE '%synthetic%'
      AND (m.name LIKE :pattern OR m.signature LIKE :pattern
           OR kd.name LIKE :pattern)
)
SELECT * FROM symbols WHERE 1 = 1
