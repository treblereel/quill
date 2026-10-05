#!/usr/bin/env python3
"""Read-only cold-start adoption checks with installed guidance and client defaults."""

import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import time
from pathlib import Path

READ_ONLY_LIMITS = (" не меняй файлы и настройки, не запускай сборки или тесты, "
                    "не делегируй работу, не подключай новые каталоги и не обращайся "
                    "к внешним сервисам.")
SCOPE = " Только анализ текущего репозитория; не обращайся к другим проектам;" + READ_ONLY_LIMITS
WORKSPACE_SCOPE = " Только анализ уже подключённых проектов workspace;" + READ_ONLY_LIMITS
TASKS = {
    "map": "Дай краткую карту этого репозитория: основные Maven-модули, фреймворк, "
           "точки входа annotation processors и где находится генерация DI-кода.",
    "usages": "Найди реализации Generator и их регистрацию в генерации DI-кода. "
              "Покажи конкретные классы, связи и файлы; отличай найденные факты от предположений.",
    "workspace": "Дай краткую карту подключённых проектов. Выбери один доступный проект "
                 "и сообщи его фреймворк и количество классов на основании подробного обзора.",
}


def overview_arguments(arguments):
    if not isinstance(arguments, dict):
        return {}
    result = {k: v for k, v in arguments.items() if k in {"view", "limit", "offset", "details"}
              and isinstance(v, (str, bool, int, float))}
    if "project" in arguments:
        selector = arguments["project"]
        result["project"] = (selector if isinstance(selector, str) and not any(
            separator in selector for separator in ("/", "\\")) else "<selector>")
    return result


def command(client, project, prompt, trust=False):
    if client == "codex":
        argv = ["codex", "exec", "--json", "--ephemeral", "--sandbox", "read-only", "-C", str(project)]
        if trust:
            argv += ["-c", 'projects={' + json.dumps(str(project)) + '={trust_level="trusted"}}']
        return argv + [prompt]
    # Permissions come only from installed settings, never from a CLI allow or bypass.
    return ["claude", "-p", prompt, "--output-format", "stream-json", "--verbose",
            "--no-session-persistence"]


def summarize(client, stdout):
    calls, results, denials = [], {}, set()
    final, final_error, answer_seen = False, False, False
    for line in stdout.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if not isinstance(event, dict):
            continue
        if client == "codex":
            item = event.get("item", {})
            if event.get("type") == "turn.completed":
                final = True
            if event.get("type") in {"error", "turn.failed"}:
                final_error = True
            if event.get("type") != "item.completed":
                continue
            if item.get("type") == "agent_message" and item.get("text", "").strip():
                answer_seen = True
            if item.get("type") == "command_execution":
                calls.append({"tool": "shell", "success": item.get("exit_code") == 0})
            if item.get("type") == "mcp_tool_call":
                result = item.get("result")
                success = (item.get("status") == "completed" and result is not None
                           and isinstance(result, dict) and not item.get("error") and not result.get("isError", False)
                           and not result.get("is_error", False))
                call = {"tool": item.get("server", "") + ":" + item.get("tool", ""),
                        "success": success}
                if item.get("tool") == "get_overview":
                    call["arguments"] = overview_arguments(item.get("arguments", {}))
                calls.append(call)
        else:
            if event.get("type") == "result":
                final = True
                final_error = bool(event.get("is_error"))
                answer_seen = bool(str(event.get("result", "")).strip())
                denials.update(x.get("tool_use_id") for x in event.get("permission_denials", []))
            message = event.get("message", {})
            if not isinstance(message, dict):
                continue
            content = message.get("content", [])
            if not isinstance(content, list):
                continue
            for item in content:
                if not isinstance(item, dict):
                    continue
                if event.get("type") == "assistant" and item.get("type") == "tool_use":
                    call = {"tool": item.get("name", ""), "id": item.get("id")}
                    if item.get("name") == "mcp__quill__get_overview":
                        call["arguments"] = overview_arguments(item.get("input", {}))
                    calls.append(call)
                if item.get("type") == "tool_result":
                    results[item.get("tool_use_id")] = not item.get("is_error", False)
    quill_denials = 0
    if client == "claude":
        for call in calls:
            identifier = call.pop("id", None)
            if identifier in denials and call["tool"].startswith("mcp__quill__"):
                quill_denials += 1
            call["success"] = results.get(identifier, False) and identifier not in denials
    is_quill = lambda name: name.startswith("quill:") or name.startswith("mcp__quill__")
    overview = lambda name: name in {"quill:get_overview", "mcp__quill__get_overview"}
    first = next((i for i, c in enumerate(calls) if overview(c["tool"])), None)
    first_quill = next((c for c in calls if is_quill(c["tool"])), None)
    return {"calls": calls, "final_answer": final and answer_seen and not final_error,
            "permission_denials": len(denials),
            "quill_permission_denials": quill_denials,
            "overview_attempted": first is not None,
            "overview_first_quill_call": first_quill is not None and overview(first_quill["tool"]),
            "overview_successful": any(overview(c["tool"]) and c["success"] for c in calls),
            "source_reads_before_overview": first is not None and any(
                c["tool"] in {"shell", "Read", "Bash", "Grep", "Glob"} for c in calls[:first]),
            "semantic_tools_successful": sorted({c["tool"] for c in calls
                if is_quill(c["tool"]) and not overview(c["tool"]) and c["success"]})}


