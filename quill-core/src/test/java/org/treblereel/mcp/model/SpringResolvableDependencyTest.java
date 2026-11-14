package org.treblereel.mcp.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Apollo AQ-004: ApplicationEventPublisher was "unknown, no candidate", so claude stopped looking for its source. */
class SpringResolvableDependencyTest {

    private static InjectionPointRecord point(String type, String strategy, Integer resolved) {
        return InjectionPointRecord.staticAnalysis(1, 1, "CONSTRUCTOR_PARAM", type, List.of(), "<init>",
                resolved, false, strategy);
    }

    @Test
    void theContextSuppliesItsOwnInterfacesWithoutABeanDefinition() {
        InjectionPointRecord marked = point("org.springframework.context.ApplicationEventPublisher",
                InjectionPointRecord.STATIC_SPRING, null).asSpringResolvable();
        assertEquals(ResolutionStatus.RESOLVED, marked.resolutionStatus());
        assertEquals(ResolutionReason.BUILTIN_BEAN, marked.resolutionReason());
        assertNull(marked.resolvedBeanId());
        String note = marked.limitations().getFirst();
        assertTrue(note.contains("prepareBeanFactory") && note.contains("spring-context")
                && note.contains("not a bean definition"), note);
        assertTrue(point("org.springframework.beans.factory.config.ConfigurableListableBeanFactory",
                InjectionPointRecord.STATIC_SPRING, null).asSpringResolvable().limitations().getFirst().contains("BeanFactory"));
    }

    @Test
    void cdiPointsResolvedPointsAndOrdinaryTypesAreUnchanged() {
        InjectionPointRecord cdi = point("org.springframework.context.ApplicationEventPublisher", InjectionPointRecord.STATIC_CDI, null);
        assertSame(cdi, cdi.asSpringResolvable());
        InjectionPointRecord resolved = point("org.springframework.context.ApplicationContext", InjectionPointRecord.STATIC_SPRING, 7);
        assertSame(resolved, resolved.asSpringResolvable());
        InjectionPointRecord ordinary = point("com.example.Service", InjectionPointRecord.STATIC_SPRING, null);
        assertSame(ordinary, ordinary.asSpringResolvable());
    }
}
