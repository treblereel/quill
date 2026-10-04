import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from quill_fixture_session_hook import emit, main
from quill_adoption_diagnostic import OVERVIEW_FIRST_GUIDANCE, configure_fixture_guidance


class FixtureSessionHookTest(unittest.TestCase):
    def test_emits_exact_context_and_external_digest_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "project"
            project.mkdir()
            ledger = root / "ledger"
            output = io.StringIO()
            self.assertTrue(emit(project, ledger, {"hook_event_name": "SessionStart",
                "source": "startup", "cwd": str(project), "session_id": "never-record-this"}, output))
            response = json.loads(output.getvalue())["hookSpecificOutput"]
            self.assertEqual("SessionStart", response["hookEventName"])
            self.assertEqual(OVERVIEW_FIRST_GUIDANCE, response["additionalContext"])
            self.assertEqual({"context_sha256"}, set(json.loads(ledger.read_text())))
            self.assertEqual([], list(project.iterdir()))

    def test_rejects_wrong_event_source_cwd_or_internal_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / "project"
            project.mkdir()
            payload = {"hook_event_name": "SessionStart", "source": "startup", "cwd": str(project)}
            for delta in ({"hook_event_name": "PostToolUse"}, {"source": "compact"},
                          {"cwd": directory}, {"cwd": ""}, {"cwd": None}):
                output = io.StringIO()
                self.assertFalse(emit(project, Path(directory) / "ledger", {**payload, **delta}, output))
                self.assertEqual("", output.getvalue())
            self.assertFalse(emit(project, project / "ledger", payload, io.StringIO()))

    def test_malformed_input_is_fail_open_and_silent(self):
        with patch("sys.stdin", io.StringIO("secret malformed JSON")), \
                patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(0, main(["/fixture", "/ledger"]))
            self.assertEqual("", output.getvalue())

    def test_session_start_does_not_change_instruction_files(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            for name in ("CLAUDE.md", "AGENTS.md"):
                (project / name).write_text("same baseline")
            before = {name: (project / name).read_bytes() for name in ("CLAUDE.md", "AGENTS.md")}
            configure_fixture_guidance(project, "session_start")
            self.assertEqual(before, {name: (project / name).read_bytes() for name in before})

    def test_oversized_input_is_silent_without_calling_emitter(self):
        with patch("sys.stdin", io.StringIO(" " * 65537)), \
                patch("quill_fixture_session_hook.emit") as emitter:
            self.assertEqual(0, main(["/fixture", "/ledger"]))
            emitter.assert_not_called()
