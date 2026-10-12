import subprocess
import sys
import unittest
from unittest.mock import MagicMock, patch

import quill_kotlin_native_e2e as e2e


class NativeKotlinCommandTest(unittest.TestCase):
    def test_command_deadline_is_enforced(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            e2e.run([sys.executable, "-c", "import time; time.sleep(5)"], timeout=.05)

    def test_request_and_command_failure_are_not_hidden(self):
        result, elapsed = e2e.run([sys.executable, "-c", "import sys; print(sys.stdin.read())"],
                                 {"source": "spaces and Unicode: λ"}, timeout=5)
        self.assertEqual({"source": "spaces and Unicode: λ"}, e2e.json.loads(result.stdout))
        self.assertGreaterEqual(elapsed, 0)
        with self.assertRaises(subprocess.CalledProcessError):
            e2e.run([sys.executable, "-c", "raise SystemExit(7)"], timeout=5)


class NativeKotlinShutdownTest(unittest.TestCase):
    def client(self):
        client = MagicMock()
        client.client.process.pid = 42
        client.client.process.wait.return_value = 0
        client.client.process.returncode = 0
        return client

    @patch.object(e2e.subprocess, "run")
    @patch.object(e2e.os, "kill", side_effect=ProcessLookupError)
    def test_eof_requires_parent_and_all_worker_descendants_to_exit(self, kill, run):
        run.return_value.stdout = "101 42\n102 101\n999 1\n"
        client = self.client()
        result = e2e.assert_clean_shutdown(client)
        self.assertEqual(2, result["worker_processes"])
        self.assertFalse(result["forced_termination"])
        self.assertEqual({101, 102}, {call.args[0] for call in kill.call_args_list})
        client.client.process.stdin.close.assert_called_once()
        client.client.process.kill.assert_not_called()

    @patch.object(e2e.subprocess, "run")
    def test_missing_worker_cannot_attest_worker_shutdown(self, run):
        run.return_value.stdout = "999 1\n"
        with self.assertRaisesRegex(AssertionError, "Expected a live external K2 worker"):
            e2e.assert_clean_shutdown(self.client())

    @patch.object(e2e.subprocess, "run")
    def test_mcp_ignoring_eof_fails_without_forced_termination(self, run):
        run.return_value.stdout = "101 42\n"
        client = self.client()
        client.client.process.wait.side_effect = subprocess.TimeoutExpired("quill", 5)
        with self.assertRaises(subprocess.TimeoutExpired):
            e2e.assert_clean_shutdown(client)
        client.client.process.kill.assert_not_called()

    @patch.object(e2e.subprocess, "run")
    @patch.object(e2e.os, "kill", return_value=None)
    @patch.object(e2e.time, "monotonic", side_effect=[0, 0, 6])
    def test_surviving_worker_fails_even_if_mcp_exits_successfully(self, monotonic, kill, run):
        run.return_value.stdout = "101 42\n"
        with self.assertRaisesRegex(AssertionError, "K2 worker survived MCP EOF"):
            e2e.assert_clean_shutdown(self.client())


if __name__ == "__main__":
    unittest.main()
