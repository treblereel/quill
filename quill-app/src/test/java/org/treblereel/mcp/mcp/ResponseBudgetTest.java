package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ResponseBudgetTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void leavesResponsesWithinBudgetUntouched() {
        String json = "{\"items\":[1,2,3]}";
        assertEquals(json, ResponseBudget.apply(json, 1024));
    }

    @Test
    void truncatesLargestArraysAndReportsOmissions() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        var items = root.putArray("items");
        for (int i = 0; i < 100; i++) {
            items.addObject().put("id", i).put("payload", "x".repeat(100));
        }

        String result = ResponseBudget.apply(root.toString(), 2048);
        var parsed = JSON.readTree(result);

        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 2048);
        assertTrue(parsed.path("_response_budget").path("truncated").asBoolean());
        assertEquals(100, parsed.path("_response_budget").path("original_bytes").asInt() > 0
                ? parsed.path("items").size()
                        + parsed.path("_response_budget").path("omitted_items").path("items").asInt()
                : -1);
        assertTrue(parsed.path("items").size() < 100);
    }
}
