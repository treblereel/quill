package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

class AdvancedCdiTest {

    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    static final Path QUILL_DIR = PROJECT_ROOT.resolve(".quill");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(QUILL_DIR)) {
            try (var walk = Files.walk(QUILL_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void indexesProducersInterceptorsAndQualifiers() throws Exception {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.indexOnly = true;
        cmd.call();

        Path dbPath = ProjectIndexStore.findDbForHead(PROJECT_ROOT);
        assertNotNull(dbPath, "index db should be created");

        Jdbi jdbi = QuillDatabase.open(dbPath);
        var classes = IndexReader.findAllClasses(jdbi);
        assertTrue(classes.size() >= 6,
                "Should index at least 6 classes (CacheService, InMemory, CacheProducer, ProductService, LoggingInterceptor, annotations)");

        var beans = IndexReader.findBeans(jdbi, null);
        assertTrue(beans.size() >= 3,
                "Should find at least 3 beans (InMemoryCacheService, CacheProducer, ProductService) + producer");

        boolean hasProducer = beans.stream().anyMatch(b -> "PRODUCER_METHOD".equals(b.kind()));
        assertTrue(hasProducer, "Should detect producer method bean from CacheProducer.cachedService()");

        boolean hasInterceptor = beans.stream().anyMatch(b -> "INTERCEPTOR".equals(b.kind()));
        assertTrue(hasInterceptor, "Should detect LoggingInterceptor as an interceptor bean");

        var producerBean = beans.stream()
                .filter(b -> "PRODUCER_METHOD".equals(b.kind()))
                .findFirst().orElseThrow();
        assertTrue(producerBean.qualifiers().contains("@Cached"),
                "Producer bean should carry @Cached qualifier");
        assertNotNull(producerBean.declaringClassId(),
                "Producer bean should reference declaring class");
        assertEquals("cachedService", producerBean.memberName(),
                "Producer bean memberName should be the method name");

        var productServiceClass = classes.stream()
                .filter(c -> c.className().endsWith("ProductService"))
                .findFirst().orElseThrow();
        var productServiceBean = beans.stream()
                .filter(b -> b.classId() == productServiceClass.id())
                .findFirst().orElseThrow();
        var ips = IndexReader.findInjectionPoints(jdbi, productServiceBean.id());
        assertEquals(2, ips.size(), "ProductService should have 2 injection points");

        var qualifiedIp = ips.stream()
                .filter(ip -> ip.qualifiers().contains("@Cached"))
                .findFirst();
        assertTrue(qualifiedIp.isPresent(), "Should have injection point with @Cached qualifier");
        assertNotNull(qualifiedIp.get().resolvedBeanId(),
                "@Cached injection point should resolve to the producer bean");

        var producerFieldBean = beans.stream()
                .filter(b -> "PRODUCER_FIELD".equals(b.kind())
                        && "appSettings".equals(b.memberName()))
                .findFirst();
        assertTrue(producerFieldBean.isPresent(),
                "Should detect producer field bean from SettingsProducer.appSettings");
        assertNotNull(producerFieldBean.get().declaringClassId(),
                "Producer field bean should reference declaring class");
        assertTrue(producerFieldBean.get().beanTypes().contains("java.util.Properties"),
                "Producer field bean types should include field type");
    }
}
