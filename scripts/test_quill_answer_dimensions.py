import json
import unittest

from quill_answer_dimensions import score_answer, dimension_counts
from quill_adoption_scenarios import _correct, SCENARIOS, evaluate_capture


class AnswerDimensionsTest(unittest.TestCase):
    def score(self, text):
        return score_answer(text, {"tests": ["ExampleTest"]}, (), _correct, False)

    def test_one_embedded_object_is_factual_evidence_not_format_compliance(self):
        value = self.score('Explanation {"tests":["ExampleTest"]} end')
        self.assertTrue(value["facts_correct"])
        self.assertFalse(value["format_valid"])
        self.assertEqual("facts_and_format:v1", value["contract"])

    def test_conflicting_or_duplicate_candidates_are_unknown_not_cherry_picked(self):
        for text, status in (('{"tests":["Wrong"]} {"tests":["ExampleTest"]}', "ambiguous"),
                             ('{"tests":[],"tests":["ExampleTest"]}', "malformed")):
            value = self.score(text)
            self.assertEqual(status, value["fact_status"])
            self.assertIsNone(value["facts_correct"])

    def test_missing_nested_wrong_type_and_extra_keys_do_not_pass(self):
        for text in ('{"other":{"tests":["ExampleTest"]}}', '[{"tests":["ExampleTest"]}]',
                     '{"tests":["ExampleTest"],"extra":1}', 'no JSON'):
            self.assertIsNone(self.score(text)["facts_correct"])
        self.assertFalse(self.score('{"tests":"ExampleTest"}')["facts_correct"])
        self.assertFalse(self.score('{"tests":["ExampleTest","ExampleTest"]}')["facts_correct"])

    def test_limits_do_not_become_incorrect_or_zero_missing_samples(self):
        value = self.score("x" * 8193)
        self.assertEqual("limit", value["fact_status"])
        self.assertIsNone(value["facts_correct"])
        self.assertEqual(0, dimension_counts([{"unavailable": True}])["captured"])

    def test_aggregate_preserves_missing_and_incorrect_denominators(self):
        rows = [{"evaluation": {"answer_dimensions": self.score(text)}} for text in (
            '{"tests":["ExampleTest"]}', '{"tests":["Wrong"]}', 'missing')]
        counts = dimension_counts(rows + [{"unavailable": True}])
        self.assertEqual((3, 2, 1), tuple(counts[key] for key in ("captured", "facts_evaluated", "facts_correct")))

    def test_legacy_pass_is_unchanged_and_factual_gate_keeps_safety_and_route(self):
        expected = SCENARIOS["dependencies"]["expected"]
        call = {"type": "item.completed", "item": {"type": "mcp_tool_call", "server": "quill",
                "tool": "get_dependencies", "status": "completed", "result": {"ok": True}}}
        final = {"type": "item.completed", "item": {"type": "agent_message", "text": "Result " + json.dumps(expected)}}
        for unchanged in (True, False):
            value = evaluate_capture("codex", [call, final], {"returncode": 0, "user_config_unchanged": True},
                "dependencies", expected, unchanged, rubric="routing")
            self.assertFalse(value["passed"])
            self.assertTrue(value["answer_dimensions"]["facts_correct"])
            self.assertEqual(unchanged, value["answer_dimensions"]["factual_workflow_passed"])

    def test_module_evidence_accepts_caller_paths_not_just_module_enumeration(self):
        expected = SCENARIOS["impact_modules"]["expected"]
        def call(tool):
            return {"type": "item.completed", "item": {"type": "mcp_tool_call", "server": "quill",
                "tool": tool, "result": {"ok": True}, "status": "completed"}}
        final = {"type": "item.completed", "item": {"type": "agent_message", "text": json.dumps(expected)}}
        for prefix, valid in (([call("get_module_graph")], False),
                              ([call("get_call_hierarchy"), call("get_module_graph")], True),
                              ([call("find_symbol_usages")], True)):
            value = evaluate_capture("codex", prefix + [final], {"returncode": 0, "user_config_unchanged": True},
                "impact_modules", expected, True, rubric="routing")
            self.assertEqual(valid, value["criteria"]["task_tool_succeeded"])
            self.assertEqual("module_impact:v2", value["evidence_contract"])
