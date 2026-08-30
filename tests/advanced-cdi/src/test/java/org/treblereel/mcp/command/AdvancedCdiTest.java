package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.JokerDatabase;

class AdvancedCdiTest {

    static final Path PROJECT_ROOT = Path.of(System.getProperty("user.dir"));
    static final Path JOKER_DIR = PROJECT_ROOT.resolve(".joker");

    @AfterEach
    void cleanup() throws Exception {
        if (Files.exists(JOKER_DIR)) {
            try (var walk = Files.walk(JOKER_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void indexesProducersInterceptorsAndQualifiers() throws Exception {
        InitCommand cmd = new InitCommand();
        cmd.projectPath = PROJECT_ROOT;
        cmd.run();

        Path dbPath = JOKER_DIR.resolve("index.db");
        assertTrue(Files.exists(dbPath));

        try (Connection conn = JokerDatabase.open(dbPath)) {
            var classes = IndexReader.findAllClasses(conn);
            assertTrue(classes.size() >= 6,
                    "Should index at least 6 classes (CacheService, InMemory, CacheProducer, ProductService, LoggingInterceptor, annotations)");

            var beans = IndexReader.findBeans(conn, null);
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
            var ips = IndexReader.findInjectionPoints(conn, productServiceBean.id());
            assertEquals(2, ips.size(), "ProductService should have 2 injection points");

            var qualifiedIp = ips.stream()
                    .filter(ip -> ip.qualifiers().contains("@Cached"))
                    .findFirst();
            assertTrue(qualifiedIp.isPresent(), "Should have injection point with @Cached qualifier");
            assertNotNull(qualifiedIp.get().resolvedBeanId(),
                    "@Cached injection point should resolve to the producer bean");
        }
    }
}
