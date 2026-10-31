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
