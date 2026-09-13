import tempfile
from pathlib import Path
import unittest

from quill_benchmark import IndexSandbox, parse_concurrency, percentile


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
