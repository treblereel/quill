package org.treblereel.mcp.model;

/** A bytecode-discovered reference to a classpath or external resource. */
public record ResourceUsageRecord(
        String resourcePath, String kind, int classId, String className, String member,
        String api, String source, String module, String sourceSet) {}
