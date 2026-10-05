import json
import unittest

from quill_chain_evidence import chain_evidence
from quill_adoption_scenarios import SCENARIOS, evaluate_capture


class ChainEvidenceTest(unittest.TestCase):
    def test_embedded_json_is_diagnostic_not_a_relaxed_score(self):
        expected = SCENARIOS["call_chain"]["expected"]
        stream = [{"type": "result", "result": "Explanation secret\n" + json.dumps(expected)}]
        evidence = chain_evidence("claude", stream, expected)
        self.assertTrue(evidence["embedded_expected_json"])
        self.assertFalse(evidence["strict_json_object"])
        self.assertGreater(evidence["surrounding_expected_json"]["prefix_chars"], 0)
        self.assertNotIn("secret", json.dumps(evidence))
        self.assertFalse(evaluate_capture("claude", stream, {"returncode": 0}, "call_chain", expected, True,
                                          rubric="routing")["passed"])

    def test_only_whitelisted_arguments_and_response_shapes_are_retained(self):
        stream = [{"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "one",
            "name": "mcp__quill__get_call_hierarchy", "input": {"target": "org.example.GreetingEndpoint",
                "method": "render", "direction": "outbound", "transitive": True, "secret": "credential"}}]}},
            {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "one",
                "content": json.dumps({"calls": [{"secret": "source content"}], "method": "render"})}]}},
            {"type": "result", "result": "not JSON"}]
        evidence = chain_evidence("claude", stream, {})
        self.assertEqual("outbound", evidence["hierarchy_calls"][0]["arguments"]["direction"])
        self.assertEqual(1, evidence["hierarchy_calls"][0]["response_shape"]["calls_count"])
        for secret in ("credential", "source content", "not JSON", '"secret"'):
            self.assertNotIn(secret, json.dumps(evidence))

    def test_malformed_arguments_do_not_crash_and_capture_is_bounded(self):
        event = {"type": "assistant", "message": {"content": [{"type": "tool_use",
            "name": "mcp__quill__get_call_hierarchy", "input": {"method": [], "direction": {}}}]}}
        evidence = chain_evidence("claude", [event] * 20, {})
        self.assertEqual(16, len(evidence["hierarchy_calls"]))

    def test_duplicate_ids_and_other_provider_calls_are_not_double_counted(self):
        event = {"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "one",
            "name": "mcp__quill__get_call_hierarchy", "input": {}}]}}
        self.assertEqual(1, len(chain_evidence("claude", [event, event], {})["hierarchy_calls"]))
        other = {"type": "item.completed", "item": {"type": "mcp_tool_call",
            "tool": "get_call_hierarchy", "server": "other"}}
        self.assertEqual([], chain_evidence("codex", [other], {})["hierarchy_calls"])
