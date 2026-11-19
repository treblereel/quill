package org.treblereel.mcp.model;

import java.util.Map;

/**
 * A direct or meta-annotation associated with an indexed application class. {@code values}
 * holds the explicitly written members of a direct annotation; defaults are not recorded.
 */
public record ClassAnnotationRecord(
        int classId,
        String annotationName,
        boolean direct,
        String viaAnnotation,
        Map<String, Object> values) {

    public ClassAnnotationRecord {
        // Sorted so serialized indexes stay deterministic.
        values = values == null || values.isEmpty() ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(values));
    }

    public ClassAnnotationRecord(int classId, String annotationName, boolean direct, String viaAnnotation) {
        this(classId, annotationName, direct, viaAnnotation, Map.of());
    }
}
