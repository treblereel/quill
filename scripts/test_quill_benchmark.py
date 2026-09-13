import tempfile
from pathlib import Path
import unittest

from quill_benchmark import (IndexSandbox, latency_summary, parse_concurrency,
                             parse_phase_timings, percentile)


class QuillBenchmarkTest(unittest.TestCase):

    def test_parse_concurrency_requires_positive_integers(self):
        self.assertEqual([4, 16, 100], parse_concurrency("4,16,100"))
        for invalid in ("", "0", "4,-1", "four"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                parse_concurrency(invalid)

    def test_percentile_uses_nearest_rank(self):
        values = [40.0, 10.0, 30.0, 20.0]
        self.assertEqual(20.0, percentile(values, 0.50))
        self.assertEqual(40.0, percentile(values, 0.95))

    def test_latency_summary_keeps_distribution_and_errors(self):
        summary = latency_summary([40.0, 10.0, 30.0, 20.0], errors=1)
        self.assertEqual({
            "samples": 4,
            "p50_latency_ms": 20.0,
            "p95_latency_ms": 40.0,
            "max_latency_ms": 40.0,
            "errors": 1,
        }, summary)

    def test_parse_phase_timings(self):
        stderr = ("[quill] Indexing sample...\n"
                  "[quill] Timings: lock_wait=1ms, application_index=42ms, total=50ms\n")
        self.assertEqual({
            "lock_wait": 1,
            "application_index": 42,
            "total": 50,
        }, parse_phase_timings(stderr))

    def test_parse_phase_timings_returns_empty_when_not_reported(self):
        self.assertEqual({}, parse_phase_timings("[quill] Done.\n"))

    def test_sandbox_removes_index_when_project_started_without_one(self):
        with tempfile.TemporaryDirectory() as directory:
            index = Path(directory) / ".quill"
            with IndexSandbox(index, keep_benchmark_index=False):
                index.mkdir()
                (index / "benchmark.db").write_bytes(b"new")
            self.assertFalse(index.exists())

    def test_sandbox_restores_existing_index(self):
        with tempfile.TemporaryDirectory() as directory:
            index = Path(directory) / ".quill"
            index.mkdir()
            (index / "original.db").write_bytes(b"old")
            with IndexSandbox(index, keep_benchmark_index=False):
                index.mkdir()
                (index / "benchmark.db").write_bytes(b"new")
            self.assertEqual(b"old", (index / "original.db").read_bytes())
            self.assertFalse((index / "benchmark.db").exists())


if __name__ == "__main__":
    unittest.main()
