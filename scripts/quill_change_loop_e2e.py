#!/usr/bin/env python3
"""Exercise Quill's complete change loop with an externally executed quick compile."""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from typing import Any

from quill_workflow_benchmark import WorkflowClient


TARGET = "org.example.GreetingService"
CHANGE = "Add an enthusiastic greeting without changing the public method signature"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def run(argv: list[str], cwd: Path, timeout: int = 180) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(
        argv, cwd=cwd, text=True, capture_output=True, timeout=timeout, check=False)
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"Command failed ({completed.returncode}): {argv!r}\n{detail}")
    return completed


def write_fixture(root: Path) -> None:
    main = root / "src/main/java/org/example/GreetingService.java"
    test = root / "src/test/java/org/example/GreetingServiceCompileProbe.java"
    main.parent.mkdir(parents=True)
    test.parent.mkdir(parents=True)
    (root / "pom.xml").write_text("""\
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.example</groupId>
  <artifactId>quill-change-loop-fixture</artifactId>
  <version>1.0-SNAPSHOT</version>
  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>
</project>
""", encoding="utf-8")
    main.write_text("""\
package org.example;

public class GreetingService {
    public String greet(String name) {
        return "Hello, " + name;
    }
}
""", encoding="utf-8")
    test.write_text("""\
package org.example;

public class GreetingServiceCompileProbe {
    private final GreetingService service = new GreetingService();

    public String compileProbe() {
        return service.greet("Quill");
    }
}
""", encoding="utf-8")


def change_source(root: Path) -> None:
    source = root / "src/main/java/org/example/GreetingService.java"
    source.write_text(source.read_text(encoding="utf-8").replace(
        'return "Hello, " + name;', 'return "Hello, " + name + "!";'), encoding="utf-8")
    # Force proof that quick_compile compiles standard test sources as well as production code.
    shutil.rmtree(root / "target/test-classes", ignore_errors=True)


def quick_compile(session: dict[str, Any]) -> dict[str, Any]:
    commands = session.get("verification_plan", {}).get("commands", [])
    matches = [command for command in commands if command.get("scope") == "quick_compile"]
    if len(matches) != 1:
        raise RuntimeError("Expected exactly one quick_compile command, got "
                           f"{matches!r} in {json.dumps(session, ensure_ascii=False)}")
    command = matches[0]
    argv = command.get("argv")
    if not isinstance(argv, list) or not all(isinstance(value, str) for value in argv):
        raise RuntimeError(f"Invalid quick_compile argv: {argv!r}")
    return command


def assert_contract(root: Path, command: dict[str, Any], maven: str) -> list[str]:
    failures: list[str] = []
    argv = command["argv"]
    runner = Path(argv[0]).name
    if (root / "mvnw").exists():
        failures.append("fixture unexpectedly contains a Maven wrapper")
    if runner not in {Path(maven).name, "mvn"}:
        failures.append(f"expected system Maven fallback, got {argv[0]!r}")
    if argv[-1] != "test-compile":
        failures.append(f"expected test-compile as the final Maven phase, got {argv!r}")
    if any(value in {"test", "verify", "integration-test"} for value in argv):
        failures.append(f"argv includes a test-executing Maven phase: {argv!r}")
    if command.get("executes_tests") is not False:
        failures.append("quick_compile must declare executes_tests=false")
    if command.get("compiles_test_sources") is not True:
        failures.append("quick_compile must declare compiles_test_sources=true")
    return failures


def blocker_codes(session: dict[str, Any]) -> list[str]:
    return [item.get("code") for item in
            session.get("verification", {}).get("blockers", [])]


