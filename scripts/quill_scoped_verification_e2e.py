#!/usr/bin/env python3
"""Check native module-scoped verification against a real three-module Maven reactor."""

import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
import time

from quill_change_loop_e2e import run
from quill_workflow_benchmark import WorkflowClient


def write_fixture(root):
    (root / "pom.xml").write_text('''<project><modelVersion>4.0.0</modelVersion>
<groupId>example</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>
<properties><maven.compiler.release>17</maven.compiler.release></properties>
<modules><module>base</module><module>app</module><module>unrelated</module></modules></project>''')
    for module in ("base", "app", "unrelated"):
        directory = root / module
        directory.mkdir()
        dependency = ('''<dependencies><dependency><groupId>example</groupId><artifactId>base</artifactId>
<version>1</version><scope>provided</scope></dependency></dependencies>''' if module == "app" else "")
        (directory / "pom.xml").write_text(f'''<project><modelVersion>4.0.0</modelVersion>
<parent><groupId>example</groupId><artifactId>root</artifactId><version>1</version></parent>
<artifactId>{module}</artifactId>{dependency}</project>''')
        name = module.title()
        for source_set in ("main", "test"):
            source = directory / f"src/{source_set}/java/example/{name}{'Test' if source_set == 'test' else ''}.java"
            source.parent.mkdir(parents=True)
            source.write_text(f"package example; public class {source.stem} {{}}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--output", type=Path, default=Path("target/benchmarks/quill-scoped-verification.json"))
    args = parser.parse_args()
    quill, maven = args.quill.resolve(), shutil.which("mvn")
    if not quill.is_file() or not maven:
        raise RuntimeError("Native Quill and system Maven required")
    report = {}
    with tempfile.TemporaryDirectory(prefix="quill-scoped-verification-") as temporary:
        project = Path(temporary).resolve()
        write_fixture(project)
        for argv in (["git", "init", "-q"], ["git", "config", "user.name", "Quill scope probe"],
                     ["git", "config", "user.email", "scope@example.invalid"],
                     [maven, "-q", "test-compile"], ["git", "add", "."],
                     ["git", "commit", "-q", "-m", "Fixture"],
                     [str(quill), "init", "--project", str(project)]):
            run(argv, project)
        # Populate captured test classpaths for all modules after installing integration.
        # Missing test-impact evidence is a separate gate and must not be relaxed here.
        run([maven, "-q", "test-compile"], project)
        old = time.time() - 3600
        os.utime(project / "unrelated/target/classes/example/Unrelated.class", (old, old))
        # Capture a real scoped successful build event without rebuilding the stale sibling.
        run([maven, "-q", "-pl", "app", "-am", "test-compile"], project)
        with WorkflowClient([str(quill)], project, 60) as client:
            repository, _ = client.call("get_build_status", {})
            session_args = {"targets": ["example.App"], "change": "Inspect the application module",
                            "view": "verification", "detail": "summary"}
            session, _ = client.call("change_session", session_args)
            report.update(repository=repository, session=session)
            assert repository["status"] == "build_required", repository
            assert session["phase"] == "complete", session
            assert session["verification_receipt"]["verified"] is True, session
            scope = session["verification_plan"]["verification_scope"]
            assert scope["kind"] == "modules_with_prerequisites", scope
            assert set(scope["modules"]) == {".", "app", "base"}, scope
            assert "unrelated" in session["verification"]["build"]["repository_stale_modules"]
            warning = next(item for item in session["project_warnings"] if item.get("build_reason") == "classes_stale")
            assert warning["blocking_for_change"] is False, warning
            os.utime(project / "base/target/classes/example/Base.class", (old, old))
            blocked, _ = client.call("verify_change", {"targets": ["example.App"]})
            report["stale_prerequisite"] = blocked
            assert blocked["verified"] is False and blocked["verdict"] == "needs_build", blocked
            assert "base" in blocked["build"]["compiled_outputs"]["stale_modules"], blocked
        report["test_reports_present"] = any(project.rglob("surefire-reports")) or any(project.rglob("failsafe-reports"))
        report["test_sources_compiled"] = all(
            (project / f"{module}/target/test-classes/example/{module.title()}Test.class").is_file()
            for module in ("app", "base", "unrelated"))
        assert report["test_sources_compiled"] is True
        assert report["test_reports_present"] is False
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print("Native scoped verification passed: unrelated stale module advisory; provided prerequisite blocks; no tests executed.")


if __name__ == "__main__":
    main()
