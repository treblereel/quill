package org.treblereel.mcp.model;

/** A project module visible from an application module's runtime context. */
public record ModuleClasspathRecord(
        String applicationModule, String visibleModule, int distance, String relation) {}
