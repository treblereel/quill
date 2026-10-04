import unittest
import io
import json
import tempfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

from quill_adoption_diagnostic import PROMPT, main, metadata_contract, plan_expectations
from quill_adoption_scenarios import SCENARIOS


class AdoptionDiagnosticTest(unittest.TestCase):
    def test_action_representations_are_derived_only_from_snapshot_not_model_answer(self):
        expected, variants = plan_expectations({"phase": "planned", "directive": {
            "primary_action": {"action": "inspect_primary", "description": "Inspect declarations"}}})
        self.assertEqual("inspect_primary", expected["primary_action"])
        self.assertEqual(["Inspect declarations", "inspect_primary — Inspect declarations"],
                         [item["primary_action"] for item in variants])

    def test_prompt_does_not_remind_agent_to_use_quill_or_tool_names(self):
        for name in ("quill", "get_overview", "change_session", "installed instructions"):
            self.assertNotIn(name, PROMPT.lower())

    def test_native_wire_contract_rejects_missing_and_empty_annotations(self):
        initialized = {"instructions": "Begin with get_overview, then change_session"}
        for annotations in (None, {}, {"readOnlyHint": "true"}):
            contract = metadata_contract(initialized, {"tools": [{"annotations": annotations}]})
            self.assertTrue(contract["server_instructions"])
            self.assertFalse(contract["query_safety_hints"])
        hints = {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": False}
        self.assertTrue(all(metadata_contract(initialized, {"tools": [{"annotations": hints}]}).values()))
        self.assertFalse(metadata_contract({}, {"tools": []})["server_instructions"])
        self.assertFalse(metadata_contract(initialized, {"tools": []})["query_safety_hints"])

    def run_diagnostic(self, *, wire_only=False, unavailable=False, no_overview=False,
                       config_changed=False, scenarios=("navigation",)):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            quill = root / "quill"
            quill.touch()
            output = root / "report.json"
            wire = MagicMock()
            wire.__enter__.return_value = wire
            wire.initialized = {"instructions": "get_overview change_session"}
            wire.request.return_value = {"tools": [{"name": "get_overview", "annotations": {
                "readOnlyHint": True, "destructiveHint": False, "openWorldHint": False}}]}
            wire.call.return_value = ({"phase": "planned", "directive": {"primary_action": {
                "action": "inspect_primary", "description": "Inspect declarations"}}}, 0)

            def run(argv, project):
                if argv[0] == str(quill.resolve()) and "init" in argv:
                    for name in ("AGENTS.md", "CLAUDE.md"):
                        (project / name).write_text("managed instructions")
                    (project / ".codex").mkdir()
                    (project / ".codex/config.toml").write_text(
                        '[mcp_servers.quill]\ncommand="quill"\nargs=["--mcp"]\n'
                        'cwd=' + json.dumps(str(project)) + '\n')
                return MagicMock(stdout="client version")

            def invoke(client, project, prompt, timeout, **kwargs):
                scenario = next(name for name in scenarios if SCENARIOS[name]["prompt"] in prompt)
                calls = []
                for tool in ([] if no_overview else ["get_overview"]) + [SCENARIOS[scenario]["tools"][0]]:
                    calls.append({"type": "item.completed", "item": {"type": "mcp_tool_call",
                        "server": "quill", "tool": tool, "status": "completed",
                        "result": {"structuredContent": {"ok": True}}}})
                calls.append({"type": "item.completed", "item": {"type": "agent_message",
                    "text": json.dumps(SCENARIOS[scenario]["expected"])}})
                return {"returncode": 0, "timeout": False, "tools": [],
                    "stdout": "\n".join(json.dumps(item) for item in calls),
                    "stderr": "secret stderr not for report", "user_config_unchanged": not config_changed}

            argv = ["diagnostic", "--quill", str(quill), "--output", str(output),
                    "--cases", "project_read_only", "--scenarios", *scenarios]
            if wire_only:
                argv.append("--wire-only")
            with patch("sys.argv", argv), patch("quill_adoption_diagnostic.loop.run", side_effect=run), \
                    patch("quill_adoption_diagnostic.WorkflowClient", return_value=wire), \
                    patch("quill_adoption_diagnostic.shutil.which", side_effect=lambda name:
                          None if unavailable and name == "codex" else "/mock/" + name), \
                    patch("quill_adoption_diagnostic.invoke", side_effect=invoke) as inference, \
                    redirect_stdout(io.StringIO()):
                exit_code = main()
            return exit_code, json.loads(output.read_text()), inference.call_count

    def test_wire_only_never_invokes_native_models(self):
        code, report, calls = self.run_diagnostic(wire_only=True)
        self.assertEqual((0, 0), (code, calls))
        self.assertEqual("wire_only", report["mode"])
        self.assertEqual(0, report["summary"]["total"])

    def test_main_scores_success_and_omits_raw_client_captures(self):
        code, report, calls = self.run_diagnostic()
        self.assertEqual((0, 1), (code, calls))
        self.assertEqual(2, report["schema_version"])
        self.assertTrue(report["summary"]["all_passed"])
        self.assertNotIn("secret", json.dumps(report))
        self.assertNotIn("stdout", report["runs"][0])
        self.assertNotIn("stderr", report["runs"][0])

    def test_main_exits_nonzero_for_policy_failure_or_unavailable_client(self):
        for kwargs in ({"no_overview": True}, {"unavailable": True}):
            code, report, _ = self.run_diagnostic(**kwargs)
            self.assertEqual(1, code)
            self.assertFalse(report["summary"]["all_passed"])
            self.assertEqual(0, report["summary"]["remaining"])

    def test_config_mutation_stops_before_next_task_and_keeps_remaining_count(self):
        code, report, calls = self.run_diagnostic(config_changed=True, scenarios=("navigation", "dependencies"))
        self.assertEqual((1, 1), (code, calls))
        self.assertEqual((2, 1, 1), tuple(report["summary"][key] for key in ("total", "completed", "remaining")))
        self.assertFalse(report["runs"][0]["evaluation"]["criteria"]["user_config_unchanged"])


if __name__ == "__main__":
    unittest.main()
