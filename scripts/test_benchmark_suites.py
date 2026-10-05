import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class BenchmarkSuitesTest(unittest.TestCase):

    def test_current_casehub_suite_covers_end_to_end_user_workflows(self):
        suite = json.loads((ROOT / "benchmarks/casehub-engine-current-effectiveness.json")
                           .read_text(encoding="utf-8"))
        tasks = suite["tasks"]
        ids = [task["id"] for task in tasks]
        categories = {task["category"] for task in tasks}

        self.assertEqual(15, len(tasks))
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue({
            "exploration", "dependency-impact", "dependency-injection",
            "git-history", "test-impact", "framework-endpoints", "configuration",
            "architecture", "dead-code", "pre-modification", "module-architecture",
            "execution-flow",
        }.issubset(categories))
        for task in tasks:
            self.assertTrue(task["prompt"].strip(), task["id"])
            self.assertTrue(task["expected"], task["id"])
            self.assertTrue(task["quill_tools"], task["id"])
            for value in task["expected"].values():
                if isinstance(value, list):
                    self.assertEqual(sorted(value), value, task["id"])


if __name__ == "__main__":
    unittest.main()
