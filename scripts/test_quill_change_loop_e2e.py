from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import quill_change_loop_e2e as loop


class ChangeLoopE2ETest(unittest.TestCase):
    def test_snapshot_retries_only_refresh_pending_with_the_same_arguments(self):
        client = Mock(timeout=30)
        client.request.side_effect = [
            {"isError": True, "structuredContent": {"error_code": "REFRESH_PENDING"}},
            {"structuredContent": {"phase": "complete"}},
        ]
        args = {"targets": ["Target"], "change": "Change", "limit": 40}
        with patch.object(loop.time, "sleep"):
            result, trace = loop.change_snapshot(client, args)
        self.assertEqual("complete", result["phase"])
        self.assertEqual(1, trace["refresh_retries"])
        self.assertEqual(client.request.call_args_list[0], client.request.call_args_list[1])
        client.request.side_effect = None
        client.request.return_value = {"isError": True, "structuredContent": {"error_code": "BUILD_FAILED"}}
        with self.assertRaises(RuntimeError):
            loop.change_snapshot(client, args)

    def test_snapshot_wait_has_a_deadline(self):
        client = Mock(timeout=1)
        client.request.return_value = {"isError": True, "structuredContent": {"error_code": "REFRESH_PENDING"}}
        with patch.object(loop.time, "perf_counter", side_effect=[0, 2]):
            with self.assertRaises(TimeoutError):
                loop.change_snapshot(client, {})

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
