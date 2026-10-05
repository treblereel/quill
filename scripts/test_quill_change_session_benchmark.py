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

    def test_summary_contract_accepts_compact_semantic_evidence(self):
        plan = {"primary_changes": [{"class": "A", "file": "A.java"}]}
        verification = {
            "verdict": "needs_build", "blockers": [],
            "next_actions": [{"action": "run_external_command"}],
        }
        session = {
            "plan": {"primary_changes": [{"class": "A", "file": "A.java"}]},
            "verification": {"verdict": "needs_build", "blockers": []},
            "verification_plan": {"commands": [{"scope": "quick_compile"}]},
            "next_actions": [{"action": "run_external_command"}],
            "omitted_sections": ["plan.member_contracts"],
        }

        self.assertTrue(all(benchmark.summary_contract(
            plan, verification, session).values()))

    def test_auto_contract_uses_plan_actions_for_planned_phase(self):
        sequence = [{"action": "inspect_primary"}]
        session = {
            "phase": "planned", "view": "plan", "plan": {},
            "next_actions": sequence, "omitted_sections": ["verification"],
        }

        self.assertTrue(all(benchmark.auto_contract(
            {"sequence": sequence}, {"next_actions": []}, session).values()))

    def test_action_parity_ignores_ids_but_detects_changed_semantics(self):
        verification = {"next_actions": [{"action": "run_external_command", "order": 1}]}
        session = {"next_actions": [{"action": "run_external_command", "order": 2,
                                     "action_id": "stable"}]}
        self.assertTrue(benchmark.actions_match({}, verification, session))
        session["next_actions"][0]["action"] = "complete"
        self.assertFalse(benchmark.actions_match({}, verification, session))

    def test_review_action_parity_preserves_phase_ordering(self):
        build = {"action": "run_external_command", "reason": "BUILD_EVIDENCE_REQUIRED"}
        review = {"action": "inspect_test_evidence", "reason": "INCOMPLETE_TEST_COVERAGE"}
        self.assertTrue(benchmark.actions_match({}, {"next_actions": [build, review]},
                         {"phase": "review_required", "next_actions": [review, build]}))


if __name__ == "__main__":
    unittest.main()
