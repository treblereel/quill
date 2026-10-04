#!/usr/bin/env python3
"""Probe installed native coding clients against Quill-created project configuration."""

import argparse
import hashlib
import json
import os
import shutil
import signal
import subprocess
import tempfile
from pathlib import Path

import quill_change_loop_e2e as loop
from quill_workflow_benchmark import WorkflowClient


def events(stdout):
    parsed = []
    for line in stdout.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            parsed.append(value)
    return parsed


def tool_names(client, stream):
    names = []
    for event in stream:
        if client == "codex":
            item = event.get("item", {})
            if event.get("type") == "item.completed" and item.get("type") == "mcp_tool_call":
                names.append(item.get("server", "") + ":" + item.get("tool", ""))
        else:
            message = event.get("message", {})
            if not isinstance(message, dict):
                continue
            for item in message.get("content", []):
                if isinstance(item, dict) and item.get("type") == "tool_use":
                    names.append(item.get("name", ""))
    return names


def workflow_access(capture):
    return any(name in {"quill:change_session", "mcp__quill__change_session"}
               for name in capture["tools"])


def user_config_digest():
    """Detect client-side config writes without recording configuration or secrets."""
    path = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")) / "config.toml"
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except FileNotFoundError:
        return None


def invoke(client, project, prompt, timeout, trusted=False, edit=False, config_overrides=(),
           profile=None, source_reads=False):
    # Writable headless threads can persist implicit trust. Supply it in memory
    # instead, including when callers initially observed successful discovery.
    trusted = trusted or (client == "codex" and edit)
    config_before = user_config_digest() if client == "codex" else None
    if client == "codex":
        argv = ["codex", "exec", "--ephemeral", "--json",
                "--sandbox", "workspace-write" if edit else "read-only", "-C", str(project)]
        if trusted:
            # Codex splits override keys on dots literally; quoted dotted path keys
            # retain their quotes. Put the path inside a TOML inline-table value.
            argv += ["-c", 'projects={' + json.dumps(str(project))
                     + '={trust_level="trusted"}}']
        for override in config_overrides:
            argv += ["-c", override]
        if profile:
            argv += ["--profile", profile]
        argv.append(prompt)
    else:
        tools = "Read,Edit,Bash,ToolSearch" + (",Grep,Glob" if source_reads else "")
        argv = ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
                "--no-session-persistence", "--permission-mode", "dontAsk",
                "--tools", tools,
                "--allowedTools", "Read", "mcp__quill__*"]
        if source_reads:
            argv += ["Grep", "Glob", "Bash(git log *)", "Bash(git show *)",
                     "Bash(git status *)", "Bash(git diff *)"]
        if edit:
            argv += ["Edit", "Bash(mvn test-compile)",
                     "Bash(/opt/homebrew/bin/mvn test-compile)"]
    print(f"Running {client}: trusted={trusted}, edit={edit}", flush=True)
    with subprocess.Popen(argv, cwd=project, text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, stdin=subprocess.DEVNULL,
                          start_new_session=True) as process:
        timed_out = False
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            # Target only the dedicated process group created for this invocation.
            os.killpg(process.pid, signal.SIGTERM)
            try:
                stdout, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                stdout, stderr = process.communicate()
        capture = {"returncode": None if timed_out else process.returncode,
                   "timeout": timed_out, "stdout": stdout, "stderr": stderr}
    capture["tools"] = tool_names(client, events(capture["stdout"]))
    if client == "codex":
        capture["user_config_unchanged"] = config_before == user_config_digest()
    print(json.dumps({"client": client, "returncode": capture["returncode"],
                      "tools": capture["tools"]}), flush=True)
    return capture


