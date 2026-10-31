WITH annotation_names(name) AS (
    SELECT a.annotation_name
    FROM class_annotations a JOIN classes c ON c.id = a.class_id
    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
    UNION
    SELECT CAST(annotation.value AS TEXT)
    FROM class_members m
    JOIN classes c ON c.id = m.class_id
    JOIN json_each(m.annotations) annotation
    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
      AND json_array_length(m.annotation_details) = 0
    UNION
    SELECT json_extract(detail.value, '$.annotationName')
    FROM class_members m
    JOIN classes c ON c.id = m.class_id
    JOIN json_each(m.annotation_details) detail
    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
)
SELECT DISTINCT name FROM annotation_names
WHERE name = :name OR name LIKE :suffix
ORDER BY CASE WHEN name = :name THEN 0 ELSE 1 END, name
