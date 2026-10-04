import unittest

import quill_change_session_benchmark as benchmark


class ChangeSessionBenchmarkTest(unittest.TestCase):

    def test_semantic_contract_compares_split_and_session_evidence(self):
        plan = {"primary_changes": [{"class": "A"}], "dependency_review": []}
        verification = {
            "verdict": "needs_build", "blockers": [{"code": "BUILD"}],
            "verification_plan": {"commands": []},
            "next_actions": [{"action": "run_external_command"}],
        }
        session = {
            "plan": {"primary_changes": [{"class": "A"}], "dependency_review": []},
            "verification": {
                "verdict": "needs_build", "blockers": [{"code": "BUILD"}],
            },
            "verification_plan": {"commands": []},
            "next_actions": [{"action": "run_external_command"}],
        }

        self.assertTrue(all(benchmark.semantic_contract(
            plan, verification, session).values()))

    def test_aggregate_reports_medians(self):
        result = benchmark.aggregate([
            {"tool_calls": 2, "latency_ms": 20.0, "response_bytes": 200},
            {"tool_calls": 2, "latency_ms": 10.0, "response_bytes": 100},
            {"tool_calls": 2, "latency_ms": 30.0, "response_bytes": 300},
        ])

        self.assertEqual(20.0, result["latency_ms_median"])
        self.assertEqual(200, result["response_bytes_median"])


if __name__ == "__main__":
    unittest.main()
