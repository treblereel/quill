import json
import tempfile
import unittest
from pathlib import Path
import subprocess
from unittest.mock import patch

from quill_agent_benchmark import (QuillTools, ResponsesClient, SourceTools, arguments, json_type, parse_final_json,
                                   quill_outcome_flags, run_agent, selected_quill_tools,
                                   selected_quill_profile, source_fallbacks, tool_usage_diagnostics)
from quill_agent_benchmark import managed_guidance
from quill_agent_benchmark import main


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

    def test_readiness_waits_locally_without_charging_agent_tool_calls(self):
        with patch("quill_agent_benchmark.McpClient"):
            quill = QuillTools(["unused"], Path("."), 10, 1000)
        responses = [{"isError": True, "structuredContent": {"status": "refresh_pending"}},
                     {"structuredContent": {"project": {"classes": 100},
                                            "_meta": {"index_id": "ready-index"}}}]
        with patch.object(quill, "_request", side_effect=responses) as request, \
                patch("quill_agent_benchmark.time.sleep") as sleep:
            receipt = quill.wait_until_ready()
        self.assertEqual(2, receipt["overview_requests"])
        self.assertEqual("ready-index", receipt["index_id"])
        self.assertEqual(0, quill.calls)
        self.assertIsNone(quill.last_call)
        sleep.assert_called_once()
        for call in request.call_args_list:
            self.assertEqual(("tools/call", {"name": "get_overview", "arguments": {}}), call.args)

    def test_readiness_rejects_errors_invalid_overviews_and_timeouts(self):
        with patch("quill_agent_benchmark.McpClient"):
            quill = QuillTools(["unused"], Path("."), 1, 1000)
        for response in [{"isError": True, "structuredContent": {"error_code": "BUILD_FAILED"}},
                         {"structuredContent": {"ready": True}}]:
            with patch.object(quill, "_request", return_value=response), \
                    self.assertRaisesRegex(RuntimeError, "readiness failed"):
                quill.wait_until_ready()
        with patch.object(quill, "_request") as request, \
                patch("quill_agent_benchmark.time.perf_counter", side_effect=[0, 2]), \
                self.assertRaisesRegex(RuntimeError, "before any model request"):
            quill.wait_until_ready()
        request.assert_not_called()

    def test_main_never_calls_model_when_mcp_preparation_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            suite = Path(directory) / "suite.json"
            suite.write_text(json.dumps({"tasks": [{"id": "one", "expected": {"ok": True}}]}))
            with patch("sys.argv", ["runner", "--suite", str(suite), "--project", directory]), \
                    patch.dict("os.environ", {"OPENAI_API_KEY": "offline-test-key"}), \
                    patch("quill_agent_benchmark.verify_project", return_value="revision"), \
                    patch("quill_agent_benchmark.ResponsesClient") as client, \
                    patch("quill_agent_benchmark.QuillTools") as quill, \
                    patch("quill_agent_benchmark.run_agent") as agent:
                quill.return_value.__enter__.return_value.wait_until_ready.side_effect = RuntimeError("not ready")
                with self.assertRaisesRegex(RuntimeError, "not ready"):
                    main()
                client.return_value.create.assert_not_called()
                agent.assert_not_called()

    def test_main_reuses_ready_mcp_and_resets_per_task_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = root / "suite.json"
            suite.write_text(json.dumps({"tasks": [{"id": id, "expected": {"ok": True}}
                                                   for id in ("one", "two")]}))
            starts = []

            def run(*args):
                quill = args[6]
                if quill is not None:
                    starts.append(quill.calls)
                    quill.calls += 1
                return {"id": args[1]["id"], "duration_seconds": 1, "observed": {"ok": True}}

            with patch("sys.argv", ["runner", "--suite", str(suite), "--project", directory,
                                    "--output-dir", str(root / "out"), "--repetitions", "2"]), \
                    patch.dict("os.environ", {"OPENAI_API_KEY": "offline-test-key"}), \
                    patch("quill_agent_benchmark.verify_project", return_value="revision"), \
                    patch("quill_agent_benchmark.ResponsesClient"), \
                    patch("quill_agent_benchmark.QuillTools") as factory, \
                    patch("quill_agent_benchmark.time.perf_counter", side_effect=[10, 12]), \
                    patch("quill_agent_benchmark.run_agent", side_effect=run):
                factory.return_value.__enter__.return_value.wait_until_ready.return_value = {
                    "duration_seconds": 1, "overview_requests": 2, "index_id": "ready-index"}
                main()
            factory.assert_called_once()
            factory.return_value.__enter__.return_value.wait_until_ready.assert_called_once()
            factory.return_value.__exit__.assert_called_once()
            self.assertEqual([0, 0, 0, 0], starts)
            capture = json.loads((root / "out/suite-with-quill.json").read_text())
            self.assertEqual([2, 0, 0, 0], [t["mcp_initialization_seconds"]
                                          for r in capture["runs"] for t in r["tasks"]])

    def test_router_default_keeps_explicit_full_catalog_available(self):
        for extra, expected in (([], "router"), (["--quill-profile", "default"], "default")):
            with patch("sys.argv", ["runner", "--suite", "s.json", "--project", ".", *extra]):
                args = arguments()
                self.assertEqual(expected, selected_quill_profile(
                    args.quill_profile, args.tool_selection, [{"id": "one"}]))
        restricted = [{"id": "one", "quill_tools": ["get_dependencies"]}]
        self.assertEqual("default", selected_quill_profile(None, "suite", restricted))
        self.assertEqual("router", selected_quill_profile(None, "all", restricted))
        self.assertEqual("default", selected_quill_profile("default", "all", []))

    def test_large_mcp_results_remain_valid_json_and_report_omitted_evidence(self):
        with patch("quill_agent_benchmark.McpClient"):
            quill = QuillTools(["unused"], Path("."), 1, 512)
        results = [{"isError": False, "structuredContent": {"classes": ["x" * 2000]}},
                   {"isError": True, "structuredContent": {"message": "x" * 2000}},
                   {"isError": False, "structuredContent": {"selected": "KeepThis"}}]
        for result in results:
            with patch.object(quill, "_request", return_value=result):
                output = quill.call("quill_list_beans", {"limit": 100})
            self.assertLessEqual(len(output), 512)
            parsed = json.loads(output)
            self.assertEqual(result["isError"], parsed["isError"])
            if "selected" in result["structuredContent"]:
                self.assertEqual(result, parsed)
                self.assertNotIn("truncated", quill.last_call.get("outcome_flags", []))
            else:
                self.assertTrue(parsed["structuredContent"]["_response_budget"]["truncated"])
                self.assertNotIn("classes", parsed["structuredContent"])
                self.assertIn("truncated", quill.last_call["outcome_flags"])
        with patch("quill_agent_benchmark.McpClient") as client:
            with self.assertRaisesRegex(ValueError, "at least 512"):
                QuillTools(["unused"], Path("."), 1, 100)
            client.assert_not_called()

    def test_mcp_optional_arguments_survive_responses_adapter_and_dispatch(self):
        schema = {
            "type": "object",
            "properties": {"view": {"type": "string", "enum": ["full", "compact"]},
                           "details": {"type": "boolean"},
                           "limit": {"type": "integer"}},
        }
        required_schema = {"type": "object", "properties": {"target": {"type": "string"}},
                           "required": ["target"], "additionalProperties": False}
        with tempfile.TemporaryDirectory() as directory, \
                patch("quill_agent_benchmark.McpClient") as mcp:
            requests = []

            def receive(ids, timeout):
                request_id = next(iter(ids))
                _, method, params = mcp.return_value.send.call_args.args
                requests.append((method, params))
                if method == "tools/list":
                    result = {"tools": [
                        {"name": "get_overview", "inputSchema": schema},
                        {"name": "get_dependencies", "inputSchema": required_schema},
                    ]}
                else:
                    result = {"structuredContent": {"ready": True}}
                return {request_id: {"result": result}}

            mcp.return_value.receive.side_effect = receive
            payloads = []

            def transport(payload):
                payloads.append(payload)
                for tool in payload["tools"]:
                    if tool["name"].startswith("quill_"):
                        self.assertIs(False, tool["strict"])
                self.assertIn("evidence_detail=summary", payload["instructions"])
                self.assertIn("quill_get_overview({}); never use view=compact", payload["instructions"])
                if len(payloads) <= 2:
                    arguments = {} if len(payloads) == 1 else {"view": "full", "details": False}
                    return {"id": str(len(payloads)), "output": [{
                        "type": "function_call", "name": "quill_get_overview",
                        "call_id": str(len(payloads)), "arguments": json.dumps(arguments)}]}
                return {"id": "final", "output": [{"type": "message", "content": [{
                    "type": "output_text", "text": '{"observed":{"ready":true}}'}]}]}

            project = Path(directory)
            with QuillTools(["unused"], project, 1, 10000) as quill:
                self.assertEqual(schema, quill.definitions[0]["parameters"])
                self.assertEqual(required_schema, quill.definitions[1]["parameters"])
                self.assertNotIn("required", schema)
                result = run_agent(
                    ResponsesClient("unused", "https://example.invalid", 1, transport),
                    {"id": "optional", "prompt": "inspect", "expected": {"ready": True}},
                    "with_quill", "fake-local-model", "medium", SourceTools(project, 1000), quill)
            self.assertEqual({"ready": True}, result["observed"])
            self.assertEqual([{"name": "get_overview", "arguments": {}},
                              {"name": "get_overview", "arguments": {"view": "full", "details": False}}],
                             [params for method, params in requests if method == "tools/call"])
            mcp.return_value.close.assert_called_once()

    def test_action_mode_is_explicit_and_read_only_default_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "A.java").write_text("class A {}\n")
            for action_mode in (False, True):
                transport = FakeResponses()
                run_agent(ResponsesClient("unused", "https://example.invalid", 1, transport),
                          {"id": "one", "prompt": "answer", "expected": {"answer": 42}},
                          "with_quill", "test-model", "medium", SourceTools(project, 1000), None,
                          action_instructions="Bounded writes permitted" if action_mode else "",
                          extra_tools=[{"type": "function", "name": "bounded_edit"}]
                          if action_mode else None)
                payload = transport.payloads[0]
                self.assertEqual(action_mode, "Bounded writes permitted" in payload["instructions"])
                self.assertEqual(not action_mode, "Do not modify files or run builds" in
                                 payload["instructions"])
                self.assertEqual(action_mode, any(tool["name"] == "bounded_edit"
                                                 for tool in payload["tools"]))

    def test_installed_guidance_is_injected_only_in_quill_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "A.java").write_text("class A {}\n", encoding="utf-8")
            for mode in ("with_quill", "without_quill"):
                transport = FakeResponses()
                result = run_agent(ResponsesClient("unused", "https://example.invalid", 1, transport),
                                   {"id": "one", "prompt": "answer", "expected": {"answer": 42}},
                                   mode, "test-model", "medium", SourceTools(project, 1000), None,
                                   guidance="Follow directive.primary_action")
                self.assertEqual(mode == "with_quill", result["installed_guidance_used"])
                self.assertEqual(mode == "with_quill",
                                 "Follow directive.primary_action" in transport.payloads[0]["instructions"])

    def test_guidance_loader_excludes_surrounding_user_text(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AGENTS.md"
            block = "<!-- quill:managed:start -->\nQuill rules\n<!-- quill:managed:end -->"
            path.write_text("User rules\n" + block + "\nUser tail", encoding="utf-8")
            self.assertEqual(block, managed_guidance(path))
            path.write_text("<!-- quill:managed:start -->\nUser tail", encoding="utf-8")
            with self.assertRaises(ValueError):
                managed_guidance(path)

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

    def test_can_expose_the_entire_selected_server_profile(self):
        definitions = [
            {"name": "quill_get_overview"},
            {"name": "quill_search_tools"},
            {"name": "quill_execute_tool"},
        ]
        quill = type("FakeQuill", (), {"definitions": definitions})()

        selected = selected_quill_tools(
            {"id": "one", "quill_tools": ["get_dependencies"]}, quill, "all")

        self.assertEqual(definitions, selected)

    def test_rejects_unknown_tool_selection_mode(self):
        quill = type("FakeQuill", (), {"definitions": []})()
        with self.assertRaisesRegex(ValueError, "Unknown Quill tool selection"):
            selected_quill_tools({"id": "one"}, quill, "invalid")

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
            {"round": 1, "tool": "source_search", "provider": "source",
             "elapsed_since_task_start_ms": 12},
            {"round": 2, "tool": "read_file", "provider": "source",
             "elapsed_since_task_start_ms": 20},
        ]

        diagnostics = tool_usage_diagnostics(trace, quill_advertised=True)

        self.assertEqual(2, diagnostics["source_first_count"])
        self.assertEqual(0, diagnostics["quill_call_count"])
        self.assertTrue(diagnostics["quill_bypassed"])
        self.assertEqual(12, diagnostics["time_to_first_tool_ms"])
        self.assertIsNone(diagnostics["time_to_first_quill_ms"])

    def test_source_before_quill_is_source_first_but_not_bypass(self):
        trace = [
            {"round": 1, "tool": "source_search", "provider": "source",
             "elapsed_since_task_start_ms": 10},
            {"round": 2, "tool": "quill_get_dependencies", "provider": "quill",
             "elapsed_since_task_start_ms": 25, "status": "ok"},
            {"round": 3, "tool": "read_file", "provider": "source"},
        ]

        diagnostics = tool_usage_diagnostics(trace, quill_advertised=True)

        self.assertEqual(1, diagnostics["source_first_count"])
        self.assertFalse(diagnostics["quill_bypassed"])
        self.assertEqual(25, diagnostics["time_to_first_quill_ms"])
        self.assertEqual(25, diagnostics["time_to_first_successful_quill_ms"])


if __name__ == "__main__":
    unittest.main()
