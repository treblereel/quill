#!/usr/bin/env python3
"""Run a paired, real-agent benchmark with and without the local Quill MCP server."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Any, Callable
from urllib import error, request

from quill_benchmark import McpClient, output_of


SOURCE_TOOLS = [
    {
        "type": "function",
        "name": "list_project_files",
        "description": "List project files with ripgrep, optionally restricted by a glob and paths.",
        "parameters": {
            "type": "object",
            "properties": {
                "paths": {"type": "array", "items": {"type": "string"}},
                "glob": {"type": "string"},
            },
            "required": ["paths", "glob"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "source_search",
        "description": "Search project text with ripgrep. Returns matching file names and lines.",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "paths": {"type": "array", "items": {"type": "string"}},
                "glob": {"type": "string"},
                "fixed_strings": {"type": "boolean"},
            },
            "required": ["query", "paths", "glob", "fixed_strings"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "read_file",
        "description": "Read an inclusive line range from one project file.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "start_line": {"type": "integer", "minimum": 1},
                "end_line": {"type": "integer", "minimum": 1},
            },
            "required": ["path", "start_line", "end_line"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "git_history",
        "description": "Read recent Git history, optionally for one path, including changed paths.",
        "parameters": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "limit": {"type": "integer", "minimum": 1, "maximum": 100},
                "name_status": {"type": "boolean"},
            },
            "required": ["path", "limit", "name_status"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "git_show_file",
        "description": "Read an inclusive line range from a file at a Git revision.",
        "parameters": {
            "type": "object",
            "properties": {
                "revision": {"type": "string"},
                "path": {"type": "string"},
                "start_line": {"type": "integer", "minimum": 1},
                "end_line": {"type": "integer", "minimum": 1},
            },
            "required": ["revision", "path", "start_line", "end_line"],
            "additionalProperties": False,
        },
        "strict": True,
    },
    {
        "type": "function",
        "name": "git_historical_paths",
        "description": "Find repository paths from all Git history containing a literal name fragment.",
        "parameters": {
            "type": "object",
            "properties": {"query": {"type": "string"}},
            "required": ["query"],
            "additionalProperties": False,
        },
        "strict": True,
    },
]


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, required=True)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--model", default="gpt-5.6-terra",
                        help="Exact Responses API model id used for both modes "
                             "(default: gpt-5.6-terra)")
    parser.add_argument("--reasoning-effort",
                        choices=("none", "low", "medium", "high", "xhigh", "max"),
                        default="medium")
    parser.add_argument("--quill", default=os.environ.get("QUILL_BIN", "quill"))
    parser.add_argument("--quill-profile", choices=("default", "router"),
                        default="default",
                        help="MCP catalog exposed by Quill (default: the normal full catalog)")
    parser.add_argument("--tool-selection", choices=("suite", "all"), default="suite",
                        help="Expose each task's quill_tools allowlist or every tool in the "
                             "selected Quill profile (default: suite)")
    parser.add_argument("--output-dir", type=Path, default=Path("target/benchmarks"))
    parser.add_argument("--api-key-env", default="OPENAI_API_KEY")
    parser.add_argument("--api-base", default="https://api.openai.com/v1")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--tool-output-limit", type=int, default=30000)
    parser.add_argument("--repetitions", type=int, default=1,
                        help="Number of independent paired runs (use 10-20 for reporting)")
    parser.add_argument("--allow-dirty", action="store_true")
    parser.add_argument("--guidance-file", type=Path,
                        help="Installed AGENTS.md or CLAUDE.md; inject its managed Quill block "
                             "into with_quill runs to measure instruction adoption")
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def managed_guidance(path: Path) -> str:
    content = path.read_text(encoding="utf-8")
    start, end = "<!-- quill:managed:start -->", "<!-- quill:managed:end -->"
    if content.count(start) != 1 or content.count(end) != 1 \
            or content.index(end) < content.index(start):
        raise ValueError(f"Malformed managed Quill guidance in {path}")
    return content[content.index(start):content.index(end) + len(end)]


def checked_path(project: Path, value: str) -> Path:
    candidate = (project / value).resolve()
    try:
        candidate.relative_to(project)
    except ValueError as exc:
        raise ValueError(f"Path escapes the project: {value}") from exc
    return candidate


def clipped(value: str, limit: int) -> str:
    if len(value) <= limit:
        return value
    return value[:limit] + f"\n[truncated after {limit} characters]"


class SourceTools:
    def __init__(self, project: Path, output_limit: int):
        self.project = project.resolve()
        self.output_limit = output_limit
        self.calls = 0
        self.manual_verification_steps = 0
        self.last_call: dict[str, Any] | None = None

    def call(self, name: str, arguments: dict[str, Any]) -> str:
        self.calls += 1
        started = time.perf_counter()
        result = ""
        error_type: str | None = None
        try:
            result = self._call(name, arguments)
            return result
        except BaseException as exc:
            error_type = type(exc).__name__
            raise
        finally:
            self.last_call = call_trace(
                name, "source", arguments, result, started, error_type)

    def _call(self, name: str, arguments: dict[str, Any]) -> str:
        if name == "list_project_files":
            command = ["rg", "--files", "--hidden", "--no-ignore",
                       "--glob", "!.git/**", "--glob", "!.quill/**"]
            if arguments["glob"]:
                command.extend(["--glob", arguments["glob"]])
            paths = arguments["paths"] or ["."]
            for value in paths:
                checked_path(self.project, value)
            command.extend(["--", *paths])
            return self._run(command)
        if name == "source_search":
            command = ["rg", "--line-number", "--no-heading", "--color", "never",
                       "--hidden", "--no-ignore", "--glob", "!.git/**",
                       "--glob", "!.quill/**"]
            if arguments["fixed_strings"]:
                command.append("--fixed-strings")
            if arguments["glob"]:
                command.extend(["--glob", arguments["glob"]])
            command.extend(["--", arguments["query"]])
            paths = arguments["paths"] or ["."]
            for value in paths:
                checked_path(self.project, value)
            command.extend(paths)
            return self._run(command)
        if name == "read_file":
            self.manual_verification_steps += 1
            path = checked_path(self.project, arguments["path"])
            if not path.is_file():
                return f"File not found: {arguments['path']}"
            start = arguments["start_line"]
            end = arguments["end_line"]
            if end < start or end - start > 1000:
                return "Invalid line range: end_line must be >= start_line and span at most 1001 lines"
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
            rendered = "\n".join(f"{index}: {lines[index - 1]}"
                                   for index in range(start, min(end, len(lines)) + 1))
            return clipped(rendered, self.output_limit)
        if name == "git_history":
            command = [
                "git", "log", f"-{arguments['limit']}", "--date=iso-strict",
                "--format=%H%x09%an%x09%ae%x09%ad%x09%s",
            ]
            if arguments["name_status"]:
                command.append("--name-status")
            if arguments["path"]:
                checked_path(self.project, arguments["path"])
                # Match Quill's un-simplified RevWalk history. The default path-limited
                # git log can omit merge commits even when the file differs from the
                # merge's first parent.
                command.append("--full-history")
                command.extend(["--", arguments["path"]])
            return self._run(command)
        if name == "git_show_file":
            revision = arguments["revision"]
            if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/^~-]*", revision):
                return "Invalid Git revision"
            checked_path(self.project, arguments["path"])
            start = arguments["start_line"]
            end = arguments["end_line"]
            if end < start or end - start > 1000:
                return "Invalid line range: end_line must be >= start_line and span at most 1001 lines"
            self.manual_verification_steps += 1
            content = self._run_raw(
                ["git", "show", f"{revision}:{arguments['path']}"])
            lines = content.splitlines()
            rendered = "\n".join(f"{index}: {lines[index - 1]}"
                                   for index in range(start, min(end, len(lines)) + 1))
            return clipped(rendered, self.output_limit)
        if name == "git_historical_paths":
            query = arguments["query"]
            if not query:
                return "Query must not be empty"
            history = self._run_raw(["git", "log", "--all", "--name-only", "--format="])
            matches = sorted({line for line in history.splitlines() if query in line})
            return clipped("\n".join(matches), self.output_limit)
        raise ValueError(f"Unknown source tool: {name}")

    def _run(self, command: list[str]) -> str:
        return clipped(self._run_raw(command), self.output_limit)

    def _run_raw(self, command: list[str]) -> str:
        completed = subprocess.run(command, cwd=self.project, text=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   timeout=30, check=False)
        output = completed.stdout
        if completed.returncode not in (0, 1):
            output = f"Command exited {completed.returncode}\n{output}"
        return output


class QuillTools:
    def __init__(self, command: list[str], project: Path, timeout: int, output_limit: int):
        self.client = McpClient(command, project, timeout)
        self.timeout = timeout
        self.output_limit = output_limit
        self.next_id = 1
        self.calls = 0
        self.definitions: list[dict[str, Any]] = []
        self.last_call: dict[str, Any] | None = None

    def __enter__(self) -> "QuillTools":
        try:
            self._request("initialize", {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "clientInfo": {"name": "quill-agent-benchmark", "version": "1"},
            })
            assert self.client.process.stdin is not None
            self.client.process.stdin.write(
                '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n')
            self.client.flush()
            listed = self._request("tools/list", {})
            tools = listed.get("tools", [])
            if not isinstance(tools, list):
                raise RuntimeError("Quill tools/list returned no tools array")
            for tool in tools:
                name = tool.get("name")
                schema = tool.get("inputSchema", {"type": "object", "properties": {}})
                if not isinstance(name, str) or not isinstance(schema, dict):
                    continue
                self.definitions.append({
                    "type": "function",
                    "name": f"quill_{name}",
                    "description": tool.get("description", "Quill project-index query"),
                    "parameters": schema,
                })
            return self
        except BaseException:
            self.client.close()
            raise

    def __exit__(self, *_: object) -> None:
        self.client.close()

    def _request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        request_id = self.next_id
        self.next_id += 1
        self.client.send(request_id, method, params)
        self.client.flush()
        response = self.client.receive({request_id}, self.timeout)[request_id]
        if "error" in response:
            raise RuntimeError(f"Quill MCP {method} failed: {response['error']}")
        result = response.get("result")
        if not isinstance(result, dict):
            raise RuntimeError(f"Quill MCP {method} returned an invalid result")
        return result

    def call(self, exposed_name: str, arguments: dict[str, Any]) -> str:
        self.calls += 1
        started = time.perf_counter()
        output = ""
        error_type: str | None = None
        outcome_flags: list[str] = []
        try:
            result = self._request("tools/call", {
                "name": exposed_name.removeprefix("quill_"),
                "arguments": arguments,
            })
            outcome_flags = quill_outcome_flags(result)
            output = clipped(json.dumps(result, ensure_ascii=False), self.output_limit)
            return output
        except BaseException as exc:
            error_type = type(exc).__name__
            raise
        finally:
            self.last_call = call_trace(
                exposed_name, "quill", arguments, output, started, error_type,
                outcome_flags)


def call_trace(name: str, provider: str, arguments: dict[str, Any], output: str,
               started: float, error_type: str | None,
               outcome_flags: list[str] | None = None) -> dict[str, Any]:
    """Return metadata useful for cost diagnosis without persisting tool contents."""
    trace = {
        "tool": name,
        "provider": provider,
        "duration_ms": round((time.perf_counter() - started) * 1000, 3),
        "argument_bytes": len(json.dumps(arguments, ensure_ascii=False).encode()),
        "output_bytes": len(output.encode()),
        "status": "ok" if error_type is None else "error",
        "error_type": error_type,
    }
    if outcome_flags:
        trace["outcome_flags"] = outcome_flags
    return trace


def quill_outcome_flags(result: dict[str, Any]) -> list[str]:
    """Classify response limitations without retaining indexed project contents."""
    flags: set[str] = set()
    if result.get("isError") is True:
        flags.add("error")
    structured = result.get("structuredContent")
    if not isinstance(structured, dict):
        return sorted(flags)

    def inspect(value: Any, key: str = "") -> None:
        normalized_key = key.lower()
        if isinstance(value, dict):
            for child_key, child in value.items():
                inspect(child, str(child_key))
        elif isinstance(value, list):
            for child in value:
                inspect(child, key)
        elif isinstance(value, bool) and value:
            if normalized_key in {"commit_stale", "structure_stale", "stale_warning"}:
                flags.add("stale")
            elif normalized_key == "truncated":
                flags.add("truncated")
        elif isinstance(value, str):
            normalized_value = value.lower()
            if normalized_key == "error":
                flags.add("error")
                if "not found" in normalized_value:
                    flags.add("not_found")
            if normalized_key in {"status", "resolution_status"}:
                if normalized_value == "unknown":
                    flags.add("unknown")
                elif normalized_value == "unsupported_mechanism":
                    flags.add("unsupported")

    inspect(structured)
    return sorted(flags)


def source_fallbacks(tool_trace: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Find source-tool calls made after the latest Quill query in one task."""
    latest_quill: dict[str, Any] | None = None
    fallbacks: list[dict[str, Any]] = []
    for trace in tool_trace:
        provider = trace.get("provider")
        if provider == "quill":
            latest_quill = trace
        elif provider == "source" and latest_quill is not None:
            fallback = {
                "round": trace.get("round"),
                "quill_tool": latest_quill.get("tool"),
                "source_tool": trace.get("tool"),
                "quill_status": latest_quill.get("status"),
            }
            flags = latest_quill.get("outcome_flags")
            if flags:
                fallback["quill_outcome_flags"] = flags
            fallbacks.append(fallback)
    return fallbacks


