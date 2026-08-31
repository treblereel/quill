package org.treblereel.mcp.model;

import java.util.List;

public record BeanRecord(
        int id,
        int classId,
        String kind,
        String scope,
        List<String> qualifiers,
        List<String> stereotypes,
        boolean isAlternative,
        Integer priority,
        List<String> profiles,
        Integer declaringClassId,
        String memberName,
        List<String> beanTypes
) {
    public BeanRecord withId(int newId) {
        return new BeanRecord(newId, classId, kind, scope, qualifiers, stereotypes, isAlternative, priority, profiles, declaringClassId, memberName, beanTypes);
    }
}
