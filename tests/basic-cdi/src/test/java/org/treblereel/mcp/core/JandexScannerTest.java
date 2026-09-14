package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.fixture.*;
import org.treblereel.mcp.model.ClassRecord;

class JandexScannerTest {

    @TempDir Path tempDir;
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
    void extractsAllClasses() {
        List<ClassRecord> classes = JandexScanner.extractClasses(index);
        assertEquals(6, classes.size());
    }

    @Test
    void identifiesInterfaces() {
        List<ClassRecord> classes = JandexScanner.extractClasses(index);
        ClassRecord paymentService = classes.stream()
                .filter(c -> c.className().endsWith("PaymentService"))
                .findFirst().orElseThrow();
        assertEquals("INTERFACE", paymentService.kind());
    }

    @Test
    void identifiesRecords() {
        List<ClassRecord> classes = JandexScanner.extractClasses(index);
        ClassRecord dto = classes.stream()
                .filter(c -> c.className().endsWith("OrderDTO"))
                .findFirst().orElseThrow();
        assertEquals("RECORD", dto.kind());
    }

    @Test
    void identifiesAnnotations() {
        List<ClassRecord> classes = JandexScanner.extractClasses(index);
        ClassRecord premium = classes.stream()
                .filter(c -> c.className().endsWith("Premium"))
                .findFirst().orElseThrow();
        assertEquals("ANNOTATION", premium.kind());
    }

    @Test
    void marksBeans() {
        List<ClassRecord> classes = JandexScanner.extractClasses(index);
        ClassRecord stripe = classes.stream()
                .filter(c -> c.className().endsWith("StripePaymentService"))
                .findFirst().orElseThrow();
        assertTrue(stripe.isBean());

        ClassRecord dto = classes.stream()
                .filter(c -> c.className().endsWith("OrderDTO"))
                .findFirst().orElseThrow();
        assertFalse(dto.isBean());
    }

    @Test
    void capturesSuperclassAndInterfaces() {
        List<ClassRecord> classes = JandexScanner.extractClasses(index);
        ClassRecord stripe = classes.stream()
                .filter(c -> c.className().endsWith("StripePaymentService"))
                .findFirst().orElseThrow();
        assertTrue(stripe.interfaces().stream().anyMatch(i -> i.contains("PaymentService")));
    }

    @Test
    void resolvesSourcesInRootOrderAndCountsTokens() throws Exception {
        Path relative = Path.of("org/treblereel/mcp/fixture/PaymentService.java");
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        Files.createDirectories(first.resolve(relative).getParent());
        Files.createDirectories(second.resolve(relative).getParent());
        Files.writeString(first.resolve(relative),
                "package org.treblereel.mcp.fixture; public interface PaymentService {}");
        Files.writeString(second.resolve(relative), "ignored duplicate source");

        ClassRecord paymentService = JandexScanner.extractClasses(index, List.of(first, second))
                .stream()
                .filter(record -> record.className().endsWith("PaymentService"))
                .findFirst().orElseThrow();

        assertEquals(first.resolve(relative).toString(), paymentService.sourceFile());
        assertTrue(paymentService.sourceTokens() > 0);
    }

    @Test
    void countsMultipleMatchedSourcesExactly() throws Exception {
        Path sourceRoot = tempDir.resolve("sources");
        Map<String, String> sources = Map.of(
                "PaymentService", "package org.treblereel.mcp.fixture; interface PaymentService {}",
                "StripePaymentService", "package org.treblereel.mcp.fixture; class StripePaymentService {}",
                "OrderDTO", "package org.treblereel.mcp.fixture; record OrderDTO(String id) {}");
        for (var entry : sources.entrySet()) {
            Path source = sourceRoot.resolve("org/treblereel/mcp/fixture/")
                    .resolve(entry.getKey() + ".java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, entry.getValue());
        }

        Map<String, ClassRecord> records = JandexScanner.extractClasses(index, List.of(sourceRoot))
                .stream().collect(Collectors.toMap(
                        record -> record.className().substring(record.className().lastIndexOf('.') + 1),
                        Function.identity()));

        sources.forEach((className, source) -> assertEquals(
                TokenCounter.count(source), records.get(className).sourceTokens()));
    }

    @Test
    void resolvesKotlinSourceAndCountsTokens() throws Exception {
        Path relative = Path.of("org/treblereel/mcp/fixture/PaymentService.kt");
        Path sourceRoot = tempDir.resolve("kotlin");
        Files.createDirectories(sourceRoot.resolve(relative).getParent());
        String source = "package org.treblereel.mcp.fixture\ninterface PaymentService";
        Files.writeString(sourceRoot.resolve(relative), source);

        ClassRecord paymentService = JandexScanner.extractClasses(index, List.of(sourceRoot))
                .stream()
                .filter(record -> record.className().endsWith("PaymentService"))
                .findFirst().orElseThrow();

        assertEquals(sourceRoot.resolve(relative).toString(), paymentService.sourceFile());
        assertEquals(TokenCounter.count(source), paymentService.sourceTokens());
    }
}