def tool_usage_diagnostics(tool_trace: list[dict[str, Any]],
                           quill_advertised: bool) -> dict[str, Any]:
    """Classify source-first calls and tasks that never invoke advertised Quill tools."""
    first_quill = next((index for index, trace in enumerate(tool_trace)
                        if trace.get("provider") == "quill"), None)
    source_first = [
        {"round": trace.get("round"), "source_tool": trace.get("tool")}
        for index, trace in enumerate(tool_trace)
        if trace.get("provider") == "source"
        and (first_quill is None or index < first_quill)
    ]
    quill_calls = sum(trace.get("provider") == "quill" for trace in tool_trace)
    source_calls = sum(trace.get("provider") == "source" for trace in tool_trace)
    first_tool = next(iter(tool_trace), None)
    first_quill_trace = next((trace for trace in tool_trace
                              if trace.get("provider") == "quill"), None)
    first_successful_quill = next((trace for trace in tool_trace
                                   if trace.get("provider") == "quill"
                                   and trace.get("status") == "ok"
                                   and "error" not in trace.get("outcome_flags", [])), None)
    return {
        "quill_call_count": quill_calls,
        "source_call_count": source_calls,
        "source_first_count": len(source_first),
        "source_first_calls": source_first,
        "quill_bypassed": bool(quill_advertised and quill_calls == 0),
        "time_to_first_tool_ms": (first_tool or {}).get("elapsed_since_task_start_ms"),
        "time_to_first_quill_ms": (first_quill_trace or {}).get(
            "elapsed_since_task_start_ms"),
        "time_to_first_successful_quill_ms": (first_successful_quill or {}).get(
            "elapsed_since_task_start_ms"),
    }


