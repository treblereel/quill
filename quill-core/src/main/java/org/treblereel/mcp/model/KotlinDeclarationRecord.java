package org.treblereel.mcp.model;

/** Kotlin source declaration linked to its owning indexed JVM class. */
public record KotlinDeclarationRecord(
        int classId,
        String kind,
        String name,
        String jvmName,
        String descriptor,
        String semanticModel,
        boolean isSuspend,
        boolean extension,
        boolean hasDefaultParameters,
        boolean mutable,
        boolean lateinit,
        boolean delegated,
        boolean synthetic) {}
