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
import org.treblereel.mcp.model.ResolutionStatus;

class ApplicationContextResolverTest {

    @Test
    void excludesBeansOutsideOwnerApplicationClasspath() {
        List<ClassRecord> classes = List.of(
                cls("example.Consumer", "app-one"),
                cls("example.OneService", "app-one"),
                cls("example.TwoService", "app-two"));
        List<BeanRecord> beans = List.of(
                bean(1, 1, "example.Consumer", false),
                bean(2, 2, "example.Service", false),
                bean(3, 3, "example.Service", false));
        InjectionPointRecord point = InjectionPointRecord.staticAnalysis(1, 1, "FIELD",
                "example.Service", List.of("@Default"), "service", null, true,
                InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined = ApplicationContextResolver.refine(
                List.of(point), beans, classes, List.of(), List.of(
                        context("app-one", "app-one", 0),
                        context("app-two", "app-two", 0))).getFirst();

        assertEquals(ResolutionStatus.RESOLVED, refined.resolutionStatus());
        assertEquals(2, refined.resolvedBeanId());
        assertEquals("UNIQUE_CONTEXT_CANDIDATE", refined.resolutionReason());
    }

    @Test
    void reportsContextRequiredWhenSharedModuleResolvesDifferentlyPerApplication() {
        List<ClassRecord> classes = List.of(
                cls("example.Consumer", "shared"),
                cls("example.OneService", "app-one"),
                cls("example.TwoService", "app-two"));
        List<BeanRecord> beans = List.of(
                bean(1, 1, "example.Consumer", false),
                bean(2, 2, "example.Service", false),
                bean(3, 3, "example.Service", false));
        InjectionPointRecord point = InjectionPointRecord.staticAnalysis(1, 1, "FIELD",
                "example.Service", List.of("@Default"), "service", null, true,
                InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined = ApplicationContextResolver.refine(
                List.of(point), beans, classes, List.of(), List.of(
                        context("app-one", "app-one", 0), context("app-one", "shared", 1),
                        context("app-two", "app-two", 0), context("app-two", "shared", 1),
                        context("shared", "shared", 0))).getFirst();

        assertEquals(ResolutionStatus.CONTEXT_REQUIRED, refined.resolutionStatus());
        assertNull(refined.resolvedBeanId());
    }

    @Test
    void ignoresInvisibleSpringPrimaryCandidate() {
        List<ClassRecord> classes = List.of(
                cls("example.Consumer", "app-one"),
                cls("example.LocalService", "app-one"),
                cls("example.RemotePrimary", "app-two"));
        List<BeanRecord> beans = List.of(
                bean(1, 1, "example.Consumer", false),
                bean(2, 2, "example.Service", false),
                bean(3, 3, "example.Service", true));
        InjectionPointRecord point = InjectionPointRecord.staticAnalysis(1, 1, "FIELD",
                "example.Service", List.of("@Default"), "service", 3, false,
                InjectionPointRecord.STATIC_SPRING);

        InjectionPointRecord refined = ApplicationContextResolver.refine(
                List.of(point), beans, classes, List.of(), List.of(
                        context("app-one", "app-one", 0),
                        context("app-two", "app-two", 0))).getFirst();

        assertEquals(2, refined.resolvedBeanId());
    }

    @Test
    void regularBeanSuppressesDefaultBeanInsideApplicationContext() {
        List<ClassRecord> classes = List.of(
                cls("example.Consumer", "app"),
                cls("example.FallbackService", "shared"),
                cls("example.RealService", "app"));
        List<BeanRecord> beans = List.of(
                bean(1, 1, "example.Consumer", false),
                defaultBean(2, 2, "example.Service"),
                bean(3, 3, "example.Service", false));
        InjectionPointRecord point = InjectionPointRecord.staticAnalysis(1, 1, "FIELD",
                "example.Service", List.of("@Default"), "service", null, true,
                InjectionPointRecord.STATIC_CDI);

        InjectionPointRecord refined = ApplicationContextResolver.refine(
                List.of(point), beans, classes, List.of(), List.of(
                        context("app", "app", 0), context("app", "shared", 1))).getFirst();

        assertEquals(ResolutionStatus.RESOLVED, refined.resolutionStatus());
        assertEquals(3, refined.resolvedBeanId());
        assertTrue(refined.resolutionTrace().candidates().stream()
                .anyMatch(candidate -> candidate.beanId() == 2
                        && "DEFAULT_BEAN_SUPPRESSED".equals(candidate.reason())));
    }

    private static ModuleClasspathRecord context(String application, String visible, int distance) {
        return new ModuleClasspathRecord(application, visible, distance,
                distance == 0 ? "self" : "project_dependency");
    }

    private static ClassRecord cls(String name, String module) {
        return new ClassRecord(0, name, "CLASS", "java.lang.Object", List.of(),
                module + "/src/main/java/" + name.replace('.', '/') + ".java", 1, true, 10,
                null, "source", "current", module, "main");
    }

    private static BeanRecord bean(int id, int classId, String type, boolean primary) {
        return new BeanRecord(id, classId, "CLASS", "@Singleton", List.of("@Default"),
                List.of(), primary, primary ? 0 : null, null, null, null, List.of(type));
    }

    private static BeanRecord defaultBean(int id, int classId, String type) {
        return new BeanRecord(id, classId, "CLASS", "@Singleton", List.of("@Default"),
                List.of(), false, true, null, null, null, null, List.of(type));
    }
}