def fingerprints(project):
    home = Path.home()
    files = [project / name for name in ("AGENTS.md", "CLAUDE.md", ".mcp.json",
             ".codex/config.toml", ".claude/settings.json", ".claude/settings.local.json")]
    files += [Path(os.environ.get("CODEX_HOME", home / ".codex")) / "config.toml",
              home / ".claude/settings.json"]
    return [hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None for p in files]


def failure_category(returncode, timed_out, output):
    if timed_out:
        return "timeout"
    if returncode == 0:
        return None
    lower = output.lower()
    if "model is at capacity" in lower:
        return "model_capacity"
    if any(value in lower for value in ("usage limit", "rate_limit", "rate limit", "quota", "too many requests")):
        return "rate_or_usage_limit"
    if any(value in lower for value in ("unauthorized", "authentication", "invalid api key")):
        return "authentication"
    if "stream" in lower and ("disconnect" in lower or "closed" in lower):
        return "stream_disconnected"
    return "client_error"


def client_errors(stdout):
    errors = []
    for line in stdout.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if not isinstance(event, dict) or event.get("type") not in {"error", "turn.failed"}:
            continue
        value = event.get("message", event.get("error", ""))
        if isinstance(value, dict):
            value = value.get("message", value.get("code", "client_error"))
        value = re.sub(r"sk-[A-Za-z0-9_-]+|Bearer\s+\S+", "<redacted>", str(value))
        value = re.sub(r"/(?:Users|private|tmp)/[^\s\"']+", "<path>", value)
        errors.append(value[:400])
    return errors


def run(client, project, prompt, timeout, trust=False):
    before, start = fingerprints(project), time.monotonic()
    with subprocess.Popen(command(client, project, prompt, trust), cwd=project,
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, start_new_session=True) as process:
        timed_out = False
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            os.killpg(process.pid, signal.SIGTERM)
            try:
                stdout, stderr = process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                stdout, stderr = process.communicate()
        result = summarize(client, stdout)
        errors = client_errors(stdout)
        result.update(returncode=process.returncode, timeout=timed_out,
                      client_errors=errors,
                      failure_category=failure_category(process.returncode, timed_out, " ".join(errors) + stderr),
                      elapsed_seconds=round(time.monotonic() - start, 2),
                      settings_unchanged=before == fingerprints(project))
        result["passed"] = (process.returncode == 0 and not timed_out and result["settings_unchanged"]
                            and result["final_answer"] and result["overview_successful"]
                            and result["overview_first_quill_call"]
                            and not result["source_reads_before_overview"] and not result["quill_permission_denials"])
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, type=Path)
    parser.add_argument("--client", choices=["codex", "claude"], required=True)
    parser.add_argument("--task", choices=TASKS, default="map")
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--trust-project", action="store_true",
                        help="Explicit invocation-only Codex trust; no global writes")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    project = args.project.resolve()
    if not project.is_dir() or args.runs < 1 or args.timeout < 1:
        parser.error("Existing project and positive runs/timeout required")
    report = {"schema_version": 1, "scope": "fresh headless processes/chats/MCP; existing index and user defaults",
              "client": args.client, "task": args.task, "invocation_trust": args.trust_project,
              "version": subprocess.check_output([args.client, "--version"], text=True).strip(), "runs": []}
    for index in range(args.runs):
        scope = WORKSPACE_SCOPE if args.task == "workspace" else SCOPE
        result = run(args.client, project, TASKS[args.task] + scope, args.timeout, args.trust_project)
        if args.task == "usages":
            result["passed"] = result["passed"] and bool(result["semantic_tools_successful"])
        if args.task == "workspace":
            overviews = [call for call in result["calls"] if call["tool"] in {
                "quill:get_overview", "mcp__quill__get_overview"} and call["success"]]
            result["passed"] = result["passed"] and bool(overviews) and (
                overviews[0].get("arguments", {}).get("view") == "compact") and any(
                call.get("arguments", {}).get("view") == "full"
                and call.get("arguments", {}).get("project") for call in overviews)
        report["runs"].append(result)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # Persist only names, outcome flags and overview arguments: no raw output or user paths.
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps({"run": index + 1, "client": args.client, **result}, ensure_ascii=False), flush=True)
        if not result["settings_unchanged"]:
            break
    return 0 if len(report["runs"]) == args.runs and all(r["passed"] for r in report["runs"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
