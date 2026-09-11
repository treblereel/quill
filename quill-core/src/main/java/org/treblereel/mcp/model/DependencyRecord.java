package org.treblereel.mcp.model;

public record DependencyRecord(
        int fromClassId,
        int toClassId,
        String kind,
        Integer injectionPointId,
        int occurrenceCount
) {
    public DependencyRecord(int fromClassId, int toClassId, String kind, Integer injectionPointId) {
        this(fromClassId, toClassId, kind, injectionPointId, 1);
    }
}
