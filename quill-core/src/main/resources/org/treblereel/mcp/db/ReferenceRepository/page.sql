SELECT entry_type, key, kind, file, line, module, source_set,
       class_id, class_name, member, parameter_index, annotation, source
FROM (
    SELECT 'definition' AS entry_type, 0 AS entry_rank,
           d.key, d.kind, d.file, d.line, d.module, d.source_set,
           NULL AS class_id, NULL AS class_name, NULL AS member,
           NULL AS parameter_index, NULL AS annotation, NULL AS source
    FROM configuration_definitions d WHERE %s
    UNION ALL
    SELECT 'usage' AS entry_type, 1 AS entry_rank,
           u.key, u.kind, NULL AS file, NULL AS line, u.module, u.source_set,
           u.class_id, u.class_name, u.member, u.parameter_index,
           u.annotation, u.source
    FROM configuration_usages u WHERE %s
) references_page
ORDER BY key, entry_rank, COALESCE(file, class_name),
         COALESCE(member, ''), COALESCE(parameter_index, -1)
LIMIT :limit OFFSET :offset
