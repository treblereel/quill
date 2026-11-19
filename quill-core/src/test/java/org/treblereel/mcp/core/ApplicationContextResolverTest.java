package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.treblereel.mcp.model.ModuleClasspathRecord;
import org.treblereel.mcp.model.ResolutionReason;
import org.treblereel.mcp.model.ResolutionStatus;

class ApplicationContextResolverTest {

    @Test
    void excludesBeansOutsideOwnerApplicationClasspath() {
        List<ClassRecord> classes =
                List.of(
                        cls("example.Consumer", "app-one"),
                        cls("example.OneService", "app-one"),
                        cls("example.TwoService", "app-two"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        bean(2, 2, "example.Service", false),
                        bean(3, 3, "example.Service", false));
        InjectionPointRecord point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined =
                ApplicationContextResolver.refine(
                                List.of(point),
                                beans,
                                classes,
                                List.of(),
                                List.of(
                                        context("app-one", "app-one", 0),
                                        context("app-two", "app-two", 0)))
                        .getFirst();

        assertEquals(ResolutionStatus.RESOLVED, refined.resolutionStatus());
        assertEquals(2, refined.resolvedBeanId());
        assertEquals(ResolutionReason.UNIQUE_CONTEXT_CANDIDATE, refined.resolutionReason());
    }

    @Test
    void reportsContextRequiredWhenSharedModuleResolvesDifferentlyPerApplication() {
        List<ClassRecord> classes =
                List.of(
                        cls("example.Consumer", "shared"),
                        cls("example.OneService", "app-one"),
                        cls("example.TwoService", "app-two"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        bean(2, 2, "example.Service", false),
                        bean(3, 3, "example.Service", false));
        InjectionPointRecord point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined =
                ApplicationContextResolver.refine(
                                List.of(point),
                                beans,
                                classes,
                                List.of(),
                                List.of(
                                        context("app-one", "app-one", 0),
                                        context("app-one", "shared", 1),
                                        context("app-two", "app-two", 0),
                                        context("app-two", "shared", 1),
                                        context("shared", "shared", 0)))
                        .getFirst();

        assertEquals(ResolutionStatus.CONTEXT_REQUIRED, refined.resolutionStatus());
        assertNull(refined.resolvedBeanId());
    }

    @Test
    void ignoresInvisibleSpringPrimaryCandidate() {
        List<ClassRecord> classes =
                List.of(
                        cls("example.Consumer", "app-one"),
                        cls("example.LocalService", "app-one"),
                        cls("example.RemotePrimary", "app-two"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        bean(2, 2, "example.Service", false),
                        bean(3, 3, "example.Service", true));
        InjectionPointRecord point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        3,
                        false,
                        InjectionPointRecord.STATIC_SPRING);

        InjectionPointRecord refined =
                ApplicationContextResolver.refine(
                                List.of(point),
                                beans,
                                classes,
                                List.of(),
                                List.of(
                                        context("app-one", "app-one", 0),
                                        context("app-two", "app-two", 0)))
                        .getFirst();

        assertEquals(2, refined.resolvedBeanId());
    }

    @Test
    void uniqueDefaultBeanKeepsDefaultBeanReason() {
        List<ClassRecord> classes =
                List.of(cls("example.Consumer", "app"), cls("example.FallbackService", "app"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        defaultBean(2, 2, "example.Service"));
        InjectionPointRecord point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined =
                ApplicationContextResolver.refine(
                                List.of(point),
                                beans,
                                classes,
                                List.of(),
                                List.of(context("app", "app", 0)))
                        .getFirst();

        assertEquals(2, refined.resolvedBeanId());
        assertEquals(ResolutionReason.DEFAULT_BEAN_FALLBACK_SELECTED, refined.resolutionReason());
        assertTrue(
                refined.resolutionTrace().candidates().stream()
                        .anyMatch(
                                candidate ->
                                        candidate.beanId() == 2
                                                && candidate.reason()
                                                        == ResolutionReason
                                                                .DEFAULT_BEAN_FALLBACK_SELECTED));
    }

    @Test
    void twoDefaultBeansRemainAmbiguous() {
        List<ClassRecord> classes =
                List.of(
                        cls("example.Consumer", "app"),
                        cls("example.FirstFallback", "app"),
                        cls("example.SecondFallback", "app"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        defaultBean(2, 2, "example.Service"),
                        defaultBean(3, 3, "example.Service"));
        InjectionPointRecord point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined =
                ApplicationContextResolver.refine(
                                List.of(point),
                                beans,
                                classes,
                                List.of(),
                                List.of(context("app", "app", 0)))
                        .getFirst();

        assertEquals(ResolutionStatus.AMBIGUOUS, refined.resolutionStatus());
        assertEquals(null, refined.resolvedBeanId());
    }

    @Test
    void regularBeanSuppressesDefaultBeanInsideApplicationContext() {
        List<ClassRecord> classes =
                List.of(
                        cls("example.Consumer", "app"),
                        cls("example.FallbackService", "shared"),
                        cls("example.RealService", "app"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        defaultBean(2, 2, "example.Service"),
                        bean(3, 3, "example.Service", false));
        InjectionPointRecord point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined =
                ApplicationContextResolver.refine(
                                List.of(point),
                                beans,
                                classes,
                                List.of(),
                                List.of(context("app", "app", 0), context("app", "shared", 1)))
                        .getFirst();

        assertEquals(ResolutionStatus.RESOLVED, refined.resolutionStatus());
        assertEquals(3, refined.resolvedBeanId());
        assertEquals(ResolutionReason.UNIQUE_CONTEXT_CANDIDATE, refined.resolutionReason());
        assertTrue(
                refined.resolutionTrace().candidates().stream()
                        .anyMatch(
                                candidate ->
                                        candidate.beanId() == 2
                                                && candidate.reason()
                                                        == ResolutionReason
                                                                .DEFAULT_BEAN_SUPPRESSED));
    }

    @Test
    void moduleRefinementDoesNotReintroduceDecoratorsOrResolveDelegateChain() {
        var classes =
                List.of(
                        cls("example.Consumer", "app"),
                        cls("example.Real", "app"),
                        cls("example.Wrapper", "app"));
        var decorator =
                new BeanRecord(
                        3,
                        3,
                        "DECORATOR",
                        "@Dependent",
                        List.of("@Default"),
                        List.of(),
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of("example.Service"));
        var beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        bean(2, 2, "example.Service", false),
                        decorator);
        var point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_CDI);
        var delegate =
                new InjectionPointRecord(
                        2,
                        3,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "delegate",
                        null,
                        ResolutionStatus.UNSUPPORTED_MECHANISM,
                        InjectionPointRecord.STATIC_CDI,
                        org.treblereel.mcp.model.ResolutionReason.DECORATOR_DELEGATE_CHAIN,
                        org.treblereel.mcp.model.ResolutionConfidence.LOW,
                        List.of(),
                        org.treblereel.mcp.model.ResolutionTrace.EMPTY);
        var result =
                ApplicationContextResolver.refine(
                        List.of(point, delegate),
                        beans,
                        classes,
                        List.of(),
                        List.of(context("app", "app", 0)));
        assertEquals(2, result.getFirst().resolvedBeanId());
        assertTrue(
                result.getFirst().resolutionTrace().candidates().stream()
                        .anyMatch(
                                c ->
                                        c.beanId() == 3
                                                && c.reason()
                                                        == org.treblereel.mcp.model.ResolutionReason
                                                                .NOT_INJECTION_BEAN));
        assertEquals(delegate, result.get(1));
    }

    /**
     * Apollo AQ-016: jdbcUserDetailsManager's DataSource in apollo-portal resolved to a test
     * configuration of apollo-audit-impl (32 main-source points resolved to test beans). A
     * dependency's test sources are on no other module's classpath, and a module's own test sources
     * are not in its production context.
     */
    @Test
    void testSourceBeansServeOnlyTestPointsOfTheirOwnModule() {
        List<ClassRecord> classes =
                List.of(
                        cls("example.Consumer", "app"),
                        testCls("example.AppTestConfig", "app"),
                        testCls("example.LibTestConfig", "lib"),
                        testCls("example.ConsumerTest", "app"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        bean(2, 2, "example.Service", false),
                        bean(3, 3, "example.Service", false),
                        bean(4, 4, "example.ConsumerTest", false));
        InjectionPointRecord production =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_SPRING);
        InjectionPointRecord test =
                InjectionPointRecord.staticAnalysis(
                        2,
                        4,
                        "FIELD",
                        "example.Service",
                        List.of("@Default"),
                        "service",
                        null,
                        true,
                        InjectionPointRecord.STATIC_SPRING);

        List<InjectionPointRecord> refined =
                ApplicationContextResolver.refine(
                        List.of(production, test),
                        beans,
                        classes,
                        List.of(),
                        List.of(
                                context("app", "app", 0),
                                context("app", "lib", 1),
                                context("lib", "lib", 0)));

        assertEquals(
                ResolutionStatus.UNKNOWN,
                refined.get(0).resolutionStatus(),
                refined.get(0).toString());
        assertNull(refined.get(0).resolvedBeanId());
        assertEquals(
                ResolutionStatus.RESOLVED,
                refined.get(1).resolutionStatus(),
                refined.get(1).toString());
        assertEquals(2, refined.get(1).resolvedBeanId());
    }

    /**
     * A lazy provider point keeps resolving against its type argument in each application context.
     */
    @Test
    void aLazyProviderPointResolvesAgainstItsTypeArgumentInContext() {
        List<ClassRecord> classes =
                List.of(cls("example.Consumer", "app"), cls("example.ServiceImpl", "app"));
        List<BeanRecord> beans =
                List.of(
                        bean(1, 1, "example.Consumer", false),
                        bean(2, 2, "example.Service", false));
        InjectionPointRecord point =
                InjectionPointRecord.staticAnalysis(
                        1,
                        1,
                        "FIELD",
                        "org.springframework.beans.factory.ObjectProvider<example.Service>",
                        List.of(),
                        "service",
                        2,
                        false,
                        InjectionPointRecord.STATIC_SPRING);

        InjectionPointRecord refined =
                ApplicationContextResolver.refine(
                                List.of(point),
                                beans,
                                classes,
                                List.of(),
                                List.of(context("app", "app", 0)))
                        .getFirst();

        assertEquals(ResolutionStatus.RESOLVED, refined.resolutionStatus(), refined.toString());
        assertEquals(2, refined.resolvedBeanId());
    }

    private static ClassRecord testCls(String name, String module) {
        return new ClassRecord(
                0,
                name,
                "CLASS",
                "java.lang.Object",
                List.of(),
                module + "/src/test/java/" + name.replace('.', '/') + ".java",
                1,
                true,
                10,
                null,
                "source",
                "current",
                module,
                "test");
    }

    private static ModuleClasspathRecord context(String application, String visible, int distance) {
        return new ModuleClasspathRecord(
                application, visible, distance, distance == 0 ? "self" : "project_dependency");
    }

    private static ClassRecord cls(String name, String module) {
        return new ClassRecord(
                0,
                name,
                "CLASS",
                "java.lang.Object",
                List.of(),
                module + "/src/main/java/" + name.replace('.', '/') + ".java",
                1,
                true,
                10,
                null,
                "source",
                "current",
                module,
                "main");
    }

    private static BeanRecord bean(int id, int classId, String type, boolean primary) {
        return new BeanRecord(
                id,
                classId,
                "CLASS",
                "@Singleton",
                List.of("@Default"),
                List.of(),
                primary,
                primary ? 0 : null,
                null,
                null,
                null,
                List.of(type));
    }

    private static BeanRecord defaultBean(int id, int classId, String type) {
        return new BeanRecord(
                id,
                classId,
                "CLASS",
                "@Singleton",
                List.of("@Default"),
                List.of(),
                false,
                true,
                null,
                null,
                null,
                null,
                List.of(type));
    }
}
