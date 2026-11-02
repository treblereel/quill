#!/usr/bin/env python3
"""Attribute every model request of recorded benchmark sessions to what that turn did.

ALL tokens are dominated by the context each model request resends, so the question for the
4x goal is which turns could disappear. Each request is classified by the actions the model
chose in its response (Quill query, tool-catalog search, source read, source search, build,
other shell, final answer) and its input+output tokens are charged to that turn.

Codex: rollout.jsonl, one ``token_count`` event per request; the response items recorded
before it are that request's actions. Prewarm requests are on the wire, not in the rollout,
and are not counted here. Claude: events.jsonl assistant messages grouped by message id.
"""
import argparse
import collections
import json
import re
import sys
from pathlib import Path

QUILL = re.compile(r"mcp__quill__(\w+)")
READ_COMMANDS = re.compile(r"(^|[;&|(\s])(cat|sed|nl|head|tail|less|awk)\s")
SEARCH_COMMANDS = re.compile(r"(^|[;&|(\s])(rg|grep|find|ls|fd|git\s+grep)\s")
# A command word, not a file name: "A.java | sed" is a read, "./mvnw test" a build.
BUILD_COMMANDS = re.compile(r"(^|[\s;&|(=/])(mvnw?|gradlew?|javac|java)(\s|$)")
CATALOG = ("search_tools", "tool_search")
# When one turn did several things, it is charged to the first category in this order.
PRIORITY = ("quill_query", "catalog_search", "build", "source_search", "source_read",
            "other_tool", "final_answer")


def classify_command(command):
    if BUILD_COMMANDS.search(command):
        return "build"
    if SEARCH_COMMANDS.search(" " + command):
        return "source_search"
    if READ_COMMANDS.search(" " + command):
        return "source_read"
    return "other_tool"


def codex_actions(item):
    """Categories of one Codex response item (a code-mode script or a function call)."""
    text = item.get("input") or item.get("arguments") or ""
    if not isinstance(text, str):
        text = json.dumps(text)
    found = set()
    for name in QUILL.findall(text):
        found.add("catalog_search" if name in CATALOG else "quill_query")
    if "ALL_TOOLS" in text:
        found.add("catalog_search")
    for command in re.findall(r'cmd\s*:\s*"((?:[^"\\]|\\.)*)"', text):
        found.add(classify_command(command.encode().decode("unicode_escape")))
    if not found:
        found.add(classify_command(text))
    return found


def claude_action(block):
    name = block.get("name", "")
    if name.startswith("mcp__quill__"):
        return "catalog_search" if name[len("mcp__quill__"):] in CATALOG else "quill_query"
    if name == "ToolSearch":
        return "catalog_search"
    if name == "Read":
        return "source_read"
    if name in ("Grep", "Glob"):
        return "source_search"
    if name == "Bash":
        return classify_command(block.get("input", {}).get("command", ""))
    return "other_tool"


def primary(actions):
    return next(category for category in PRIORITY if category in actions)


def codex_turns(rollout):
    turns, pending = [], set()
    for line in rollout.read_text().splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        payload = event.get("payload") or {}
        if event.get("type") == "response_item" and payload.get("type") in (
                "custom_tool_call", "function_call"):
            pending |= codex_actions(payload)
        elif event.get("type") == "event_msg" and payload.get("type") == "token_count":
            usage = (payload.get("info") or {}).get("last_token_usage")
            if not usage:
                continue
            turns.append({"actions": sorted(pending or {"final_answer"}),
                          "input": usage.get("input_tokens", 0),
                          "cached": usage.get("cached_input_tokens", 0),
                          "output": usage.get("output_tokens", 0)})
            pending = set()
    return turns


def claude_turns(events):
    messages = collections.OrderedDict()
    for line in events.read_text().splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") != "assistant":
            continue
        message = event.get("message") or {}
        turn = messages.setdefault(message.get("id"), {"actions": set(), "usage": {}})
        for block in message.get("content", []):
            if isinstance(block, dict) and block.get("type") == "tool_use":
                turn["actions"].add(claude_action(block))
        usage = message.get("usage") or {}
        # Streamed snapshots of one message repeat its usage; output grows until the last one.
        if usage.get("output_tokens", 0) >= turn["usage"].get("output_tokens", 0):
            turn["usage"] = usage
    turns = []
    for turn in messages.values():
        usage = turn["usage"]
        cached = usage.get("cache_read_input_tokens", 0)
        turns.append({"actions": sorted(turn["actions"] or {"final_answer"}),
                      "input": usage.get("input_tokens", 0) + cached
                      + usage.get("cache_creation_input_tokens", 0),
                      "cached": cached, "output": usage.get("output_tokens", 0)})
    return turns


def session_turns(session):
    if (session / "rollout.jsonl").is_file():
        return "codex", codex_turns(session / "rollout.jsonl")
    if (session / "events.jsonl").is_file():
        return "claude", claude_turns(session / "events.jsonl")
    return None, []


def summarize(sessions_dir):
    clients = {}
    for session in sorted(Path(sessions_dir).iterdir()):
        if not session.is_dir():
            continue
        client, turns = session_turns(session)
        if client is None or not turns:
            continue
        summary = clients.setdefault(client, {
            "sessions": 0, "requests": 0, "all": 0, "by_category": {}, "sessions_detail": []})
        summary["sessions"] += 1
        session_all = 0
        for turn in turns:
            tokens = turn["input"] + turn["output"]
            category = summary["by_category"].setdefault(
                primary(turn["actions"]), {"requests": 0, "all": 0})
            category["requests"] += 1
            category["all"] += tokens
            session_all += tokens
        summary["requests"] += len(turns)
        summary["all"] += session_all
        summary["sessions_detail"].append({
            "session": session.name, "requests": len(turns), "all": session_all,
            "turns": [primary(turn["actions"]) for turn in turns]})
    for summary in clients.values():
        summary["requests_per_session"] = round(summary["requests"] / summary["sessions"], 2)
        summary["sessions_detail"].sort(key=lambda detail: detail["all"], reverse=True)
    return clients


def print_report(clients, out=None):
    out = out or sys.stdout
    for client, summary in sorted(clients.items()):
        print(f"{client}: {summary['sessions']} sessions, {summary['requests']} requests "
              f"({summary['requests_per_session']} per session), ALL {summary['all']:,}", file=out)
        for category in PRIORITY:
            value = summary["by_category"].get(category)
            if value:
                share = value["all"] / summary["all"] if summary["all"] else 0
                print(f"  {category:15} {value['requests']:>5} requests "
                      f"{value['all']:>12,} ALL {share:6.1%}", file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sessions", required=True, help="benchmark sessions directory")
    parser.add_argument("--out", help="write the JSON report here")
    args = parser.parse_args(argv)
    clients = summarize(args.sessions)
    if args.out:
        Path(args.out).write_text(json.dumps(clients, indent=1) + "\n")
    print_report(clients)
    return 0


if __name__ == "__main__":
    sys.exit(main())
