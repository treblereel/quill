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
        boolean isDefault,
        Integer priority,
        List<String> profiles,
        Integer declaringClassId,
        String memberName,
        List<String> beanTypes,
        /** Container that discovered the bean: {@code spring} or {@code cdi}; null when unknown. */
        String framework
) {
    public BeanRecord {
        qualifiers = qualifiers == null ? null : List.copyOf(qualifiers);
        stereotypes = stereotypes == null ? null : List.copyOf(stereotypes);
        profiles = profiles == null ? null : List.copyOf(profiles);
        beanTypes = beanTypes == null ? null : List.copyOf(beanTypes);
    }

    public static final String SPRING = "spring";
    public static final String CDI = "cdi";

    public BeanRecord(int id, int classId, String kind, String scope,
            List<String> qualifiers, List<String> stereotypes, boolean isAlternative,
            boolean isDefault, Integer priority, List<String> profiles, Integer declaringClassId,
            String memberName, List<String> beanTypes) {
        this(id, classId, kind, scope, qualifiers, stereotypes, isAlternative, isDefault,
                priority, profiles, declaringClassId, memberName, beanTypes, null);
    }

    public BeanRecord(int id, int classId, String kind, String scope,
            List<String> qualifiers, List<String> stereotypes, boolean isAlternative,
            Integer priority, List<String> profiles, Integer declaringClassId,
            String memberName, List<String> beanTypes) {
        this(id, classId, kind, scope, qualifiers, stereotypes, isAlternative, false,
                priority, profiles, declaringClassId, memberName, beanTypes, null);
    }

    public boolean spring() {
        return SPRING.equals(framework);
    }

    public BeanRecord withFramework(String newFramework) {
        return new BeanRecord(id, classId, kind, scope, qualifiers, stereotypes,
                isAlternative, isDefault, priority, profiles, declaringClassId,
                memberName, beanTypes, newFramework);
    }

    public BeanRecord withId(int newId) {
        return new BeanRecord(newId, classId, kind, scope, qualifiers, stereotypes,
                isAlternative, isDefault, priority, profiles, declaringClassId,
                memberName, beanTypes, framework);
    }
}
