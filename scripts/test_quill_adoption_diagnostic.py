import unittest
import io
import json
import tempfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

from quill_adoption_diagnostic import (PROMPT, main, metadata_contract, plan_expectations,
                                      configure_fixture_profile, client_usage, profile_catalog_valid,
                                      configure_fixture_guidance, OVERVIEW_FIRST_GUIDANCE)
from quill_adoption_scenarios import SCENARIOS


class AdoptionDiagnosticTest(unittest.TestCase):
    def test_guidance_variants_preserve_generated_block_and_agents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = "<!-- quill:managed:start -->\nmanaged guidance\n<!-- quill:managed:end -->\n"
            (root / "CLAUDE.md").write_text(original)
            (root / "AGENTS.md").write_text("unchanged agents")
            baseline = configure_fixture_guidance(root, "baseline")
            self.assertEqual(original, (root / "CLAUDE.md").read_text())
            variant = configure_fixture_guidance(root, "overview_first")
            self.assertEqual(OVERVIEW_FIRST_GUIDANCE + original, (root / "CLAUDE.md").read_text())
            self.assertEqual(baseline["AGENTS.md"], variant["AGENTS.md"])
            self.assertNotEqual(baseline["CLAUDE.md"], variant["CLAUDE.md"])
            (root / "CLAUDE.md").write_text("not managed")
            with self.assertRaises(ValueError):
                configure_fixture_guidance(root, "overview_first")
            self.assertEqual("not managed", (root / "CLAUDE.md").read_text())

    def test_experimental_guidance_rejects_other_clients_or_catalogs_before_launch(self):
        for extra in ([], ["--cases", "project_read_only"],
                      ["--cases", "claude_project", "--tool-profile", "router"]):
            with patch("quill_adoption_diagnostic.invoke") as inference:
                with self.assertRaises(ValueError):
                    main(["--output", "/unused", "--guidance-variant", "overview_first", *extra])
                inference.assert_not_called()

    def test_source_controls_reject_legacy_rubric_before_fixture_or_client_creation(self):
        with patch("quill_adoption_diagnostic.invoke") as inference:
            with self.assertRaises(ValueError):
                main(["--output", "/unused", "--scenarios", "literal"])
            inference.assert_not_called()

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
                       config_changed=False, bad_wire=False, scenarios=("navigation",), hook=False,
                       hook_emits=False, hook_variant="session_start"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            quill = root / "quill"
            quill.touch()
            output = root / "report.json"
            wire = MagicMock()
            wire.__enter__.return_value = wire
            wire.initialized = {"instructions": "get_overview change_session"}
            wire.request.return_value = {"tools": [{"name": name, "annotations": {
                "readOnlyHint": True, "destructiveHint": False, "openWorldHint": False}}
                for name in ("get_overview", "search_symbols", "find_symbol_usages", "get_dependencies",
                             "get_file_history", "change_session")]}
            if bad_wire:
                wire.request.return_value = {"tools": []}
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
                    (project / ".claude").mkdir()
                    (project / ".claude/settings.json").write_text('{"enabledMcpjsonServers":["quill"]}')
                return MagicMock(stdout="client version")

            def invoke(client, project, prompt, timeout, **kwargs):
                if hook_emits:
                    import shlex
                    from quill_fixture_session_hook import emit
                    settings = json.loads((project / ".claude/settings.json").read_text())
                    command = settings["hooks"]["SessionStart"][0]["hooks"][0]["command"]
                    parts = shlex.split(command)
                    ledger = Path(parts[3])
                    emit(project, ledger, {"hook_event_name": "SessionStart", "source": "startup",
                        "cwd": str(project)}, io.StringIO(), parts[4] if len(parts) == 5 else "overview_first")
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
                    "--cases", "claude_project" if hook else "project_read_only", "--scenarios", *scenarios]
            if hook:
                argv.extend(["--guidance-variant", hook_variant])
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

    def test_hook_firing_requires_real_matching_emission_and_no_fixture_exemption(self):
        for emits in (False, True):
            _, report, calls = self.run_diagnostic(hook=True, hook_emits=emits)
            self.assertEqual(1, calls)
            run = report["runs"][0]
            self.assertEqual(emits, run["hook_evidence"]["expected_context_emitted"])
            self.assertEqual(int(emits), run["hook_evidence"]["emissions"])
            self.assertEqual(emits, run["evaluation"]["criteria"]["session_hook_emitted"])
            self.assertTrue(run["evaluation"]["criteria"]["fixture_unchanged"])

    def test_hook_wire_only_never_attests_emission(self):
        code, report, calls = self.run_diagnostic(hook=True, wire_only=True)
        self.assertEqual((0, 0), (code, calls))
        self.assertEqual([], report["runs"])

    def test_routing_hook_scores_the_correct_context_digest(self):
        _, report, _ = self.run_diagnostic(hook=True, hook_emits=True, hook_variant="routing_hook")
        self.assertTrue(report["runs"][0]["hook_evidence"]["expected_context_emitted"])

    def test_bad_wire_contract_stops_before_inference_and_keeps_remaining_coverage(self):
        code, report, calls = self.run_diagnostic(bad_wire=True)
        self.assertEqual((1, 0), (code, calls))
        self.assertEqual(1, report["summary"]["remaining"])
        self.assertFalse(report["wire_contract"]["profile_catalog"])

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

    def test_profile_configuration_is_fixture_only_and_rejects_extra_settings(self):
        import tomllib
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / ".codex").mkdir()
            codex = project / ".codex/config.toml"
            original = '[mcp_servers.quill]\ncommand="quill"\nargs=["--mcp"]\ncwd="/fixture"\n'
            codex.write_text(original)
            (project / ".mcp.json").write_text(json.dumps({"mcpServers": {
                "quill": {"command": "quill", "args": ["--mcp"]}, "other": {"command": "other"}}}))
            configure_fixture_profile(project, "router")
            self.assertEqual(["--mcp", "--tools", "router"],
                tomllib.loads(codex.read_text())["mcp_servers"]["quill"]["args"])
            self.assertEqual({"command": "other"}, json.loads((project / ".mcp.json").read_text())["mcpServers"]["other"])
            codex.write_text('model="do-not-touch"\n' + original)
            before = codex.read_text()
            with self.assertRaises(ValueError):
                configure_fixture_profile(project, "core")
            self.assertEqual(before, codex.read_text())

    def test_usage_does_not_turn_missing_invalid_values_into_zero(self):
        self.assertEqual({}, client_usage("codex", [{"type": "turn.completed", "usage": {
            "input_tokens": True, "output_tokens": -1}}]))
        self.assertEqual({"input_tokens": 3}, client_usage("codex", [
            {"type": "turn.completed", "usage": {"input_tokens": 1}},
            {"type": "turn.completed", "usage": {"input_tokens": 2}}]))
        self.assertEqual({"cache_read_input_tokens": 0}, client_usage("claude", [
            {"type": "result", "usage": {"cache_read_input_tokens": 0, "secret": "ignored"}}]))

    def test_wire_profile_catalog_prevents_wrong_catalog_from_reaching_inference(self):
        router = {"tools": [{"name": name} for name in ("get_overview", "search_tools", "execute_tool")]}
        self.assertTrue(profile_catalog_valid("router", router))
        self.assertFalse(profile_catalog_valid("core", router))
        self.assertFalse(profile_catalog_valid("full", {"tools": [{"name": "get_overview"}]}))


if __name__ == "__main__":
    unittest.main()
