package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.QuillTools;
import org.treblereel.mcp.model.*;

class SpringQuillToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path tempDir;
    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = QuillDatabase.create(tempDir.resolve("spring-test.db"));

        var classes = List.of(
                new ClassRecord(0, "com.example.UserRepository", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/com/example/UserRepository.java", 15, true, 200),
                new ClassRecord(0, "com.example.UserService", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/com/example/UserService.java", 30, true, 400),
                new ClassRecord(0, "com.example.NotificationService", "INTERFACE", null,
                        List.of(), "src/main/java/com/example/NotificationService.java", 5, false, 50),
                new ClassRecord(0, "com.example.EmailNotificationService", "CLASS", "java.lang.Object",
                        List.of("com.example.NotificationService"),
                        "src/main/java/com/example/EmailNotificationService.java", 20, true, 300),
                new ClassRecord(0, "com.example.AppConfig", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/com/example/AppConfig.java", 10, true, 150),
                new ClassRecord(0, "com.example.OrderController", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/com/example/OrderController.java", 25, true, 350),
                new ClassRecord(0, "com.example.RequestContext", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/com/example/RequestContext.java", 5, false, 50)
        );

        var beans = List.of(
                new BeanRecord(1, 1, "CLASS", "@Singleton", List.of("@Default"),
                        List.of(), false, null, null, null, null,
                        List.of("com.example.UserRepository")),
                new BeanRecord(2, 2, "CLASS", "@Singleton", List.of("@Default"),
                        List.of(), false, null, null, null, null,
                        List.of("com.example.UserService")),
                new BeanRecord(3, 4, "CLASS", "@Singleton", List.of("@Default"),
                        List.of(), true, 0, null, null, null,
                        List.of("com.example.EmailNotificationService", "com.example.NotificationService")),
                new BeanRecord(4, 5, "CLASS", "@Singleton", List.of("@Default"),
                        List.of(), false, null, null, null, null,
                        List.of("com.example.AppConfig")),
                new BeanRecord(5, 5, "PRODUCER_METHOD", "@Prototype", List.of("@Default"),
                        List.of(), false, null, null, 5, "requestContext",
                        List.of("com.example.RequestContext")),
                new BeanRecord(6, 6, "CLASS", "@Singleton", List.of("@Default"),
                        List.of(), false, null, null, null, null,
                        List.of("com.example.OrderController"))
        );

        var ips = List.of(
                InjectionPointRecord.staticAnalysis(1, 2, "FIELD",
                        "com.example.UserRepository", List.of("@Default"),
                        "userRepository", 1, false, InjectionPointRecord.STATIC_SPRING),
                InjectionPointRecord.staticAnalysis(2, 2, "FIELD",
                        "com.example.NotificationService", List.of("@Default"),
                        "notificationService", 3, false, InjectionPointRecord.STATIC_SPRING),
                InjectionPointRecord.staticAnalysis(3, 6, "CONSTRUCTOR_PARAM",
                        "com.example.UserService", List.of("@Default"),
                        "<init>", 2, false, InjectionPointRecord.STATIC_SPRING),
                InjectionPointRecord.staticAnalysis(4, 6, "FIELD",
                        "com.example.RequestContext", List.of("@Default"),
                        "requestContext", 5, false, InjectionPointRecord.STATIC_SPRING)
        );

        var deps = List.of(
                new DependencyRecord(2, 1, "SPRING_INJECT", 1),
                new DependencyRecord(2, 4, "SPRING_INJECT", 2),
                new DependencyRecord(6, 2, "SPRING_INJECT", 3),
                new DependencyRecord(6, 5, "SPRING_INJECT", 4)
        );

        IndexWriter.write(jdbi, classes, beans, ips, deps,
                Map.of("indexed_at", "2026-09-01T10:00:00", "last_commit", "def5678"));
    }

    @Test
    void getBeansShowsSpringBeans() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, null, null, null);
        assertTrue(result.contains("UserRepository"));
        assertTrue(result.contains("UserService"));
        assertTrue(result.contains("EmailNotificationService"));
        assertTrue(result.contains("OrderController"));
    }

    @Test
    void getBeansFiltersBySingletonScope() {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, "@Singleton", null, null, null);
        assertTrue(result.contains("UserRepository"));
        assertTrue(result.contains("UserService"));
        assertFalse(result.contains("requestContext"));
    }

    @Test
    void getBeansFiltersByProducerMethodKind() throws Exception {
        var tools = new QuillTools();
        String result = tools.getBeans(jdbi, null, null, "PRODUCER_METHOD", null, null);
        JsonNode root = JSON.readTree(result);
        assertEquals(1, root.get("total").asInt());
        assertTrue(result.contains("PRODUCER_METHOD"));
        assertTrue(result.contains("@Prototype"));

        JsonNode bean = root.get("beans").get(0);
        assertEquals("requestContext", bean.get("member").asText(),
                "Producer bean should expose member name");
        assertEquals("com.example.RequestContext", bean.get("produced_type").asText(),
                "Producer bean should expose produced type");
    }

    @Test
    void getDependenciesShowsSpringInject() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "UserService", "outbound", 1);
        JsonNode root = JSON.readTree(result);
        assertTrue(result.contains("SPRING_INJECT"));
        JsonNode dependsOn = root.get("depends_on");
        assertEquals(2, dependsOn.size());
    }

    @Test
    void getDependenciesDepth2ExpandsTransitive() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "OrderController", "outbound", 2);
        JsonNode root = JSON.readTree(result);
        JsonNode dependsOn = root.get("depends_on");
        assertEquals(2, dependsOn.size());
        boolean hasUserService = false;
        JsonNode userServiceNested = null;
        for (JsonNode dep : dependsOn) {
            if ("com.example.UserService".equals(dep.get("class").asText())) {
                hasUserService = true;
                userServiceNested = dep.get("depends_on");
            }
        }
        assertTrue(hasUserService, "OrderController should depend on UserService");
        assertNotNull(userServiceNested, "depth=2 should expand UserService dependencies");
        assertTrue(userServiceNested.size() >= 1);
    }

    @Test
    void getDependenciesInboundShowsDependents() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "UserRepository", "inbound", 1);
        JsonNode root = JSON.readTree(result);
        JsonNode dependedBy = root.get("depended_by");
        assertNotNull(dependedBy);
        assertEquals(1, dependedBy.size());
        assertEquals("com.example.UserService", dependedBy.get(0).get("class").asText());
    }

    @Test
    void getInjectionPointsShowsFieldAndConstructor() throws Exception {
        var tools = new QuillTools();
        String result = tools.getInjectionPoints(jdbi, "UserService");
        JsonNode root = JSON.readTree(result);
        JsonNode ips = root.get("injection_points");
        assertEquals(2, ips.size());
        assertTrue(result.contains("userRepository"));
        assertTrue(result.contains("notificationService"));
    }

    @Test
    void getInjectionPointsConstructorParam() throws Exception {
        var tools = new QuillTools();
        String result = tools.getInjectionPoints(jdbi, "OrderController");
        JsonNode root = JSON.readTree(result);
        JsonNode ips = root.get("injection_points");
        assertEquals(2, ips.size());
        assertTrue(ips.get(0).get("kind").asText().equals("CONSTRUCTOR_PARAM"));
    }

    @Test
    void getInjectionPointsShowsProducerIdentity() throws Exception {
        var tools = new QuillTools();
        String result = tools.getInjectionPoints(jdbi, "OrderController");
        JsonNode root = JSON.readTree(result);
        JsonNode ips = root.get("injection_points");
        JsonNode producerIp = null;
        for (JsonNode ip : ips) {
            if ("requestContext".equals(ip.get("field").asText())) {
                producerIp = ip;
                break;
            }
        }
        assertNotNull(producerIp, "Should find requestContext injection point");
        assertEquals("com.example.AppConfig", producerIp.get("resolved_to").asText(),
                "resolved_to should show declaring class");
        assertEquals("requestContext", producerIp.get("resolved_member").asText(),
                "resolved_member should show @Bean method name");
        assertEquals("com.example.RequestContext", producerIp.get("resolved_produced_type").asText(),
                "resolved_produced_type should show the produced type");
    }

    @Test
    void getOverviewShowsSpringProject() throws Exception {
        var tools = new QuillTools();
        String result = tools.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);
        assertEquals(7, root.get("project").get("classes").asInt());
        assertEquals(6, root.get("project").get("beans").asInt());

        JsonNode byScope = root.get("beans_by_scope");
        assertTrue(byScope.has("@Singleton"));
        assertEquals(5, byScope.get("@Singleton").asInt());
        assertTrue(byScope.has("@Prototype"));
        assertEquals(1, byScope.get("@Prototype").asInt());

        JsonNode byKind = root.get("beans_by_kind");
        assertTrue(byKind.has("CLASS"));
        assertTrue(byKind.has("PRODUCER_METHOD"));
    }

    @Test
    void getRiskWorksWithSpringInject() throws Exception {
        var tools = new QuillTools();
        String result = tools.getRisk(jdbi, "UserService");
        JsonNode root = JSON.readTree(result);
        assertEquals("com.example.UserService", root.get("target").asText());
        assertTrue(root.has("risk_score"));
        JsonNode signals = root.get("signals");
        assertTrue(signals.get("fan_out").get("value").asInt() >= 2,
                "UserService has 2 outbound SPRING_INJECT deps");
        assertTrue(signals.get("fan_in").get("value").asInt() >= 1,
                "UserService has 1 inbound dep from OrderController");
    }

    @Test
    void searchClassesFindsSpringBeans() throws Exception {
        var tools = new QuillTools();
        String result = tools.searchClasses(jdbi, "*Service", 10);
        assertTrue(result.contains("UserService"));
        assertTrue(result.contains("EmailNotificationService"));
        assertTrue(result.contains("NotificationService"));
    }

    @Test
    void architectureHubsDetected() throws Exception {
        var tools = new QuillTools();
        String result = tools.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);
        JsonNode hubs = root.get("architecture_hubs");
        assertNotNull(hubs);
        assertTrue(hubs.size() >= 1, "UserService should be an architecture hub");
    }
}
