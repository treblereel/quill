import tempfile
from pathlib import Path
import unittest

from quill_perf_smoke import enforce_budgets, generate_fixture


class QuillPerfSmokeTest(unittest.TestCase):

    def test_fixture_generation_creates_requested_classes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            generate_fixture(root, 5, 3)

            self.assertEqual(5, len(list(
                (root / "src/main/java/perf/fixture").glob("Service*.java"))))
            self.assertEqual(7, len(list((root / "target/classes").rglob("*.class"))))
            self.assertEqual(3, len(list((root / "target/dependency-jars").glob("*.jar"))))
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
                "tools": [{"errors": 0}],
                "batches": [{"errors": 0, "elapsed_seconds": 1.0}],
            },
        }
        enforce_budgets(base, 2.0, 200.0, 2.0)

        slow = {**base, "cold_init": {**base["cold_init"], "duration_seconds": 3.0}}
        with self.assertRaises(RuntimeError):
            enforce_budgets(slow, 2.0, 200.0, 2.0)


if __name__ == "__main__":
    unittest.main()
