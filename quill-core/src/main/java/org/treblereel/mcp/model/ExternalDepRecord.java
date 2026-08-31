package org.treblereel.mcp.model;

public record ExternalDepRecord(
        int classId,
        String externalType,
        String usageKind
) {}
