SELECT c.class_name, 'CLASS' AS kind, c.class_name AS member_name,
       NULL AS signature, NULL AS descriptor, NULL AS parameter_types,
       c.source_file, c.source_line,
       NULL AS semantic_kind, NULL AS semantic_name,
       0 AS is_suspend, 0 AS is_extension, 0 AS has_defaults,
       NULL AS value_parameters,
       CASE WHEN c.source_file = :path OR f.project_path = :path
                  OR f.repository_path = :path THEN 1 ELSE 0 END AS same_file
FROM classes c LEFT JOIN files f ON f.id = c.file_id
WHERE c.lifecycle = 'current'
  AND (c.class_name = :identifier
       OR c.class_name LIKE '%.' || :identifier
       OR c.class_name LIKE '%$' || :identifier)
UNION ALL
SELECT c.class_name, m.kind, m.name, m.signature, m.descriptor,
       m.parameter_types, c.source_file, c.source_line,
       kd.kind AS semantic_kind,
       kd.name AS semantic_name,
       COALESCE(kd.is_suspend, 0) AS is_suspend,
       COALESCE(kd.is_extension, 0) AS is_extension,
       COALESCE(kd.has_default_parameters, 0) AS has_defaults,
       kd.value_parameters,
       CASE WHEN c.source_file = :path OR f.project_path = :path
                  OR f.repository_path = :path THEN 1 ELSE 0 END AS same_file
FROM class_members m JOIN classes c ON c.id = m.class_id
LEFT JOIN files f ON f.id = c.file_id
LEFT JOIN kotlin_declarations kd ON kd.class_id = m.class_id
  AND ((kd.kind = 'CONSTRUCTOR' AND m.kind = 'CONSTRUCTOR'
        AND kd.jvm_name = '<init>' AND kd.descriptor = m.descriptor)
       OR (kd.kind = 'FUNCTION' AND kd.jvm_name = m.name
        AND kd.descriptor = m.descriptor)
       OR (kd.kind = 'PROPERTY' AND kd.jvm_name = m.name
           AND (kd.descriptor = '' OR kd.descriptor = m.descriptor)))
WHERE c.lifecycle = 'current'
  AND (m.name = :identifier OR kd.name = :identifier)
ORDER BY same_file DESC, class_name, kind, signature
LIMIT 200
