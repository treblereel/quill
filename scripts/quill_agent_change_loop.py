#!/usr/bin/env python3
"""Bounded real-agent edit/compile/recovery probe, never a general shell agent."""

import argparse
import json
import shutil
import tempfile
from pathlib import Path

import quill_change_loop_e2e as loop
from quill_agent_benchmark import ResponsesClient, SourceTools, QuillTools, managed_guidance, run_agent
from quill_workflow_benchmark import WorkflowClient


SOURCE = "src/main/java/org/example/GreetingService.java"
SESSION = {"targets": [loop.TARGET], "change": loop.CHANGE}
EXTRA_TOOLS = [
    {"type": "function", "name": "replace_fixture_source",
     "description": "Replace one exact occurrence in GreetingService.java only.",
     "parameters": {"type": "object", "properties": {
         "old": {"type": "string"}, "new": {"type": "string"}},
         "required": ["old", "new"], "additionalProperties": False}, "strict": True},
    {"type": "function", "name": "execute_recommended_compile",
     "description": "Execute the current Quill directive's compile-only recommendation externally. "
                    "Returns compiler output; no tests, shell, or arbitrary command arguments.",
     "parameters": {"type": "object", "properties": {},
                    "additionalProperties": False}, "strict": True},
]


class ChangeTools(SourceTools):
    def __init__(self, project, quill, maven, inject_failure=True):
        super().__init__(project, 30000)
        self.quill, self.maven = quill, maven
        self.inject_failure = inject_failure
        self.executions = []
        self.edits = 0

    def snapshot(self):
        with WorkflowClient([str(self.quill)], self.project, 60) as oracle:
            snapshot, _ = oracle.call("change_session", SESSION)
        return snapshot

    def _call(self, name, arguments):
        if name == "replace_fixture_source":
            print("Agent: replace_fixture_source", flush=True)
            path = self.project / SOURCE
            content = path.read_text()
            old, new = arguments["old"], arguments["new"]
            if not old or content.count(old) != 1:
                raise ValueError("Replacement requires exactly one nonempty matching occurrence")
            path.write_text(content.replace(old, new))
            self.edits += 1
            return "Source updated; obtain fresh change_session evidence."
        if name == "execute_recommended_compile":
            print("Agent: execute_recommended_compile", flush=True)
            if len(self.executions) >= 4:
                raise ValueError("Compile budget exhausted (4 executions)")
            snapshot = self.snapshot()
            primary = snapshot.get("directive", {}).get("primary_action", {})
            command = next((primary[key] for key in
                            ("preparation_command", "retry_command", "command")
                            if isinstance(primary.get(key), dict)), {})
            failures = loop.assert_contract(self.project, command, self.maven) if isinstance(
                command.get("argv"), list) and command["argv"] else [
                "No current primary compile recommendation"]
            cwd = Path(primary.get("working_directory", "")).resolve()
            if cwd != self.project:
                failures.append("Compile directory is not the isolated fixture")
            if command.get("argv") != [self.maven, "test-compile"]:
                failures.append("Only system Maven test-compile is permitted")
            if failures:
                raise ValueError("; ".join(failures))
            if self.inject_failure:
                path = self.project / SOURCE
                content = path.read_text()
                if 'return "Hello, " + name + "!";' not in content:
                    raise ValueError("Apply the requested enthusiastic greeting before compiling")
                loop.break_source(self.project)
                self.inject_failure = False
            build = loop.run_unchecked(command["argv"], cwd)
            after = self.snapshot()
            capture = {"argv": command["argv"], "returncode": build.returncode,
                       "phase": after.get("phase"),
                       "verified": after.get("verification_receipt", {}).get("verified", False)}
            self.executions.append(capture)
            return json.dumps({**capture, "stdout": build.stdout[-20000:],
                               "stderr": build.stderr[-10000:]})
        return super()._call(name, arguments)


