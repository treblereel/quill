package org.treblereel.mcp.mcp.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.treblereel.mcp.model.BeanRecord;

class BeanTermsTest {

    private static BeanRecord bean(String framework, String kind, String scope, List<String> qualifiers) {
        return new BeanRecord(1, 1, kind, scope, qualifiers, List.of(), false, false, null, null,
                null, null, List.of(), framework);
    }

    @Test
    void springBeansUseSpringScopeKindAndQualifiers() {
        BeanRecord bean = bean(BeanRecord.SPRING, "PRODUCER_METHOD", "@Singleton",
                List.of("@Default", "@Qualifier(\"passwordEncoder\")"));
        assertEquals("BEAN_METHOD", BeanTerms.kind(bean));
        assertEquals("singleton", BeanTerms.scope(bean));
        assertEquals(List.of("@Qualifier(\"passwordEncoder\")"), BeanTerms.qualifiers(bean));
        assertEquals("request", BeanTerms.scope(BeanRecord.SPRING, "@RequestScoped"));
        assertEquals("refresh", BeanTerms.scope(BeanRecord.SPRING, "@refresh"));
    }

    @Test
    void cdiAndUnknownBeansKeepTheirStoredTerms() {
        for (String framework : new String[] {BeanRecord.CDI, null}) {
            BeanRecord bean = bean(framework, "PRODUCER_METHOD", "@Singleton", List.of("@Default", "@Any"));
            assertEquals("PRODUCER_METHOD", BeanTerms.kind(bean));
            assertEquals("@Singleton", BeanTerms.scope(bean));
            assertEquals(List.of("@Default", "@Any"), BeanTerms.qualifiers(bean));
        }
    }

    @Test
    void indexRowsAndFiltersTranslate() {
        assertEquals("[\"@Qualifier(\\\"a\\\")\"]", BeanTerms.qualifiersJson("spring", "[\"@Default\",\"@Qualifier(\\\"a\\\")\"]"));
        assertEquals("[\"@Default\"]", BeanTerms.qualifiersJson("cdi", "[\"@Default\"]"));
        assertEquals("@Singleton", BeanTerms.storedScope("singleton"));
        assertEquals("@ApplicationScoped", BeanTerms.storedScope("@ApplicationScoped"));
        assertEquals("PRODUCER_METHOD", BeanTerms.storedKind("BEAN_METHOD"));
        assertEquals("CLASS", BeanTerms.storedKind("CLASS"));
    }
}
