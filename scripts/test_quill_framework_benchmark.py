import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from quill_agent_benchmark import ResponsesClient, SourceTools, run_agent, verify_project
from quill_framework_benchmark import (FRAMEWORKS, ROOT, fixture_digest, prepare,
                                       require_closed_gate, runtime_evidence, TEST_CLASSES,
                                       validate_suite, verify, verify_quill)
from quill_task_benchmark import score_run


def suite_for(framework):
    return json.loads((ROOT / f"benchmarks/{framework}-orders-effectiveness.json").read_text())


class FrameworkBenchmarkTest(unittest.TestCase):
    def test_v2_contracts_match_source_and_cannot_score_v1_captures(self):
        for framework in FRAMEWORKS:
            suite = suite_for(framework)
            project = ROOT / "benchmarks/framework-apps" / framework
            self.assertEqual(2, suite["oracle_version"])
            for task in suite["tasks"]:
                self.assertTrue(task["id"].startswith(framework + "-v2-"))
                if any(isinstance(value, list) for value in task["expected"].values()):
                    self.assertIn("lexicographic order", task["prompt"])
                # Check gold method declarations against source, including both endpoints of edges.
                signatures = re.findall(r'org\.example\.orders\.\w+\.\w+\([^)]*\)',
                                        json.dumps(task["expected"]))
                for signature in signatures:
                    owner, method, params = re.fullmatch(
                        r'org\.example\.orders\.(\w+)\.(\w+)\(([^)]*)\)', signature).groups()
                    source = next(project.glob(f"*/src/*/java/org/example/orders/{owner}.java"))
                    declaration = rf'\b{method}\(\s*' + (
                        rf'{params}\s+\w+\s*' if params else '') + r'\)\s*\{'
                    unannotated = re.sub(r'@[\w.]+(?:\([^)]*\))?\s*', '', source.read_text())
                    self.assertRegex(unannotated, declaration, signature)
                    self.assertIn("simple parameter type names", task["prompt"])
                if task["id"].endswith("event-flow"):
                    self.assertIn("concrete project source method implementing OrderEvents.publish",
                                  task["prompt"])
                    self.assertIn("project wrapper declaration", task["prompt"])
                    self.assertEqual("org.example.orders.FrameworkOrderEvents.publish(OrderPlaced)",
                                     task["expected"]["publisher"])
                if task["id"].endswith("dynamic-unknown"):
                    self.assertIn("not quoted strings", task["prompt"])
            captures = [{"id": task["id"], "observed": task["expected"]} for task in suite["tasks"]]
            self.assertEqual(12, score_run({"tasks": suite["tasks"]}, {"tasks": captures})
                             ["totals"]["complete_tasks"])
            captures[0]["id"] = captures[0]["id"].replace("-v2-", "-")
            with self.assertRaisesRegex(ValueError, "unknown task ids"):
                score_run({"tasks": suite["tasks"]}, {"tasks": captures})

    def test_24_paired_tasks_have_reviewed_source_and_negative_controls(self):
        suites = [suite_for(framework) for framework in FRAMEWORKS]
        self.assertEqual(24, sum(len(suite["tasks"]) for suite in suites))
        self.assertEqual([task["category"] for task in suites[0]["tasks"]],
                         [task["category"] for task in suites[1]["tasks"]])
        for framework, suite in zip(FRAMEWORKS, suites):
            project = ROOT / "benchmarks/framework-apps" / framework
            validate_suite(suite, project)
            extension = (project / ".mvn/extensions.xml").read_text()
            self.assertIn("<artifactId>quill-maven-extension</artifactId>", extension)
            self.assertIn("<version>0.1.4</version>", extension)
            self.assertNotRegex(suite["project_revision"], r"^[0-9a-f]{40}$")
            # Expose the normal tool catalog, including for the cheap source-control task.
            self.assertTrue(all("quill_tools" not in task for task in suite["tasks"]))
            impact = next(task for task in suite["tasks"] if task["id"].endswith("impacted-tests"))
            self.assertEqual(4, len(impact["expected"]["tests"]))
            self.assertFalse(any("Preview" in name or "Health" in name
                                 for name in impact["expected"]["tests"]))
            self.assertFalse(list(project.rglob("*effectiveness.json")))

    def test_preparation_pins_clean_projects_and_keeps_gold_outside(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "prepared"
            prepare(output)
            for framework in FRAMEWORKS:
                project = output / framework
                suite = json.loads((output / f"{framework}-orders-effectiveness.json").read_text())
                self.assertRegex(verify_project(suite, project, False), r"^[0-9a-f]{40}$")
                self.assertEqual(suite_for(framework)["fixture_sha256"], fixture_digest(project))
                # Same source interface available to both benchmark modes cannot escape to gold.
                with self.assertRaises(ValueError):
                    SourceTools(project, 1000).call("read_file", {
                        "path": f"../{framework}-orders-effectiveness.json",
                        "start_line": 1, "end_line": 100})
                self.assertFalse((project / "AGENTS.md").exists())
                (project / "api/target").mkdir(parents=True)
                (project / "api/target/probe").write_text("derived")
                self.assertEqual(suite["fixture_sha256"], fixture_digest(project))
                self.assertEqual(suite["project_revision"], verify_project(suite, project, False))
                (project / "service/src/main/java/org/example/orders/OrderService.java").write_text("changed")
                with self.assertRaisesRegex(ValueError, "Fixture inputs changed"):
                    validate_suite(suite, project)
                with self.assertRaisesRegex(ValueError, "dirty"):
                    verify_project(suite, project, False)
            with self.assertRaises(FileExistsError):
                prepare(output)

    def test_rejects_output_inside_sources(self):
        with self.assertRaisesRegex(ValueError, "outside"):
            prepare(ROOT / "benchmarks/framework-apps/quarkus/recursive-copy")

    def test_source_fingerprint_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "link").symlink_to(ROOT / "pom.xml")
            with self.assertRaisesRegex(ValueError, "symlinks"):
                fixture_digest(root)

    def test_gold_rejects_missing_and_external_evidence(self):
        project = ROOT / "benchmarks/framework-apps/quarkus"
        for path in ("missing.java", "../spring/pom.xml"):
            suite = suite_for("quarkus")
            suite["tasks"][0]["oracle"]["source_files"] = [path]
            with self.assertRaisesRegex(ValueError, "oracle evidence"):
                validate_suite(suite, project)

    def test_ordinary_runner_and_scorer_work_without_network(self):
        for framework in FRAMEWORKS:
            project = ROOT / "benchmarks/framework-apps" / framework
            task = suite_for(framework)["tasks"][0]
            calls = []

            def transport(payload):
                calls.append(payload)
                if len(calls) == 1:
                    return {"id": "source", "output": [{"type": "function_call",
                            "name": "read_file", "call_id": "read",
                            "arguments": json.dumps({"path": "service/src/main/java/org/example/orders/OrderService.java",
                                                      "start_line": 1, "end_line": 100})}]}
                # Derive this observation from the real source-tool response, never the gold.
                source = payload["input"][0]["output"]
                prefix = re.search(r'place\(long id\).*?return "([^"]+)"', source).group(1)
                return {"id": "answer", "output": [{"type": "message", "content": [
                    {"type": "output_text", "text": json.dumps({"observed": {"prefix": prefix}})}]}]}

            client = ResponsesClient("unused", "https://example.invalid", 1, transport)
            capture = run_agent(client, task, "without_quill", "fake-local-model", "medium",
                                SourceTools(project, 10000), None)
            score = score_run({"tasks": [task]}, {"tasks": [capture]})
            self.assertEqual(1, score["totals"]["complete_tasks"])
            self.assertEqual(2, len(calls))

    def test_runtime_gate_requires_all_tests_and_independent_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            with self.assertRaisesRegex(ValueError, "six real JUnit"):
                runtime_evidence(project, suite_for("quarkus"))
            report = project / "api/target/surefire-reports/TEST-probe.xml"
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite failures="1"><testcase classname="probe"/></testsuite>')
            with self.assertRaisesRegex(ValueError, "Unsuccessful"):
                runtime_evidence(project, suite_for("quarkus"))

    def test_runtime_gate_rejects_a_wrong_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            report = project / "api/target/surefire-reports/TEST-probe.xml"
            report.parent.mkdir(parents=True)
            report.write_text('<testsuite>' + ''.join(
                f'<testcase classname="{name}"/>' for name in TEST_CLASSES) + '</testsuite>')
            suite = suite_for("spring")
            observations = {key: value for task in suite["tasks"]
                            for key, value in task["oracle"]["runtime_observations"].items()}
            evidence = project / "api/target/runtime-observations.properties"
            evidence.write_text(''.join(f'{key}={value}\n' for key, value in observations.items()))
            self.assertEqual(6, runtime_evidence(project, suite)["junit_tests"])
            observations["http_status"] = "500"
            evidence.write_text(''.join(f'{key}={value}\n' for key, value in observations.items()))
            with self.assertRaisesRegex(ValueError, "Runtime oracle disagrees"):
                runtime_evidence(project, suite)

    def test_quill_gate_requires_complete_phase_and_verified_receipt(self):
        session = {"phase": "complete", "phase_gate": {"status": "satisfied"},
                   "verification_receipt": {"verified": True},
                   "review_checklist": {"required_count": 0}}
        require_closed_gate(session)
        for field, value in (("phase", "planned"), ("phase_gate", {"status": "ready"}),
                             ("verification_receipt", {"verified": "true"}),
                             ("verification_receipt", {"verified": False}),
                             ("review_checklist", {"required_count": 1})):
            with self.subTest(field=field, value=value):
                with self.assertRaisesRegex(ValueError, "gate is not closed"):
                    require_closed_gate({**session, field: value})

    def test_quill_preflight_waits_for_readiness_and_saves_blocked_guidance(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "gate.json"
            blocked = {"phase": "verification_required", "phase_gate": {"status": "action_required"},
                       "verification_receipt": {"verified": False},
                       "directive": {"primary_action": {"action": "run_external_command"}}}
            with patch("quill_framework_benchmark.subprocess.run") as command, \
                    patch("quill_framework_benchmark.QuillTools") as tool, \
                    patch("quill_framework_benchmark.time.sleep") as sleep:
                calls = tool.return_value.__enter__.return_value.call
                calls.side_effect = [json.dumps({"structuredContent": {"status": "refresh_pending"}}),
                                     json.dumps({"structuredContent": {"project": {}}}),
                                     json.dumps({"structuredContent": blocked})]
                with self.assertRaisesRegex(ValueError, "gate is not closed"):
                    verify_quill(ROOT / "benchmarks/framework-apps/quarkus", "/local/quill", report)
                self.assertEqual(blocked, json.loads(report.read_text()))
                self.assertEqual("quill_get_overview", calls.call_args_list[0].args[0])
                self.assertEqual("quill_change_session", calls.call_args_list[-1].args[0])
                self.assertEqual(26, len(calls.call_args_list[-1].args[1]["targets"]))
                command.assert_called_once()
                sleep.assert_called_once_with(1)

    def test_failed_build_invalidates_previous_oracle_and_quill_receipts(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            receipts = [workspace / name for name in (
                "oracle-verification.json", "quill-verification.json",
                "quarkus-quill-gate.json", "spring-quill-gate.json")]
            for receipt in receipts:
                receipt.write_text('{"stale":true}')
            (workspace / "quarkus-orders-effectiveness.json").write_text('{"project_revision":"old"}')
            with patch("quill_framework_benchmark.shutil.which", return_value="/local/tool"), \
                    patch("quill_framework_benchmark.validate_suite"), \
                    patch("quill_framework_benchmark.verify_project"), \
                    patch("quill_framework_benchmark.subprocess.run",
                          side_effect=subprocess.CalledProcessError(1, ["mvn"])):
                with self.assertRaises(subprocess.CalledProcessError):
                    verify(workspace, "mvn", "quill")
            self.assertTrue(all(not receipt.exists() for receipt in receipts))


if __name__ == "__main__":
    unittest.main()
