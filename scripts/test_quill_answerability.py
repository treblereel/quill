import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest

import quill_answerability as a

# Stands in for `quill prefetch`: echoes the class names it finds in the prompt as a pack.
STUB = textwrap.dedent('''
    import json, re, sys
    hook = json.load(sys.stdin)
    names = re.findall(r"[a-z]+(?:\\.[a-zA-Z]+)+", hook["prompt"])
    if names:
        context = json.dumps({"facts": [{"class": name} for name in names]})
        print(json.dumps({"hookSpecificOutput": {"additionalContext": context}}))
''')


class AnswerabilityTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.stub = self.dir / "stub.py"
        self.stub.write_text(STUB)
        self.project = self.dir / "project"
        (self.project / ".quill").mkdir(parents=True)

    def test_identifiers_skip_prose_versions_and_java_lang(self):
        found = a.identifiers({"beans": ["io.acme.Orders#place(java.lang.String):id", "see e.g. text"],
                               "version": "3.40.1.1", "type": "java.lang.String",
                               "key": "orders.timeout"})
        self.assertIn("io.acme.Orders#place", found)
        self.assertIn("orders.timeout", found)
        self.assertNotIn("3.40.1.1", found)
        self.assertNotIn("java.lang.String", found)

    def test_member_reference_counts_when_class_and_member_are_named(self):
        self.assertTrue(a.mentioned("io.acme.Orders#place(java.lang.String)",
                                    '{"class":"io.acme.Orders","method":"place"}'))
        self.assertFalse(a.mentioned("io.acme.Orders#cancel", '{"class":"io.acme.Orders"}'))

    def test_linked_prompts_share_one_session_and_coverage_counts_every_turn(self):
        oracles = {"cases": {
            "CQ-1": {"expected": {"classes": ["io.acme.Orders", "io.acme.Billing"]}},
            "CQ-2": {"expected": {"classes": ["io.acme.Missing"]}},
            "CQ-3": {"expected": {"note": "no identifiers here"}}}}
        manager = {"sessions": [
            {"case": "CQ-1", "prompts": ["What uses io.acme.Orders?", "And io.acme.Billing?"]},
            {"case": "CQ-2", "prompts": ["Explain the startup order"]},
            {"case": "CQ-3", "prompts": ["Anything"]}]}
        (self.dir / "oracles.json").write_text(json.dumps(oracles))
        (self.dir / "manager.json").write_text(json.dumps(manager))
        out = self.dir / "report.json"
        with contextlib.redirect_stdout(io.StringIO()):
            a.main(["--oracles", str(self.dir / "oracles.json"),
                    "--manager", str(self.dir / "manager.json"),
                    "--project", str(self.project), "--out", str(out),
                    "--guard", str(self.dir), "--", sys.executable, str(self.stub)])
        report = json.loads(out.read_text())
        cases = {case["case"]: case for case in report["cases"]}
        self.assertEqual(1.0, cases["CQ-1"]["coverage"])
        self.assertEqual(2, cases["CQ-1"]["packs_delivered"])
        self.assertEqual(0.0, cases["CQ-2"]["coverage"])
        self.assertEqual(["io.acme.Missing"], cases["CQ-2"]["missing"])
        self.assertIsNone(cases["CQ-3"]["coverage"])
        self.assertEqual({"cases": 3, "with_pack": 1, "fully_covered": 1, "mean_coverage": 0.5},
                         {k: report["summary"][k] for k in ("cases", "with_pack", "fully_covered",
                                                             "mean_coverage")})


if __name__ == "__main__":
    unittest.main()
