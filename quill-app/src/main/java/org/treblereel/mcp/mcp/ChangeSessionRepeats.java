package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A repeated change_session for the same targets states only what changed since this server
 * process last answered it, whatever the view or detail. In V21 CQ-049 Codex asked 12 times and
 * each answer restated 5-7 KB the agent already held; in V22 it asked detail=full for the plan
 * view and then for the verification view, and both restated the same affected tests.
 * Sections a previous answer lacked are stated whole.
 */
final class ChangeSessionRepeats {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_KEYS = 16;
    /** Below this a pointer saves too little to be worth reading. */
    private static final int MIN_ELIDED_BYTES = 120;
    /** What the answer is about and where it stands is always stated. */
    private static final Set<String> ALWAYS = Set.of("schema_version", "session_id", "change", "phase", "phase_gate",
            "state_model", "detail", "view", "view_requested", "targets", "next_actions");
    /** An object holding one of these is kept and compared inside: the agent acts on them. */
    private static final Set<String> KEEP = Set.of("action", "action_id", "status", "verdict", "verified", "receipt_id");
    private static final String UNCHANGED = "unchanged_since_previous_call";
    /** In V22 CQ-049 the patch went to a disposable checkout and Codex asked 3-4 times, getting inspect_primary each time. */
    private static final String NO_EDITS = "none in this indexed checkout since the previous call. Edits made in another checkout are"
            + " outside this index: verify them with that checkout's build and say Quill evidence does not cover them."
            + " Asking again will not advance the phase.";

    private final Map<String, ObjectNode> previous = new LinkedHashMap<>(MAX_KEYS, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) { return size() > MAX_KEYS; }
    };

    static String key(String project, List<String> targets, int limit) {
        return project + "|" + targets.stream().sorted().toList() + "|" + limit;
    }

    synchronized String elide(String key, String response) {
        JsonNode parsed;
        try {
            parsed = JSON.readTree(response);
        } catch (Exception notJson) {
            return response;
        }
        if (!(parsed instanceof ObjectNode current) || current.has("error_code") || current.has("error")) return response;
        boolean restated = stateOnce(current);
        ObjectNode before = previous.get(key);
        ObjectNode known = before == null ? JSON.createObjectNode() : before.deepCopy();
        known.setAll(current.deepCopy());
        previous.put(key, known);
        if (before == null) return restated ? current.toString() : response;
        boolean noEdits = "planned".equals(before.path("phase").asText()) && "planned".equals(current.path("phase").asText())
                && current.path("directive") instanceof ObjectNode;
        ArrayNode unchanged = JSON.createArrayNode();
        elide(before, current, "", unchanged, 0);
        if (noEdits) {
            ObjectNode directive = current.with("directive");
            directive.put("edits_observed", NO_EDITS);
            restated = true;
        }
        if (unchanged.isEmpty()) return restated ? current.toString() : response;
        current.set(UNCHANGED, unchanged);
        return current.toString();
    }

    /** Equal fields go; differing objects are compared one level further, so a new receipt id keeps only itself. */
    private static void elide(ObjectNode before, ObjectNode current, String path, ArrayNode unchanged, int depth) {
        for (String field : List.copyOf(current.propertyStream().map(Map.Entry::getKey).toList())) {
            if (depth == 0 && ALWAYS.contains(field) || field.equals(UNCHANGED)) continue;
            JsonNode was = before.get(field), now = current.get(field);
            if (was == null) continue;
            boolean keyed = now instanceof ObjectNode object && KEEP.stream().anyMatch(object::has);
            if (was.equals(now) && !keyed) {
                if (now.toString().length() >= MIN_ELIDED_BYTES) {
                    current.remove(field);
                    unchanged.add(path + field);
                }
            } else if (depth < 3 && was instanceof ObjectNode wasObject && now instanceof ObjectNode nowObject) {
                elide(wasObject, nowObject, path + field + ".", unchanged, depth + 1);
            }
        }
    }

    /** plan.sequence restates next_actions, and a directive snapshot restates the primary change it is about. */
    private static boolean stateOnce(ObjectNode answer) {
        boolean changed = false;
        if (answer.path("plan") instanceof ObjectNode plan && answer.path("next_actions").isArray() && plan.has("sequence")) {
            plan.remove("sequence");
            changed = true;
        }
        JsonNode primary = answer.path("plan").path("primary_changes");
        if (!primary.isArray()) return changed;
        for (JsonNode item : answer.path("directive").path("primary_action").path("evidence_snapshot")) {
            if (!(item instanceof ObjectNode snapshot)) continue;
            for (JsonNode candidate : primary) {
                if (!candidate.path("class").equals(snapshot.path("class"))) continue;
                List<String> same = candidate.propertyStream().map(Map.Entry::getKey)
                        .filter(field -> !field.equals("class") && candidate.get(field).equals(snapshot.get(field))).toList();
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
