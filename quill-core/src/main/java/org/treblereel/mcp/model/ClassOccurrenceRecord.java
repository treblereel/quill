package org.treblereel.mcp.model;

/** A physical class-file occurrence belonging to one logical indexed class. */
public record ClassOccurrenceRecord(
        int id,
        int classId,
        String className,
        String module,
        String sourceSet,
        String outputDirectory,
        String classFile,
        String sourceFile,
        String origin) {}
