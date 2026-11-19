package org.treblereel.mcp.model;

import java.util.List;

/** A CDI or Spring bean discovered in a dependency JAR, outside application metrics. */
public record ExternalBeanRecord(
        int id,
        String className,
        String kind,
        String scope,
        List<String> qualifiers,
        List<String> stereotypes,
        boolean alternative,
        boolean defaultBean,
        Integer priority,
        List<String> profiles,
        String memberName,
        List<String> beanTypes,
        String framework,
        String artifact,
        String jarPath,
        List<ExternalInjectionPointRecord> injectionPoints,
        List<String> interceptorBindings) {

    /** A bean that is not an interceptor, or one whose bindings were not read. */
    public ExternalBeanRecord(int id, String className, String kind, String scope, List<String> qualifiers,
            List<String> stereotypes, boolean alternative, boolean defaultBean, Integer priority,
            List<String> profiles, String memberName, List<String> beanTypes, String framework,
            String artifact, String jarPath, List<ExternalInjectionPointRecord> injectionPoints) {
        this(id, className, kind, scope, qualifiers, stereotypes, alternative, defaultBean, priority, profiles,
                memberName, beanTypes, framework, artifact, jarPath, injectionPoints, List.of());
    }

    public ExternalBeanRecord {
        interceptorBindings = interceptorBindings == null ? List.of() : List.copyOf(interceptorBindings);
        qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
        stereotypes = stereotypes == null ? List.of() : List.copyOf(stereotypes);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        beanTypes = beanTypes == null ? List.of() : List.copyOf(beanTypes);
        injectionPoints = injectionPoints == null ? List.of() : List.copyOf(injectionPoints);
    }
}
