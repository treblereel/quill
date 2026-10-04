import io
import json
from pathlib import Path
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from quill_native_guidance_comparison import main, summarize
from quill_adoption_diagnostic import OVERVIEW_FIRST_GUIDANCE


class NativeGuidanceComparisonTest(unittest.TestCase):
    def exercise(self, wire_only=False, mutation=False, drift=False, exception=False, scenarios=None, treatments=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / "quill"
            binary.touch()
            output = root / "report.json"
            variants = []
            def diagnostic(argv):
                variant = argv[argv.index("--guidance-variant") + 1]
                variants.append(variant)
                if exception:
                    raise RuntimeError("secret remote error")
                selected = argv[argv.index("--scenarios") + 1:argv.index("--samples")]
                runs = [] if "--wire-only" in argv else [{
                    "case": "claude_project", "client": "claude", "scenario": scenario, "sample": 1,
                    "evaluation": {"passed": not mutation, "criteria": {"fixture_unchanged": not mutation}}}
                    for scenario in selected]
                Path(argv[argv.index("--output") + 1]).write_text(json.dumps({
                    "wire_contract": {"ok": True}, "tool_profile": "full", "guidance_variant": variant,
                    "tools": [{"name": "get_overview"}], "server_instructions": "same instructions",
                    "scenarios": {scenario: "changed" if drift and variant == "overview_first" else "same"
                                  for scenario in selected},
                    "guidance": {"AGENTS.md": "same agents", "CLAUDE.md":
                        (OVERVIEW_FIRST_GUIDANCE if variant == "overview_first" else "") + "same baseline"},
                    "runs": runs}))
            args = ["--quill", str(binary), "--output", str(output), "--samples", "2"]
            if wire_only:
                args.append("--wire-only")
            if scenarios:
                args.extend(["--scenarios", *scenarios])
            if treatments:
                args.extend(["--variants", *treatments])
            with patch("quill_native_guidance_comparison.diagnostic.main", side_effect=diagnostic), \
                    redirect_stdout(io.StringIO()):
                code = main(args)
            return code, json.loads(output.read_text()), variants

    def test_rotates_variants_and_retains_exact_task_cells(self):
        code, report, variants = self.exercise()
        self.assertEqual(["baseline", "overview_first", "overview_first", "baseline"], variants)
        self.assertEqual(0, code)
        self.assertEqual((8, 8, 0), tuple(report["summary"][name] for name in ("planned", "completed", "remaining")))
        self.assertTrue(report["summary"]["coverage_complete"])

    def test_wire_only_cannot_claim_adoption(self):
        code, report, _ = self.exercise(wire_only=True)
        self.assertEqual(0, code)
        self.assertTrue(report["summary"]["wire_passed"])
        self.assertFalse(report["summary"]["all_passed"])
        self.assertEqual([], report["planned"])

    def test_hook_comparison_rotates_selected_variants(self):
        code, report, variants = self.exercise(treatments=["baseline", "session_start"])
        self.assertEqual(0, code)
        self.assertEqual(["baseline", "session_start", "session_start", "baseline"], variants)
        self.assertEqual(8, report["summary"]["completed"])

    def test_duplicate_or_single_variant_rejected_before_binary_read(self):
        for variants in (["baseline"], ["baseline", "baseline"]):
            with self.assertRaises(ValueError):
                main(["--output", "/unused", "--variants", *variants])

    def test_selected_tasks_propagate_to_children_and_exact_denominator(self):
        code, report, _ = self.exercise(scenarios=["usages", "dependencies", "history"])
        self.assertEqual(0, code)
        self.assertEqual(["usages", "dependencies", "history"], report["scenarios"])
        self.assertEqual((12, 12, 0), tuple(report["summary"][name]
                                         for name in ("planned", "completed", "remaining")))
        self.assertEqual({"usages", "dependencies", "history"},
                         {run["scenario"] for run in report["runs"]})

    def test_duplicate_tasks_rejected_before_launch_or_binary_read(self):
        with patch("quill_native_guidance_comparison.diagnostic.main") as inference:
            with self.assertRaises(ValueError):
                main(["--output", "/unused", "--scenarios", "navigation", "navigation"])
            inference.assert_not_called()

    def test_mutation_or_prompt_drift_stop_remaining_runs(self):
        for kwargs, completed in (({"mutation": True}, 2), ({"drift": True}, 4)):
            code, report, variants = self.exercise(**kwargs)
            self.assertEqual(1, code)
            self.assertEqual(completed, report["summary"]["completed"])
            self.assertLess(len(variants), 4)
            self.assertFalse(report["summary"]["all_passed"])

    def test_exception_report_has_full_denominator_and_no_error_body(self):
        code, report, _ = self.exercise(exception=True)
        self.assertEqual(1, code)
        self.assertEqual("RuntimeError", report["failure"])
        self.assertEqual(8, report["summary"]["remaining"])
        self.assertNotIn("secret", json.dumps(report))

    def test_duplicate_or_wrong_variant_cannot_satisfy_coverage(self):
        planned = [{"variant": "baseline", "case": "claude_project", "scenario": "navigation", "sample": 1}]
        run = {**planned[0], "evaluation": {"passed": True}}
        self.assertFalse(summarize([run, run], planned)["coverage_complete"])
        self.assertFalse(summarize([{**run, "variant": "overview_first"}], planned)["all_passed"])


if __name__ == "__main__":
    unittest.main()
