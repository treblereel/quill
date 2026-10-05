import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import quill_change_loop_e2e as loop
from quill_agent_change_loop import ChangeTools, SOURCE


class ChangeToolsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.project = Path(self.temporary.name).resolve()
        loop.write_fixture(self.project)
        self.tools = ChangeTools(self.project, Path("/quill"), "/usr/bin/mvn", False)

    def test_only_exact_single_source_replacement(self):
        self.tools.call("replace_fixture_source", {
            "old": 'return "Hello, " + name;', "new": 'return "Hello, " + name + "!";'})
        self.assertEqual(1, self.tools.edits)
        self.assertIn('name + "!";', (self.project / SOURCE).read_text())
        for old in ("", "missing", " "):
            with self.assertRaises(ValueError):
                self.tools.call("replace_fixture_source", {"old": old, "new": "oops"})

    def recommendation(self, argv, cwd=None):
        return {"directive": {"primary_action": {
            "working_directory": str(cwd or self.project), "preparation_command": {
                "argv": argv, "executes_tests": False, "compiles_test_sources": True}}}}

    def test_rejects_arbitrary_or_test_executing_commands(self):
        for argv in (["/usr/bin/mvn", "test"], ["/bin/sh", "test-compile"],
                     ["/usr/bin/mvn", "-Danything=x", "test-compile"]):
            with patch.object(self.tools, "snapshot", return_value=self.recommendation(argv)), \
                    patch.object(loop, "run_unchecked") as execute:
                with self.assertRaises(ValueError):
                    self.tools.call("execute_recommended_compile", {})
                execute.assert_not_called()

    def test_rejects_outside_directory_and_missing_recommendation(self):
        for snapshot in ({}, self.recommendation(["/usr/bin/mvn", "test-compile"], Path("/tmp"))):
            with patch.object(self.tools, "snapshot", return_value=snapshot), \
                    patch.object(loop, "run_unchecked") as execute:
                with self.assertRaises(ValueError):
                    self.tools.call("execute_recommended_compile", {})
                execute.assert_not_called()

    def test_compile_budget(self):
        self.tools.executions = [{}] * 4
        with self.assertRaisesRegex(ValueError, "budget"):
            self.tools.call("execute_recommended_compile", {})


if __name__ == "__main__":
    unittest.main()
