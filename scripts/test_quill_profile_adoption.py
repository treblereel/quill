import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from quill_profile_adoption import catalog_support, main, safe_fixture, summarize, supported


class ProfileAdoptionTest(unittest.TestCase):
    def test_core_history_is_explicitly_unsupported_not_a_pass(self):
        self.assertFalse(supported("core", "history"))
        self.assertTrue(supported("router", "history"))
        self.assertFalse(catalog_support([{"name": "get_overview"}], "history"))
        self.assertTrue(catalog_support([{"name": "search_tools"}, {"name": "execute_tool"}], "history"))

    def test_duplicate_missing_or_wrong_cells_cannot_report_complete(self):
        item = {"profile": "full", "case": "project_read_only", "scenario": "navigation", "sample": 1}
        run = {**item, "evaluation": {"passed": True}}
        self.assertTrue(summarize([run], [item], []) ["all_passed"])
        self.assertFalse(summarize([run, run], [item], []) ["coverage_complete"])
        self.assertFalse(summarize([], [item], []) ["coverage_complete"])
        self.assertFalse(summarize([{**run, "sample": 2}], [item], []) ["coverage_complete"])

    def test_quality_orientation_and_provider_usage_are_separate(self):
        item = {"profile": "router", "case": "claude_project", "scenario": "navigation", "sample": 1}
        run = {**item, "evaluation": {"passed": False, "criteria": {
            "answer_correct": True, "task_tool_succeeded": True, "overview_first": False}},
            "usage": {"input_tokens": 12}}
        group = summarize([run], [item], []) ["groups"]["router:claude_project"]
        self.assertEqual((1, 1, 0), tuple(group[key] for key in
            ("answer_correct", "task_tool_succeeded", "overview_first")))
        self.assertEqual({"samples": 1, "median": 12}, group["usage"]["input_tokens"])
        self.assertIsNone(group["elapsed_seconds"])

    def test_configuration_or_fixture_mutation_requires_stop_but_unavailable_does_not(self):
        good = {"client": "codex", "user_config_unchanged": True,
                "evaluation": {"criteria": {"fixture_unchanged": True}}}
        self.assertTrue(safe_fixture({"runs": [good, {"unavailable": True}]}))
        self.assertFalse(safe_fixture({"runs": [{**good, "user_config_unchanged": False}]}))

    def test_task_breakdown_does_not_hide_a_missing_task_behind_an_aggregate(self):
        navigation = {"profile": "full", "case": "claude_project", "scenario": "navigation", "sample": 1}
        planning = {**navigation, "scenario": "change_plan"}
        run = {**navigation, "evaluation": {"passed": True, "criteria": {"overview_first": True}}}
        group = summarize([run], [navigation, planning], [])["groups"]["full:claude_project"]
        self.assertEqual((2, 1, 1), tuple(group[name] for name in ("planned", "completed", "remaining")))
        self.assertFalse(group["coverage_complete"])
        tasks = group["by_scenario"]
        self.assertTrue(tasks["navigation"]["coverage_complete"])
        self.assertEqual(1, tasks["navigation"]["criteria_passed"]["overview_first"])
        self.assertEqual((1, 0, 1), tuple(tasks["change_plan"][name]
                                        for name in ("planned", "completed", "remaining")))
        self.assertIsNone(tasks["change_plan"]["elapsed_seconds"])
        self.assertEqual({}, tasks["change_plan"]["usage"])

    def test_task_breakdown_separates_orientation_failures_from_correct_answers(self):
        cells = [{"profile": "router", "case": "claude_project", "scenario": "navigation", "sample": i}
                 for i in (1, 2)]
        runs = [{**cells[0], "evaluation": {"passed": False, "criteria": {
            "answer_correct": True, "task_tool_succeeded": True, "overview_first": False}}},
            {**cells[1], "evaluation": {"passed": True, "criteria": {
            "answer_correct": True, "task_tool_succeeded": True, "overview_first": True}}}]
        task = summarize(runs, cells, [])["groups"]["router:claude_project"]["by_scenario"]["navigation"]
        self.assertEqual((2, 1, 1, 2, 1), tuple(task[name] for name in
            ("completed", "passed", "failed", "answer_correct", "overview_first")))
        self.assertFalse(summarize([runs[0], runs[0]], cells, [])["groups"]
                         ["router:claude_project"]["by_scenario"]["navigation"]["coverage_complete"])

    def test_unavailable_task_cannot_contribute_success_even_with_malformed_evaluation(self):
        item = {"profile": "core", "case": "claude_project", "scenario": "navigation", "sample": 1}
        run = {**item, "unavailable": True, "evaluation": {"passed": True,
               "criteria": {"answer_correct": True}}}
        task = summarize([run], [item], [])["groups"]["core:claude_project"]["by_scenario"]["navigation"]
        self.assertEqual((1, 0, 0, 0), tuple(task[name] for name in
                         ("unavailable", "passed", "failed", "answer_correct")))
        self.assertFalse(summarize([run], [item], [])["all_passed"])

    def exercise(self, wire_only=False, mutation=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "quill"
            binary.touch()
            output = root / "report.json"
            calls = []
            def diagnostic(argv):
                profile = argv[argv.index("--tool-profile") + 1]
                calls.append(profile)
                names = ["get_overview", "search_tools", "execute_tool"] if profile == "router" else [
                    "get_overview", "search_symbols", "get_file_history"]
                if profile == "core":
                    names.remove("get_file_history")
                runs = []
                if "--wire-only" not in argv:
                    tasks = argv[argv.index("--scenarios") + 1:argv.index("--samples")]
                    runs = [{"case": "project_read_only", "client": "codex", "scenario": task,
                        "sample": 1, "user_config_unchanged": not mutation,
                        "evaluation": {"passed": not mutation, "criteria": {"fixture_unchanged": True}}}
                        for task in tasks]
                Path(argv[argv.index("--output") + 1]).write_text(json.dumps({
                    "wire_contract": {"ok": True}, "tools": [{"name": name} for name in names], "runs": runs}))
                return 0
            argv = ["--quill", str(binary), "--output", str(output), "--cases", "project_read_only",
                    "--scenarios", "navigation", "history", "--samples", "2"]
            if wire_only:
                argv.append("--wire-only")
            with patch("quill_profile_adoption.diagnostic.main", side_effect=diagnostic), redirect_stdout(io.StringIO()):
                code = main(argv)
            return code, json.loads(output.read_text()), calls

    def test_rotates_profile_order_preserves_unsupported_and_complete_coverage(self):
        code, report, calls = self.exercise()
        self.assertEqual(0, code)
        self.assertEqual(["full", "core", "router", "core", "router", "full"], calls)
        self.assertEqual((10, 10, 2), tuple(report["summary"][key] for key in ("planned", "completed", "unsupported")))
        self.assertTrue(report["summary"]["all_passed"])

    def test_wire_only_runs_no_native_tasks_and_cannot_claim_adoption(self):
        code, report, _ = self.exercise(wire_only=True)
        self.assertEqual(0, code)
        self.assertTrue(report["summary"]["wire_passed"])
        self.assertFalse(report["summary"]["all_passed"])
        self.assertEqual([], report["runs"])

    def test_mutation_stops_without_rollback_and_keeps_planned_denominator(self):
        code, report, calls = self.exercise(mutation=True)
        self.assertEqual((1, ["full"]), (code, calls))
        self.assertEqual(8, report["summary"]["remaining"])
        self.assertIn("stopped", report)


if __name__ == "__main__":
    unittest.main()
