import unittest
import io
import json
import tempfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

from quill_profile_benchmark import digest, main, payload, query, run_profile, size, summarize


class ProfileBenchmarkTest(unittest.TestCase):
    def test_initialization_failure_still_closes_subprocess(self):
        client = Mock()
        client.__enter__ = Mock(side_effect=ValueError("initialize failed"))
        with patch("quill_profile_benchmark.WorkflowClient", return_value=client):
            with self.assertRaises(ValueError):
                run_profile(["quill"], Path("."), "full", 1, "Example")
        client.client.close.assert_called_once()

    def test_byte_count_is_utf8_not_character_count(self):
        self.assertGreater(size({"text": "Привет"}), len('{"text":"Привет"}'))

    def test_equivalence_only_excludes_provenance(self):
        first = {"facts": [1], "nested": {"_meta": {"indexed_at": "before"}}}
        second = {"facts": [1], "nested": {"_meta": {"indexed_at": "after"}}}
        self.assertEqual(digest(first), digest(second))
        self.assertNotEqual(digest(first), digest({**first, "warnings": ["partial"]}))
        self.assertNotEqual(digest(first), digest({**first, "facts": [2]}))

    def test_errors_and_empty_results_are_not_evidence(self):
        for value in ({"isError": True}, {"structuredContent": {}},
                      {"structuredContent": {"error_code": "missing"}},
                      {"structuredContent": {"projects": [{"error": "missing"}]}}):
            with self.assertRaises(ValueError):
                payload(value)
        self.assertEqual({"facts": [1]}, payload({"content": [
            {"type": "text", "text": '{"facts":[1]}'}]}))

    def test_direct_and_router_keep_same_facts_but_count_discovery(self):
        client = Mock()
        client.request.return_value = {"structuredContent": {"facts": [1]}}
        direct = query(client, {"get_dependencies"}, "get_dependencies", {"target": "Example"})
        self.assertEqual(1, direct["tool_calls"])
        client.request.side_effect = [{"structuredContent": {"tools": [{"name": "get_dependencies"}]}},
                                      {"structuredContent": {"facts": [1]}}]
        router = query(client, {"search_tools", "execute_tool"}, "get_dependencies", {"target": "Example"})
        self.assertEqual(2, router["tool_calls"])
        self.assertEqual(direct["semantic_digest"], router["semantic_digest"])
        self.assertEqual(["search_tools", "execute_tool"], [item["tool"] for item in router["trace"]])

    def test_router_must_discover_exact_name(self):
        client = Mock()
        client.request.return_value = {"structuredContent": {"tools": [{"name": "other"}]}}
        with self.assertRaises(ValueError):
            query(client, {"search_tools", "execute_tool"}, "get_dependencies", {})
        self.assertEqual(1, client.request.call_count)

    def test_unsupported_core_capability_is_explicit_without_call(self):
        client = Mock()
        result = query(client, {"get_overview"}, "get_recent_changes", {})
        self.assertFalse(result["supported"])
        client.request.assert_not_called()

    def runs(self):
        return [{"profile": profile, "startup_ms": 1, "catalog_latency_ms": 1,
                 "catalog_tools": 3, "catalog_tools_bytes": 100, "server_instructions_bytes": 10,
                 "workloads": {name: {"supported": not (profile == "core" and name == "history"),
                    "semantic_digest": name, "latency_ms": 1, "tool_calls": 1,
                    "serialized_result_bytes": 20} for name in
                    ("orientation", "navigation", "dependencies", "change_workflow", "history")}}
                for profile in ("full", "core", "router")]

    def test_summary_requires_coverage_and_fact_equality(self):
        runs = self.runs()
        self.assertTrue(summarize(runs, 3)["all_passed"])
        self.assertFalse(summarize(runs[:2], 3)["all_passed"])
        runs[-1]["workloads"]["navigation"]["semantic_digest"] = "wrong"
        self.assertFalse(summarize(runs, 3)["all_passed"])

    def test_reduced_catalog_cannot_hide_missing_shared_capability(self):
        runs = self.runs()
        runs[1]["workloads"]["dependencies"]["supported"] = False
        summary = summarize(runs, 3)
        self.assertFalse(summary["all_passed"])
        self.assertFalse(summary["equivalence"]["dependencies"]["coverage_complete"])

    def test_cli_rotates_order_and_checks_input_invariance(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            output = root / "report.json"
            binary = root / "quill"
            binary.touch()
            expected = {run["profile"]: run for run in self.runs()}
            order = []
            def run(command, project, profile, timeout, target):
                order.append(profile)
                return dict(expected[profile])
            argv = ["benchmark", "--project", str(project), "--target", "Example", "--samples", "3",
                    "--output", str(output), "--quill", str(binary)]
            with patch("sys.argv", argv), patch("quill_profile_benchmark.run_profile", side_effect=run), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(0, main())
            self.assertEqual(["full", "core", "router", "core", "router", "full", "router", "full", "core"], order)
            self.assertTrue(json.loads(output.read_text())["fixture_unchanged"])
            with patch("sys.argv", argv), patch("quill_profile_benchmark.run_profile", side_effect=run), \
                    patch("quill_profile_benchmark.fixture_snapshot", side_effect=[{}, {"changed": True}]), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(1, main())
            report = json.loads(output.read_text())
            self.assertFalse(report["fixture_unchanged"])
            self.assertFalse(report["summary"]["all_passed"])
            self.assertEqual(1, report["summary"]["completed_runs"])

    def test_failed_run_saves_partial_report_without_remote_error_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            output = root / "report.json"
            binary = root / "quill"
            binary.touch()
            argv = ["benchmark", "--project", str(project), "--target", "Example", "--samples", "1",
                    "--output", str(output), "--quill", str(binary)]
            with patch("sys.argv", argv), patch("quill_profile_benchmark.run_profile",
                    side_effect=ValueError("secret remote error")), redirect_stdout(io.StringIO()):
                self.assertEqual(1, main())
            report = json.loads(output.read_text())
            self.assertFalse(report["summary"]["all_passed"])
            self.assertEqual(0, report["summary"]["completed_runs"])
            self.assertNotIn("secret", output.read_text())


if __name__ == "__main__":
    unittest.main()
