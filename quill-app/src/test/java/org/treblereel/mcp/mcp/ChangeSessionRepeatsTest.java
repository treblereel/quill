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
        assertTrue(second.toString().length() < 1000, "The note that no edits are visible is the rest: " + second);
    }

    private static ObjectNode ranked(int firstScore, String... classes) {
        ObjectNode root = snapshot("rename parse", "r1");
        var tests = root.putObject("plan").putObject("test_plan").putArray("tests");
        for (int i = 0; i < classes.length; i++) {
            tests.addObject().put("class", "org.acme.tests." + classes[i]).put("score", i < 3 ? firstScore : 50)
                    .put("confidence", "medium").put("file", "src/test/java/org/acme/tests/" + classes[i] + ".java");
        }
        return root;
    }

    /** V23 CQ-049: a second target re-scored 11 of the same 20 tests and all 20 rows were stated again. */
    @Test
    void aRepeatedTestListStatesOnlyTheRowsThatChanged() throws Exception {
        var repeats = new ChangeSessionRepeats();
        repeats.elide("k", ranked(100, "A", "B", "C", "D", "E", "F", "G", "Old").toString());

        var again = JSON.readTree(repeats.elide("k", ranked(90, "A", "B", "C", "D", "E", "F", "G", "New").toString()));
        var tests = again.at("/plan/test_plan/tests");
        assertEquals(4, tests.path("unchanged_count").asInt(), again.toString());
        assertEquals(3, tests.path("changed").size(), again.toString());
        assertEquals(90, tests.path("changed").get(0).path("score").asInt());
        assertEquals("org.acme.tests.A", tests.path("changed").get(0).path("class").asText());
        assertFalse(tests.path("changed").get(0).has("file"), "Unchanged fields of a changed row are not restated");
        assertEquals("org.acme.tests.New", tests.path("added").get(0).path("class").asText());
        assertEquals("org.acme.tests.Old", tests.path("removed").get(0).asText());
    }

    @Test
    void anotherProjectAndErrorsGetTheWholeAnswer() throws Exception {
        var repeats = new ChangeSessionRepeats();
        repeats.elide(ChangeSessionRepeats.key("a", 20), snapshot("a", "r1").toString());
        assertTrue(repeats.elide(ChangeSessionRepeats.key("b", 20), snapshot("a", "r1").toString()).contains("v".repeat(2000)));
        assertTrue(repeats.elide("k", "{\"error_code\":\"X\"}").contains("error_code"));
    }

    /** V22 CQ-049: the patch went to a disposable checkout; Codex asked 3-4 times and got inspect_primary each time. */
    @Test
    void aRepeatThatSeesNoEditsSaysSoAndThatAskingAgainWillNotAdvance() throws Exception {
        var repeats = new ChangeSessionRepeats();
        String first = repeats.elide("k", snapshot("rename parse", "r1").toString());
        assertFalse(first.contains("edits_observed"), "Before editing, planned is the expected phase");

        var again = JSON.readTree(repeats.elide("k", snapshot("renamed parse in /tmp/copy", "r1").toString()));
        String observed = again.path("directive").path("edits_observed").asText();
        assertTrue(observed.contains("another checkout") && observed.contains("checkout=") && observed.contains("build") && observed.contains("will not advance"), again.toString());
        assertEquals("a1", again.path("directive").path("primary_action").path("action_id").asText(), again.toString());

        ObjectNode edited = snapshot("renamed parse", "r1").put("phase", "verification_required");
        assertFalse(repeats.elide("k", edited.toString()).contains("edits_observed"));
    }

    /** V22 CQ-049: the plan view stated the affected tests as plan.test_plan.tests, the verification view again as test_evidence.tests. */
    @Test
    void theSameAffectedTestsAreStatedOnceAcrossViewsAndWithinAnAnswer() throws Exception {
        var repeats = new ChangeSessionRepeats();
        String tests = "[{\"class\":\"p.ATest\",\"score\":100},{\"class\":\"p.BTest\",\"score\":70}]";
        ObjectNode plan = (ObjectNode) JSON.readTree("{\"phase\":\"planned\",\"plan\":{\"test_plan\":{\"tests\":" + tests + "}}}");
        repeats.elide("k", plan.toString());
        ObjectNode verification = (ObjectNode) JSON.readTree("{\"phase\":\"planned\",\"verification\":{\"test_evidence\":{\"total\":2,\"tests\":" + tests + "}}}");
        var answer = JSON.readTree(repeats.elide("k", verification.toString()));
        assertEquals("same as plan.test_plan.tests in the previous call", answer.at("/verification/test_evidence/tests").asText(), answer.toString());

        ObjectNode all = (ObjectNode) JSON.readTree("{\"phase\":\"planned\",\"plan\":{\"test_plan\":{\"tests\":" + tests + "}},"
                + "\"verification\":{\"test_evidence\":{\"tests\":" + tests + "}}}");
        var once = JSON.readTree(new ChangeSessionRepeats().elide("k", all.toString()));
        assertEquals(2, once.at("/plan/test_plan/tests").size(), once.toString());
        assertEquals("same as plan.test_plan.tests", once.at("/verification/test_evidence/tests").asText(), once.toString());
    }

    /** V22 CQ-049: Codex asked detail=full, then view=verification detail=full; both restated the same 10 KB. */
    @Test
    void aFullAnswerAfterAnotherViewStatesOnlyWhatIsNew() throws Exception {
        var repeats = new ChangeSessionRepeats();
        ObjectNode plan = snapshot("a", "r1");
        plan.remove(List.of("verification", "verification_receipt"));
        plan.putObject("plan").put("tests", "t".repeat(3000));
        repeats.elide(ChangeSessionRepeats.key("", 20), plan.toString());

        ObjectNode verification = snapshot("a", "r1");
        verification.putObject("plan").put("tests", "t".repeat(3000));
        var answer = JSON.readTree(repeats.elide(ChangeSessionRepeats.key("", 20), verification.toString()));
        assertTrue(answer.path("verification").path("evidence").asText().length() == 2000, "Sections the first view lacked are whole");
        assertFalse(answer.has("plan"), answer.toString());
        assertTrue(answer.path("unchanged_since_previous_call").toString().contains("plan"), answer.toString());
    }

    /** V23 CQ-049: Codex added the new test file to its targets on the second call and got all 22 KB again. */
    @Test
    void aRepeatWithOneMoreTargetStatesOnlyWhatChanged() throws Exception {
        var repeats = new ChangeSessionRepeats();
        ObjectNode first = snapshot("rename parse", "r1");
        first.putArray("targets").add("p.Mapper");
        repeats.elide(ChangeSessionRepeats.key("", 20), first.toString());
        ObjectNode second = snapshot("renamed parse; test added", "r1");
        second.putArray("targets").add("p.Mapper").add("src/test/java/p/MapperValueTest.java");
        var answer = JSON.readTree(repeats.elide(ChangeSessionRepeats.key("", 20), second.toString()));
        assertEquals(2, answer.path("targets").size(), "What changed is stated: " + answer);
        assertFalse(answer.path("verification").has("evidence"), answer.toString());
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
