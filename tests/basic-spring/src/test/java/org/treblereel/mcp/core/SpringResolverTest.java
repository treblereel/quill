package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.fixture.spring.*;
import org.treblereel.mcp.model.*;

class SpringResolverTest {

    @TempDir Path tempDir;
    Index index;

    @BeforeEach
    void setUp() throws Exception {
        Indexer indexer = new Indexer();
        for (Class<?> cls : List.of(
                UserRepository.class, UserService.class, NotificationService.class,
                EmailNotificationService.class, SmsNotificationService.class,
                AppConfig.class, CacheManager.class, RequestContext.class,
                OrderController.class, UserDTO.class)) {
            indexer.indexClass(cls);
        }
        index = indexer.complete();
    }

    @Test
    void detectsSpringProject() {
        assertTrue(SpringResolver.isSpringProject(index));
    }

    @Test
    void nonSpringProjectNotDetected() throws Exception {
        Indexer plain = new Indexer();
        plain.indexClass(UserDTO.class);
        assertFalse(SpringResolver.isSpringProject(plain.complete()));
    }

    @Test
    void discoversComponentBeans() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> b.kind().equals("CLASS") && hasClassName(result, b, "EmailNotificationService")));
        assertTrue(result.beans().stream()
                .anyMatch(b -> b.kind().equals("CLASS") && hasClassName(result, b, "SmsNotificationService")));
    }

    @Test
    void discoversServiceBeans() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "UserService")));
    }

    @Test
    void discoversRepositoryBeans() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "UserRepository")));
    }

    @Test
    void discoversRestControllerBeans() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "OrderController")));
    }

    @Test
    void extractsFrameworkEndpointMethodsAndPaths() {
        var endpoints = JandexScanner.extractFrameworkEndpoints(
                index, Map.of(OrderController.class.getName(), 1));

        assertEquals(1, endpoints.size());
        var endpoint = endpoints.getFirst();
        assertEquals("spring", endpoint.framework());
        assertEquals(List.of("POST"), endpoint.httpMethods());
        assertEquals(List.of("/orders"), endpoint.classPaths());
        assertEquals(List.of("/{userId}"), endpoint.methodPaths());
        assertEquals("handleOrder", endpoint.methodName());
        assertFalse(endpoint.descriptor().isBlank());
    }

    @Test
    void indexesConfigurationDefinitionsAndAnnotatedConsumers() throws Exception {
        Path resources = tempDir.resolve("src/main/resources");
        Files.createDirectories(resources.resolve("META-INF"));
        Files.writeString(resources.resolve("application.properties"),
                "orders.region=us-west\norders.currency USD\n"
                        + "orders\\.escaped=value\norders.empty\n"
                        + "orders.long\\\n  .name=value\n");
        Files.writeString(resources.resolve("application.yml"),
                "orders:\n  timeout: 30s\n");
        Files.writeString(resources.resolve("META-INF/persistence.xml"),
                "<persistence><persistence-unit name=\"orders\"/></persistence>\n");
        var classes = List.of(new ClassRecord(0, OrderController.class.getName(), "CLASS",
                "java.lang.Object", List.of(),
                "src/main/java/org/treblereel/mcp/fixture/spring/OrderController.java",
                1, true, 10, null, "source", "current", ".", "main"));

        var result = ConfigurationScanner.scan(tempDir, List.of(tempDir), index,
                Map.of(OrderController.class.getName(), 1), classes).configuration();

        assertTrue(result.definitions().stream()
                .anyMatch(value -> value.key().equals("orders.region")
                        && value.kind().equals("property") && value.line() == 1));
        assertTrue(result.definitions().stream()
                .anyMatch(value -> value.key().equals("orders.currency")
                        && value.kind().equals("property") && value.line() == 2));
        assertTrue(result.definitions().stream()
                .anyMatch(value -> value.key().equals("orders.escaped")
                        && value.kind().equals("property") && value.line() == 3));
        assertTrue(result.definitions().stream()
                .anyMatch(value -> value.key().equals("orders.empty")
                        && value.kind().equals("property") && value.line() == 4));
        assertTrue(result.definitions().stream()
                .anyMatch(value -> value.key().equals("orders.long.name")
                        && value.kind().equals("property") && value.line() == 5));
        assertTrue(result.definitions().stream()
                .anyMatch(value -> value.key().equals("orders.timeout")
                        && value.kind().equals("yaml_property")));
        assertTrue(result.definitions().stream()
                .anyMatch(value -> value.key().equals("orders")
                        && value.kind().equals("persistence_unit")));
        assertTrue(result.usages().stream()
                .anyMatch(value -> value.key().equals("orders.region")
                        && value.member().equals("region")
                        && value.kind().equals("config_key")));
    }

    @Test
    void discoversConfigurationBeans() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.beans().stream()
                .anyMatch(b -> b.kind().equals("CLASS") && hasClassName(result, b, "AppConfig")));
    }

    @Test
    void discoversProducerMethodBeans() {
        var result = SpringResolver.resolve(index);
        var producers = result.beans().stream()
                .filter(b -> b.kind().equals("PRODUCER_METHOD"))
                .toList();
        assertTrue(producers.size() >= 2, "Should find at least 2 @Bean methods (cacheManager, requestContext)");

        assertTrue(producers.stream().anyMatch(b -> "cacheManager".equals(b.memberName())));
        assertTrue(producers.stream().anyMatch(b -> "requestContext".equals(b.memberName())));
    }

    @Test
    void producerMethodHasDeclaringClass() {
        var result = SpringResolver.resolve(index);
        var cacheManagerBean = result.beans().stream()
                .filter(b -> "cacheManager".equals(b.memberName()))
                .findFirst().orElseThrow();

        assertNotNull(cacheManagerBean.declaringClassId());
        Integer configClassId = result.classNameToId().get(
                "org.treblereel.mcp.fixture.spring.AppConfig");
        assertEquals(configClassId, cacheManagerBean.declaringClassId());
    }

    @Test
    void producerMethodBeanTypes() {
        var result = SpringResolver.resolve(index);
        var cacheManagerBean = result.beans().stream()
                .filter(b -> "cacheManager".equals(b.memberName()))
                .findFirst().orElseThrow();

        assertTrue(cacheManagerBean.beanTypes().contains(
                "org.treblereel.mcp.fixture.spring.CacheManager"));
    }

    @Test
    void defaultScopeIsSingleton() {
        var result = SpringResolver.resolve(index);
        var userService = findBean(result, "UserService");
        assertEquals("@Singleton", userService.scope());
    }

    @Test
    void scopeAnnotationHonored() {
        var result = SpringResolver.resolve(index);
        var requestContext = result.beans().stream()
                .filter(b -> "requestContext".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertEquals("@Prototype", requestContext.scope());
    }

    @Test
    void primaryDetected() {
        var result = SpringResolver.resolve(index);
        var email = findBean(result, "EmailNotificationService");
        assertTrue(email.isAlternative(), "@Primary should set isAlternative=true");
    }

    @Test
    void profileDetected() {
        var result = SpringResolver.resolve(index);
        var sms = findBean(result, "SmsNotificationService");
        assertNotNull(sms.profiles());
        assertTrue(sms.profiles().contains("sms"));
    }

    @Test
    void noProfileReturnsNull() {
        var result = SpringResolver.resolve(index);
        var userService = findBean(result, "UserService");
        assertNull(userService.profiles());
    }

    @Test
    void beanTypesIncludeInterfaces() {
        var result = SpringResolver.resolve(index);
        var email = findBean(result, "EmailNotificationService");
        assertTrue(email.beanTypes().contains(
                "org.treblereel.mcp.fixture.spring.NotificationService"),
                "Bean types should include implemented interface");
        assertTrue(email.beanTypes().contains(
                "org.treblereel.mcp.fixture.spring.EmailNotificationService"),
                "Bean types should include the class itself");
    }

    @Test
    void defaultQualifier() {
        var result = SpringResolver.resolve(index);
        var userRepo = findBean(result, "UserRepository");
        assertTrue(userRepo.qualifiers().contains("@Default"));
    }

    @Test
    void nonBeanClassNotInBeans() {
        var result = SpringResolver.resolve(index);
        assertFalse(result.beans().stream()
                .anyMatch(b -> hasClassName(result, b, "UserDTO") && b.kind().equals("CLASS")
                        && b.memberName() == null));
    }

    @Test
    void fieldInjectionPointsDetected() {
        var result = SpringResolver.resolve(index);
        var userServiceBean = findBean(result, "UserService");
        var ips = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == userServiceBean.id())
                .toList();
        assertEquals(2, ips.size(), "UserService has 2 @Autowired fields");
        assertTrue(ips.stream().anyMatch(ip ->
                ip.fieldName().equals("userRepository") && ip.kind().equals("FIELD")));
        assertTrue(ips.stream().anyMatch(ip ->
                ip.fieldName().equals("notificationService") && ip.kind().equals("FIELD")));
    }

    @Test
    void constructorInjectionPointsDetected() {
        var result = SpringResolver.resolve(index);
        var controller = findBean(result, "OrderController");
        var ips = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == controller.id())
                .toList();
        assertEquals(1, ips.size());
        assertEquals("CONSTRUCTOR_PARAM", ips.get(0).kind());
        assertTrue(ips.get(0).targetType().contains("UserService"));
    }

    @Test
    void fieldInjectionResolvesToBean() {
        var result = SpringResolver.resolve(index);
        var userServiceBean = findBean(result, "UserService");
        var repoIp = result.injectionPoints().stream()
                .filter(ip -> ip.beanId() == userServiceBean.id()
                        && ip.fieldName().equals("userRepository"))
                .findFirst().orElseThrow();
        assertNotNull(repoIp.resolvedBeanId(),
                "userRepository injection should resolve to UserRepository bean");
    }

    @Test
    void constructorInjectionResolvesToBean() {
        var result = SpringResolver.resolve(index);
        var controller = findBean(result, "OrderController");
        var ip = result.injectionPoints().stream()
                .filter(i -> i.beanId() == controller.id())
                .findFirst().orElseThrow();
        assertNotNull(ip.resolvedBeanId(),
                "Constructor param UserService should resolve");
    }

    @Test
    void dependencyEdgesCreated() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.kind().equals("SPRING_INJECT")),
                "Should have SPRING_INJECT dependency edges");
    }

    @Test
    void nonBeanDependenciesAdded() {
        var result = SpringResolver.resolve(index);
        assertTrue(result.dependencies().stream()
                .anyMatch(d -> d.kind().equals("CLASS_REFERENCE")),
                "Should have CLASS_REFERENCE edges for non-bean classes");
    }

    @Test
    void totalBeanCount() {
        var result = SpringResolver.resolve(index);
        long classBeans = result.beans().stream()
                .filter(b -> b.kind().equals("CLASS")).count();
        long producers = result.beans().stream()
                .filter(b -> b.kind().equals("PRODUCER_METHOD")).count();
        assertTrue(classBeans >= 6,
                "Should find at least 6 CLASS beans (UserRepo, UserService, Email, Sms, AppConfig, OrderController), got " + classBeans);
        assertTrue(producers >= 2,
                "Should find at least 2 PRODUCER_METHOD beans, got " + producers);
    }

    private BeanRecord findBean(BeanResolver.ResolutionResult result, String shortName) {
        return result.beans().stream()
                .filter(b -> hasClassName(result, b, shortName) && b.memberName() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Bean not found: " + shortName));
    }

    // --- Resolution logic unit tests ---

    @Test
    void resolveByTypeReturnsUnsatisfiedForUnknownType() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        var r = SpringResolver.resolveByType("com.NoSuchType", List.of("@Default"), candidates);
        assertNull(r.beanId());
        assertFalse(r.isAmbiguous());
    }

    @Test
    void resolveByTypeSingleCandidate() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Foo", List.of(
                new SpringResolver.BeanCandidate(10, 1, List.of("@Default"), false)));
        var r = SpringResolver.resolveByType("com.Foo", List.of("@Default"), candidates);
        assertEquals(10, r.beanId());
        assertFalse(r.isAmbiguous());
    }

    @Test
    void resolveByTypeQualifierFiltersCorrectly() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Iface", List.of(
                new SpringResolver.BeanCandidate(1, 10, List.of("@Qualifier(\"a\")"), false),
                new SpringResolver.BeanCandidate(2, 20, List.of("@Qualifier(\"b\")"), false)));
        var r = SpringResolver.resolveByType("com.Iface", List.of("@Qualifier(\"b\")"), candidates);
        assertEquals(2, r.beanId());
        assertFalse(r.isAmbiguous());
    }

    @Test
    void resolveByTypeQualifierMismatchReturnsCandidateEvidence() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Iface", List.of(
                new SpringResolver.BeanCandidate(1, 10, List.of("@Qualifier(\"a\")"), false),
                new SpringResolver.BeanCandidate(2, 20, List.of("@Qualifier(\"b\")"), false)));
        var r = SpringResolver.resolveByType("com.Iface", List.of("@Qualifier(\"c\")"), candidates);
        assertNull(r.beanId());
        assertFalse(r.isAmbiguous(), "Qualifier mismatch stays unresolved, not ambiguous");
        assertEquals(2, r.trace().candidates().size());
        assertTrue(r.trace().candidates().stream().allMatch(candidate ->
                candidate.disposition()
                        == org.treblereel.mcp.model.CandidateDisposition.EXCLUDED));
        assertTrue(r.trace().candidates().stream().allMatch(candidate ->
                candidate.reason().equals("QUALIFIER_MISMATCH")));
    }

    @Test
    void resolveByTypePrimaryWinsAmongMultiple() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Iface", List.of(
                new SpringResolver.BeanCandidate(1, 10, List.of("@Default"), false),
                new SpringResolver.BeanCandidate(2, 20, List.of("@Default"), true),
                new SpringResolver.BeanCandidate(3, 30, List.of("@Default"), false)));
        var r = SpringResolver.resolveByType("com.Iface", List.of("@Default"), candidates);
        assertEquals(2, r.beanId());
        assertFalse(r.isAmbiguous());
        assertEquals("PRIMARY_CANDIDATE", r.trace().candidates().stream()
                .filter(candidate -> candidate.beanId() == 2)
                .findFirst().orElseThrow().reason());
    }

    @Test
    void resolveByTypeMultiplePrimariesAreAmbiguous() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Iface", List.of(
                new SpringResolver.BeanCandidate(1, 10, List.of("@Default"), true),
                new SpringResolver.BeanCandidate(2, 20, List.of("@Default"), true)));
        var r = SpringResolver.resolveByType("com.Iface", List.of("@Default"), candidates);
        assertNull(r.beanId());
        assertTrue(r.isAmbiguous(), "Multiple @Primary → ambiguous");
    }

    @Test
    void resolveByTypeNoPrimaryMultipleCandidatesIsAmbiguous() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Iface", List.of(
                new SpringResolver.BeanCandidate(1, 10, List.of("@Default"), false),
                new SpringResolver.BeanCandidate(2, 20, List.of("@Default"), false)));
        var r = SpringResolver.resolveByType("com.Iface", List.of("@Default"), candidates);
        assertNull(r.beanId());
        assertTrue(r.isAmbiguous(), "Multiple candidates without @Primary → ambiguous");
    }

    // --- Qualifier matching ALL-semantics ---

    @Test
    void resolveByTypeRequiresAllQualifiers() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Iface", List.of(
                new SpringResolver.BeanCandidate(1, 10,
                        List.of("@Qualifier(\"a\")", "@Qualifier(\"b\")"), false),
                new SpringResolver.BeanCandidate(2, 20,
                        List.of("@Qualifier(\"a\")"), false)));
        var r = SpringResolver.resolveByType("com.Iface",
                List.of("@Qualifier(\"a\")", "@Qualifier(\"b\")"), candidates);
        assertEquals(1, r.beanId(),
                "Only candidate 1 has both qualifiers; candidate 2 has only 'a'");
    }

    @Test
    void resolveByTypePartialQualifierMatchFiltersOut() {
        var candidates = new java.util.HashMap<String, List<SpringResolver.BeanCandidate>>();
        candidates.put("com.Iface", List.of(
                new SpringResolver.BeanCandidate(1, 10, List.of("@Qualifier(\"a\")"), false)));
        var r = SpringResolver.resolveByType("com.Iface",
                List.of("@Qualifier(\"a\")", "@Qualifier(\"b\")"), candidates);
        assertNull(r.beanId(), "Candidate has only 'a'; requiring both remains unresolved");
    }

    // --- Default bean name ---

    @Test
    void defaultBeanNameLowercasesFirstChar() {
        assertEquals("userService", SpringResolver.defaultBeanName("UserService"));
        assertEquals("orderController", SpringResolver.defaultBeanName("OrderController"));
    }

    @Test
    void defaultBeanNamePreservesConsecutiveUppercase() {
        assertEquals("URLMapper", SpringResolver.defaultBeanName("URLMapper"));
        assertEquals("HTMLParser", SpringResolver.defaultBeanName("HTMLParser"));
    }

    @Test
    void defaultBeanNameSingleChar() {
        assertEquals("a", SpringResolver.defaultBeanName("A"));
    }

    // --- Bean name qualifier on class beans ---

    @Test
    void classBeanHasBeanNameQualifier() {
        var result = SpringResolver.resolve(index);
        var userService = findBean(result, "UserService");
        assertTrue(userService.qualifiers().contains("@Qualifier(\"userService\")"),
                "CLASS bean should include default bean name as qualifier");
    }

    // --- @Bean method name qualifier on producers ---

    @Test
    void producerBeanHasMethodNameQualifier() {
        var result = SpringResolver.resolve(index);
        var cacheManager = result.beans().stream()
                .filter(b -> "cacheManager".equals(b.memberName()))
                .findFirst().orElseThrow();
        assertTrue(cacheManager.qualifiers().contains("@Qualifier(\"cacheManager\")"),
                "PRODUCER_METHOD bean should include method name as qualifier");
    }

    // --- Dependency index enables transitive type closure across library boundaries ---

    @Test
    void collectAllSupertypesStopsWithoutDependencyIndex() throws Exception {
        Indexer appIndexer = new Indexer();
        appIndexer.indexClass(CacheManager.class);
        Index appIndex = appIndexer.complete();

        Set<String> types = new java.util.LinkedHashSet<>();
        SpringResolver.collectAllSupertypes(
                org.jboss.jandex.DotName.createSimple(CacheManager.class.getName()),
                appIndex, types);

        assertTrue(types.contains(CacheManager.class.getName()));
        // Without dependency index, superclass java.lang.Object is excluded by the filter,
        // but more importantly, any library interface wouldn't be traversed
    }

    @Test
    void collectAllSupertypesTraversesDependencyIndex() throws Exception {
        // Simulate: app has ConcreteClass, dependency has LibraryInterface
        // ConcreteClass implements LibraryInterface (which extends Serializable)
        Indexer appIndexer = new Indexer();
        appIndexer.indexClass(EmailNotificationService.class);
        Index appIndex = appIndexer.complete();

        Indexer depIndexer = new Indexer();
        depIndexer.indexClass(NotificationService.class);
        Index depIndex = depIndexer.complete();

        IndexView composite = org.jboss.jandex.CompositeIndex.create(appIndex, depIndex);

        Set<String> types = new java.util.LinkedHashSet<>();
        SpringResolver.collectAllSupertypes(
                org.jboss.jandex.DotName.createSimple(EmailNotificationService.class.getName()),
                composite, types);

        assertTrue(types.contains(EmailNotificationService.class.getName()));
        assertTrue(types.contains(NotificationService.class.getName()),
                "Should traverse into dependency index to find NotificationService interface");
    }

    @Test
    void resolveWithDependencyIndexIncludesLibraryTypes() throws Exception {
        // Index only EmailNotificationService in the "app" index
        // Index NotificationService in the "dependency" index
        // Verify that EmailNotificationService's bean types include NotificationService
        Indexer appIndexer = new Indexer();
        for (Class<?> cls : List.of(
                UserRepository.class, UserService.class,
                EmailNotificationService.class, SmsNotificationService.class,
                AppConfig.class, CacheManager.class, RequestContext.class,
                OrderController.class, UserDTO.class)) {
            appIndexer.indexClass(cls);
        }
        Index appIndex = appIndexer.complete();

        // Simulate NotificationService being in a separate library JAR
        Indexer depIndexer = new Indexer();
        depIndexer.indexClass(NotificationService.class);
        Index depIndex = depIndexer.complete();

        var result = SpringResolver.resolve(appIndex, depIndex);
        var emailBean = result.beans().stream()
                .filter(b -> hasClassName(result, b, "EmailNotificationService") && b.memberName() == null)
                .findFirst().orElseThrow();

        assertTrue(emailBean.beanTypes().contains(
                NotificationService.class.getName()),
                "Bean types should include interface from dependency index");
    }

    private boolean hasClassName(BeanResolver.ResolutionResult result, BeanRecord bean, String shortName) {
        for (var entry : result.classNameToId().entrySet()) {
            if (entry.getValue() == bean.classId() && entry.getKey().endsWith(shortName)) {
                return true;
            }
        }
        return false;
    }
}
