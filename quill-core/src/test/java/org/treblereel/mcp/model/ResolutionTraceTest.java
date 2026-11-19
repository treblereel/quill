package org.treblereel.mcp.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResolutionTraceTest {
    @Test
    void readsExistingTraceAndPreservesWireValues() throws Exception {
        var json = new ObjectMapper();
        String stored = """
                {"candidates":[{"beanId":1,"className":"example.Base","relatedBeanId":2,
                  "disposition":"EXCLUDED","reason":"SPECIALIZED_BY",
                  "rules":["TYPE_ASSIGNABILITY","SPECIALIZATION"]}],
                 "appliedRules":["TYPE_ASSIGNABILITY","SPECIALIZATION"],
                 "unsupportedRules":["RUNTIME_CDI_EXTENSIONS"]}
                """;
        ResolutionTrace trace = json.readValue(stored, ResolutionTrace.class);
        assertEquals(ResolutionReason.SPECIALIZED_BY, trace.candidates().getFirst().reason());
        assertEquals(ResolutionRule.SPECIALIZATION, trace.appliedRules().getLast());
        assertEquals(json.readTree(stored), json.valueToTree(trace));
        assertEquals(trace, json.readValue(json.writeValueAsString(trace), ResolutionTrace.class));
        ResolutionCandidate remapped = trace.candidates().getFirst().withBeanIds(10, 20);
        assertEquals(ResolutionReason.SPECIALIZED_BY, remapped.reason());
        assertEquals(20, remapped.relatedBeanId());
    }

    @Test
    void keepsQualifierSpellingsAndContextMessages() {
        assertEquals("@Default", BuiltinQualifier.DEFAULT.annotation());
        assertEquals("@Any", BuiltinQualifier.ANY.annotation());
        assertEquals("@Named", BuiltinQualifier.NAMED.annotation());
        assertEquals("Resolved in application module context 'app'.",
                ResolutionMessages.resolvedInApplicationContext("app"));
        assertEquals("Resolution differs across application contexts: [a, b].",
                ResolutionMessages.differingApplicationContexts(java.util.List.of("a", "b")));
    }
}
