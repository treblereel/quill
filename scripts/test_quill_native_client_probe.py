import unittest
import tomllib
from pathlib import Path
from unittest.mock import MagicMock, patch

from quill_native_client_probe import events, invoke, tool_names, workflow_access


class NativeClientProbeTest(unittest.TestCase):
    def test_adoption_can_offer_source_search_without_unrestricted_shell_or_edits(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.communicate.return_value = ("", "")
        process.returncode = 0
        with patch("quill_native_client_probe.subprocess.Popen", return_value=process) as launch:
            invoke("claude", Path("/tmp/fixture"), "read-only task", 1, source_reads=True)
        argv = launch.call_args.args[0]
        allowed = argv[argv.index("--allowedTools") + 1:]
        self.assertIn("Grep", allowed)
        self.assertIn("Glob", allowed)
        self.assertIn("Bash(git log *)", allowed)
        self.assertNotIn("Bash", allowed)
        self.assertNotIn("Edit", allowed)
        self.assertNotIn("Bash(mvn test-compile)", allowed)

    def test_native_commands_preserve_auto_discovery_and_bound_permissions(self):
        for client in ("codex", "claude"):
            process = MagicMock()
            process.__enter__.return_value = process
            process.communicate.return_value = ("", "")
            process.returncode = 0
            with patch("quill_native_client_probe.subprocess.Popen", return_value=process) as launch:
                invoke(client, Path("/tmp/fixture"), "fixture task", 1, trusted=True, edit=True)
                argv = launch.call_args.args[0]
                self.assertNotIn("--ignore-user-config", argv)
                self.assertNotIn("--bare", argv)
                self.assertNotIn("--mcp-config", argv)
                self.assertNotIn("--dangerously-skip-permissions", argv)
                self.assertTrue(launch.call_args.kwargs["start_new_session"])
                if client == "claude":
                    self.assertEqual(1, argv.count("--allowedTools"))
                    self.assertIn("Bash(mvn test-compile)", argv)
                    self.assertNotIn("Bash", argv[argv.index("--allowedTools") + 1:])
                else:
                    self.assertIn('projects={"/tmp/fixture"={trust_level="trusted"}}', argv)

    def test_trust_override_keeps_path_in_value_not_dotted_key(self):
        project = Path('/tmp/fixture.with.dots and spaces')
        process = MagicMock()
        process.__enter__.return_value = process
        process.communicate.return_value = ("", "")
        process.returncode = 0
        with patch("quill_native_client_probe.subprocess.Popen", return_value=process) as launch:
            invoke("codex", project, "fixture task", 1, trusted=True)
            argv = launch.call_args.args[0]
            override = argv[argv.index("-c") + 1]
        key, value = override.split("=", 1)
        self.assertEqual("projects", key)
        self.assertEqual({str(project): {"trust_level": "trusted"}},
                         tomllib.loads("value=" + value)["value"])

    def test_writable_codex_probe_supplies_in_memory_trust(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.communicate.return_value = ("", "")
        process.returncode = 0
        with patch("quill_native_client_probe.subprocess.Popen", return_value=process) as launch:
            invoke("codex", Path("/tmp/fixture"), "task", 1, edit=True)
        self.assertIn('projects={"/tmp/fixture"={trust_level="trusted"}}',
                      launch.call_args.args[0])

    def test_resources_or_mentions_do_not_prove_workflow_access(self):
        self.assertFalse(workflow_access({"tools": ["quill:list_mcp_resources"]}))
        for tool in ("quill:change_session", "mcp__quill__change_session"):
            self.assertTrue(workflow_access({"tools": [tool]}))

    def test_client_config_side_effects_are_reported_without_contents(self):
        process = MagicMock()
        process.__enter__.return_value = process
        process.communicate.return_value = ("", "")
        process.returncode = 0
        for after, unchanged in (("before", True), ("changed", False)):
            with patch("quill_native_client_probe.subprocess.Popen", return_value=process), \
                    patch("quill_native_client_probe.user_config_digest", side_effect=["before", after]):
                capture = invoke("codex", Path("/tmp/fixture"), "task", 1, trusted=True)
            self.assertEqual(unchanged, capture["user_config_unchanged"])
            self.assertNotIn("before", capture.values())
            self.assertNotIn("changed", capture.values())

    def test_event_parser_ignores_noise_and_nonobjects(self):
        self.assertEqual([{"type": "result"}], events('noise\n[]\n{"type":"result"}\n'))

    def test_codex_counts_only_completed_mcp_calls(self):
        item = {"type": "mcp_tool_call", "server": "quill", "tool": "get_overview"}
        stream = [{"type": "item.started", "item": item},
                  {"type": "item.completed", "item": item},
                  {"type": "item.completed", "item": {"type": "command_execution"}}]
        self.assertEqual(["quill:get_overview"], tool_names("codex", stream))

    def test_claude_extracts_actual_tool_use_not_catalog(self):
        stream = [{"type": "system", "tools": ["mcp__quill__change_session"]},
                  {"type": "user", "message": "plain text"},
                  {"type": "assistant", "message": {"content": [
                      {"type": "text", "text": "calling Quill"},
                      {"type": "tool_use", "name": "mcp__quill__change_session"}]}}]
        self.assertEqual(["mcp__quill__change_session"], tool_names("claude", stream))


if __name__ == "__main__":
    unittest.main()
