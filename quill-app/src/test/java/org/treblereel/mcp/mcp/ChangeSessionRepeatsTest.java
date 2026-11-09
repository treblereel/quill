package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeSessionRepeatsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode snapshot(String change, String receipt) {
        ObjectNode root = JSON.createObjectNode().put("schema_version", 1).put("session_id", change.hashCode() + "")
                .put("change", change).put("phase", "planned");
        root.putObject("phase_gate").put("status", "blocked");
        root.putObject("verification").put("verdict", "needs_build").put("evidence", "v".repeat(2000));
        root.putObject("verification_receipt").put("receipt_id", receipt).put("observed", "o".repeat(1000));
        root.putArray("next_actions").addObject().put("action", "verify").put("action_id", "a1");
        root.putObject("directive").put("status", "planned").putObject("primary_action")
                .put("action", "verify").put("action_id", "a1").put("evidence_snapshot", "e".repeat(1500));
        return root;
    }

    /** V21 CQ-049: Codex repeated change_session 12 times; each answer restated 5-7 KB of unchanged evidence. */
    @Test
    void aRepeatStatesOnlyWhatChanged() throws Exception {
        var repeats = new ChangeSessionRepeats();
        String first = repeats.elide("k", snapshot("rename parse", "r1").toString());
        assertTrue(first.contains("v".repeat(2000)), "The first answer is whole");

        var second = JSON.readTree(repeats.elide("k", snapshot("renamed parse; tests added", "r2").toString()));
        assertEquals("needs_build", second.path("verification").path("verdict").asText(), second.toString());
        assertFalse(second.path("verification").has("evidence"), second.toString());
        assertEquals("r2", second.path("verification_receipt").path("receipt_id").asText(), second.toString());
        assertFalse(second.path("verification_receipt").has("observed"), second.toString());
        assertEquals("a1", second.path("directive").path("primary_action").path("action_id").asText());
        assertFalse(second.path("directive").path("primary_action").has("evidence_snapshot"), second.toString());
        assertEquals("planned", second.path("phase").asText(), "Identity and phase are always stated");
        String unchanged = second.path("unchanged_since_previous_call").toString();
        assertTrue(unchanged.contains("verification.evidence") && unchanged.contains("verification_receipt.observed")
                && unchanged.contains("directive.primary_action.evidence_snapshot"), unchanged);
        assertTrue(second.toString().length() < 700, second.toString());
    }

    @Test
    void otherTargetsAndErrorsGetTheWholeAnswer() throws Exception {
        var repeats = new ChangeSessionRepeats();
        repeats.elide("k", snapshot("a", "r1").toString());
        assertTrue(repeats.elide("other", snapshot("a", "r1").toString()).contains("v".repeat(2000)));
        assertTrue(repeats.elide("k", "{\"error_code\":\"X\"}").contains("error_code"));
    }

    /** V22 CQ-049: Codex asked detail=full, then view=verification detail=full; both restated the same 10 KB. */
    @Test
    void aFullAnswerAfterAnotherViewStatesOnlyWhatIsNew() throws Exception {
        var repeats = new ChangeSessionRepeats();
        ObjectNode plan = snapshot("a", "r1");
        plan.remove(List.of("verification", "verification_receipt"));
        plan.putObject("plan").put("tests", "t".repeat(3000));
        repeats.elide(ChangeSessionRepeats.key("", List.of("p.A"), 20), plan.toString());

        ObjectNode verification = snapshot("a", "r1");
        verification.putObject("plan").put("tests", "t".repeat(3000));
        var answer = JSON.readTree(repeats.elide(ChangeSessionRepeats.key("", List.of("p.A"), 20), verification.toString()));
        assertTrue(answer.path("verification").path("evidence").asText().length() == 2000, "Sections the first view lacked are whole");
        assertFalse(answer.has("plan"), answer.toString());
        assertTrue(answer.path("unchanged_since_previous_call").toString().contains("plan"), answer.toString());
    }

    /** V21: the plan restated next_actions as plan.sequence and each primary change inside the directive. */
    @Test
    void anAnswerStatesItsPlanOnce() throws Exception {
        ObjectNode root = JSON.createObjectNode().put("phase", "planned");
        root.putArray("next_actions").addObject().put("order", 1).put("action", "inspect_primary").put("action_id", "a1");
        ObjectNode plan = root.putObject("plan");
        plan.putArray("sequence").addObject().put("order", 1).put("action", "inspect_primary");
        plan.putArray("primary_changes").addObject().put("class", "p.A").put("file", "A.java").put("risk_level", "MEDIUM");
        root.putObject("directive").putObject("primary_action").put("action_id", "a1").putArray("evidence_snapshot")
                .addObject().put("class", "p.A").put("file", "A.java").put("risk_level", "MEDIUM").put("coupling", "fan_in 6");

        var answer = JSON.readTree(new ChangeSessionRepeats().elide("k", root.toString()));
        assertFalse(answer.path("plan").has("sequence"), answer.toString());
        var snapshot = answer.path("directive").path("primary_action").path("evidence_snapshot").get(0);
        assertEquals("p.A", snapshot.path("class").asText());
        assertEquals("fan_in 6", snapshot.path("coupling").asText());
        assertFalse(snapshot.has("file") || snapshot.has("risk_level"), snapshot.toString());
        assertEquals("plan.primary_changes", snapshot.path("same_fields_as").asText(), snapshot.toString());
    }
}
