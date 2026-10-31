SELECT class_id, kind, name, signature, descriptor, type_name, parameter_types,
       modifiers, annotations, annotation_details
FROM class_members WHERE class_id IN (<classIds>)
  AND (:compilerMethods OR (modifiers NOT LIKE '%bridge%' AND modifiers NOT LIKE '%synthetic%'))
ORDER BY class_id,
         CASE kind WHEN 'FIELD' THEN 0
                   WHEN 'CONSTRUCTOR' THEN 1 ELSE 2 END,
         name, signature
