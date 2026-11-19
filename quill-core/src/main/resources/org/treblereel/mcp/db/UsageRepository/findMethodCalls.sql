SELECT mc.*,
       COALESCE(me.caller_branch_count, 0) AS caller_branch_count,
       COALESCE(me.caller_exception_handler_count, 0) AS caller_exception_handler_count,
       COALESCE(me.caller_control_flow_edges, '[]') AS caller_control_flow_edges,
       COALESCE(me.caller_async_boundaries, '[]') AS caller_async_boundaries,
       COALESCE(me.caller_external_calls, '[]') AS caller_external_calls, source.class_name AS from_class,
       source.source_file AS from_source,
       source.source_line AS from_source_line,
       source.origin AS from_origin, source.module AS from_module,
       source.source_tokens AS from_source_tokens,
       target.class_name AS to_class,
       target.source_file AS to_source,
       target.source_line AS to_source_line,
       target.origin AS to_origin, target.module AS to_module,
       target.source_tokens AS to_source_tokens
FROM method_calls mc
LEFT JOIN method_evidence me ON me.from_class_id = mc.from_class_id
    AND me.from_method = mc.from_method AND me.from_descriptor = mc.from_descriptor
JOIN classes source ON source.id = mc.from_class_id
JOIN classes target ON target.id = mc.to_class_id
WHERE
