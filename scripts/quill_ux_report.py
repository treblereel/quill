#!/usr/bin/env python3
"""Summarize Quill's local, content-free MCP UX telemetry."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from statistics import median
from typing import Any


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("telemetry", type=Path,
                        help=".quill/telemetry/mcp-tools.jsonl")
    parser.add_argument("--baseline", type=Path,
                        help="Optional earlier telemetry log to compare")
    parser.add_argument("--output", type=Path, help="Optional JSON report path")
    return parser.parse_args()


def load_events(path: Path) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = json.loads(line)
        if not isinstance(value, dict) or not isinstance(value.get("tool"), str):
            raise ValueError(f"Invalid telemetry event at {path}:{number}")
        events.append(value)
    if not events:
        raise ValueError(f"No telemetry events in {path}")
    return events


def percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def distribution(values: list[float]) -> dict[str, float]:
    return {
        "median": round(median(values), 3),
        "p95": round(percentile(values, 0.95), 3),
        "max": round(max(values), 3),
    }


def summarize(events: list[dict[str, Any]]) -> dict[str, Any]:
    by_tool: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for event in events:
        tool = event.get("routed_tool") or event["tool"]
        by_tool[str(tool)].append(event)

    tools: list[dict[str, Any]] = []
    for name, rows in sorted(by_tool.items(), key=lambda item: (-len(item[1]), item[0])):
        durations = [float(row.get("duration_ms", 0)) for row in rows]
        response_sizes = [float(row.get("response_bytes", 0)) for row in rows]
        tools.append({
            "tool": name,
            "calls": len(rows),
            "errors": sum(row.get("status") == "error" for row in rows),
            "stale": sum(bool(row.get("stale_warning")) for row in rows),
            "truncated": sum(bool(row.get("truncated") or row.get("has_more")) for row in rows),
            "duration_ms": distribution(durations),
            "response_bytes": distribution(response_sizes),
        })

    durations = [float(event.get("duration_ms", 0)) for event in events]
    response_sizes = [float(event.get("response_bytes", 0)) for event in events]
    statuses = Counter(str(event.get("status", "unknown")) for event in events)
    sessions = {event.get("session_id") for event in events if event.get("session_id")}
    return {
        "schema_version": 1,
        "calls": len(events),
        "sessions": len(sessions),
        "unique_tools": len(by_tool),
        "statuses": dict(sorted(statuses.items())),
        "stale_responses": sum(bool(event.get("stale_warning")) for event in events),
        "truncated_responses": sum(
            bool(event.get("truncated") or event.get("has_more")) for event in events),
        "duration_ms": distribution(durations),
        "response_bytes": distribution(response_sizes),
        "total_response_bytes": int(sum(response_sizes)),
        "tools": tools,
    }


def compare(current: dict[str, Any], baseline: dict[str, Any]) -> dict[str, Any]:
    return {
        "calls": current["calls"] - baseline["calls"],
        "unique_tools": current["unique_tools"] - baseline["unique_tools"],
        "errors": current["statuses"].get("error", 0)
                  - baseline["statuses"].get("error", 0),
        "stale_responses": current["stale_responses"] - baseline["stale_responses"],
        "truncated_responses": current["truncated_responses"]
                               - baseline["truncated_responses"],
        "duration_median_ms": round(
            current["duration_ms"]["median"] - baseline["duration_ms"]["median"], 3),
        "response_median_bytes": round(
            current["response_bytes"]["median"]
            - baseline["response_bytes"]["median"], 3),
    }


def print_report(report: dict[str, Any]) -> None:
    current = report["current"]
    print(f"Quill UX telemetry: {current['calls']} calls, "
          f"{current['sessions']} sessions, {current['unique_tools']} tools")
    print("tool                              calls errors stale trunc  p50 ms  p95 ms  p50 bytes")
    for row in current["tools"]:
        print(f"{row['tool']:<33} {row['calls']:>5} {row['errors']:>6} "
              f"{row['stale']:>5} {row['truncated']:>5} "
              f"{row['duration_ms']['median']:>7.1f} "
              f"{row['duration_ms']['p95']:>7.1f} "
              f"{row['response_bytes']['median']:>10.0f}")
    if "delta" in report:
        print("delta vs baseline: " + json.dumps(report["delta"], sort_keys=True))


def main() -> int:
    args = arguments()
    current = summarize(load_events(args.telemetry))
    report: dict[str, Any] = {"current": current}
    if args.baseline:
        baseline = summarize(load_events(args.baseline))
        report["baseline"] = baseline
        report["delta"] = compare(current, baseline)
    print_report(report)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"UX report failed: {error}", file=__import__("sys").stderr)
        raise SystemExit(2)
