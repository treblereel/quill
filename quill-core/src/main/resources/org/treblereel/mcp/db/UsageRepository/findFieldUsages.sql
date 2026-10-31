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
ORDER BY access.to_class_id, access.field_name, access.field_descriptor
