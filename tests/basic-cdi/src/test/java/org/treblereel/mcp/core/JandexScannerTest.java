package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.fixture.*;
import org.treblereel.mcp.model.ClassRecord;

class JandexScannerTest {

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
}
