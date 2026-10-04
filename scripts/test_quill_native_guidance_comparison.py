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
    def exercise(self, wire_only=False, mutation=False, drift=False, exception=False):
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
                runs = [] if "--wire-only" in argv else [{
                    "case": "claude_project", "client": "claude", "scenario": scenario, "sample": 1,
                    "evaluation": {"passed": not mutation, "criteria": {"fixture_unchanged": not mutation}}}
                    for scenario in ("navigation", "change_plan")]
                Path(argv[argv.index("--output") + 1]).write_text(json.dumps({
                    "wire_contract": {"ok": True}, "tool_profile": "full", "guidance_variant": variant,
                    "tools": [{"name": "get_overview"}], "server_instructions": "same instructions",
                    "scenarios": {"navigation": "changed" if drift and variant == "overview_first" else "same"},
                    "guidance": {"AGENTS.md": "same agents", "CLAUDE.md":
                        (OVERVIEW_FIRST_GUIDANCE if variant == "overview_first" else "") + "same baseline"},
                    "runs": runs}))
            args = ["--quill", str(binary), "--output", str(output), "--samples", "2"]
            if wire_only:
                args.append("--wire-only")
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
