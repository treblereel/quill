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
        int callerExceptionHandlerCount,
        List<String> callerControlFlowEdges,
        List<String> callerAsyncBoundaries) {

    public MethodCallRecord {
        evidenceLines = evidenceLines == null ? List.of() : List.copyOf(evidenceLines);
        instructionOrdinals = instructionOrdinals == null
                ? List.of() : List.copyOf(instructionOrdinals);
        callerControlFlowEdges = callerControlFlowEdges == null
                ? List.of() : List.copyOf(callerControlFlowEdges);
        callerAsyncBoundaries = callerAsyncBoundaries == null
                ? List.of() : List.copyOf(callerAsyncBoundaries);
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
                invocationKind, occurrenceCount, evidenceLines, instructionOrdinals,
                0, 0, List.of(), List.of());
    }

    public MethodCallRecord(int fromClassId, String fromMethod, String fromDescriptor,
            int toClassId, String toMethod, String toDescriptor, String invocationKind,
            int occurrenceCount, List<Integer> evidenceLines, List<Integer> instructionOrdinals,
            int callerBranchCount, int callerExceptionHandlerCount) {
        this(fromClassId, fromMethod, fromDescriptor, toClassId, toMethod, toDescriptor,
                invocationKind, occurrenceCount, evidenceLines, instructionOrdinals,
                callerBranchCount, callerExceptionHandlerCount, List.of(), List.of());
    }

    public MethodCallRecord(int fromClassId, String fromMethod, String fromDescriptor,
            int toClassId, String toMethod, String toDescriptor, String invocationKind,
            int occurrenceCount, List<Integer> evidenceLines, List<Integer> instructionOrdinals,
            int callerBranchCount, int callerExceptionHandlerCount,
            List<String> callerControlFlowEdges) {
        this(fromClassId, fromMethod, fromDescriptor, toClassId, toMethod, toDescriptor,
                invocationKind, occurrenceCount, evidenceLines, instructionOrdinals,
                callerBranchCount, callerExceptionHandlerCount, callerControlFlowEdges,
                List.of());
    }
}