class ResponsesClient:
    def __init__(self, api_key: str, api_base: str, timeout: int,
                 transport: Callable[[dict[str, Any]], dict[str, Any]] | None = None):
        self.api_key = api_key
        self.url = api_base.rstrip("/") + "/responses"
        self.timeout = timeout
        self.transport = transport

    def create(self, payload: dict[str, Any]) -> dict[str, Any]:
        if self.transport:
            return self.transport(payload)
        body = json.dumps(payload).encode("utf-8")
        api_request = request.Request(self.url, data=body, method="POST", headers={
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        })
        try:
            with request.urlopen(api_request, timeout=self.timeout) as response:
                value = json.loads(response.read())
        except error.HTTPError as exc:
            detail = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Responses API returned HTTP {exc.code}: {detail}") from exc
        if not isinstance(value, dict):
            raise RuntimeError("Responses API returned a non-object response")
        if value.get("error"):
            raise RuntimeError(f"Responses API failed: {value['error']}")
        return value


def parse_final_json(text: str) -> dict[str, Any]:
    candidate = text.strip()
    if candidate.startswith("```"):
        lines = candidate.splitlines()
        candidate = "\n".join(lines[1:-1])
    value = json.loads(candidate)
    if not isinstance(value, dict) or not isinstance(value.get("observed"), dict):
        raise ValueError("Final response must be a JSON object containing observed")
    return value


