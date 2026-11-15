package org.treblereel.mcp.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class StatedLimitationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** V21 sessions received the same caveat texts in answer after answer. */
    @Test
    void aCaveatIsStatedOncePerServer() throws Exception {
        var stated = new StatedLimitations();
        String first = stated.stateOnce("{\"limitations\":[\"bytecode only\"],\"usages\":{\"limitations\":[\"no reflection\"]}}");
        assertTrue(first.contains("bytecode only") && first.contains("no reflection"), first);

        var second = JSON.readTree(stated.stateOnce("{\"limitations\":[\"bytecode only\",\"new caveat\"],\"usages\":{\"limitations\":[\"no reflection\"]}}"));
        assertEquals("[\"new caveat\"]", second.path("limitations").toString(), second.toString());
        assertFalse(second.path("usages").has("limitations"), second.toString());
        assertEquals(2, second.path("limitations_stated_earlier").asInt(), second.toString());

        assertTrue(new StatedLimitations().stateOnce("{\"limitations\":[\"bytecode only\"]}").contains("bytecode only"),
                "Another server has stated nothing yet");
        assertEquals("not json", stated.stateOnce("not json"));
    }
}
