package org.treblereel.mcp.model;

import java.util.List;

/** Injection metadata owned by a bean loaded from a dependency JAR. */
public record ExternalInjectionPointRecord(
        String kind,
        String targetType,
        List<String> qualifiers,
        String member,
        List<String> annotations,
        String configurationKey,
        String configurationDefault,
        boolean configurationRequired) {

    public ExternalInjectionPointRecord {
        qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }
}
