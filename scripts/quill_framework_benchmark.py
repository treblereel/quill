#!/usr/bin/env python3
"""Prepare framework task suites and verify their independent oracles; no model API calls."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET

from quill_agent_benchmark import QuillTools, verify_project


ROOT = Path(__file__).resolve().parents[1]
FRAMEWORKS = ("quarkus", "spring")
DERIVED = {"target", ".git", ".quill"}
TEST_CLASSES = {
    "org.example.orders.OrderRepositoryTest",
    "org.example.orders.OrderServiceTest",
    "org.example.orders.ReorderServiceTest",
    "org.example.orders.PreviewServiceTest",
    "org.example.orders.OrderHttpTest",
    "org.example.orders.HealthServiceTest",
}


def fixture_digest(project: Path) -> str:
    """Bind the reviewed gold to every source/config/test input, excluding derived state."""
    digest = hashlib.sha256()
    for path in sorted(project.rglob("*")):
        relative = path.relative_to(project)
        if any(part in DERIVED for part in relative.parts):
            continue
        if path.is_symlink():
            raise ValueError(f"Fixture symlinks are not allowed: {relative}")
        if path.is_file():
            digest.update(relative.as_posix().encode() + b"\0")
            digest.update(path.read_bytes() + b"\0")
    return digest.hexdigest()


def validate_suite(suite: dict, project: Path) -> None:
    framework = suite["framework"]
    if framework not in FRAMEWORKS:
        raise ValueError(f"Unknown framework: {framework}")
    if fixture_digest(project) != suite["fixture_sha256"]:
        raise ValueError("Fixture inputs changed: review the gold before rebaselining its fingerprint")
    tasks = suite["tasks"]
    if len(tasks) != 12 or len({task["id"] for task in tasks}) != 12:
        raise ValueError("Each framework suite must have 12 unique tasks")
    for task in tasks:
        if not task["id"].startswith(framework + "-") or not task["expected"]:
            raise ValueError(f"Invalid framework task: {task['id']}")
        for source in task["oracle"]["source_files"]:
            path = (project / source).resolve()
            if not path.is_relative_to(project.resolve()) or not path.is_file():
                raise ValueError(f"Missing or external oracle evidence: {source}")
        for value in task["expected"].values():
            if isinstance(value, list) and value != sorted(set(value)):
                raise ValueError(f"Expected arrays must be sorted and unique: {task['id']}")


def run(argv: list[str], cwd: Path, **kwargs) -> str:
    return subprocess.check_output(argv, cwd=cwd, text=True, **kwargs).strip()


def prepare(output: Path) -> None:
    output = output.resolve()
    fixtures = ROOT / "benchmarks/framework-apps"
    if output.is_relative_to(fixtures.resolve()):
        raise ValueError("Preparation output must be outside the fixture sources")
    # Refuse reuse: never replace user edits or silently re-pin an existing capture project.
    output.mkdir(parents=True, exist_ok=False)
    for framework in FRAMEWORKS:
        source = ROOT / "benchmarks/framework-apps" / framework
        suite = json.loads((ROOT / f"benchmarks/{framework}-orders-effectiveness.json").read_text())
        validate_suite(suite, source)
        project = output / framework
        shutil.copytree(source, project, ignore=shutil.ignore_patterns(*DERIVED))
        run(["git", "init", "--quiet"], project)
        run(["git", "-c", "core.excludesFile=/dev/null", "add", "--all"], project)
        environment = dict(os.environ, GIT_AUTHOR_DATE="2000-01-01T00:00:00+00:00",
                           GIT_COMMITTER_DATE="2000-01-01T00:00:00+00:00")
        run(["git", "-c", "user.name=Framework Benchmark", "-c",
             "user.email=framework-benchmark@example.invalid", "-c", "commit.gpgsign=false",
             "-c", "core.hooksPath=/dev/null", "commit", "--quiet", "-m",
             f"Pin {framework} orders benchmark fixture"], project, env=environment)
        suite["project_revision"] = run(["git", "rev-parse", "HEAD"], project)
        validate_suite(suite, project)
        verify_project(suite, project, False)
        (output / f"{framework}-orders-effectiveness.json").write_text(
            json.dumps(suite, indent=2) + "\n", encoding="utf-8")
        print(f"Prepared {framework}: {project} at {suite['project_revision']}", flush=True)


def runtime_evidence(project: Path, suite: dict) -> dict:
    reports = sorted(project.glob("*/target/surefire-reports/TEST-*.xml"))
    observed_classes = set()
    test_count = 0
    for report in reports:
        result = ET.parse(report).getroot()
        if any(int(result.get(key, "0")) for key in ("failures", "errors", "skipped")):
            raise ValueError(f"Unsuccessful or skipped oracle tests: {report}")
        for case in result.findall("testcase"):
            observed_classes.add(case.attrib["classname"])
            test_count += 1
    if observed_classes != TEST_CLASSES or test_count != 6:
        raise ValueError(f"Expected six real JUnit oracle tests, got {observed_classes}, {test_count}")
    values = dict(line.split("=", 1) for line in
                  (project / "api/target/runtime-observations.properties").read_text().splitlines())
    checked = []
    for task in suite["tasks"]:
        for key, expected in task["oracle"]["runtime_observations"].items():
            if values.get(key) != expected:
                raise ValueError(f"Runtime oracle disagrees for {task['id']}: {key}")
        checked.append({"id": task["id"], "source_fingerprint_verified": True,
                        "runtime_keys": sorted(task["oracle"]["runtime_observations"])})
    return {"junit_tests": test_count, "runtime_observations": values, "tasks": checked}


def require_closed_gate(session: dict) -> None:
    if (session.get("phase") != "complete"
            or session.get("phase_gate", {}).get("status") != "satisfied"
            or session.get("verification_receipt", {}).get("verified") is not True
            or session.get("review_checklist", {}).get("required_count") != 0):
        raise ValueError("Quill gate is not closed; inspect the saved change_session response")


def verify_quill(project: Path, binary: str, report: Path) -> dict:
    subprocess.run([binary, "init", "--index-only", "--project", str(project)], check=True)
    targets = sorted(path.relative_to(project).as_posix()
                     for path in project.glob("*/src/*/java/**/*.java"))
    with QuillTools([binary], project, 60, 500000) as quill:
        def query(name: str, arguments: dict) -> dict:
            deadline = time.monotonic() + 60
            while True:
                response = json.loads(quill.call("quill_" + name, arguments))
                data = response.get("structuredContent", {})
                if data.get("status") != "refresh_pending":
                    if response.get("isError"):
                        raise ValueError(f"Quill {name} failed: {data}")
                    return data
                if time.monotonic() >= deadline:
                    raise TimeoutError("Quill index preparation did not finish")
                time.sleep(1)

        query("get_overview", {})
        session = query("change_session", {
            "targets": targets,
            "change": "Verify the standalone orders benchmark application and independent runtime oracle tests with captured Maven build evidence; no model calls.",
            "limit": 100,
        })
    # Preserve blocked guidance too, but never turn it into a successful receipt.
    report.write_text(json.dumps(session, indent=2) + "\n", encoding="utf-8")
    require_closed_gate(session)
    return session


def verify(workspace: Path, maven: str, quill: str | None = None) -> None:
    workspace = workspace.resolve()
    command = shutil.which(maven)
    if command is None:
        raise ValueError(f"Maven executable not found: {maven}")
    # A failed retry must not leave a previous successful receipt looking current.
    receipt = workspace / "oracle-verification.json"
    receipt.unlink(missing_ok=True)
    gate_receipt = workspace / "quill-verification.json"
    gate_receipt.unlink(missing_ok=True)
    for framework in FRAMEWORKS:
        (workspace / f"{framework}-quill-gate.json").unlink(missing_ok=True)
    binary = shutil.which(quill) if quill else None
    if quill and binary is None:
        raise ValueError(f"Quill executable not found: {quill}")
    if binary:
        binary = str(Path(binary).resolve())
    results = {}
    gates = {}
    for framework in FRAMEWORKS:
        suite = json.loads((workspace / f"{framework}-orders-effectiveness.json").read_text())
        project = workspace / framework
        validate_suite(suite, project)
        verify_project(suite, project, False)
        # Clean removes old success reports and observations before the runtime oracle runs.
        subprocess.run([command, "--batch-mode", "--no-transfer-progress", "clean", "verify",
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                        "-DincludeScope=test", "-Dmdep.outputFile=target/quill-test-classpath.txt"],
                       cwd=project, check=True)
        validate_suite(suite, project)
        verify_project(suite, project, False)
        results[framework] = {"project_revision": suite["project_revision"],
                              "fixture_sha256": suite["fixture_sha256"],
                              **runtime_evidence(project, suite)}
        if binary:
            session = verify_quill(project, binary, workspace / f"{framework}-quill-gate.json")
            validate_suite(suite, project)
            verify_project(suite, project, False)
            gates[framework] = {"project_revision": suite["project_revision"],
                                "fixture_sha256": suite["fixture_sha256"], "session": session}
    receipt.write_text(json.dumps({"schema_version": 1, "model_calls": 0,
                                   "frameworks": results}, indent=2) + "\n", encoding="utf-8")
    print(receipt)
    if binary:
        gate_receipt.write_text(json.dumps({"schema_version": 1, "model_calls": 0,
                                            "frameworks": gates}, indent=2) + "\n",
                                encoding="utf-8")
        print(gate_receipt)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="action", required=True)
    create = subparsers.add_parser("prepare", help="Copy fixtures into new clean Git projects")
    create.add_argument("--output", type=Path, default=ROOT / "target/benchmarks/frameworks")
    check = subparsers.add_parser("verify", help="Run local Maven/JUnit oracles; never call a model")
    check.add_argument("--workspace", type=Path, default=ROOT / "target/benchmarks/frameworks")
    check.add_argument("--maven", default="mvn")
    check.add_argument("--quill", help="Local Quill binary; also require closed change_session gates")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.output)
    else:
        verify(args.workspace, args.maven, args.quill)


if __name__ == "__main__":
    main()
