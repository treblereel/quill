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
ORDER BY total_count DESC, d.to_class_id
