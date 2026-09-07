package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import org.jboss.jandex.Index;
import org.jboss.jandex.Indexer;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.fixture.advanced.*;
import org.treblereel.mcp.model.*;

class ProducerFieldFallbackTest {

    @Test
    void producerFieldCreatedViaArc() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(MetricsProducer.class);
        indexer.indexClass(MetricsConfig.class);
        Index index = indexer.complete();

        var result = BeanResolver.resolve(index);

        var producerFields = result.beans().stream()
                .filter(b -> "PRODUCER_FIELD".equals(b.kind()))
                .toList();
        assertFalse(producerFields.isEmpty(),
                "@Produces field should be discovered as PRODUCER_FIELD bean");

        var metricsField = producerFields.stream()
                .filter(b -> "metricsConfig".equals(b.memberName()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("Producer field 'metricsConfig' not found"));
        assertNotNull(metricsField.declaringClassId());
        assertTrue(metricsField.beanTypes().stream()
                .anyMatch(t -> t.contains("MetricsConfig")));
    }

    @Test
    void producerFieldCreatedInFallback() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(MetricsProducer.class);
        Index index = indexer.complete();

        var result = BeanResolver.resolve(index);

        var producerFields = result.beans().stream()
                .filter(b -> "PRODUCER_FIELD".equals(b.kind()))
                .toList();
        assertFalse(producerFields.isEmpty(),
                "@Produces field should be discovered even when its type is missing from index");

        var metricsField = producerFields.stream()
                .filter(b -> "metricsConfig".equals(b.memberName()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("Producer field 'metricsConfig' not found in fallback"));
        assertNotNull(metricsField.declaringClassId());
        assertTrue(metricsField.beanTypes().stream()
                .anyMatch(t -> t.contains("MetricsConfig")));
    }

    @Test
    void declaringClassBeanCreatedInFallback() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(MetricsProducer.class);
        Index index = indexer.complete();

        var result = BeanResolver.resolve(index);

        assertTrue(result.beans().stream()
                .anyMatch(b -> "CLASS".equals(b.kind())
                        && result.classNameToId().entrySet().stream()
                                .anyMatch(e -> e.getKey().contains("MetricsProducer")
                                        && e.getValue() == b.classId())),
                "MetricsProducer CLASS bean should be created in fallback");
    }

    @Test
    void qualifiedFallbackProducerDoesNotGainDefaultQualifier() throws Exception {
        Indexer indexer = new Indexer();
        indexer.indexClass(CacheProducer.class);
        indexer.indexClass(Cached.class);

        var result = BeanResolver.resolve(indexer.complete());
        var producer = result.beans().stream()
                .filter(b -> "cachedService".equals(b.memberName()))
                .findFirst().orElseThrow();

        assertTrue(producer.qualifiers().contains("@Cached"));
        assertTrue(producer.qualifiers().contains("@Any"));
        assertFalse(producer.qualifiers().contains("@Default"),
                "A bean with a custom CDI qualifier must not receive @Default");
    }
}
