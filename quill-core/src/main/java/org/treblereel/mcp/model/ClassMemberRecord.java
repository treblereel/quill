package org.treblereel.mcp.model;

import java.util.List;

/** An indexed field, constructor, or method declared by an application class. */
public record ClassMemberRecord(
        int classId,
        String kind,
        String name,
        String signature,
        String typeName,
        List<String> parameterTypes,
        String modifiers,
        List<String> annotations) {

    public ClassMemberRecord {
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
