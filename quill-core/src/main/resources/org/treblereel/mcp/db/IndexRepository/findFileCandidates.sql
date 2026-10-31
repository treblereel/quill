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
         repository_path
