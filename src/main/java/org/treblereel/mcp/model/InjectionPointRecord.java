package org.treblereel.mcp.model;

import java.util.List;

public record InjectionPointRecord(
        int id,
        int beanId,
        String kind,
        String targetType,
        List<String> qualifiers,
        String fieldName,
        Integer resolvedBeanId,
        boolean isAmbiguous
) {}
