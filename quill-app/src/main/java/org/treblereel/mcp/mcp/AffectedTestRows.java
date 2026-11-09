package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * An affected test inside a change_session answer is stated by what ranks it: class, score,
 * confidence, the classes it reaches the target through and its co-change count. The file
 * path, evidence kinds and reasons restate those. In V22 CQ-049 twenty ranked tests were 10 KB
 * and each answer carried them up to three times. find_impacted_tests keeps the whole row.
 */
final class AffectedTestRows {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> LISTS = List.of("/plan/test_plan/tests", "/verification/test_evidence/tests",
            "/directive/primary_action/evidence_snapshot/tests");

    private AffectedTestRows() {
    }

    static void compactIn(ObjectNode answer) {
        for (String path : LISTS) {
            if (!(answer.at(path) instanceof ArrayNode tests)) continue;
            for (int i = 0; i < tests.size(); i++) tests.set(i, row(tests.get(i)));
        }
    }

    static ObjectNode row(JsonNode test) {
        ObjectNode row = JSON.createObjectNode();
        String type = test.path("class").asText();
        row.put("class", type);
        if (test.has("score")) row.set("score", test.get("score"));
        if (test.has("confidence")) row.set("confidence", test.get("confidence"));
        String file = test.path("file").asText("");
        if (!file.isEmpty() && !file.equals("src/test/java/" + type.replace('.', '/') + ".java")) row.put("file", file);
        if (test.path("static").isBoolean() && !test.path("static").asBoolean()) row.put("static", false);
        JsonNode path = test.path("dependency_path");
        if (path.size() > 2) {
            ArrayNode via = row.putArray("via");
            for (int i = 1; i < path.size() - 1; i++) via.add(path.get(i).asText());
        }
        if (test.path("co_change_count").asInt() > 0) row.put("co_changed", test.path("co_change_count").asInt());
        return row;
    }
}
