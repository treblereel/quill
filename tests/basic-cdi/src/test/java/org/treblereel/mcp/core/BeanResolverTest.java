package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.fixture.*;
import org.treblereel.mcp.model.*;

class BeanResolverTest {

    Index index;

    @BeforeEach
    void setUp() throws Exception {
        Indexer indexer = new Indexer();
        for (Class<?> cls : List.of(PaymentService.class, StripePaymentService.class,
                MockPaymentService.class, OrderService.class, OrderDTO.class, Premium.class)) {
            indexer.indexClass(cls);
        }
        index = indexer.complete();
    }

    @Test
    void resolvesFindsBeans() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        assertFalse(result.beans().isEmpty());
        assertTrue(result.beans().stream()
                .anyMatch(b -> b.kind().equals("CLASS") && b.scope().contains("ApplicationScoped")));
    }

    @Test
    void resolvesInjectionPoints() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        assertFalse(result.injectionPoints().isEmpty());
        assertTrue(result.injectionPoints().stream()
                .anyMatch(ip -> ip.targetType().contains("PaymentService")));
    }

    @Test
    void detectsAlternative() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        assertTrue(result.beans().stream().anyMatch(BeanRecord::isAlternative));
    }

    @Test
    void buildsDependencyEdges() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        assertFalse(result.dependencies().isEmpty());
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.kind().equals("CDI_INJECT")));
    }
}
