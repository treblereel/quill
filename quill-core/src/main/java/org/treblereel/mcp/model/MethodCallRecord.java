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
        List<Integer> evidenceLines,
        List<Integer> instructionOrdinals,
        int callerBranchCount,
        int callerExceptionHandlerCount) {

    public MethodCallRecord {
        evidenceLines = evidenceLines == null ? List.of() : List.copyOf(evidenceLines);
        instructionOrdinals = instructionOrdinals == null
                ? List.of() : List.copyOf(instructionOrdinals);
    }

    public MethodCallRecord(int fromClassId, String fromMethod, String fromDescriptor,
            int toClassId, String toMethod, String toDescriptor, String invocationKind,
            int occurrenceCount, List<Integer> evidenceLines) {
        this(fromClassId, fromMethod, fromDescriptor, toClassId, toMethod, toDescriptor,
                invocationKind, occurrenceCount, evidenceLines, List.of());
    }

    public MethodCallRecord(int fromClassId, String fromMethod, String fromDescriptor,
            int toClassId, String toMethod, String toDescriptor, String invocationKind,
            int occurrenceCount, List<Integer> evidenceLines,
            List<Integer> instructionOrdinals) {
        this(fromClassId, fromMethod, fromDescriptor, toClassId, toMethod, toDescriptor,
                invocationKind, occurrenceCount, evidenceLines, instructionOrdinals, 0, 0);
    }
}
