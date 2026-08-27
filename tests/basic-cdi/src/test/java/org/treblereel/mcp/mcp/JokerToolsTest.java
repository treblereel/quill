package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.JokerDatabase;
import org.treblereel.mcp.model.*;

class JokerToolsTest {

    @TempDir Path tempDir;
    Connection conn;

    @BeforeEach
    void setUp() {
        conn = JokerDatabase.create(tempDir.resolve("test.db"));
        var classes = List.of(
                new ClassRecord(0, "org.acme.OrderService", "CLASS", "java.lang.Object",
                        List.of(), "src/main/java/org/acme/OrderService.java", 10, true, 500),
                new ClassRecord(0, "org.acme.PaymentService", "INTERFACE", null,
                        List.of(), "src/main/java/org/acme/PaymentService.java", 5, false, 100),
                new ClassRecord(0, "org.acme.StripePaymentService", "CLASS", "java.lang.Object",
                        List.of("org.acme.PaymentService"), "src/main/java/org/acme/StripePaymentService.java", 8, true, 300)
        );
        var beans = List.of(
                new BeanRecord(0, 1, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("OrderService", "Object")),
                new BeanRecord(0, 3, "CLASS", "@ApplicationScoped", List.of("@Default"),
                        List.of(), false, null, null, null, null, List.of("PaymentService", "StripePaymentService", "Object"))
        );
        var ips = List.of(
                new InjectionPointRecord(0, 1, "FIELD", "PaymentService",
                        List.of("@Default"), "paymentService", 2, false)
        );
        var deps = List.of(
                new DependencyRecord(1, 3, "CDI_INJECT", 1)
        );
        IndexWriter.write(conn, classes, beans, ips, deps,
                Map.of("indexed_at", "2026-08-26T14:30:00", "last_commit", "abc1234"));
    }

    @Test
    void getBeansReturnsAllBeans() {
        var tools = new JokerTools();
        String result = tools.getBeans(conn, null, null, null, null, null);
        assertTrue(result.contains("OrderService"));
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("\"total\":2"));
    }

    @Test
    void getBeansFiltersbyScope() {
        var tools = new JokerTools();
        String result = tools.getBeans(conn, null, "@ApplicationScoped", null, null, null);
        assertTrue(result.contains("OrderService"));
    }

    @Test
    void getDependenciesShowsOutbound() {
        var tools = new JokerTools();
        String result = tools.getDependencies(conn, "OrderService", "outbound", 1);
        assertTrue(result.contains("StripePaymentService"));
        assertTrue(result.contains("CDI_INJECT"));
    }

    @Test
    void getInjectionPointsShowsResolution() {
        var tools = new JokerTools();
        String result = tools.getInjectionPoints(conn, "OrderService");
        assertTrue(result.contains("PaymentService"));
        assertTrue(result.contains("paymentService"));
        assertTrue(result.contains("unique") || result.contains("resolved_to"));
    }
}
