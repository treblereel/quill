package org.treblereel.mcp.model;

public record CdiProblem(
        int id,
        Integer classId,
        String className,
        String problemType,
        String message
) {}
