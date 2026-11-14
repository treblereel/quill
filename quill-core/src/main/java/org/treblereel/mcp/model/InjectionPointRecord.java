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
        ResolutionStatus resolutionStatus,
        String resolutionStrategy,
        ResolutionReason resolutionReason,
        ResolutionConfidence resolutionConfidence,
        List<String> limitations,
        ResolutionTrace resolutionTrace
) {
    public static final String STATIC_CDI = "STATIC_CDI";
    public static final String STATIC_SPRING = "STATIC_SPRING";

    public InjectionPointRecord {
        qualifiers = qualifiers == null ? List.of() : List.copyOf(qualifiers);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        resolutionTrace = resolutionTrace == null ? ResolutionTrace.EMPTY : resolutionTrace;
    }

    public static InjectionPointRecord staticAnalysis(int id, int beanId, String kind,
            String targetType, List<String> qualifiers, String fieldName,
            Integer resolvedBeanId, boolean ambiguous, String strategy) {
        ResolutionStatus status;
        ResolutionReason reason;
        ResolutionConfidence confidence;
        if (resolvedBeanId != null) {
            status = ResolutionStatus.RESOLVED;
            reason = ResolutionReason.UNIQUE_STATIC_CANDIDATE;
            confidence = ResolutionConfidence.HIGH;
        } else if (ambiguous) {
            status = ResolutionStatus.AMBIGUOUS;
            reason = ResolutionReason.MULTIPLE_STATIC_CANDIDATES;
            confidence = ResolutionConfidence.MEDIUM;
        } else {
            status = ResolutionStatus.UNKNOWN;
            reason = ResolutionReason.NO_STATIC_CANDIDATE;
            confidence = ResolutionConfidence.LOW;
        }
        return new InjectionPointRecord(id, beanId, kind, targetType, qualifiers, fieldName,
                resolvedBeanId, status, strategy, reason, confidence,
                List.of(ResolutionMessages.STATIC_MODEL_LIMITATION), ResolutionTrace.EMPTY);
    }

    /** Container-provided beans every CDI implementation supplies (CDI 4.1 section 3.10). */
    private static final java.util.Set<String> CDI_BUILTIN_TYPES = java.util.Set.of(
            "jakarta.enterprise.event.Event", "jakarta.enterprise.inject.Instance",
            "jakarta.inject.Provider", "jakarta.enterprise.inject.spi.InjectionPoint",
            "jakarta.enterprise.inject.spi.BeanManager", "jakarta.enterprise.inject.spi.BeanContainer",
            "jakarta.enterprise.inject.spi.EventMetadata", "jakarta.enterprise.inject.spi.Bean",
            "jakarta.enterprise.context.control.RequestContextController",
            "jakarta.enterprise.inject.spi.InterceptionFactory");

    public boolean isCdiBuiltin() {
        if (!STATIC_CDI.equals(resolutionStrategy) || targetType == null) return false;
        int generic = targetType.indexOf('<');
        return CDI_BUILTIN_TYPES.contains(generic < 0 ? targetType : targetType.substring(0, generic));
    }

    /**
     * Types a Spring context injects without a bean definition: AbstractApplicationContext#prepareBeanFactory
     * (spring-context) registers BeanFactory, ResourceLoader, ApplicationEventPublisher and ApplicationContext via
     * registerResolvableDependency, which also satisfies their subtypes the context or factory implements; a web
     * context adds the request/session objects (WebApplicationContextUtils.registerWebApplicationScopes).
     */
    private static final java.util.Map<String, String> SPRING_RESOLVABLE_TYPES = springResolvableTypes();

    private static java.util.Map<String, String> springResolvableTypes() {
        String context = "the ApplicationContext itself, registered by AbstractApplicationContext#prepareBeanFactory "
                + "(spring-context) via beanFactory.registerResolvableDependency";
        String factory = "the context's BeanFactory, registered by AbstractApplicationContext#prepareBeanFactory "
                + "(spring-context) via beanFactory.registerResolvableDependency";
        String web = "a request-bound proxy registered by WebApplicationContextUtils.registerWebApplicationScopes "
                + "(spring-web) via registerResolvableDependency; web application contexts only";
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        for (String type : java.util.List.of("org.springframework.context.ApplicationContext",
                "org.springframework.context.ConfigurableApplicationContext",
                "org.springframework.context.ApplicationEventPublisher",
                "org.springframework.core.io.ResourceLoader",
                "org.springframework.core.io.support.ResourcePatternResolver")) types.put(type, context);
        for (String type : java.util.List.of("org.springframework.beans.factory.BeanFactory",
                "org.springframework.beans.factory.ListableBeanFactory",
                "org.springframework.beans.factory.HierarchicalBeanFactory",
                "org.springframework.beans.factory.config.AutowireCapableBeanFactory",
                "org.springframework.beans.factory.config.ConfigurableBeanFactory",
                "org.springframework.beans.factory.config.ConfigurableListableBeanFactory")) types.put(type, factory);
        for (String type : java.util.List.of("jakarta.servlet.ServletRequest", "jakarta.servlet.http.HttpServletRequest",
                "jakarta.servlet.ServletResponse", "jakarta.servlet.http.HttpServletResponse",
                "jakarta.servlet.http.HttpSession", "org.springframework.web.context.request.WebRequest",
                "org.springframework.web.context.request.NativeWebRequest")) types.put(type, web);
        return java.util.Map.copyOf(types);
    }

    /** What supplies a Spring container-provided dependency, or null when the type is not one. */
    public String springResolvableProvider() {
        if (!STATIC_SPRING.equals(resolutionStrategy) || targetType == null) return null;
        int generic = targetType.indexOf('<');
        return SPRING_RESOLVABLE_TYPES.get(generic < 0 ? targetType : targetType.substring(0, generic));
    }

    /** Marks an unresolved Spring container-provided dependency as satisfied, saying what supplies it. */
    public InjectionPointRecord asSpringResolvable() {
        String provider = springResolvableProvider();
        if (provider == null || resolvedBeanId != null
                || resolutionStatus != ResolutionStatus.UNKNOWN && resolutionStatus != ResolutionStatus.UNSATISFIED) return this;
        return new InjectionPointRecord(id, beanId, kind, targetType, qualifiers, fieldName,
                null, ResolutionStatus.RESOLVED, resolutionStrategy, ResolutionReason.BUILTIN_BEAN,
                ResolutionConfidence.HIGH,
                List.of("Supplied by " + provider + "; a resolvable dependency is not a bean definition, "
                        + "so getBeansOfType/getBean do not return it"),
                resolutionTrace);
    }

    /** Marks a built-in injection point as satisfied by the container rather than unknown. */
    public InjectionPointRecord asCdiBuiltin() {
        if (!isCdiBuiltin()) return this;
        return new InjectionPointRecord(id, beanId, kind, targetType, qualifiers, fieldName,
                null, ResolutionStatus.RESOLVED, resolutionStrategy, ResolutionReason.BUILTIN_BEAN,
                ResolutionConfidence.HIGH,
                List.of("Built-in bean supplied by the CDI container; no application bean class exists"),
                resolutionTrace);
    }

    public InjectionPointRecord withId(int newId) {
        return new InjectionPointRecord(newId, beanId, kind, targetType, qualifiers, fieldName,
                resolvedBeanId, resolutionStatus, resolutionStrategy, resolutionReason,
                resolutionConfidence, limitations, resolutionTrace);
    }

    public InjectionPointRecord withResolution(Integer newResolvedBeanId,
            boolean ambiguous, ResolutionTrace trace) {
        InjectionPointRecord resolved = staticAnalysis(id, beanId, kind, targetType,
                qualifiers, fieldName, newResolvedBeanId, ambiguous, resolutionStrategy);
        return new InjectionPointRecord(resolved.id(), resolved.beanId(), resolved.kind(),
                resolved.targetType(), resolved.qualifiers(), resolved.fieldName(),
                resolved.resolvedBeanId(), resolved.resolutionStatus(),
                resolved.resolutionStrategy(), resolved.resolutionReason(),
                resolved.resolutionConfidence(), resolved.limitations(), trace);
    }
}