def snapshot(quill, project):
    with WorkflowClient([str(quill)], project, 60) as oracle:
        value, _ = oracle.call("change_session", {"targets": [loop.TARGET], "change": loop.CHANGE})
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", choices=["codex", "claude", "both"], default="both")
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--smoke-only", action="store_true",
                        help="Only read-only discovery, including per-invocation Codex trust retry")
    parser.add_argument("--output", type=Path,
                        default=Path("target/benchmarks/quill-native-client-probe.json"))
    args = parser.parse_args()
    quill, maven = args.quill.resolve(), shutil.which("mvn")
    if not quill.is_file() or not maven:
        raise ValueError("Native Quill and system Maven required")
    report = {"schema_version": 1, "clients": {},
              "scope": "native CLI headless; installed guidance/config; no global config changes"}
    def save():
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
    for client in (["codex", "claude"] if args.client == "both" else [args.client]):
        if not shutil.which(client):
            report["clients"][client] = {"unavailable": True}
            continue
        with tempfile.TemporaryDirectory(prefix="quill-native-" + client + "-") as temporary:
            project = Path(temporary).resolve()
            loop.write_fixture(project)
            for argv in (["git", "init", "-q"],
                         ["git", "config", "user.name", "Quill native probe"],
                         ["git", "config", "user.email", "native@example.invalid"],
                         [maven, "-q", "test-compile"], ["git", "add", "."],
                         ["git", "commit", "-q", "-m", "Fixture"],
                         [str(quill), "init", "--project", str(project)]):
                loop.run(argv, project)
            capture = {"version": loop.run([client, "--version"], project).stdout.strip()}
            report["clients"][client] = capture
            scope = "Work only in this fixture. Do not access credentials, other projects, " \
                    "or unrelated services. Do not delegate. "
            smoke = scope + "Assess the change workflow for org.example.GreetingService: " + \
                    loop.CHANGE + ". Do not edit or execute build commands. " \
                    "Return phase, primary action and verified from current tool evidence."
            capture["smoke"] = invoke(client, project, smoke, args.timeout)
            save()
            trusted = False
            if client == "codex" and not workflow_access(capture["smoke"]):
                trusted = True
                capture["trusted_smoke"] = invoke(client, project, smoke, args.timeout, trusted=True)
                save()
            selected = capture.get("trusted_smoke", capture["smoke"])
            if selected["returncode"] != 0 or not workflow_access(selected):
                capture["workflow_skipped"] = "Native client did not demonstrate Quill tool access"
            elif not args.smoke_only:
                shutil.rmtree(project / "target/test-classes")
                prompt = scope + loop.CHANGE + " in org.example.GreetingService. Append ! " \
                    "to the greeting. Compile production and standard test sources but do not " \
                    "execute any tests or e2e. Follow the installed project instructions. " \
                    "Use system Maven if there is no wrapper. Confirm completion using fresh " \
                    "workflow evidence. Do not change settings, build files or commit."
                capture["edit"] = invoke(client, project, prompt, args.timeout, trusted, True)
                save()
                edited = snapshot(quill, project)
                capture["after_edit"] = {"phase": edited.get("phase"),
                    "receipt": edited.get("verification_receipt")}
                path = project / "src/main/java/org/example/GreetingService.java"
                if 'return "Hello, " + name + "!";' in path.read_text():
                    loop.break_source(project)
                    failed = loop.run_unchecked([maven, "test-compile"], project)
                    capture["injected_failure"] = {"returncode": failed.returncode,
                                                   "phase": snapshot(quill, project).get("phase")}
                    recovery = scope + "The fixture now contains a deliberate compiler error. " \
                        "Diagnose and repair it, preserving the enthusiastic greeting and public " \
                        "signature. Follow installed instructions, use compile-only verification, " \
                        "no tests/e2e, no settings changes or commits. Confirm the final workflow " \
                        "phase and verified receipt from fresh evidence."
                    capture["recovery"] = invoke(client, project, recovery, args.timeout, trusted, True)
                    save()
                    final = snapshot(quill, project)
                    capture["after_recovery"] = {"phase": final.get("phase"),
                                                 "receipt": final.get("verification_receipt")}
                capture["test_reports_present"] = any((project / relative).exists() for relative in
                    ("target/surefire-reports", "target/failsafe-reports"))
                capture["test_sources_compiled"] = (project / "target/test-classes/org/example/"
                                                    "GreetingServiceCompileProbe.class").is_file()
        save()
    print("Report: " + str(args.output), flush=True)


if __name__ == "__main__":
    main()