def main() -> int:
    args = arguments()
    quill = args.quill.resolve()
    if not quill.is_file():
        raise RuntimeError(f"Quill executable not found: {quill}")
    maven = shutil.which(args.maven)
    if maven is None:
        raise RuntimeError(f"System Maven executable not found: {args.maven}")

    with tempfile.TemporaryDirectory(prefix="quill-change-loop-") as temporary:
        project = Path(temporary)
        write_fixture(project)
        run(["git", "init", "-q"], project)
        run(["git", "config", "user.name", "Quill E2E"], project)
        run(["git", "config", "user.email", "quill-e2e@example.invalid"], project)
        run([maven, "-q", "test-compile"], project)
        run(["git", "add", "."], project)
        run(["git", "commit", "-q", "-m", "Initial fixture"], project)
        init = run([str(quill), "init", "--project", str(project)], project)

        session_args = {
            "targets": [TARGET], "change": CHANGE, "limit": 10,
            "detail": "summary", "view": "auto",
        }
        with WorkflowClient([str(quill)], project, args.request_timeout) as client:
            planned, planned_trace = client.call("change_session", session_args)
            change_source(project)
            required, required_trace = client.call("change_session", session_args)
            command = quick_compile(required)
            failures = assert_contract(project, command, maven)
            action = next((item for item in required.get("next_actions", [])
                           if item.get("command_scope") == "quick_compile"), {})
            if action.get("executed_by_quill") is not False:
                failures.append("next action must declare executed_by_quill=false")
            if planned.get("phase") != "planned" or planned.get("view") != "plan":
                failures.append(f"unexpected initial phase/view: "
                                f"{planned.get('phase')}/{planned.get('view')}")
            if required.get("phase") != "verification_required" \
                    or required.get("view") != "verification":
                failures.append(f"unexpected dirty phase/view: "
                                f"{required.get('phase')}/{required.get('view')}")
            if "BUILD_EVIDENCE_REQUIRED" not in blocker_codes(required):
                failures.append("dirty session did not require build evidence")
            if failures:
                raise RuntimeError("Pre-execution contract failed: " + "; ".join(failures))

            build = run(command["argv"], Path(
                required["verification_plan"]["working_directory"]))
            completed, completed_trace = client.call("change_session", session_args)

        post_failures: list[str] = []
        for relative in (
                "target/classes/org/example/GreetingService.class",
                "target/test-classes/org/example/GreetingServiceCompileProbe.class"):
            if not (project / relative).is_file():
                post_failures.append(f"missing compiled output {relative}")
        for relative in ("target/surefire-reports", "target/failsafe-reports"):
            if (project / relative).exists():
                post_failures.append(f"test execution evidence unexpectedly exists: {relative}")
        if "BUILD_EVIDENCE_REQUIRED" in blocker_codes(completed):
            post_failures.append("build evidence blocker remained after successful quick compile")
        build_status = completed.get("verification", {}).get("diagnostics", {}).get("build_status")
        if build_status != "success":
            post_failures.append(f"expected successful build status, got {build_status!r}")
        if completed.get("phase") != "complete":
            post_failures.append(f"expected complete phase, got {completed.get('phase')!r}")
        if post_failures:
            raise RuntimeError("Post-execution contract failed: " + "; ".join(post_failures))

        result = {
            "schema_version": 1,
            "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
            "fixture": {"build_system": "maven", "wrapper_present": False,
                        "target": TARGET},
            "init": {"stdout": init.stdout.strip(), "stderr": init.stderr.strip()},
            "phases": [planned.get("phase"), required.get("phase"), completed.get("phase")],
            "views": [planned.get("view"), required.get("view"), completed.get("view")],
            "quick_compile": command,
            "external_execution": {
                "returncode": build.returncode,
                "production_compiled": True,
                "test_sources_compiled": True,
                "tests_executed": False,
            },
            "build_evidence": {
                "before_blockers": blocker_codes(required),
                "after_blockers": blocker_codes(completed),
                "after_status": build_status,
            },
            "mcp_trace": [planned_trace, required_trace, completed_trace],
            "contract_complete": True,
        }

    output = args.output or Path("target/benchmarks") / (
        f"quill-change-loop-e2e-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({
        "phases": result["phases"],
        "argv": command["argv"],
        "build_status": build_status,
        "contract_complete": True,
    }, indent=2))
    print(f"Report: {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.TimeoutExpired) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
