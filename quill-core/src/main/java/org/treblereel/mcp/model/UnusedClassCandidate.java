package org.treblereel.mcp.model;

/** Indexed evidence used to conservatively identify classes without inbound references. */
public record UnusedClassCandidate(
        ClassRecord classRecord,
        int inboundClassCount,
        int inboundOccurrenceCount,
        int productionInboundClassCount,
        int testInboundClassCount,
        int hierarchyUserCount,
        int directAnnotationCount,
        boolean hasMainMethod) {}
