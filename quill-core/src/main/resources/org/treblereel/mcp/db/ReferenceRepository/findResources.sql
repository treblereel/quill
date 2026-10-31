SELECT entry_type, resource_path, file, class_id, class_name, member, api,
       source, module, source_set, kind
FROM (
  SELECT 'definition' entry_type,
         substr(f.project_path,
                instr(f.project_path, '/resources/') + 11) resource_path,
         f.project_path file, NULL class_id, NULL class_name, NULL member,
         NULL api, NULL source, f.module, f.source_set, f.kind, 0 rank
  FROM files f WHERE %s
  UNION ALL
  SELECT 'usage', u.resource_path, NULL, u.class_id, u.class_name, u.member,
         u.api, u.source, u.module, u.source_set, u.kind, 1 rank
  FROM resource_usages u WHERE %s
) refs ORDER BY resource_path, rank, COALESCE(file, class_name)
LIMIT :limit OFFSET :offset
