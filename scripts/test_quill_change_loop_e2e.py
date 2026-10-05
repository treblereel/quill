from pathlib import Path
import tempfile
import unittest

import quill_change_loop_e2e as loop


class ChangeLoopE2ETest(unittest.TestCase):
    def test_quick_compile_contract_accepts_system_maven_without_wrapper(self):
        with tempfile.TemporaryDirectory() as temporary:
            failures = loop.assert_contract(Path(temporary), {
                "argv": ["/usr/local/bin/mvn", "test-compile"],
                "executes_tests": False,
                "compiles_test_sources": True,
            }, "/usr/local/bin/mvn")

        self.assertEqual([], failures)

    def test_quick_compile_contract_rejects_test_execution(self):
        with tempfile.TemporaryDirectory() as temporary:
            failures = loop.assert_contract(Path(temporary), {
                "argv": ["mvn", "test"],
                "executes_tests": True,
                "compiles_test_sources": True,
            }, "mvn")

        self.assertTrue(any("test-compile" in failure for failure in failures))
        self.assertTrue(any("test-executing" in failure for failure in failures))
        self.assertTrue(any("executes_tests=false" in failure for failure in failures))

    def test_quick_compile_selects_one_scoped_command(self):
        command = loop.quick_compile({"verification_plan": {"commands": [
            {"scope": "quick_compile", "argv": ["mvn", "test-compile"]},
            {"scope": "module_fallback", "argv": ["mvn", "test"]},
        ]}})

        self.assertEqual("quick_compile", command["scope"])


if __name__ == "__main__":
    unittest.main()
