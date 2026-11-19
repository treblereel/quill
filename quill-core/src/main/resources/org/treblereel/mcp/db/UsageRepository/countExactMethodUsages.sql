SELECT count(*) FROM method_calls mc
JOIN classes source ON source.id = mc.from_class_id
JOIN classes target ON target.id = mc.to_class_id
WHERE source.lifecycle = 'current'
  AND source.origin != 'orphan_output'
  AND target.lifecycle = 'current'
  AND target.origin != 'orphan_output'
  AND mc.to_class_id IN (<ownerIds>) AND mc.to_method = :method
  AND mc.to_descriptor = :descriptor
