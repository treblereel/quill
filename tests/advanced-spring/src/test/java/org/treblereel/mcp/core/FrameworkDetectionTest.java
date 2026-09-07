package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.fixture.spring.advanced.*;

class FrameworkDetectionTest {

    @Test
    void springProjectDetectedWithComponentAnnotation() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(StripeGateway.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
    }

    @Test
    void springProjectDetectedWithServiceAnnotation() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(CheckoutService.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
    }

    @Test
    void springProjectDetectedWithRepositoryAnnotation() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(AuditLog.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
    }

    @Test
    void springProjectDetectedWithControllerAnnotation() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(DashboardController.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
    }

    @Test
    void springProjectDetectedWithConfigurationAnnotation() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(InfraConfig.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
    }

    @Test
    void plainClassNotDetectedAsSpring() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(ConnectionPool.class);
        Index index = indexer.complete();
        assertFalse(SpringResolver.isSpringProject(index));
    }

    @Test
    void interfaceAloneNotDetectedAsSpring() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(PaymentGateway.class);
        Index index = indexer.complete();
        assertFalse(SpringResolver.isSpringProject(index));
    }

    @Test
    void emptyIndexNotDetectedAsSpring() throws Exception {
        Indexer indexer = new Indexer();
        Index index = indexer.complete();
        assertFalse(SpringResolver.isSpringProject(index));
    }

    @Test
    void springResolverProducesNonEmptyResult() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(AuditLog.class);
        indexer.indexClass(AuditService.class);
        Index index = indexer.complete();

        var result = SpringResolver.resolve(index);
        assertFalse(result.beans().isEmpty());
        assertFalse(result.injectionPoints().isEmpty());
        assertFalse(result.dependencies().isEmpty());
    }

    @Test
    void cdiResolverProducesNonEmptyResultForCdiProject() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(jakarta.enterprise.context.ApplicationScoped.class);
        indexer.indexClass(jakarta.inject.Inject.class);
        indexer.indexClass(ConnectionPool.class);
        Index index = indexer.complete();
        assertFalse(SpringResolver.isSpringProject(index));
    }

    @Test
    void cdiProjectDetectedWithApplicationScoped() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(CdiScopedBean.class);
        Index index = indexer.complete();
        assertTrue(BeanResolver.isCdiProject(index));
        assertFalse(SpringResolver.isSpringProject(index));
    }

    @Test
    void springOnlyProjectIsNotCdi() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(StripeGateway.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
        assertFalse(BeanResolver.isCdiProject(index));
    }

    @Test
    void mixedFrameworkDetected() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(StripeGateway.class);
        indexer.indexClass(CdiScopedBean.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
        assertTrue(BeanResolver.isCdiProject(index));
    }

    @Test
    void jakartaInjectSingletonIsNotCdiIndicator() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(SingletonSpringService.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index),
                "@Service should be detected as Spring");
        assertFalse(BeanResolver.isCdiProject(index),
                "@Singleton (jakarta.inject) should NOT trigger CDI detection");
    }

    @Test
    void springServiceWithSingletonNotMixed() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(SingletonSpringService.class);
        indexer.indexClass(StripeGateway.class);
        Index index = indexer.complete();
        assertTrue(SpringResolver.isSpringProject(index));
        assertFalse(BeanResolver.isCdiProject(index),
                "Spring project using @Singleton should not be flagged as mixed");
    }

    @Test
    void resolveAllBeansInMixedIndex() throws Exception {
        Indexer indexer = new Indexer();
        for (Class<?> cls : List.of(
                StripeGateway.class, PaypalGateway.class, CheckoutService.class,
                AuditLog.class, AuditService.class, DashboardController.class,
                PaymentGateway.class, ConnectionPool.class, ExternalApiClient.class,
                ReportGenerator.class, NamedDataSource.class, InfraConfig.class)) {
            indexer.indexClass(cls);
        }
        Index index = indexer.complete();

        assertTrue(SpringResolver.isSpringProject(index));
        var result = SpringResolver.resolve(index);

        long classBeans = result.beans().stream().filter(b -> b.kind().equals("CLASS")).count();
        long producers = result.beans().stream().filter(b -> b.kind().equals("PRODUCER_METHOD")).count();
        assertTrue(classBeans >= 9);
        assertTrue(producers >= 2);

        assertFalse(result.injectionPoints().isEmpty());
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.kind().equals("SPRING_INJECT")));
    }
}
