package org.treblereel.mcp.model;

import java.util.List;

/** Aggregated bytecode access to an application field. */
public record FieldAccessRecord(
        int fromClassId,
        String fromMethod,
        String fromDescriptor,
        int toClassId,
        String fieldName,
        String fieldDescriptor,
        String accessKind,
        int occurrenceCount,
        List<Integer> evidenceLines) {

    public FieldAccessRecord {
        evidenceLines = evidenceLines == null ? List.of() : List.copyOf(evidenceLines);
    }
}
