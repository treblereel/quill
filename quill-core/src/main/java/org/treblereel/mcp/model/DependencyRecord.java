package org.treblereel.mcp.model;

import java.util.List;

public record DependencyRecord(
        int fromClassId,
        int toClassId,
        String kind,
        Integer injectionPointId,
        int occurrenceCount,
        List<Integer> evidenceLines
) {
    public DependencyRecord {
        evidenceLines = evidenceLines == null ? List.of() : List.copyOf(evidenceLines);
    }

    public DependencyRecord(int fromClassId, int toClassId, String kind, Integer injectionPointId) {
        this(fromClassId, toClassId, kind, injectionPointId, 1, List.of());
    }

    public DependencyRecord(int fromClassId, int toClassId, String kind,
            Integer injectionPointId, int occurrenceCount) {
        this(fromClassId, toClassId, kind, injectionPointId, occurrenceCount, List.of());
    }
}