def json_type(value: Any) -> str:
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return "null"


def selected_quill_tools(task: dict[str, Any], quill: QuillTools | None,
                         selection: str = "suite") -> list[dict[str, Any]]:
    if quill is None:
        return []
    if selection == "all":
        return quill.definitions
    if selection != "suite":
        raise ValueError(f"Unknown Quill tool selection: {selection}")
    requested = task.get("quill_tools")
    if requested is None:
        return quill.definitions
    if not isinstance(requested, list) or not all(isinstance(name, str) for name in requested):
        raise ValueError(f"Task {task.get('id')} quill_tools must be a string array")
    allowed = {f"quill_{name}" for name in requested}
    selected = [tool for tool in quill.definitions if tool["name"] in allowed]
    missing = sorted(allowed - {tool["name"] for tool in selected})
    if missing:
        raise ValueError(f"Task {task.get('id')} requests unknown Quill tools: "
                         + ", ".join(missing))
    return selected


def run_agent(client: ResponsesClient, task: dict[str, Any], mode: str, model: str,
              reasoning_effort: str, source: SourceTools,
              quill: QuillTools | None, tool_selection: str = "suite",
              guidance: str = "", *, action_instructions: str = "",
              extra_tools: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    expected = task.get("expected")
    if not isinstance(expected, dict) or not expected:
        raise ValueError(f"Task {task.get('id')} has no expected object")
    expected_shape = {key: json_type(value) for key, value in expected.items()}
    quill_tools = selected_quill_tools(task, quill, tool_selection)
    tools = [*SOURCE_TOOLS, *quill_tools, *(extra_tools or [])]
    tool_catalog_bytes = len(json.dumps(tools, ensure_ascii=False).encode())
    instructions = (
        "You are evaluating a Java project. Answer only from tool evidence. "
        "Do not modify files or run builds. Choose the cheapest sufficient evidence: use source "
        "search/read for an exact local literal, and Quill for project-wide aggregation, generated "
        "outputs, dependency graphs, or lifecycle history. Do not re-check a fresh Quill result "
        "when its evidence directly proves the fact; verify stale, unknown, or unsupported claims. "
        "Return only JSON with one key, observed, whose object has exactly this key/type shape: "
        f"{json.dumps(expected_shape)}. Array values must be sorted. "
        "Use null when evidence is insufficient."
    )
    if action_instructions:
        instructions = (action_instructions + "\nReturn only JSON with one key, observed, "
                        "whose object has exactly this key/type shape: "
                        + json.dumps(expected_shape) + ". Use null for insufficient evidence.")
    if guidance and mode == "with_quill":
        instructions += "\n\nInstalled Quill guidance:\n" + guidance
    next_input: Any = task["prompt"]
    previous_response_id: str | None = None
    totals = {"input_tokens": 0, "cached_input_tokens": 0, "output_tokens": 0,
              "model_requests": 0}
    response_ids: list[str] = []
    response_models: set[str] = set()
    model_rounds: list[dict[str, Any]] = []
    tool_trace: list[dict[str, Any]] = []
    final_text: str | None = None
    started = time.perf_counter()
    while totals["model_requests"] < 30:
        payload: dict[str, Any] = {
            "model": model,
            "instructions": instructions,
            "input": next_input,
            "tools": tools,
            "tool_choice": "auto",
            "parallel_tool_calls": False,
            "reasoning": {"effort": reasoning_effort},
            "store": True,
        }
        if previous_response_id:
            payload["previous_response_id"] = previous_response_id
        request_started = time.perf_counter()
        response = client.create(payload)
        request_duration_ms = round((time.perf_counter() - request_started) * 1000, 3)
        totals["model_requests"] += 1
        if isinstance(response.get("id"), str):
            response_ids.append(response["id"])
        if isinstance(response.get("model"), str):
            response_models.add(response["model"])
        usage = response.get("usage") or {}
        round_input = int(usage.get("input_tokens", 0))
        round_cached = int(
            (usage.get("input_tokens_details") or {}).get("cached_tokens", 0))
        round_output = int(usage.get("output_tokens", 0))
        totals["input_tokens"] += round_input
        totals["cached_input_tokens"] += round_cached
        totals["output_tokens"] += round_output
        previous_response_id = response.get("id")
        calls = [item for item in response.get("output", [])
                 if item.get("type") == "function_call"]
        model_rounds.append({
            "round": totals["model_requests"],
            "response_id": response.get("id"),
            "duration_ms": request_duration_ms,
            "input_tokens": round_input,
            "cached_input_tokens": round_cached,
            "output_tokens": round_output,
            "tool_calls": len(calls),
        })
        if calls:
            outputs = []
            for call in calls:
                args = json.loads(call.get("arguments", "{}"))
                name = call["name"]
                provider = quill if name.startswith("quill_") and quill else source
                try:
                    result = provider.call(name, args)
                except (OSError, ValueError, subprocess.SubprocessError) as exc:
                    result = f"Tool error: {exc}"
                if provider.last_call:
                    tool_trace.append({
                        "round": totals["model_requests"],
                        "elapsed_since_task_start_ms": round(
                            (time.perf_counter() - started) * 1000, 3),
                        **provider.last_call,
                    })
                outputs.append({"type": "function_call_output",
                                "call_id": call["call_id"], "output": result})
            next_input = outputs
            continue
        texts = [content.get("text", "") for item in response.get("output", [])
                 if item.get("type") == "message"
                 for content in item.get("content", []) if content.get("type") == "output_text"]
        final_text = "".join(texts)
        break
    if final_text is None:
        raise RuntimeError(f"Task {task.get('id')} exceeded 30 model requests")
    parsed = parse_final_json(final_text)
    fallbacks = source_fallbacks(tool_trace)
    usage_diagnostics = tool_usage_diagnostics(tool_trace, bool(quill_tools))
    return {
        "id": task["id"],
        "installed_guidance_used": bool(guidance and mode == "with_quill"),
        "observed": parsed["observed"],
        "duration_seconds": round(time.perf_counter() - started, 3),
        "input_tokens": totals["input_tokens"],
        "cached_input_tokens": totals["cached_input_tokens"],
        "output_tokens": totals["output_tokens"],
        "model_requests": totals["model_requests"],
        "response_ids": response_ids,
        "response_models": sorted(response_models),
        "tool_catalog_count": len(tools),
        "tool_catalog_bytes": tool_catalog_bytes,
        "quill_tools_advertised": [tool["name"] for tool in quill_tools],
        "model_rounds": model_rounds,
        "tool_trace": tool_trace,
        "source_fallback_count": len(fallbacks),
        "source_fallbacks": fallbacks,
        **usage_diagnostics,
        "quill_bypass_count": int(usage_diagnostics["quill_bypassed"]),
        "requests": source.calls + (quill.calls if quill else 0),
        "manual_verification_steps": source.manual_verification_steps,
    }


def verify_project(suite: dict[str, Any], project: Path, allow_dirty: bool) -> str:
    head = output_of(["git", "rev-parse", "HEAD"], project)
    expected = suite.get("project_revision")
    if not head:
        raise ValueError(f"Not a git project: {project}")
    if expected and head != expected:
        raise ValueError(f"Project is at {head}, suite requires {expected}")
    dirty = output_of(["git", "status", "--porcelain"], project)
    if dirty and not allow_dirty:
        raise ValueError("Project worktree is dirty; commit/stash it or pass --allow-dirty")
    return head


def main() -> int:
    args = arguments()
    suite = load_json(args.suite)
    tasks = suite.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        raise ValueError("Suite must contain a non-empty tasks array")
    if args.repetitions < 1:
        raise ValueError("--repetitions must be at least 1")
    project = args.project.resolve()
    guidance = managed_guidance(args.guidance_file) if args.guidance_file else ""
    revision = verify_project(suite, project, args.allow_dirty)
    api_key = os.environ.get(args.api_key_env)
    if not api_key:
        raise ValueError(f"Environment variable {args.api_key_env} is not set")
    client = ResponsesClient(api_key, args.api_base, args.timeout)
    quill_command = args.quill
    if os.sep in quill_command or (os.altsep and os.altsep in quill_command):
        quill_command = str(Path(quill_command).resolve())
    quill_launch_command = [quill_command]
    if args.quill_profile == "router":
        quill_launch_command.extend(["--tools", "router"])
    captures: dict[str, list[dict[str, Any]]] = {
        "with_quill": [], "without_quill": []}
    for repetition in range(args.repetitions):
        paired = {
            mode: {
                "mode": mode,
                "run_index": repetition + 1,
                "project_revision": revision,
                "quill_profile": args.quill_profile,
                "tool_selection": args.tool_selection,
                "order_schedule": [],
                "tasks": [],
            }
            for mode in captures
        }
        # Flip both task and repetition parity to distribute warm-cache/temporal bias.
        for index, task in enumerate(tasks):
            order = (("with_quill", "without_quill")
                     if (repetition + index) % 2 == 0
                     else ("without_quill", "with_quill"))
            for mode in captures:
                paired[mode]["order_schedule"].append({
                    "task": task.get("id"), "first_mode": order[0]})
            for mode in order:
                print(f"[run {repetition + 1}/{args.repetitions}] "
                      f"[{mode}] {task.get('id')}...", file=sys.stderr)
                source = SourceTools(project, args.tool_output_limit)
                if mode == "with_quill":
                    with QuillTools(quill_launch_command, project, args.timeout,
                                    args.tool_output_limit) as quill:
                        result = run_agent(client, task, mode, args.model,
                                           args.reasoning_effort, source, quill,
                                           args.tool_selection, guidance)
                else:
                    result = run_agent(client, task, mode, args.model,
                                       args.reasoning_effort, source, None)
                paired[mode]["tasks"].append(result)
        for mode in captures:
            captures[mode].append(paired[mode])
    args.output_dir.mkdir(parents=True, exist_ok=True)
    stem = args.suite.stem
    for mode, runs in captures.items():
        suffix = mode.replace("_", "-")
        path = args.output_dir / f"{stem}-{suffix}.json"
        capture = runs[0] if len(runs) == 1 else {
            "schema_version": 2,
            "mode": mode,
            "project_revision": revision,
            "quill_profile": args.quill_profile,
            "tool_selection": args.tool_selection,
            "repetitions": len(runs),
            "runs": runs,
        }
        path.write_text(json.dumps(capture, indent=2) + "\n", encoding="utf-8")
        print(path)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        print(f"Agent benchmark failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
