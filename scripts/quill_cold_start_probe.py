#!/usr/bin/env python3
"""Read-only cold-start adoption checks with installed guidance and client defaults."""

import argparse
import hashlib
import json
import os
import re
import signal
import subprocess
import queue
import sys
import tempfile
import threading
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


def observed_command(project, prompt, trust, trace):
    argv = command('codex', project, prompt, trust)
    # Resolve the same trusted configuration layer; do not persist configuration or secrets.
    config_args = argv[argv.index('-C'): -1]
    config = json.loads(subprocess.check_output(['codex', *config_args, 'mcp', 'get', 'quill', '--json'], text=True))
    transport = config.get('transport', {})
    if not config.get('enabled') or transport.get('type') != 'stdio':
        raise ValueError('Observation requires an enabled stdio Quill launcher')
    launcher = [sys.executable, str(Path(__file__).with_name('quill_mcp_observer.py').resolve()),
                str(trace), transport['command'], *transport.get('args', [])]
    return argv[:-1] + ['-c', 'mcp_servers.quill.command=' + json.dumps(launcher[0]),
                       '-c', 'mcp_servers.quill.args=' + json.dumps(launcher[1:])] + argv[-1:]


def stream_process(process, timeout, start):
    events, output, errors, timeline, pending = queue.Queue(), [], [], [], {}
    def read(stream, channel):
        try:
            for line in stream:
                events.put((channel, line, time.monotonic()))
        finally:
            events.put((channel, None, time.monotonic()))
    threads = [threading.Thread(target=read, args=(process.stdout, 'out'), daemon=True),
               threading.Thread(target=read, args=(process.stderr, 'err'), daemon=True)]
    for thread in threads:
        thread.start()
    closed, timed_out, killed_at = set(), False, None
    while len(closed) < 2 or process.poll() is None:
        now = time.monotonic()
        if not timed_out and now - start >= timeout:
            timed_out, killed_at = True, now
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        if timed_out and now - killed_at >= 5:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        try:
            channel, line, observed = events.get(timeout=0.1)
        except queue.Empty:
            continue
        if line is None:
            closed.add(channel)
            continue
        (output if channel == 'out' else errors).append(line)
        if channel != 'out':
            continue
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if not isinstance(event, dict):
            continue
        item = event.get('item', {})
        if not isinstance(item, dict) or item.get('type') not in {'mcp_tool_call', 'command_execution'}:
            continue
        identifier = item.get('id')
        if not isinstance(identifier, str):
            continue
        stamp = round((observed - start) * 1000, 3)
        if event.get('type') == 'item.started' and identifier not in pending:
            tool = 'shell' if item['type'] == 'command_execution' else item.get('server', '') + ':' + item.get('tool', '')
            entry = {'tool': tool, 'started_ms': stamp, 'completed_ms': None, 'event_span_ms': None}
            pending[identifier] = entry
            timeline.append(entry)
        if event.get('type') == 'item.completed':
            entry = pending.pop(identifier, None)
            if entry is not None:
                entry.update(completed_ms=stamp, event_span_ms=round(stamp - entry['started_ms'], 3))
    process.wait()
    for thread in threads:
        thread.join(timeout=1)
    return ''.join(output), ''.join(errors), timed_out, timeline


def observation_receipt(trace, start):
    receipts = [json.loads(line) for line in trace.read_text().splitlines()] if trace.is_file() else []
    for receipt in receipts:
        receipt['elapsed_ms'] = round((receipt.pop('monotonic_seconds') - start) * 1000, 3)
    catalogs = [r for r in receipts if r['event'] == 'response' and r['method'] == 'tools/list'
                and r['success'] and 'tool_count' in r]
    return {'scope': 'instrumented original stdio launcher; invocation-only override',
            'catalog_delivered_to_client': bool(catalogs),
            'overview_in_delivered_catalog': any(r.get('has_overview') for r in catalogs),
            'model_visibility': 'unknown', 'receipts': receipts}


def timing_summary(timeline, elapsed_ms):
    spans = sorted((c['started_ms'], c['completed_ms'] if c['completed_ms'] is not None else elapsed_ms)
                   for c in timeline)
    busy, until = 0, 0
    for start, end in spans:
        busy += max(0, end - max(start, until))
        until = max(until, end)
    quill = [c for c in timeline if c['tool'].startswith('quill:')]
    return {'basis': 'client item.started/item.completed arrival; not model reasoning attribution',
            'first_tool_started_ms': min((c['started_ms'] for c in timeline), default=None),
            'first_quill_started_ms': min((c['started_ms'] for c in quill), default=None),
            'observed_tool_span_union_ms': round(busy, 3),
            'outside_observed_tool_spans_ms': round(max(0, elapsed_ms - busy), 3),
            'unfinished_tools': sum(c['completed_ms'] is None for c in timeline)}


def run(client, project, prompt, timeout, trust=False, observe_mcp=False):
    before = fingerprints(project)
    with tempfile.TemporaryDirectory(prefix='quill-observer-') as temporary:
        trace = Path(temporary) / 'receipts.jsonl'
        argv = observed_command(project, prompt, trust, trace) if observe_mcp else command(client, project, prompt, trust)
        # Exclude configuration lookup from client process timing.
        start = time.monotonic()
        return run_process(client, project, timeout, before, start, argv, trace if observe_mcp else None)


def run_process(client, project, timeout, before, start, argv, trace):
    with subprocess.Popen(argv, cwd=project,
                          stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          text=True, start_new_session=True) as process:
        stdout, stderr, timed_out, timeline = stream_process(process, timeout, start)
        result = summarize(client, stdout)
        errors = client_errors(stdout)
        elapsed_ms = (time.monotonic() - start) * 1000
        result.update(returncode=process.returncode, timeout=timed_out,
                      tool_timeline=timeline,
                      timing_summary=timing_summary(timeline, elapsed_ms) if client == 'codex' else None,
                      mcp_observation=observation_receipt(trace, start) if trace else {
                          'scope': 'unmodified client launch', 'catalog_delivered_to_client': None,
                          'model_visibility': 'unknown'},
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
    parser.add_argument('--observe-mcp', action='store_true',
                        help='Codex-only diagnostic stdio relay; invocation-only launcher override, no raw payload logs')
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    project = args.project.resolve()
    if not project.is_dir() or args.runs < 1 or args.timeout < 1:
        parser.error("Existing project and positive runs/timeout required")
    if args.observe_mcp and args.client != 'codex':
        parser.error('--observe-mcp currently supports Codex only')
    report = {"schema_version": 1, "scope": "fresh headless processes/chats/MCP; existing index and user defaults",
              "client": args.client, "task": args.task, "invocation_trust": args.trust_project,
              "instrumented_mcp": args.observe_mcp,
              "version": subprocess.check_output([args.client, "--version"], text=True).strip(), "runs": []}
    for index in range(args.runs):
        scope = WORKSPACE_SCOPE if args.task == "workspace" else SCOPE
        result = run(args.client, project, TASKS[args.task] + scope, args.timeout, args.trust_project, args.observe_mcp)
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
