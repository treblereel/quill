package org.treblereel.mcp.model;

import java.util.Map;

/**
 * Exact location of an annotation declared on a field, method, constructor, or parameter.
 * {@code values} holds the explicitly written annotation members; defaults are not recorded.
 */
public record MemberAnnotationRecord(
        String annotationName,
        String targetKind,
        Integer parameterIndex,
        String parameterName,
        String parameterType,
        Map<String, Object> values) {

    public MemberAnnotationRecord {
        // Sorted so serialized indexes stay deterministic.
        values = values == null || values.isEmpty() ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(values));
    }

    public MemberAnnotationRecord(String annotationName, String targetKind, Integer parameterIndex,
            String parameterName, String parameterType) {
        this(annotationName, targetKind, parameterIndex, parameterName, parameterType, Map.of());
    }
}
