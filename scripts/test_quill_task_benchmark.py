import unittest

from quill_task_benchmark import build_report, score_run


SUITE = {
    "name": "sample",
    "tasks": [
        {"id": "one", "expected": {"status": "resolved", "fan_in": 2}},
        {"id": "two", "expected": {"deleted": False}},
    ],
}


def run(mode, first, second):
    return {
        "mode": mode,
        "tasks": [
            {"id": "one", "observed": first, "duration_seconds": 2,
             "input_tokens": 100, "output_tokens": 20, "requests": 2,
             "manual_verification_steps": 1},
            {"id": "two", "observed": second, "duration_seconds": 3,
             "input_tokens": 200, "output_tokens": 30, "requests": 3,
             "manual_verification_steps": 2},
        ],
    }


class QuillTaskBenchmarkTest(unittest.TestCase):

    def test_scores_accuracy_resources_and_missing_facts(self):
        result = score_run(SUITE, run("with_quill",
                                     {"status": "resolved", "fan_in": 2}, {}))

        self.assertEqual(3, result["totals"]["correct_facts"]
                         + result["totals"]["missing_facts"])
        self.assertEqual(2, result["totals"]["correct_facts"])
        self.assertEqual(1, result["totals"]["missing_facts"])
        self.assertEqual(0.6667, result["totals"]["fact_accuracy"])
        self.assertEqual(350, result["totals"]["total_tokens"])
        self.assertEqual(5, result["totals"]["requests"])
        self.assertEqual(3, result["totals"]["manual_verification_steps"])

    def test_comparison_uses_paired_runs_and_not_compression(self):
        with_quill = run("with_quill",
                         {"status": "resolved", "fan_in": 2}, {"deleted": False})
        without_quill = run("without_quill",
                            {"status": "unknown", "fan_in": 1}, {})
        without_quill["tasks"][0]["input_tokens"] = 500

        report = build_report(SUITE, with_quill, without_quill)

        delta = report["comparison"]["with_quill_minus_without_quill"]
        self.assertGreater(delta["fact_accuracy"], 0)
        self.assertLess(delta["total_tokens"], 0)
        self.assertTrue(report["comparison"]["interpretation"]
                        ["compression_metadata_is_not_used_as_agent_token_savings"])

    def test_rejects_unknown_task_ids(self):
        invalid = {"mode": "bad", "tasks": [
            {"id": "unknown", "observed": {}, "duration_seconds": 0,
             "input_tokens": 0, "output_tokens": 0, "requests": 0,
             "manual_verification_steps": 0},
        ]}
        with self.assertRaises(ValueError):
            score_run(SUITE, invalid)


if __name__ == "__main__":
    unittest.main()
