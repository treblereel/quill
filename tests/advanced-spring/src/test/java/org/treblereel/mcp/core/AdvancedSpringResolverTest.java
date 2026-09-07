package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.fixture.spring.advanced.*;
import org.treblereel.mcp.model.*;

class AdvancedSpringResolverTest {

    Index index;

    @BeforeEach
    void setUp() throws Exception {
        Indexer indexer = new Indexer();
        for (Class<?> cls : List.of(
                BaseGateway.class, PaymentGateway.class, StripeGateway.class, PaypalGateway.class,
                PaymentProcessor.class, DefaultPaymentHandler.class,
                CheckoutService.class, AuditLog.class, AuditService.class,
                DashboardController.class, ExternalApiClient.class,
                ReportGenerator.class, NamedDataSource.class,
                InfraConfig.class, ConnectionPool.class, PoolConsumer.class,
                BusinessComponent.class, DomainService.class, OrderProcessor.class,
                org.springframework.web.context.annotation.RequestScope.class,
                BaseGatewayConsumer.class,
                LiteModeComponent.class, EnableInfra.class, MetaConfiguredBeans.class)) {
            indexer.indexClass(cls);
        }
        index = indexer.complete();
    }

    // --- Implicit constructor injection (no @Autowired) ---

    @Test
    void implicitSingleConstructorInjection() {
        var result = SpringResolver.resolve(index);
        var dashboard = findBean(result, "DashboardController");
        var ips = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == dashboard.id())
                .toList();
        assertEquals(2, ips.size(), "DashboardController has 2 constructor params");
        assertTrue(ips.stream().allMatch(ip -> ip.kind().equals("CONSTRUCTOR_PARAM")));
    }

    @Test
    void implicitConstructorResolvesParams() {
        var result = SpringResolver.resolve(index);
        var dashboard = findBean(result, "DashboardController");
        var ips = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == dashboard.id())
                .toList();
        assertTrue(ips.stream().allMatch(ip -> ip.resolvedBeanId() != null),
                "Both constructor params should resolve to beans");
    }

    // --- @Controller stereotype ---

    @Test
    void controllerStereotypeDiscovered() {
        var result = SpringResolver.resolve(index);
        var dashboard = findBean(result, "DashboardController");
        assertEquals("CLASS", dashboard.kind());
    }

    // --- Setter injection ---

    @Test
    void setterInjectionDetected() {
        var result = SpringResolver.resolve(index);
        var auditService = findBean(result, "AuditService");
        var ips = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == auditService.id())
                .toList();
        assertEquals(1, ips.size());
        assertEquals("METHOD_PARAM", ips.get(0).kind());
        assertEquals("setAuditLog", ips.get(0).fieldName());
    }

    @Test
    void setterInjectionResolvesToBean() {
        var result = SpringResolver.resolve(index);
        var auditService = findBean(result, "AuditService");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == auditService.id())
                .findFirst().orElseThrow();
        assertNotNull(ip.resolvedBeanId(), "Setter injection of AuditLog should resolve");
    }

    // --- @Qualifier on bean + injection point ---

    @Test
    void qualifierOnBeanDetected() {
        var result = SpringResolver.resolve(index);
        var stripe = findBean(result, "StripeGateway");
        assertTrue(stripe.qualifiers().stream()
                .anyMatch(q -> q.contains("stripe")),
                "StripeGateway should have @Qualifier(\"stripe\")");
    }

    @Test
    void qualifierOnPaypalDetected() {
        var result = SpringResolver.resolve(index);
        var paypal = findBean(result, "PaypalGateway");
        assertTrue(paypal.qualifiers().stream()
                .anyMatch(q -> q.contains("paypal")),
                "PaypalGateway should have @Qualifier(\"paypal\")");
    }

    @Test
    void explicitComponentNameIsAvailableAsQualifier() {
        var result = SpringResolver.resolve(index);
        var paypal = findBean(result, "PaypalGateway");
        assertTrue(paypal.qualifiers().contains("@Qualifier(\"paypalGatewayBean\")"));
        assertFalse(paypal.qualifiers().contains("@Qualifier(\"paypalGateway\")"),
                "Explicit component name replaces the generated default bean name");
    }

    // --- @Named qualifier ---

    @Test
    void namedQualifierDetected() {
        var result = SpringResolver.resolve(index);
        var ds = findBean(result, "NamedDataSource");
        assertTrue(ds.qualifiers().stream()
                .anyMatch(q -> q.contains("primary-db")),
                "NamedDataSource should have @Named(\"primary-db\")");
    }

    // --- Multi-level dependency chain: Dashboard → AuditService → AuditLog ---

    @Test
    void multiLevelDependencyChain() {
        var result = SpringResolver.resolve(index);
        var dashboardClassId = classIdFor(result, "DashboardController");
        var auditServiceClassId = classIdFor(result, "AuditService");
        var auditLogClassId = classIdFor(result, "AuditLog");

        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.fromClassId() == dashboardClassId
                        && d.toClassId() == auditServiceClassId
                        && d.kind().equals("SPRING_INJECT")),
                "Dashboard should depend on AuditService");
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.fromClassId() == auditServiceClassId
                        && d.toClassId() == auditLogClassId
                        && d.kind().equals("SPRING_INJECT")),
                "AuditService should depend on AuditLog");
    }

    // --- @Configuration with @Primary and @Scope on @Bean ---

    @Test
    void configurationProducerMethodsDiscovered() {
        var result = SpringResolver.resolve(index);
        var producers = result.beans().stream()
                .filter(b -> b.kind().equals("PRODUCER_METHOD"))
                .filter(b -> hasClassName(result, b, "InfraConfig"))
                .toList();
        assertEquals(3, producers.size(), "InfraConfig has 3 @Bean methods");
    }

    @Test
    void producerPrimaryDetected() {
        var result = SpringResolver.resolve(index);
        var mainPool = result.beans().stream()
                .filter(b -> "mainPool".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertTrue(mainPool.isAlternative(), "@Primary on mainPool should set isAlternative");
    }

    @Test
    void producerPrototypeScopeDetected() {
        var result = SpringResolver.resolve(index);
        var tempPool = result.beans().stream()
                .filter(b -> "tempPool".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertEquals("@Prototype", tempPool.scope());
    }

    @Test
    void beanNamesAndAliasesAreAvailableAsQualifiers() {
        var result = SpringResolver.resolve(index);
        var tempPool = result.beans().stream()
                .filter(b -> "tempPool".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertTrue(tempPool.qualifiers().contains("@Qualifier(\"temporaryPool\")"));
        assertTrue(tempPool.qualifiers().contains("@Qualifier(\"poolAlias\")"));
        assertFalse(tempPool.qualifiers().contains("@Qualifier(\"tempPool\")"));
    }

    @Test
    void beanMethodProfilesAreMergedIntoProducer() {
        var result = SpringResolver.resolve(index);
        var tempPool = result.beans().stream()
                .filter(b -> "tempPool".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertEquals(List.of("prod"), tempPool.profiles());
    }

    @Test
    void producerDefaultScopeSingleton() {
        var result = SpringResolver.resolve(index);
        var mainPool = result.beans().stream()
                .filter(b -> "mainPool".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertEquals("@Singleton", mainPool.scope());
    }

    @Test
    void producerBeanTypesIncludeReturnType() {
        var result = SpringResolver.resolve(index);
        var mainPool = result.beans().stream()
                .filter(b -> "mainPool".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertTrue(mainPool.beanTypes().contains(
                "org.treblereel.mcp.fixture.spring.advanced.ConnectionPool"));
    }

    // --- Unresolved injection (interface with no implementors indexed) ---

    @Test
    void unresolvedFieldInjectionHasNullResolvedBean() {
        var result = SpringResolver.resolve(index);
        var reportGen = findBean(result, "ReportGenerator");
        var apiClientIp = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == reportGen.id()
                        && ip.targetType().contains("ExternalApiClient"))
                .findFirst();
        assertTrue(apiClientIp.isEmpty() || apiClientIp.get().resolvedBeanId() == null,
                "ExternalApiClient has no bean implementation — should be unresolved");
    }

    @Test
    void resolvedFieldInjectionInSameBean() {
        var result = SpringResolver.resolve(index);
        var reportGen = findBean(result, "ReportGenerator");
        var auditLogIp = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == reportGen.id()
                        && ip.targetType().contains("AuditLog"))
                .findFirst().orElseThrow();
        assertNotNull(auditLogIp.resolvedBeanId(),
                "AuditLog field injection should resolve");
    }

    // --- Interface bean types ---

    @Test
    void implementorBeanTypesIncludeInterface() {
        var result = SpringResolver.resolve(index);
        var stripe = findBean(result, "StripeGateway");
        assertTrue(stripe.beanTypes().contains(
                "org.treblereel.mcp.fixture.spring.advanced.PaymentGateway"));
    }

    // --- Transitive type hierarchy ---

    @Test
    void beanTypesIncludeTransitiveInterface() {
        var result = SpringResolver.resolve(index);
        var stripe = findBean(result, "StripeGateway");
        assertTrue(stripe.beanTypes().contains(
                "org.treblereel.mcp.fixture.spring.advanced.BaseGateway"),
                "StripeGateway implements PaymentGateway extends BaseGateway — BaseGateway must be in bean types");
    }

    @Test
    void injectionByTransitiveInterfaceResolves() {
        var result = SpringResolver.resolve(index);
        var consumer = findBean(result, "BaseGatewayConsumer");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == consumer.id()
                        && i.targetType().contains("BaseGateway"))
                .findFirst().orElseThrow();
        assertNotNull(ip.resolvedBeanId(),
                "Injection of BaseGateway with @Qualifier(\"stripe\") should resolve to StripeGateway");
        var stripe = findBean(result, "StripeGateway");
        assertEquals(stripe.id(), ip.resolvedBeanId());
    }

    @Test
    void transitiveInterfaceCreatesDependencyEdge() {
        var result = SpringResolver.resolve(index);
        var consumerClassId = classIdFor(result, "BaseGatewayConsumer");
        var stripeClassId = classIdFor(result, "StripeGateway");
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.fromClassId() == consumerClassId
                        && d.toClassId() == stripeClassId
                        && d.kind().equals("SPRING_INJECT")),
                "BaseGatewayConsumer should have SPRING_INJECT edge to StripeGateway via transitive BaseGateway");
    }

    // --- Total counts ---

    @Test
    void totalClassBeanCount() {
        var result = SpringResolver.resolve(index);
        long classBeans = result.beans().stream()
                .filter(b -> b.kind().equals("CLASS")).count();
        assertEquals(16, classBeans,
                "Stripe, Paypal, PaymentProcessor, DefaultPaymentHandler, Checkout, AuditLog, AuditService, Dashboard, ReportGen, NamedDS, InfraConfig, PoolConsumer, OrderProcessor, BaseGatewayConsumer, LiteModeComponent, MetaConfiguredBeans");
    }

    @Test
    void totalProducerBeanCount() {
        var result = SpringResolver.resolve(index);
        long producers = result.beans().stream()
                .filter(b -> b.kind().equals("PRODUCER_METHOD")).count();
        assertEquals(5, producers, "mainPool, tempPool, poolInfo, liteModePool, metaPool");
    }

    @Test
    void nonBeanClassNotDiscovered() {
        var result = SpringResolver.resolve(index);
        assertFalse(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "ConnectionPool") && b.memberName() == null),
                "ConnectionPool is not annotated — should not be a CLASS bean");
    }

    @Test
    void interfaceNotDiscoveredAsBean() {
        var result = SpringResolver.resolve(index);
        assertFalse(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "PaymentGateway") && b.memberName() == null));
        assertFalse(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "ExternalApiClient") && b.memberName() == null));
    }

    // --- CLASS_REFERENCE for non-bean ---

    @Test
    void nonBeanClassReferenceEdges() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.kind().equals("CLASS_REFERENCE")),
                "Should have CLASS_REFERENCE edges for non-bean ConnectionPool");
    }

    // --- @Bean producer injection ---

    @Test
    void producerBeanCanBeInjected() {
        var result = SpringResolver.resolve(index);
        var consumer = findBean(result, "PoolConsumer");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == consumer.id()
                        && i.targetType().contains("ConnectionPool"))
                .findFirst().orElseThrow();
        assertNotNull(ip.resolvedBeanId(),
                "ConnectionPool produced by @Bean should be injectable");
    }

    @Test
    void beanMethodParametersAreInjectionPoints() {
        var result = SpringResolver.resolve(index);
        var config = findBean(result, "InfraConfig");
        var poolInfoIps = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == config.id()
                        && ip.fieldName().equals("poolInfo"))
                .toList();
        assertEquals(1, poolInfoIps.size(),
                "poolInfo(@Bean method) has 1 parameter that should be an injection point");
        assertEquals("METHOD_PARAM", poolInfoIps.get(0).kind());
        assertTrue(poolInfoIps.get(0).targetType().contains("ConnectionPool"));
    }

    @Test
    void beanMethodParameterResolvesToProducer() {
        var result = SpringResolver.resolve(index);
        var config = findBean(result, "InfraConfig");
        var poolInfoIp = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == config.id()
                        && ip.fieldName().equals("poolInfo"))
                .findFirst().orElseThrow();
        assertNotNull(poolInfoIp.resolvedBeanId(),
                "ConnectionPool param in @Bean poolInfo should resolve to a producer bean");
    }

    // --- Qualifier-based resolution ---

    @Test
    void qualifierResolvesToCorrectBean() {
        var result = SpringResolver.resolve(index);
        var checkout = findBean(result, "CheckoutService");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == checkout.id())
                .findFirst().orElseThrow();
        assertNotNull(ip.resolvedBeanId(),
                "@Qualifier(\"stripe\") PaymentGateway should resolve to StripeGateway");
        var stripe = findBean(result, "StripeGateway");
        assertEquals(stripe.id(), ip.resolvedBeanId(),
                "Should resolve to StripeGateway, not PaypalGateway");
    }

    @Test
    void qualifierResolutionCreatesDependencyEdge() {
        var result = SpringResolver.resolve(index);
        var checkoutClassId = classIdFor(result, "CheckoutService");
        var stripeClassId = classIdFor(result, "StripeGateway");
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.fromClassId() == checkoutClassId
                        && d.toClassId() == stripeClassId
                        && d.kind().equals("SPRING_INJECT")),
                "CheckoutService should have SPRING_INJECT edge to StripeGateway");
    }

    @Test
    void primaryResolvesWhenNoQualifier() {
        var result = SpringResolver.resolve(index);
        var handler = findBean(result, "DefaultPaymentHandler");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == handler.id()
                        && i.targetType().contains("PaymentGateway"))
                .findFirst().orElseThrow();
        assertNotNull(ip.resolvedBeanId(),
                "@Primary PaymentProcessor should win when no qualifier specified");
        var processor = findBean(result, "PaymentProcessor");
        assertEquals(processor.id(), ip.resolvedBeanId(),
                "Should resolve to @Primary PaymentProcessor");
    }

    // --- Producer @Primary resolves correctly over non-primary ---

    @Test
    void producerPrimaryResolvesOverNonPrimary() {
        var result = SpringResolver.resolve(index);
        var consumer = findBean(result, "PoolConsumer");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == consumer.id()
                        && i.targetType().contains("ConnectionPool"))
                .findFirst().orElseThrow();
        var mainPool = result.beans().stream()
                .filter(b -> "mainPool".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertEquals(mainPool.id(), ip.resolvedBeanId(),
                "Should resolve to @Primary mainPool, not tempPool");
    }

    @Test
    void producerBeanCreatesDependencyEdge() {
        var result = SpringResolver.resolve(index);
        var consumerClassId = classIdFor(result, "PoolConsumer");
        var configClassId = classIdFor(result, "InfraConfig");
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.fromClassId() == consumerClassId
                        && d.toClassId() == configClassId
                        && d.kind().equals("SPRING_INJECT")),
                "PoolConsumer should have SPRING_INJECT edge to InfraConfig (producer declaring class)");
    }

    @Test
    void ambiguousInjectionIsDetected() {
        var result = SpringResolver.resolve(index);
        var reportGen = findBean(result, "ReportGenerator");
        var externalIp = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == reportGen.id()
                        && ip.targetType().contains("ExternalApiClient"))
                .findFirst();
        assertTrue(externalIp.isEmpty() || !externalIp.get().isAmbiguous(),
                "Single unresolved interface should be unsatisfied, not ambiguous");
    }

    // --- Composed / meta-annotated stereotypes ---

    @Test
    void composedAnnotationDiscoveredAsBean() {
        var result = SpringResolver.resolve(index);
        var orderProcessor = findBean(result, "OrderProcessor");
        assertEquals("CLASS", orderProcessor.kind());
    }

    @Test
    void multiLevelComposedScopeIsDetected() {
        var result = SpringResolver.resolve(index);
        var orderProcessor = findBean(result, "OrderProcessor");
        assertEquals("@RequestScoped", orderProcessor.scope());
    }

    @Test
    void composedAnnotationBeanHasInjectionPoints() {
        var result = SpringResolver.resolve(index);
        var orderProcessor = findBean(result, "OrderProcessor");
        var ips = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == orderProcessor.id())
                .toList();
        assertEquals(1, ips.size(), "OrderProcessor has 1 @Autowired field");
        assertTrue(ips.get(0).targetType().contains("AuditLog"));
    }

    @Test
    void composedAnnotationBeanResolvesInjection() {
        var result = SpringResolver.resolve(index);
        var orderProcessor = findBean(result, "OrderProcessor");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == orderProcessor.id())
                .findFirst().orElseThrow();
        assertNotNull(ip.resolvedBeanId(),
                "AuditLog injection in @DomainService bean should resolve");
    }

    // --- @Bean in @Component (lite mode) ---

    @Test
    void liteModeComponentDiscoveredAsBean() {
        var result = SpringResolver.resolve(index);
        var lite = findBean(result, "LiteModeComponent");
        assertEquals("CLASS", lite.kind());
    }

    @Test
    void liteModeComponentBeanMethodsDiscovered() {
        var result = SpringResolver.resolve(index);
        var producers = result.beans().stream()
                .filter(b -> b.kind().equals("PRODUCER_METHOD"))
                .filter(b -> hasClassName(result, b, "LiteModeComponent"))
                .toList();
        assertEquals(1, producers.size(), "LiteModeComponent has 1 @Bean method");
        assertEquals("liteModePool", producers.get(0).memberName());
    }

    // --- @Bean in meta-annotated @Configuration ---

    @Test
    void metaConfiguredBeansDiscoveredAsBean() {
        var result = SpringResolver.resolve(index);
        var meta = findBean(result, "MetaConfiguredBeans");
        assertEquals("CLASS", meta.kind());
    }

    @Test
    void metaConfiguredBeanMethodsDiscovered() {
        var result = SpringResolver.resolve(index);
        var producers = result.beans().stream()
                .filter(b -> b.kind().equals("PRODUCER_METHOD"))
                .filter(b -> hasClassName(result, b, "MetaConfiguredBeans"))
                .toList();
        assertEquals(1, producers.size(), "MetaConfiguredBeans has 1 @Bean method");
        assertEquals("metaPool", producers.get(0).memberName());
    }

    // --- Helpers ---

    private BeanRecord findBean(BeanResolver.ResolutionResult result, String shortName) {
        return result.beans().stream()
                .filter(b -> hasClassName(result, b, shortName) && b.memberName() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Bean not found: " + shortName));
    }

    private boolean hasClassName(BeanResolver.ResolutionResult result, BeanRecord bean, String shortName) {
        for (var entry : result.classNameToId().entrySet()) {
            if (entry.getValue() == bean.classId() && entry.getKey().endsWith(shortName)) {
                return true;
            }
        }
        return false;
    }

    private int classIdFor(BeanResolver.ResolutionResult result, String shortName) {
        return result.classNameToId().entrySet().stream()
                .filter(e -> e.getKey().endsWith(shortName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Class not found: " + shortName))
                .getValue();
    }
}
