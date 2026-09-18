package org.treblereel.mcp.model;

import java.util.List;

/** An indexed field, constructor, or method declared by an application class. */
public record ClassMemberRecord(
        int classId,
        String kind,
        String name,
        String signature,
        String descriptor,
        String typeName,
        List<String> parameterTypes,
        String modifiers,
        List<String> annotations,
        List<MemberAnnotationRecord> annotationDetails) {

    public ClassMemberRecord {
        parameterTypes = parameterTypes == null ? List.of() : List.copyOf(parameterTypes);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        annotationDetails = annotationDetails == null ? List.of() : List.copyOf(annotationDetails);
        descriptor = descriptor == null ? "" : descriptor;
    }

    public ClassMemberRecord(int classId, String kind, String name, String signature,
            String descriptor, String typeName, List<String> parameterTypes, String modifiers,
            List<String> annotations) {
        this(classId, kind, name, signature, descriptor, typeName,
                parameterTypes, modifiers, annotations, List.of());
    }

    public ClassMemberRecord(int classId, String kind, String name, String signature,
            String typeName, List<String> parameterTypes, String modifiers,
            List<String> annotations) {
        this(classId, kind, name, signature, "", typeName,
                parameterTypes, modifiers, annotations, List.of());
    }
}
