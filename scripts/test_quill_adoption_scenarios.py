import json
import unittest
import tempfile
from pathlib import Path

from quill_adoption_scenarios import SCENARIOS, evaluate_capture, fixture_snapshot, native_trace, prompt_for, summarize


def codex_call(name, result=None, identifier=None, **extra):
    item = {"type": "mcp_tool_call", "server": "quill", "tool": name,
            "status": "completed", "result": result if result is not None else {"structuredContent": {"ok": True}}}
    if identifier:
        item["id"] = identifier
    item.update(extra)
    return {"type": "item.completed", "item": item}


def final(value):
    return {"type": "item.completed", "item": {"type": "agent_message", "text": json.dumps(value)}}


class AdoptionScenariosTest(unittest.TestCase):
    def score(self, stream, scenario="dependencies", expected=None, **capture):
        return evaluate_capture("codex", stream,
            {"returncode": 0, "timeout": False, "user_config_unchanged": True, **capture},
            scenario, expected or SCENARIOS[scenario]["expected"], True)

    def test_prompts_do_not_name_provider_tools_or_instructions(self):
        for scenario in SCENARIOS:
            for word in ("quill", "get_overview", "change_session", "mcp", "installed instructions"):
                self.assertNotIn(word, prompt_for(scenario).lower())

    def test_requires_overview_and_task_success_and_correct_facts(self):
        stream = [codex_call("get_overview"), codex_call("get_dependencies"),
                  final(SCENARIOS["dependencies"]["expected"])]
        self.assertTrue(self.score(stream)["passed"])
        self.assertFalse(self.score(stream[1:])["passed"])
        self.assertFalse(self.score([stream[0], stream[-1]])["passed"])
        self.assertFalse(self.score(stream[:-1] + [final({"dependencies": []})])["passed"])

    def test_failed_and_missing_results_do_not_count_as_adoption(self):
        for payload, extra in [({"isError": True, "content": []}, {}),
                               ({"structuredContent": {"error_code": "FAILURE"}}, {}),
                               ({"structured_content": {"error_code": "FAILURE"}}, {}),
                               ({"content": [{"type": "text", "text": '{"error":"failed"}'}]}, {}),
                               ({}, {}), ({"content": []}, {}),
                               ({"ok": True}, {"status": "failed"}),
                               ({"ok": True}, {"error": {"message": "failed"}})]:
            result = self.score([codex_call("get_overview"), codex_call("get_dependencies", payload, **extra),
                                 final(SCENARIOS["dependencies"]["expected"])])
            self.assertFalse(result["passed"], (payload, extra))
            self.assertFalse(result["criteria"]["task_tool_succeeded"])

    def test_claude_correlates_results_deduplicates_and_rejects_embedded_errors(self):
        stream = [{"type": "assistant", "message": {"content": [
            {"type": "tool_use", "id": "overview", "name": "mcp__quill__get_overview", "input": {}},
            {"type": "tool_use", "id": "deps", "name": "mcp__quill__get_dependencies", "input": {}}]}},
            {"type": "user", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "deps", "content": '{"error_code":"FAILED"}'},
                {"type": "tool_result", "tool_use_id": "overview", "content": '{"project":{}}'}]}}]
        trace, _, _ = native_trace("claude", stream + [stream[0]])
        self.assertEqual(["get_overview", "get_dependencies"], [call["tool"] for call in trace])
        self.assertEqual(["ok", "error"], [call["status"] for call in trace])
        trace, _, _ = native_trace("claude", [stream[0]])
        self.assertEqual(["unknown", "unknown"], [call["status"] for call in trace])

    def test_empty_claude_tool_result_is_not_success(self):
        stream = [{"type": "assistant", "message": {"content": [
            {"type": "tool_use", "id": "call", "name": "mcp__quill__get_overview"}]}},
            {"type": "user", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "call", "content": []}]}}]
        self.assertEqual("unknown", native_trace("claude", stream)[0][0]["status"])

    def test_router_preserves_semantic_task_name(self):
        stream = [codex_call("get_overview"), codex_call("execute_tool",
                  arguments={"name": "get_dependencies", "arguments": {}}),
                  final(SCENARIOS["dependencies"]["expected"])]
        self.assertTrue(self.score(stream)["passed"])

    def test_catalog_mentions_and_other_servers_do_not_count(self):
        stream = [final(SCENARIOS["dependencies"]["expected"]),
                  codex_call("get_overview", server="other"), codex_call("get_dependencies", server="other")]
        self.assertFalse(self.score(stream)["passed"])
        self.assertEqual(0, self.score(stream)["quill_call_count"])

    def test_started_order_is_kept_when_parallel_calls_complete_out_of_order(self):
        overview = codex_call("get_overview", identifier="one")
        dependencies = codex_call("get_dependencies", identifier="two")
        stream = [{"type": "item.started", "item": overview["item"]},
                  {"type": "item.started", "item": dependencies["item"]},
                  dependencies, overview, final(SCENARIOS["dependencies"]["expected"])]
        self.assertTrue(self.score(stream)["passed"])
        self.assertEqual(2, self.score(stream)["quill_call_count"])

    def test_wrong_types_extra_facts_duplicates_and_nonjson_fail(self):
        prefix = [codex_call("get_overview"), codex_call("get_dependencies")]
        for value in ({"dependencies": "org.example.GreetingService"},
                      {"dependencies": ["org.example.GreetingService"] * 2},
                      {"dependencies": ["org.example.GreetingService"], "extra": True}):
            self.assertFalse(self.score(prefix + [final(value)])["criteria"]["answer_correct"])
        self.assertFalse(self.score(prefix)["criteria"]["answer_correct"])
        expected = {"phase": "planned", "primary_action": "inspect_primary", "verified": False}
        self.assertFalse(self.score([codex_call("get_overview"), codex_call("change_session"),
            final({**expected, "verified": 0})], "change_plan", expected)["criteria"]["answer_correct"])

    def test_failure_timeout_and_configuration_changes_fail_even_with_good_tools(self):
        stream = [codex_call("get_overview"), codex_call("get_dependencies"),
                  final(SCENARIOS["dependencies"]["expected"])]
        for capture in ({"returncode": 1}, {"timeout": True}, {"user_config_unchanged": False}):
            self.assertFalse(self.score(stream, **capture)["passed"])
        self.assertFalse(self.score(stream + [{"type": "turn.failed"}])["passed"])

    def test_list_facts_ignore_order_but_not_missing_callers(self):
        expected = SCENARIOS["usages"]["expected"]
        stream = [codex_call("get_overview"), codex_call("find_symbol_usages"),
                  final({"callers": list(reversed(expected["callers"]))})]
        self.assertTrue(self.score(stream, "usages")["passed"])

    def test_summary_does_not_hide_unavailable_clients(self):
        summary = summarize([{"scenario": "navigation", "evaluation": {"passed": True}},
                             {"scenario": "navigation", "unavailable": True},
                             {"scenario": "usages", "evaluation": {"passed": False}}])
        self.assertEqual((3, 1, 1, 1), tuple(summary[key] for key in ("total", "passed", "failed", "unavailable")))
        self.assertFalse(summary["all_passed"])
        self.assertFalse(summarize([])["all_passed"])

    def test_partial_report_cannot_claim_completed_success(self):
        run = {"scenario": "navigation", "evaluation": {"passed": True,
            "successful_quill_call_count": 2, "criteria": {"answer_correct": True}}}
        summary = summarize([run], expected_total=10)
        self.assertEqual((10, 1, 9), (summary["total"], summary["completed"], summary["remaining"]))
        self.assertFalse(summary["all_passed"])
        self.assertEqual(1, summary["quill_used"])
        self.assertEqual(1, summary["criteria_passed"]["answer_correct"])

    def test_exact_action_description_is_valid_but_arbitrary_paraphrase_is_not(self):
        expected = {"phase": "planned", "primary_action": "inspect_primary", "verified": False}
        variant = {**expected, "primary_action": "Inspect the resolved declarations and member contracts before editing"}
        prefix = [codex_call("get_overview"), codex_call("change_session")]
        for value, correct in ((variant, True), ({**variant, "primary_action": "Looks good"}, False)):
            result = evaluate_capture("codex", prefix + [final(value)],
                {"returncode": 0, "timeout": False, "user_config_unchanged": True},
                "change_plan", expected, True, [variant])
            self.assertEqual(correct, result["criteria"]["answer_correct"])

    def test_fixture_changes_fail_without_following_symlinks_and_ignore_derived_index(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "Example.java"
            source.write_text("before")
            (root / "link").symlink_to(root / "missing-external-target")
            before = fixture_snapshot(root)
            self.assertIn("link", before)
            derived = root / ".quill"
            derived.mkdir()
            (derived / "index.db").write_text("derived")
            self.assertEqual(before, fixture_snapshot(root))
            source.write_text("after")
            self.assertNotEqual(before, fixture_snapshot(root))
        result = evaluate_capture("codex", [codex_call("get_overview"), codex_call("get_dependencies"),
            final(SCENARIOS["dependencies"]["expected"])],
            {"returncode": 0, "timeout": False, "user_config_unchanged": True}, "dependencies",
            SCENARIOS["dependencies"]["expected"], False)
        self.assertFalse(result["passed"])

    def test_source_before_overview_is_reported_not_confused_with_adoption(self):
        stream = [{"type": "item.completed", "item": {"type": "command_execution", "command": "cat AGENTS.md"}},
                  codex_call("get_overview"), codex_call("get_dependencies"),
                  final(SCENARIOS["dependencies"]["expected"])]
        self.assertTrue(self.score(stream)["passed"])
        self.assertEqual(1, self.score(stream)["source_before_quill"])

    def test_builds_or_edit_attempts_fail_even_if_fixture_hashes_did_not_change(self):
        prefix = [codex_call("get_overview"), codex_call("get_dependencies"),
                  final(SCENARIOS["dependencies"]["expected"])]
        for item in ({"type": "file_change"}, {"type": "command_execution", "command": "mvn -q test-compile"},
                     {"type": "command_execution", "command": "./mvnw test"}):
            self.assertFalse(self.score(prefix + [{"type": "item.completed", "item": item}])["passed"])


if __name__ == "__main__":
    unittest.main()
