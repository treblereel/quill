package org.treblereel.mcp.model;

import java.util.List;

/** Aggregated bytecode call edge between two application methods. */
public record MethodCallRecord(
        int fromClassId,
        String fromMethod,
        String fromDescriptor,
        int toClassId,
        String toMethod,
        String toDescriptor,
        String invocationKind,
        int occurrenceCount,
        List<Integer> evidenceLines) {

    public MethodCallRecord {
        evidenceLines = evidenceLines == null ? List.of() : List.copyOf(evidenceLines);
    }
}