def evaluate(project, source, result, final):
    trace = result["tool_trace"]
    return {
        "overview_first": bool(trace) and trace[0]["tool"] == "quill_get_overview",
        "fresh_final_session": bool(trace) and trace[-1]["tool"] == "quill_change_session"
            and trace[-1].get("status") == "ok",
        "agent_edited_and_repaired": source.edits >= 2,
        "failed_then_successful_compile": len(source.executions) >= 2
            and source.executions[0]["returncode"] != 0
            and source.executions[0]["phase"] == "blocked"
            and source.executions[-1]["returncode"] == 0,
        "complete_with_verified_receipt": final.get("phase") == "complete"
            and final.get("verification_receipt", {}).get("verified") is True,
        "strict_agent_answer": result["observed"] == {"phase": "complete", "verified": True}
            and type(result["observed"].get("verified")) is bool,
        "enthusiastic_greeting": 'return "Hello, " + name + "!";' in
            (project / SOURCE).read_text(),
        "public_signature_preserved": "public String greet(String name)" in
            (project / SOURCE).read_text(),
        "production_and_test_sources_compiled": all((project / path).is_file() for path in (
            "target/classes/org/example/GreetingService.class",
            "target/test-classes/org/example/GreetingServiceCompileProbe.class")),
        "no_test_reports": not any((project / path).exists() for path in (
            "target/surefire-reports", "target/failsafe-reports")),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--key-file", type=Path, required=True)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--output", type=Path,
                        default=Path("target/benchmarks/quill-agent-change-loop.json"))
    args = parser.parse_args()
    try:
        client = ResponsesClient(args.key_file.read_text().strip(), "https://api.openai.com/v1", 60)
        quill, maven = args.quill.resolve(), shutil.which("mvn")
        if not quill.is_file() or not maven:
            raise ValueError("Native Quill and system Maven are required")
        with tempfile.TemporaryDirectory(prefix="quill-agent-loop-") as temporary:
            project = Path(temporary).resolve()
            loop.write_fixture(project)
            for argv in (["git", "init", "-q"],
                         ["git", "config", "user.name", "Quill agent probe"],
                         ["git", "config", "user.email", "probe@example.invalid"],
                         [maven, "-q", "test-compile"], ["git", "add", "."],
                         ["git", "commit", "-q", "-m", "Fixture"],
                         [str(quill), "init", "--project", str(project)]):
                loop.run(argv, project)
            shutil.rmtree(project / "target/test-classes")
            source = ChangeTools(project, quill, maven)
            initial = source.snapshot()
            task = {"id": "edit-compile-recover", "expected": {"phase": "complete", "verified": True},
                    "prompt": loop.CHANGE + " in org.example.GreetingService. "
                    "Change the returned greeting to append !. Follow installed Quill guidance, "
                    "compile production and standard test sources without executing tests. "
                    "The fixture deliberately injects a syntax error immediately before the first "
                    "compile. Diagnose and repair it, then recompile and verify completion. "
                    "Do not claim completion from a command exit code alone."}
            with QuillTools([str(quill)], project, 60, 30000) as tools:
                result = run_agent(client, task, "with_quill", args.model, "medium", source,
                                   tools, "all", managed_guidance(project / "AGENTS.md"),
                                   action_instructions="You are implementing a change in an isolated "
                                   "Java fixture. Use tools for all edits and builds. Never run tests. "
                                   "Report the final phase and boolean verified from fresh Quill evidence.",
                                   extra_tools=EXTRA_TOOLS)
            final = source.snapshot()
            checks = evaluate(project, source, result, final)
            report = {"schema_version": 1, "model": args.model,
                      "scope": "one bounded API-agent fixture; not a native client or comparative eval",
                      "initial_phase": initial.get("phase"), "checks": checks,
                      "passed": all(checks.values()), "executions": source.executions,
                      "agent": result, "verification_receipt": final.get("verification_receipt")}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps({"passed": report["passed"], "checks": checks,
                              "report": str(args.output)}, indent=2), flush=True)
            return 0 if report["passed"] else 1
    finally:
        args.key_file.unlink(missing_ok=True)
        print("Temporary key file removed", flush=True)


if __name__ == "__main__":
    raise SystemExit(main())
