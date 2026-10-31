WITH kotlin_types AS (
    SELECT class_id FROM kotlin_declarations
    WHERE kind NOT IN ('FUNCTION', 'PROPERTY')
), symbols AS (
    SELECT c.kind AS symbol_kind,
           CASE WHEN kt.class_id IS NOT NULL OR lower(c.source_file) LIKE '%.kt'
                THEN 'kotlin' ELSE 'java' END AS language
    FROM classes c
    LEFT JOIN kotlin_types kt ON kt.class_id = c.id
    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
      AND c.class_name LIKE :pattern
    UNION ALL
    SELECT m.kind AS symbol_kind,
           CASE WHEN kd.class_id IS NOT NULL OR lower(c.source_file) LIKE '%.kt'
                THEN 'kotlin' ELSE 'java' END AS language
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
SELECT count(*) FROM symbols WHERE 1 = 1
