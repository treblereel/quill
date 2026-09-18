package org.treblereel.mcp.model;

/** Exact location of an annotation declared on a field, method, constructor, or parameter. */
public record MemberAnnotationRecord(
        String annotationName,
        String targetKind,
        Integer parameterIndex,
        String parameterName,
        String parameterType) {}
