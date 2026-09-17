import unittest

from quill_task_benchmark import (build_repeated_report, build_report, score_run,
                                  runs_from_documents)


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
             "input_tokens": 100, "cached_input_tokens": 40,
             "output_tokens": 20, "model_requests": 1, "requests": 2,
             "manual_verification_steps": 1, "source_fallback_count": 2,
             "source_first_count": 1, "quill_bypass_count": 0,
             "source_fallbacks": [{"quill_tool": "quill_get_dependencies",
                                    "source_tool": "source_search"}]},
            {"id": "two", "observed": second, "duration_seconds": 3,
             "input_tokens": 200, "cached_input_tokens": 50,
             "output_tokens": 30, "model_requests": 2, "requests": 3,
             "manual_verification_steps": 2, "source_fallback_count": 0},
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
        self.assertEqual(260, result["totals"]["uncached_tokens"])
        self.assertEqual(3, result["totals"]["model_requests"])
        self.assertEqual(5, result["totals"]["requests"])
        self.assertEqual(3, result["totals"]["manual_verification_steps"])
        self.assertEqual(2, result["totals"]["source_fallback_count"])
        self.assertEqual(1, result["totals"]["source_first_count"])
        self.assertEqual(0, result["totals"]["quill_bypass_count"])
        self.assertEqual(1, len(result["tasks"][0]["source_fallbacks"]))

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

    def test_rejects_mismatched_project_revision(self):
        suite = {**SUITE, "project_revision": "expected"}
        capture = run("with_quill", {"status": "resolved", "fan_in": 2},
                      {"deleted": False})
        capture["project_revision"] = "different"

        with self.assertRaisesRegex(ValueError, "does not match"):
            score_run(suite, capture)

    def test_accepts_declared_semantic_equivalent(self):
        suite = {**SUITE, "tasks": [
            {"id": "one", "expected": {"status": "resolved", "fan_in": 2},
             "accepted": {"status": ["satisfied"]}},
            SUITE["tasks"][1],
        ]}
        result = score_run(suite, run("with_quill",
                                     {"status": "satisfied", "fan_in": 2},
                                     {"deleted": False}))
        self.assertEqual(1.0, result["totals"]["fact_accuracy"])

    def test_accepts_json_numeric_strings_but_not_boolean_strings(self):
        numeric = score_run(SUITE, run(
            "with_quill", {"status": "resolved", "fan_in": "2"},
            {"deleted": False}))
        boolean = score_run(SUITE, run(
            "with_quill", {"status": "resolved", "fan_in": 2},
            {"deleted": "false"}))

        self.assertEqual(1.0, numeric["totals"]["fact_accuracy"])
        self.assertEqual(1, boolean["totals"]["incorrect_facts"])

    def test_summarizes_repeated_paired_runs(self):
        first_with = run("with_quill", {"status": "resolved", "fan_in": 2},
                         {"deleted": False})
        second_with = run("with_quill", {"status": "resolved", "fan_in": 2},
                          {"deleted": False})
        second_with["tasks"][0]["duration_seconds"] = 12
        baseline = run("without_quill", {"status": "unknown", "fan_in": 1}, {})

        report = build_repeated_report(
            SUITE, [first_with, second_with], [baseline, baseline])

        self.assertEqual(2, report["run_count"])
        duration = report["with_quill"]["summary"]["metrics"]["duration_seconds"]
        self.assertEqual(10, duration["median"])
        self.assertEqual(15, duration["p95"])
        self.assertGreater(report["paired_delta_median"]["fact_accuracy"], 0)

    def test_expands_repeated_run_documents(self):
        one = run("with_quill", {"status": "resolved", "fan_in": 2},
                  {"deleted": False})
        self.assertEqual([one, one], runs_from_documents([{"runs": [one, one]}]))


if __name__ == "__main__":
    unittest.main()
