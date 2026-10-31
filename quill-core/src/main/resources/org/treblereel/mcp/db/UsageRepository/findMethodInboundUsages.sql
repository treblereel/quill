SELECT calls.to_class_id, calls.to_method, calls.to_descriptor,
       COUNT(DISTINCT calls.from_class_id) AS caller_class_count,
       SUM(calls.occurrence_count) AS occurrence_count
FROM method_calls calls
JOIN classes target ON target.id = calls.to_class_id
JOIN classes source ON source.id = calls.from_class_id
WHERE target.lifecycle = 'current'
  AND target.origin != 'orphan_output'
  AND source.lifecycle = 'current'
  AND source.origin != 'orphan_output'
GROUP BY calls.to_class_id, calls.to_method, calls.to_descriptor
ORDER BY calls.to_class_id, calls.to_method, calls.to_descriptor
