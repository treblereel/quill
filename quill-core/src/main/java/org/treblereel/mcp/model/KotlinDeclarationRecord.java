package org.treblereel.mcp.model;

import java.util.List;

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
        boolean synthetic,
        List<KotlinValueParameter> valueParameters, KotlinDeclarationTypes declarationTypes) {
    public KotlinDeclarationRecord {
        // null means metadata unavailable; an empty list means a known zero-parameter callable.
        valueParameters = valueParameters == null ? null : List.copyOf(valueParameters);
    }

    public KotlinDeclarationRecord(int classId, String kind, String name, String jvmName,
            String descriptor, String semanticModel, boolean isSuspend, boolean extension,
            boolean hasDefaultParameters, boolean mutable, boolean lateinit,
            boolean delegated, boolean synthetic, List<KotlinValueParameter> valueParameters) {
        this(classId, kind, name, jvmName, descriptor, semanticModel, isSuspend, extension,
                hasDefaultParameters, mutable, lateinit, delegated, synthetic, valueParameters, null);
    }

    public KotlinDeclarationRecord(int classId, String kind, String name, String jvmName,
            String descriptor, String semanticModel, boolean isSuspend, boolean extension,
            boolean hasDefaultParameters, boolean mutable, boolean lateinit,
            boolean delegated, boolean synthetic) {
        this(classId, kind, name, jvmName, descriptor, semanticModel, isSuspend, extension,
                hasDefaultParameters, mutable, lateinit, delegated, synthetic, null);
    }
}
