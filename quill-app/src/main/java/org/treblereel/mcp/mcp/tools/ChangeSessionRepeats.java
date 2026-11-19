package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.Json;

/**
 * A repeated change_session for the same targets states only what changed since this server process
 * last answered it, whatever the view or detail. In V21 CQ-049 Codex asked 12 times and each answer
 * restated 5-7 KB the agent already held; in V22 it asked detail=full for the plan view and then
 * for the verification view, and both restated the same affected tests. Sections a previous answer
 * lacked are stated whole.
 */
public final class ChangeSessionRepeats {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int MAX_KEYS = 16;

    /** Below this a pointer saves too little to be worth reading. */
    private static final int MIN_ELIDED_BYTES = 120;

    /** What the answer is about and where it stands is always stated. */
    private static final Set<String> ALWAYS =
            Set.of(
                    "schema_version",
                    "session_id",
                    "change",
                    "phase",
                    "phase_gate",
                    "state_model",
                    "detail",
                    "view",
                    "view_requested",
                    "targets",
                    "next_actions");

    /** An object holding one of these is kept and compared inside: the agent acts on them. */
    private static final Set<String> KEEP =
            Set.of("action", "action_id", "status", "verdict", "verified", "receipt_id");

    private static final String UNCHANGED = "unchanged_since_previous_call";
    private static final String[][] TEST_LISTS = {
        {"/verification/test_evidence/tests", "/plan/test_plan/tests"},
        {"/plan/test_plan/tests", "/verification/test_evidence/tests"}
    };

    /**
     * In V22 CQ-049 the patch went to a disposable checkout and Codex asked 3-4 times, getting
     * inspect_primary each time.
     */
    private static final String NO_EDITS =
            "none in this indexed checkout since the previous call. Asking again will not advance"
                    + " the phase. For edits in another checkout pass checkout=<its path> once, verify them with that checkout's build"
                    + " and say Quill evidence does not cover them.";

