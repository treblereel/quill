package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** V41: Codex guessed getTemplate, initHttpSecurity and registerConfigMappings; the answers listed members alphabetically or not at all. */
class NameSimilarityTest {

    @Test void closestNamesComeFirst() {
        assertEquals(List.of("getTemplates", "addTemplate", "createTemplate"),
                NameSimilarity.closest("getTemplate", List.of("addTemplate", "bus", "createTemplate", "getTemplates", "delete"), 3));
        assertEquals("initializeHttpAuthenticatorHandler",
                NameSimilarity.closest("initHttpSecurity", List.of("addInterceptedEndpoint", "applicationBasicAuthRequired",
                        "initializeHttpAuthenticatorHandler", "collectAnnotatedClasses"), 1).getFirst());
    }

    @Test void unrelatedNamesStayAlphabetical() {
        assertEquals(List.of("alpha", "beta"), NameSimilarity.closest("zzz", List.of("beta", "alpha"), 5));
    }
}
