package org.treblereel.mcp.model;

/** A direct or meta-annotation associated with an indexed application class. */
public record ClassAnnotationRecord(
        int classId,
        String annotationName,
        boolean direct,
        String viaAnnotation) {}
