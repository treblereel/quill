package org.treblereel.mcp.model;

public record CoChangeRecord(
        String filePath,
        Integer classId,
        int coChangeCount,
        double couplingRatio
) {}
