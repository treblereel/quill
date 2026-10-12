import tempfile
import os
import subprocess
from pathlib import Path
import unittest
from unittest.mock import patch

from quill_perf_smoke import enforce_budgets, generate_fixture


class QuillPerfSmokeTest(unittest.TestCase):

    def test_fixture_isolated_from_global_signing_and_automatic_maintenance(self):
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            global_config = parent / "gitconfig"
            global_config.write_text(
                "[commit]\n\tgpgsign = true\n[gpg]\n\tprogram = nonexistent-quill-test-signer\n"
                "[gc]\n\tauto = 1\n[maintenance]\n\tauto = true\n", encoding="utf-8")
            root = parent / "fixture"
            root.mkdir()
            with patch.dict(os.environ, {"GIT_CONFIG_GLOBAL": str(global_config)}):
                generate_fixture(root, 2, 1)
                for key, expected in (("gc.auto", "0"), ("maintenance.auto", "false"),
                                      ("commit.gpgsign", "false")):
                    value = subprocess.check_output(
                        ["git", "config", "--local", key], cwd=root, text=True).strip()
                    self.assertEqual(expected, value)
                self.assertEqual("fixture", subprocess.check_output(
                    ["git", "log", "-1", "--format=%s"], cwd=root, text=True).strip())

    def test_fixture_generation_creates_requested_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate_fixture(root, 5, 3)

            self.assertEqual(5, len(list(
                (root / "src/main/java/perf/fixture").glob("Service*.java"))))
            self.assertEqual(7, len(list((root / "target/classes").rglob("*.class"))))
            self.assertEqual(3, len(list(
                (root / "target/dependency-repository").rglob("*.jar"))))
            self.assertTrue((root / "target/quill-classpath.txt").is_file())

    def test_budgets_reject_errors_and_slow_runs(self):
        base = {
            "cold_init": {
                "exit_code": 0, "duration_seconds": 1.0,
                "peak_rss_mib": 100.0,
                "phase_timings_ms": {"total": 900, "dependency_jar_index": 100},
                "stderr": "[quill] Indexing 3 dependency JARs...",
            },
            "cache_hit": {
                "exit_code": 0, "duration_seconds": 0.5,
                "peak_rss_mib": 90.0,
                "phase_timings_ms": {
                    "total": 400, "dependency_cache_read": 100,
                    "dependency_jar_index": 0,
                },
                "stderr": "[quill] Reusing dependency index for 3 JARs...",
            },
            "mcp": {
                "tool_catalog_bytes": 20_000,
                "tools": [{"errors": 0}],
                "batches": [{"errors": 0, "elapsed_seconds": 1.0}],
            },
            "router_catalog": {
                "tool_catalog_count": 3,
                "tool_catalog_bytes": 2_000,
            },
        }
        enforce_budgets(base, 2.0, 200.0, 2.0)

        slow = {**base, "cold_init": {**base["cold_init"], "duration_seconds": 3.0}}
        with self.assertRaises(RuntimeError):
            enforce_budgets(slow, 2.0, 200.0, 2.0)

        bloated_router = {**base, "router_catalog": {
            "tool_catalog_count": 3, "tool_catalog_bytes": 5_000}}
        with self.assertRaises(RuntimeError):
            enforce_budgets(bloated_router, 2.0, 200.0, 2.0)

        expanded_catalog = {**base, "mcp": {
            **base["mcp"], "tool_catalog_bytes": 60_000}}
        with self.assertRaises(RuntimeError):
            enforce_budgets(expanded_catalog, 2.0, 200.0, 2.0)


if __name__ == "__main__":
    unittest.main()