    private final Map<String, ObjectNode> previous =
            new LinkedHashMap<>(MAX_KEYS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) {
                    return size() > MAX_KEYS;
                }
            };

    /**
     * Targets are not part of the key: only values equal to the previous answer are elided, so a
     * changed target list is stated and the rest still is not. In V23 CQ-049 Codex added its new
     * test file to the targets and got the whole 22 KB answer again.
     */
    static String key(String project, int limit) {
        return project + "|" + limit;
    }

    public synchronized ObjectNode elide(String key, ObjectNode response) {
        ObjectNode current = response;
        if (current.has("error_code") || current.has("error")) return response;
        boolean restated = stateOnce(current);
        ObjectNode before = previous.get(key);
        ObjectNode known = before == null ? JSON.createObjectNode() : before.deepCopy();
        known.setAll(current.deepCopy());
        previous.put(key, known);
        if (before == null) return response;
        restated |= sameTests(current, before, " in the previous call");
        boolean noEdits =
                "planned".equals(before.path("phase").asText())
                        && "planned".equals(current.path("phase").asText())
                        && current.path("directive") instanceof ObjectNode;
        ArrayNode unchanged = JSON.createArrayNode();
        elide(before, current, "", unchanged, 0);
        if (noEdits) {
            ObjectNode directive = current.with("directive");
            directive.put("edits_observed", NO_EDITS);
            restated = true;
        }
        if (unchanged.isEmpty()) return response;
        current.set(UNCHANGED, unchanged);
        return current;
    }

    /**
     * Equal fields go; differing objects are compared one level further, so a new receipt id keeps
     * only itself.
     */
    private static void elide(
            ObjectNode before, ObjectNode current, String path, ArrayNode unchanged, int depth) {
        for (String field : List.copyOf(current.propertyStream().map(Map.Entry::getKey).toList())) {
            if (depth == 0 && ALWAYS.contains(field) || field.equals(UNCHANGED)) continue;
            JsonNode was = before.get(field), now = current.get(field);
            if (was == null) continue;
            // The directive is what Codex prints of an answer; it is compared inside, never dropped
            // whole.
            boolean keyed =
                    now instanceof ObjectNode object
                            && (KEEP.stream().anyMatch(object::has)
                                    || depth == 0 && field.equals("directive"));
            if (was.equals(now) && !keyed) {
                if (now.toString().length() >= MIN_ELIDED_BYTES) {
                    current.remove(field);
                    unchanged.add(path + field);
                }
            } else if (depth < 3
                    && was instanceof ObjectNode wasObject
                    && now instanceof ObjectNode nowObject) {
                elide(wasObject, nowObject, path + field + ".", unchanged, depth + 1);
            } else if (was instanceof ArrayNode wasRows && now instanceof ArrayNode nowRows) {
                ObjectNode rows = changedRows(wasRows, nowRows);
                if (rows != null
                        && rows.toString().length() + MIN_ELIDED_BYTES <= now.toString().length()) {
                    current.set(field, rows);
                }
            }
        }
    }

    /**
     * Rows keyed by class are stated by what differs from the previous call: changed rows keep only
     * their class and differing fields. In V23 CQ-049 a second target re-scored 11 of the same 20
     * affected tests and all 20 were stated again.
     */
    private static ObjectNode changedRows(ArrayNode before, ArrayNode current) {
        Map<String, JsonNode> previous = new LinkedHashMap<>();
        for (JsonNode row : before) {
            if (!row.path("class").isTextual()
                    || previous.put(row.get("class").asText(), row) != null) return null;
        }
        ObjectNode result = JSON.createObjectNode();
        ArrayNode changed = JSON.createArrayNode(), added = JSON.createArrayNode();
        Set<String> seen = new java.util.HashSet<>();
        int same = 0;
        for (JsonNode row : current) {
            if (!(row instanceof ObjectNode object) || !row.path("class").isTextual()) return null;
            String type = row.get("class").asText();
            if (!seen.add(type)) return null;
            JsonNode was = previous.get(type);
            if (was == null) added.add(row);
            else if (was.equals(row)) same++;
            else {
                ObjectNode difference = JSON.createObjectNode().put("class", type);
                object.propertyStream()
                        .filter(entry -> !entry.getValue().equals(was.get(entry.getKey())))
                        .forEach(entry -> difference.set(entry.getKey(), entry.getValue()));
                was.propertyStream()
                        .map(Map.Entry::getKey)
                        .filter(key -> !object.has(key))
                        .forEach(difference::putNull);
                changed.add(difference);
            }
        }
        result.put("basis", "previous_call");
        result.put("unchanged_count", same);
        if (!changed.isEmpty()) result.set("changed", changed);
        if (!added.isEmpty()) result.set("added", added);
        ArrayNode removed = JSON.createArrayNode();
        previous.keySet().stream().filter(type -> !seen.contains(type)).forEach(removed::add);
        if (!removed.isEmpty()) result.set("removed", removed);
        return result;
    }

    /**
     * The plan and the verification rank the same affected tests; a list equal to the other one is
     * named, not repeated.
     */
    private static boolean sameTests(ObjectNode answer, JsonNode source, String where) {
        boolean changed = false;
        for (String[] pair : TEST_LISTS) {
            JsonNode tests = answer.at(pair[0]), other = source.at(pair[1]);
            if (tests.isArray()
                    && !tests.isEmpty()
                    && tests.equals(other)
                    && answer.at(parent(pair[0])) instanceof ObjectNode owner) {
                owner.put("tests", "same as " + pair[1].substring(1).replace('/', '.') + where);
                changed = true;
            }
        }
        return changed;
    }

    private static String parent(String pointer) {
        return pointer.substring(0, pointer.lastIndexOf('/'));
    }

    /**
     * plan.sequence restates next_actions, and a directive snapshot restates the primary change it
     * is about.
     */
    private static boolean stateOnce(ObjectNode answer) {
        boolean changed = sameTests(answer, answer, "");
        if (answer.path("plan") instanceof ObjectNode plan
                && answer.path("next_actions").isArray()
                && plan.has("sequence")) {
            plan.remove("sequence");
            changed = true;
        }
        JsonNode primary = answer.path("plan").path("primary_changes");
        if (!primary.isArray()) return changed;
        for (JsonNode item :
                answer.path("directive").path("primary_action").path("evidence_snapshot")) {
            if (!(item instanceof ObjectNode snapshot)) continue;
            for (JsonNode candidate : primary) {
                if (!candidate.path("class").equals(snapshot.path("class"))) continue;
                List<String> same =
                        candidate
                                .propertyStream()
                                .map(Map.Entry::getKey)
                                .filter(
                                        field ->
                                                !field.equals("class")
                                                        && candidate
                                                                .get(field)
                                                                .equals(snapshot.get(field)))
                                .toList();
                if (same.isEmpty()) break;
                snapshot.remove(same);
                snapshot.put("same_fields_as", "plan.primary_changes");
                changed = true;
                break;
            }
        }
        return changed;
    }
}
