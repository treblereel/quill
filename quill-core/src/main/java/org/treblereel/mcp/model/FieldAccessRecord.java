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
        List<Integer> evidenceLines,
        List<Integer> instructionOrdinals) {

    public FieldAccessRecord {
        evidenceLines = evidenceLines == null ? List.of() : List.copyOf(evidenceLines);
        instructionOrdinals = instructionOrdinals == null
                ? List.of() : List.copyOf(instructionOrdinals);
    }

    public FieldAccessRecord(int fromClassId, String fromMethod, String fromDescriptor,
            int toClassId, String fieldName, String fieldDescriptor, String accessKind,
            int occurrenceCount, List<Integer> evidenceLines) {
        this(fromClassId, fromMethod, fromDescriptor, toClassId, fieldName, fieldDescriptor,
                accessKind, occurrenceCount, evidenceLines, List.of());
    }
}
