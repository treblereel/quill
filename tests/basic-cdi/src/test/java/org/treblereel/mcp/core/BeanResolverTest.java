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
                MockPaymentService.class, OrderService.class, OrderDTO.class, Premium.class,
                DatabaseConfig.class, DataSourceConsumer.class, ServiceStereotype.class,
                StereotypedService.class, CustomScoped.class, CustomScopedService.class)) {
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

    @Test
    void producerWithExternalReturnTypeNotExcluded() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "DatabaseConfig") && b.kind().equals("CLASS")),
                "DatabaseConfig should be a bean even though it has a @Produces returning javax.sql.DataSource");
    }

    @Test
    void producerMethodWithExternalReturnTypeDiscovered() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> "dataSource".equals(b.memberName()) && b.kind().equals("PRODUCER_METHOD")),
                "@Produces dataSource() should be discovered as PRODUCER_METHOD");
    }

    @Test
    void producerMethodWithInternalReturnTypeAlsoDiscovered() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> "orderDto".equals(b.memberName()) && b.kind().equals("PRODUCER_METHOD")),
                "@Produces orderDto() should be discovered alongside external-type producer");
    }

    @Test
    void databaseConfigPreservesScope() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        var dbConfig = result.beans().stream()
                .filter(b -> hasClassName(result, b, "DatabaseConfig") && b.kind().equals("CLASS"))
                .findFirst().orElseThrow();
        assertEquals("@ApplicationScoped", dbConfig.scope(),
                "DatabaseConfig scope should be @ApplicationScoped, not a fallback default");
    }

    @Test
    void producerMethodPreservesDeclaringClass() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        var dataSourceBean = result.beans().stream()
                .filter(b -> "dataSource".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertNotNull(dataSourceBean.declaringClassId());
        Integer configClassId = result.classNameToId().entrySet().stream()
                .filter(e -> e.getKey().endsWith("DatabaseConfig"))
                .findFirst().orElseThrow().getValue();
        assertEquals(configClassId, dataSourceBean.declaringClassId());
    }

    @Test
    void injectionPointsNotMarkedAmbiguousWhenResolvable() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        var orderServiceIps = result.injectionPoints().stream()
                .filter(ip -> ip.targetType().contains("PaymentService"))
                .toList();
        assertFalse(orderServiceIps.isEmpty());
        for (var ip : orderServiceIps) {
            assertFalse(ip.isAmbiguous() && ip.resolvedBeanId() == null,
                    "Resolvable injection points should not be marked ambiguous with null resolution");
        }
    }

    @Test
    void fallbackProducerResolvesInjectionPoint() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);

        var dataSourceProducer = result.beans().stream()
                .filter(b -> "dataSource".equals(b.memberName()) && "PRODUCER_METHOD".equals(b.kind()))
                .findFirst();
        assertTrue(dataSourceProducer.isPresent(), "Fallback should create dataSource producer bean");

        var consumerBean = result.beans().stream()
                .filter(b -> hasClassName(result, b, "DataSourceConsumer") && b.memberName() == null)
                .findFirst();
        assertTrue(consumerBean.isPresent(), "DataSourceConsumer should be a bean");

        var dsIp = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == consumerBean.get().id()
                        && ip.targetType().contains("DataSource"))
                .findFirst();
        assertTrue(dsIp.isPresent(), "DataSourceConsumer should have a DataSource injection point");
        assertNotNull(dsIp.get().resolvedBeanId(),
                "@Inject DataSource should resolve to fallback producer after re-resolution pass");
        assertEquals(dataSourceProducer.get().id(), dsIp.get().resolvedBeanId(),
                "Should resolve to the dataSource() producer bean");
    }

    @Test
    void fallbackProducerCreatesDependencyEdge() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);

        var consumerBean = result.beans().stream()
                .filter(b -> hasClassName(result, b, "DataSourceConsumer") && b.memberName() == null)
                .findFirst().orElseThrow();

        var dsIp = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == consumerBean.id()
                        && ip.targetType().contains("DataSource"))
                .findFirst().orElseThrow();

        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.injectionPointId() != null
                        && d.injectionPointId() == dsIp.id()
                        && d.kind().equals("CDI_INJECT")),
                "Re-resolution should create a CDI_INJECT dependency edge for the DataSource IP");
    }

    @Test
    void fallbackMarksMultipleMatchingProducersAmbiguous() {
        var beans = List.of(
                new BeanRecord(1, 10, "PRODUCER_METHOD", "@Dependent",
                        List.of("@Default", "@Any"), List.of(), false, null,
                        null, 10, "first", List.of("example.DataSource")),
                new BeanRecord(2, 20, "PRODUCER_METHOD", "@Dependent",
                        List.of("@Default", "@Any"), List.of(), false, null,
                        null, 20, "second", List.of("example.DataSource")),
                new BeanRecord(3, 30, "CLASS", "@Dependent",
                        List.of("@Default", "@Any"), List.of(), false, null,
                        null, null, null, List.of("example.Consumer")));
        var injectionPoints = new java.util.ArrayList<>(List.of(
                new InjectionPointRecord(1, 3, "FIELD", "example.DataSource",
                        List.of("@Default"), "dataSource", null, false)));
        var dependencies = new java.util.ArrayList<DependencyRecord>();

        BeanResolver.reResolveUnresolved(beans, injectionPoints, dependencies);

        assertNull(injectionPoints.get(0).resolvedBeanId());
        assertTrue(injectionPoints.get(0).isAmbiguous(),
                "Two fallback @Default producers must be reported as ambiguous");
        assertTrue(dependencies.isEmpty(), "Ambiguous injection must not create a dependency edge");
    }

    @Test
    void fallbackDefaultInjectionDoesNotMatchCustomQualifiedBean() {
        var beans = List.of(
                new BeanRecord(1, 10, "CLASS", "@Dependent",
                        List.of("@Premium", "@Any"), List.of(), false, null,
                        null, null, null, List.of("example.Service")),
                new BeanRecord(2, 20, "CLASS", "@Dependent",
                        List.of("@Default", "@Any"), List.of(), false, null,
                        null, null, null, List.of("example.Consumer")));
        var injectionPoints = new java.util.ArrayList<>(List.of(
                new InjectionPointRecord(1, 2, "FIELD", "example.Service",
                        List.of("@Default"), "service", null, false)));

        BeanResolver.reResolveUnresolved(
                beans, injectionPoints, new java.util.ArrayList<>());

        assertNull(injectionPoints.get(0).resolvedBeanId());
        assertFalse(injectionPoints.get(0).isAmbiguous(),
                "A custom-only bean is unsatisfied for an unqualified injection point");
    }

    @Test
    void resolvesScopesDeclaredDirectlyAndThroughStereotypes() {
        BeanResolver.ResolutionResult result = BeanResolver.resolve(index);
        BeanRecord stereotyped = result.beans().stream()
                .filter(bean -> hasClassName(result, bean, "StereotypedService"))
                .findFirst().orElseThrow();
        BeanRecord customScoped = result.beans().stream()
                .filter(bean -> hasClassName(result, bean, "CustomScopedService"))
                .findFirst().orElseThrow();

        assertEquals("@ApplicationScoped", stereotyped.scope());
        assertTrue(stereotyped.stereotypes().contains("@ServiceStereotype"));
        assertEquals("@CustomScoped", customScoped.scope());
    }

    @jakarta.enterprise.inject.Stereotype
    @jakarta.enterprise.context.ApplicationScoped
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @interface ServiceStereotype {}

    @ServiceStereotype
    static class StereotypedService {}

    @jakarta.enterprise.context.NormalScope
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
    @interface CustomScoped {}

    @CustomScoped
    static class CustomScopedService {}

    private boolean hasClassName(BeanResolver.ResolutionResult result, BeanRecord bean, String shortName) {
        for (var entry : result.classNameToId().entrySet()) {
            if (entry.getValue() == bean.classId() && entry.getKey().endsWith(shortName)) {
                return true;
            }
        }
        return false;
    }
}
