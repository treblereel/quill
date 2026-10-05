#!/usr/bin/env python3
"""Run a small real-agent adoption probe against freshly installed Quill guidance."""

import argparse
import json
import shutil
import tempfile
from pathlib import Path

import quill_change_loop_e2e as loop
from quill_agent_benchmark import ResponsesClient, SourceTools, QuillTools, managed_guidance, run_agent
from quill_workflow_benchmark import WorkflowClient


def evaluate_capture(result, expected):
    result["expected"] = expected
    result["correct"] = result["observed"] == expected and all(
        type(result["observed"].get(key)) is type(value) for key, value in expected.items())
    trace = result["tool_trace"]
    result["overview_first"] = bool(trace) and (
        trace[0]["tool"].removeprefix("quill_") == "get_overview")
    result["change_session_used"] = any(
        item["tool"].removeprefix("quill_") == "change_session" for item in trace)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-file", type=Path, required=True,
                        help="Temporary API-key file; removed after the probe, including on failure")
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--output", type=Path,
                        default=Path("target/benchmarks/quill-guidance-adoption.json"))
    args = parser.parse_args()
    key = args.key_file.read_text().strip()
    # The key stays in this process; agent source tools are scoped to the temporary fixture.
    client = ResponsesClient(key, "https://api.openai.com/v1", 60)
    quill = args.quill.resolve()
    captures = []
    try:
        with tempfile.TemporaryDirectory(prefix="quill-guidance-adoption-") as temporary:
            project = Path(temporary)
            loop.write_fixture(project)
            for argv in (["git", "init", "-q"],
                         ["git", "config", "user.name", "Quill adoption probe"],
                         ["git", "config", "user.email", "adoption@example.invalid"],
                         [shutil.which("mvn"), "-q", "test-compile"],
                         ["git", "add", "."], ["git", "commit", "-q", "-m", "Fixture"],
                         [str(quill), "init", "--project", str(project)]):
                loop.run(argv, project)
            guidance = managed_guidance(project / "AGENTS.md")
            for dirty in (False, True):
                if dirty:
                    loop.change_source(project)
                with WorkflowClient([str(quill)], project, 60) as oracle:
                    snapshot, _ = oracle.call("change_session", {
                        "targets": [loop.TARGET], "change": loop.CHANGE,
                    })
                expected = {
                    "phase": snapshot["phase"],
                    "primary_action": snapshot["directive"]["primary_action"]["action"],
                    "verified": snapshot.get("verification_receipt", {}).get("verified", False),
                }
                task = {
                    "id": "dirty-change" if dirty else "before-editing",
                    "prompt": "Assess the current change workflow for org.example.GreetingService: "
                              + loop.CHANGE + ". Return its phase, the immediate recommended "
                              "primary_action, and whether verification is complete (verified). "
                              "Do not edit files or execute commands. Use current tool evidence.",
                    "expected": expected,
                }
                print("Running " + task["id"], flush=True)
                with QuillTools([str(quill)], project, 60, 30000) as tools:
                    result = run_agent(client, task, "with_quill", args.model, "medium",
                                       SourceTools(project, 30000), tools, "all", guidance)
                evaluate_capture(result, expected)
                captures.append(result)
                print(json.dumps({"task": task["id"], "correct": result["correct"],
                                  "overview_first": result["overview_first"],
                                  "change_session_used": result["change_session_used"],
                                  "observed": result["observed"]}), flush=True)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps({
            "schema_version": 1, "model": args.model,
            "scope": "two read-only tasks with installed AGENTS.md; no comparative claim",
            "tasks": captures,
        }, indent=2) + "\n")
        print("Report: " + str(args.output))
    finally:
        args.key_file.unlink(missing_ok=True)
        print("Temporary key file removed", flush=True)


if __name__ == "__main__":
    main()
