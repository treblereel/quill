import tempfile
import unittest
from pathlib import Path
import subprocess

from quill_agent_benchmark import (ResponsesClient, SourceTools, json_type, parse_final_json,
                                   quill_outcome_flags, run_agent, selected_quill_tools,
                                   source_fallbacks, tool_usage_diagnostics)


class FakeResponses:
    def __init__(self):
        self.payloads = []

    def __call__(self, payload):
        self.payloads.append(payload)
        if len(self.payloads) == 1:
            return {
                "id": "first",
                "model": "test-model-2026-01-01",
                "usage": {"input_tokens": 100, "output_tokens": 10,
                          "input_tokens_details": {"cached_tokens": 25}},
                "output": [{"type": "function_call", "name": "read_file",
                            "call_id": "call-1",
                            "arguments": '{"path":"A.java","start_line":1,"end_line":1}'}],
            }
        return {
            "id": "second",
            "model": "test-model-2026-01-01",
            "usage": {"input_tokens": 80, "output_tokens": 20,
                      "input_tokens_details": {"cached_tokens": 40}},
            "output": [{"type": "message", "content": [
                {"type": "output_text", "text": '{"observed":{"answer":42}}'},
            ]}],
        }


class QuillAgentBenchmarkTest(unittest.TestCase):

    def test_collects_real_per_response_usage_and_tool_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "A.java").write_text("class A {}\n", encoding="utf-8")
            transport = FakeResponses()
            client = ResponsesClient("unused", "https://example.invalid", 1, transport)

            result = run_agent(client, {"id": "one", "prompt": "answer",
                                        "expected": {"answer": 42}},
                               "without_quill", "test-model", "medium",
                               SourceTools(project, 1000), None)

        self.assertEqual({"answer": 42}, result["observed"])
        self.assertEqual(180, result["input_tokens"])
        self.assertEqual(65, result["cached_input_tokens"])
        self.assertEqual(30, result["output_tokens"])
        self.assertEqual(2, result["model_requests"])
        self.assertEqual(["first", "second"], result["response_ids"])
        self.assertEqual(["test-model-2026-01-01"], result["response_models"])
        self.assertEqual(1, result["requests"])
        self.assertEqual(1, result["manual_verification_steps"])
        self.assertEqual(0, result["source_fallback_count"])
        self.assertEqual(1, result["source_first_count"])
        self.assertFalse(result["quill_bypassed"])
        self.assertEqual("first", transport.payloads[1]["previous_response_id"])
        self.assertEqual(6, result["tool_catalog_count"])
        self.assertGreater(result["tool_catalog_bytes"], 0)
        self.assertEqual(2, len(result["model_rounds"]))
        self.assertEqual(100, result["model_rounds"][0]["input_tokens"])
        self.assertEqual(1, result["model_rounds"][0]["tool_calls"])
        self.assertEqual(1, len(result["tool_trace"]))
        trace = result["tool_trace"][0]
        self.assertEqual("read_file", trace["tool"])
        self.assertEqual("source", trace["provider"])
        self.assertEqual("ok", trace["status"])
        self.assertGreater(trace["argument_bytes"], 0)
        self.assertGreater(trace["output_bytes"], 0)
        self.assertGreaterEqual(trace["duration_ms"], 0)

    def test_rejects_paths_outside_project(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = SourceTools(Path(directory), 1000)
            with self.assertRaises(ValueError):
                tools.call("read_file", {"path": "../secret", "start_line": 1,
                                         "end_line": 2})

    def test_lists_ignored_build_outputs_but_not_quill_data(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / ".gitignore").write_text("target/\n.quill/\n", encoding="utf-8")
            (project / "target").mkdir()
            (project / "target" / "Generated.java").write_text("class Generated {}\n")
            (project / ".quill").mkdir()
            (project / ".quill" / "index.db").write_text("private")

            result = SourceTools(project, 1000).call(
                "list_project_files", {"paths": [], "glob": ""})

        self.assertIn("target/Generated.java", result)
        self.assertNotIn(".quill/index.db", result)

    def test_finds_deleted_paths_in_git_history(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            subprocess.run(["git", "init", "-q"], cwd=project, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"],
                           cwd=project, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=project, check=True)
            old = project / "OldGenerator.java"
            old.write_text("class OldGenerator {}\n")
            subprocess.run(["git", "add", "OldGenerator.java"], cwd=project, check=True)
            subprocess.run(["git", "commit", "-qm", "old"], cwd=project, check=True)
            old.unlink()
            subprocess.run(["git", "commit", "-qam", "delete"], cwd=project, check=True)

            result = SourceTools(project, 1000).call(
                "git_historical_paths", {"query": "OldGenerator"})

        self.assertEqual("OldGenerator.java", result)

    def test_git_history_includes_author(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            subprocess.run(["git", "init", "-q"], cwd=project, check=True)
            subprocess.run(["git", "config", "user.email", "test@example.com"],
                           cwd=project, check=True)
            subprocess.run(["git", "config", "user.name", "Benchmark Author"],
                           cwd=project, check=True)
            (project / "A.java").write_text("class A {}\n", encoding="utf-8")
            subprocess.run(["git", "add", "A.java"], cwd=project, check=True)
            subprocess.run(["git", "commit", "-qm", "initial"], cwd=project, check=True)

            result = SourceTools(project, 1000).call("git_history", {
                "path": "A.java", "limit": 10, "name_status": False,
            })

        fields = result.strip().split("\t")
        self.assertEqual(5, len(fields))
        self.assertEqual("Benchmark Author", fields[1])
        self.assertEqual("test@example.com", fields[2])
        self.assertEqual("initial", fields[4])

    def test_parses_fenced_json(self):
        self.assertEqual({"observed": {"x": True}},
                         parse_final_json('```json\n{"observed":{"x":true}}\n```'))

    def test_describes_expected_types_without_exposing_values(self):
        self.assertEqual("boolean", json_type(True))
        self.assertEqual("integer", json_type(42))
        self.assertEqual("array", json_type(["a"]))

    def test_selects_declared_quill_tools_without_exposing_the_full_catalog(self):
        quill = type("FakeQuill", (), {"definitions": [
            {"name": "quill_get_dependencies"},
            {"name": "quill_get_overview"},
        ]})()
        selected = selected_quill_tools(
            {"id": "one", "quill_tools": ["get_dependencies"]}, quill)
        self.assertEqual([{"name": "quill_get_dependencies"}], selected)
        with self.assertRaisesRegex(ValueError, "unknown Quill tools"):
            selected_quill_tools({"id": "bad", "quill_tools": ["missing"]}, quill)

    def test_reports_source_calls_after_quill_with_response_limitations(self):
        flags = quill_outcome_flags({
            "isError": False,
            "structuredContent": {
                "resolution_status": "unknown",
                "_meta": {"structure_stale": True},
                "_response_budget": {"truncated": True},
            },
        })
        trace = [
            {"round": 1, "tool": "quill_get_dependencies", "provider": "quill",
             "status": "ok", "outcome_flags": flags},
            {"round": 2, "tool": "source_search", "provider": "source", "status": "ok"},
            {"round": 3, "tool": "read_file", "provider": "source", "status": "ok"},
        ]

        fallbacks = source_fallbacks(trace)

        self.assertEqual(["stale", "truncated", "unknown"], flags)
        self.assertEqual(2, len(fallbacks))
        self.assertEqual("quill_get_dependencies", fallbacks[0]["quill_tool"])
        self.assertEqual("source_search", fallbacks[0]["source_tool"])
        self.assertEqual(flags, fallbacks[0]["quill_outcome_flags"])

    def test_reports_source_first_and_quill_bypass_separately(self):
        trace = [
            {"round": 1, "tool": "source_search", "provider": "source"},
            {"round": 2, "tool": "read_file", "provider": "source"},
        ]

        diagnostics = tool_usage_diagnostics(trace, quill_advertised=True)

        self.assertEqual(2, diagnostics["source_first_count"])
        self.assertEqual(0, diagnostics["quill_call_count"])
        self.assertTrue(diagnostics["quill_bypassed"])

    def test_source_before_quill_is_source_first_but_not_bypass(self):
        trace = [
            {"round": 1, "tool": "source_search", "provider": "source"},
            {"round": 2, "tool": "quill_get_dependencies", "provider": "quill"},
            {"round": 3, "tool": "read_file", "provider": "source"},
        ]

        diagnostics = tool_usage_diagnostics(trace, quill_advertised=True)

        self.assertEqual(1, diagnostics["source_first_count"])
        self.assertFalse(diagnostics["quill_bypassed"])


if __name__ == "__main__":
    unittest.main()
