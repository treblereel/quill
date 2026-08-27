package org.treblereel.mcp.model;

public record DependencyRecord(
        int fromClassId,
        int toClassId,
        String kind,
        Integer injectionPointId
) {}
