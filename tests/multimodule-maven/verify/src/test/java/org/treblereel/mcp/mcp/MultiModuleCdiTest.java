package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.treblereel.mcp.command.ProjectIndexStore;
import java.util.Comparator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

class MultiModuleCdiTest {

    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir")).getParent();
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");
    static final ObjectMapper JSON = new ObjectMapper();

    static Jdbi jdbi;

    @BeforeAll
    static void indexProject() {
        assertTrue(ProjectInitializer.initialize(PROJECT_ROOT, true));
        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        assertNotNull(dbPath);
        jdbi = QuillDatabase.open(dbPath);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void crossModuleInjectionIsResolved() throws Exception {
        var tools = new QuillTools();
        String result = tools.getInjectionPoints(jdbi, "UserService");
        JsonNode root = JSON.readTree(result);

        JsonNode ips = root.get("injection_points");
        assertNotNull(ips);
        assertEquals(1, ips.size());

        JsonNode ip = ips.get(0);
        assertEquals("notificationService", ip.get("field").asText());
        assertEquals("resolved", ip.get("resolution").asText(),
                "Cross-module injection should be resolved (SmsNotificationService wins via @Alternative @Priority)");

        assertEquals(0, root.get("unsatisfied").size());
        assertEquals(0, root.get("ambiguous").size());
    }

    @Test
    void alternativeBeanIsIndexed() {
        var beans = IndexReader.findBeans(jdbi, null);
        var smsBeans = beans.stream()
                .filter(b -> IndexReader.findClassById(jdbi, b.classId())
                        .map(c -> c.className().contains("SmsNotificationService")).orElse(false))
                .toList();
        assertEquals(1, smsBeans.size());

        var sms = smsBeans.get(0);
        assertTrue(sms.isAlternative(), "SmsNotificationService should be marked as @Alternative");
        assertNotNull(sms.priority(), "SmsNotificationService should have @Priority");
    }

    @Test
    void crossModuleDependencyGraphIsComplete() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "UserService", "outbound", 1);
        JsonNode root = JSON.readTree(result);

        JsonNode dependsOn = root.get("depends_on");
        assertNotNull(dependsOn);
        assertTrue(dependsOn.size() >= 1,
                "UserService should depend on at least one notification service impl");
    }

    @Test
    void crossModuleInboundDependency() throws Exception {
        var tools = new QuillTools();
        String result = tools.getDependencies(jdbi, "SmsNotificationService", "inbound", 1);
        JsonNode root = JSON.readTree(result);

        JsonNode dependedBy = root.get("depended_by");
        assertNotNull(dependedBy);

        boolean hasUserService = false;
        for (JsonNode dep : dependedBy) {
            if (dep.get("class").asText().contains("UserService")) {
                hasUserService = true;
            }
        }
        assertTrue(hasUserService,
                "SmsNotificationService should show UserService as inbound dependency (via CDI injection)");
    }

    @Test
    void overviewCountsMatchMultiModule() throws Exception {
        var tools = new QuillTools();
        String result = tools.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);

        JsonNode project = root.get("project");
        assertEquals(8, project.get("classes").asInt(),
                "Should include 5 main classes and 3 compiled test classes");
        assertEquals(3, project.get("beans").asInt(),
                "Should have 3 CDI beans");

        JsonNode problems = root.get("problems");
        assertEquals(0, problems.get("unsatisfied_count").asInt());
        assertEquals(0, problems.get("ambiguous_count").asInt());
    }

    @Test
    void searchClassesFindsAcrossModules() throws Exception {
        var tools = new QuillTools();

        String dtoResult = tools.searchClasses(jdbi, "*DTO", 20);
        JsonNode dtoRoot = JSON.readTree(dtoResult);
        assertTrue(dtoRoot.get("classes").toString().contains("UserDTO"),
                "Should find UserDTO from common module");

        String serviceResult = tools.searchClasses(jdbi, "*Service", 20);
        JsonNode serviceRoot = JSON.readTree(serviceResult);
        String serviceJson = serviceRoot.get("classes").toString();
        assertTrue(serviceJson.contains("UserService"));
        assertTrue(serviceJson.contains("EmailNotificationService"));
        assertTrue(serviceJson.contains("SmsNotificationService"));
    }

    @Test
    void classesAndResolutionCandidatesExposeModuleContext() throws Exception {
        var dto = IndexReader.findClassByName(jdbi, "org.acme.common.UserDTO").orElseThrow();
        assertEquals("common", dto.module());
        assertEquals("main", dto.sourceSet());

        JsonNode injections = JSON.readTree(
                new QuillTools().getInjectionPoints(jdbi, "UserService"));
        JsonNode candidate = injections.path("injection_points").get(0)
                .path("resolution_trace").path("candidates").get(0);
        assertEquals("service", candidate.path("module").asText());
        assertEquals("main", candidate.path("source_set").asText());
    }

    @Test
    void classAndBeanQueriesCanBeScopedToModule() throws Exception {
        var queries = new QuillToolQueries();
        JsonNode commonClasses = JSON.readTree(
                queries.searchClasses(jdbi, "*", "common", "main", 20));
        assertEquals(2, commonClasses.path("classes").size());
        assertTrue(commonClasses.path("classes").toString().contains("UserDTO"));
        assertFalse(commonClasses.path("classes").toString().contains("UserService"));

        JsonNode serviceBeans = JSON.readTree(queries.getBeans(
                jdbi, null, null, null, null, null, "service", "main", 20));
        assertEquals(3, serviceBeans.path("beans").size());
        assertTrue(serviceBeans.path("beans").toString().contains("UserService"));
    }

    @Test
    void beansByKindShowsCorrectDistribution() throws Exception {
        var tools = new QuillTools();
        String result = tools.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);

        JsonNode byKind = root.get("beans_by_kind");
        assertNotNull(byKind);
        assertTrue(byKind.has("CLASS"));
        assertEquals(3, byKind.get("CLASS").asInt());
    }

    @Test
    void beansByScopeShowsAllApplicationScoped() throws Exception {
        var tools = new QuillTools();
        String result = tools.getOverview(jdbi);
        JsonNode root = JSON.readTree(result);

        JsonNode byScope = root.get("beans_by_scope");
        assertNotNull(byScope);
        assertTrue(byScope.has("@ApplicationScoped"));
        assertEquals(3, byScope.get("@ApplicationScoped").asInt(),
                "All 3 beans should be @ApplicationScoped");
    }

    @Test
    void metadataContainsProjectRoot() {
        var meta = IndexReader.getMetadata(jdbi);
        assertEquals(PROJECT_ROOT.toString(), meta.get("project_root"));
        assertNotNull(meta.get("indexed_at"));
    }
}
