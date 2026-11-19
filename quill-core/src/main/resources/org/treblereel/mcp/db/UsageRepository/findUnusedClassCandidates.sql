WITH incoming AS (
    SELECT d.to_class_id,
           COUNT(DISTINCT d.from_class_id) AS class_count,
           COALESCE(SUM(d.occurrence_count), 0) AS occurrence_count,
           COUNT(DISTINCT CASE
               WHEN COALESCE(source.source_set, 'main') = 'test'
               THEN source.id END) AS test_count,
           COUNT(DISTINCT CASE
               WHEN COALESCE(source.source_set, 'main') != 'test'
               THEN source.id END) AS production_count
    FROM dependencies d
    JOIN classes source ON source.id = d.from_class_id
    WHERE d.from_class_id != d.to_class_id
      AND source.lifecycle = 'current'
      AND source.origin != 'orphan_output'
    GROUP BY d.to_class_id
), hierarchy_edges AS (
    SELECT child.id AS child_id, child.superclass AS target_name
    FROM classes child
    WHERE child.superclass IS NOT NULL
      AND child.lifecycle = 'current'
      AND child.origin != 'orphan_output'
    UNION ALL
    SELECT child.id AS child_id, interface.value AS target_name
    FROM classes child, json_each(child.interfaces) interface
    WHERE child.lifecycle = 'current'
      AND child.origin != 'orphan_output'
), hierarchy AS (
    SELECT target_name, COUNT(DISTINCT child_id) AS user_count
    FROM hierarchy_edges
    GROUP BY target_name
), annotations AS (
    SELECT class_id, COUNT(*) AS annotation_count
    FROM class_annotations
    WHERE direct = 1
    GROUP BY class_id
), main_methods AS (
    SELECT DISTINCT class_id
    FROM class_members
    WHERE kind = 'METHOD' AND name = 'main'
      AND modifiers LIKE '%static%'
      AND parameter_types = '["java.lang.String[]"]'
)
SELECT c.*,
       COALESCE(incoming.class_count, 0) AS inbound_class_count,
       COALESCE(incoming.occurrence_count, 0) AS inbound_occurrence_count,
       COALESCE(incoming.production_count, 0) AS production_inbound_count,
       COALESCE(incoming.test_count, 0) AS test_inbound_count,
       COALESCE(hierarchy.user_count, 0) AS hierarchy_user_count,
       COALESCE(annotations.annotation_count, 0) AS annotation_count,
       CASE WHEN main_methods.class_id IS NULL THEN 0 ELSE 1 END AS has_main
FROM classes c
LEFT JOIN incoming ON incoming.to_class_id = c.id
LEFT JOIN hierarchy ON hierarchy.target_name = c.class_name
LEFT JOIN annotations ON annotations.class_id = c.id
LEFT JOIN main_methods ON main_methods.class_id = c.id
WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
ORDER BY c.class_name, c.module, c.source_set, c.id
