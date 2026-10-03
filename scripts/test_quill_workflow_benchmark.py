import unittest

import quill_workflow_benchmark as benchmark


class WorkflowBenchmarkTest(unittest.TestCase):

    def test_contract_flags_distinguish_legacy_and_workflow_evidence(self):
        legacy = benchmark.contract_flags("legacy", {
            "get_symbol_details": {}, "find_usages": {},
            "find_impacted_tests": {}, "assess_change_risk": {},
        })
        workflow = benchmark.contract_flags("workflow", {
            "primary_changes": [{"risk_score": 4.0}],
            "dependency_review": [], "test_plan": {}, "_meta": {},
            "sequence": [{"order": 1}],
        })

        self.assertTrue(all(legacy.values()))
        self.assertTrue(all(workflow.values()))

    def test_comparison_reports_call_payload_and_latency_deltas(self):
        result = benchmark.comparison(
            {"tool_calls_per_sample": 4, "latency_ms_median": 100.0,
             "response_bytes_median": 1000},
            {"tool_calls_per_sample": 1, "latency_ms_median": 80.0,
             "response_bytes_median": 700})

        self.assertEqual(3, result["tool_call_reduction"])
        self.assertEqual(75.0, result["tool_call_reduction_percent"])
        self.assertEqual(-20.0, result["latency_delta_percent"])
        self.assertEqual(-30.0, result["response_bytes_delta_percent"])
        self.assertIn("no model-token", result["claim_scope"])

    def test_call_sets_exercise_equivalent_change_preparation(self):
        legacy = benchmark.legacy_calls("Target", 7)
        workflow = benchmark.workflow_calls("Target", "Change", 7)

        self.assertEqual(4, len(legacy))
        self.assertEqual(["plan_change"], [name for name, _ in workflow])
        self.assertEqual(["Target"], workflow[0][1]["targets"])


if __name__ == "__main__":
    unittest.main()
