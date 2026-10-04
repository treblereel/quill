#!/usr/bin/env python3
"""Benchmark Quill's change workflow against equivalent atomic MCP queries."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import statistics
import sys
import time
from typing import Any

from quill_benchmark import McpClient, project_metadata


DEFAULT_TARGET = "io.casehub.engine.internal.engine.DefaultCaseDefinitionRegistry"
DEFAULT_CHANGE = "Add a new configuration resolution strategy"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--quill", default=os.environ.get("QUILL_BIN", "quill"))
    parser.add_argument("--target", default=DEFAULT_TARGET)
    parser.add_argument("--change", default=DEFAULT_CHANGE)
    parser.add_argument("--samples", type=int, default=5)
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--request-timeout", type=int, default=60)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


class WorkflowClient:
    def __init__(self, command: list[str], project: Path, timeout: int):
        self.client = McpClient(command, project, timeout)
        self.timeout = timeout
        self.next_id = 1

    def __enter__(self) -> "WorkflowClient":
        response = self.request("initialize", {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "quill-workflow-benchmark", "version": "1"},
        })
        if "protocolVersion" not in response:
            raise RuntimeError(f"MCP initialization failed: {response}")
        self.initialized = response
        assert self.client.process.stdin is not None
        self.client.process.stdin.write(
            '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n')
        self.client.flush()
        return self

    def __exit__(self, *_: object) -> None:
        self.client.close()

    def request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        request_id = self.next_id
        self.next_id += 1
        self.client.send(request_id, method, params)
        self.client.flush()
        response = self.client.receive({request_id}, self.timeout)[request_id]
        if "error" in response:
            raise RuntimeError(f"MCP {method} failed: {response['error']}")
        result = response.get("result")
        if not isinstance(result, dict):
            raise RuntimeError(f"MCP {method} returned an invalid result")
        return result

    def call(self, name: str, arguments: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
        started = time.perf_counter()
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        elapsed_ms = (time.perf_counter() - started) * 1000
        structured = result.get("structuredContent")
        if result.get("isError") is True or not isinstance(structured, dict):
            raise RuntimeError(f"Quill tool {name} failed: {result}")
        payload_bytes = len(json.dumps(
            structured, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        return structured, {
            "tool": name,
            "latency_ms": round(elapsed_ms, 3),
            "response_bytes": payload_bytes,
        }


def legacy_calls(target: str, limit: int) -> list[tuple[str, dict[str, Any]]]:
    return [
        ("get_symbol_details", {"target": target, "include_members": False}),
        ("find_usages", {"target": target, "limit": limit}),
        ("find_impacted_tests", {
            "targets": [target], "transitive": True, "max_depth": 3, "limit": limit,
        }),
        ("assess_change_risk", {"target": target}),
    ]


def workflow_calls(target: str, change: str, limit: int) -> list[tuple[str, dict[str, Any]]]:
    return [
        ("plan_change", {"targets": [target], "change": change, "limit": limit}),
    ]


def contract_flags(name: str, payload: dict[str, Any]) -> dict[str, bool]:
    if name == "legacy":
        return {
            "symbol": "get_symbol_details" in payload,
            "usages": "find_usages" in payload,
            "tests": "find_impacted_tests" in payload,
            "risk": "assess_change_risk" in payload,
        }
    return {
        "primary_files": bool(payload.get("primary_changes")),
        "dependency_review": isinstance(payload.get("dependency_review"), list),
        "tests": isinstance(payload.get("test_plan"), dict),
        "risk": any("risk_score" in item for item in payload.get("primary_changes", [])),
        "freshness": isinstance(payload.get("_meta"), dict),
        "ordered_sequence": bool(payload.get("sequence")),
        "verification_plan": isinstance(payload.get("verification_plan"), dict),
    }


def verification_plan_summary(payload: dict[str, Any]) -> dict[str, Any] | None:
    plan = payload.get("verification_plan")
    if not isinstance(plan, dict):
        return None
    runner = plan.get("runner") if isinstance(plan.get("runner"), dict) else {}
    commands = plan.get("commands") if isinstance(plan.get("commands"), list) else []
    first_command = commands[0] if commands and isinstance(commands[0], dict) else {}
    return {
        "runner_kind": runner.get("kind"),
        "runner_source": runner.get("source"),
        "runner_available": runner.get("available"),
        "command_count": len(commands),
        "first_argv": first_command.get("argv"),
    }


def run_path(client: WorkflowClient, calls: list[tuple[str, dict[str, Any]]],
             mode: str) -> dict[str, Any]:
    traces: list[dict[str, Any]] = []
    payloads: dict[str, Any] = {}
    for name, call_arguments in calls:
        payload, trace = client.call(name, call_arguments)
        traces.append(trace)
        payloads[name] = payload
    evidence = payloads if mode == "legacy" else payloads[calls[-1][0]]
    flags = contract_flags(mode, evidence)
    return {
        "tool_calls": len(traces),
        "latency_ms": round(sum(item["latency_ms"] for item in traces), 3),
        "response_bytes": sum(item["response_bytes"] for item in traces),
        "contract_complete": all(flags.values()),
        "contract_flags": flags,
        "trace": traces,
        "result_status": evidence.get("plan_status") if mode != "legacy" else None,
        "verification_plan": verification_plan_summary(evidence),
    }


def aggregate(samples: list[dict[str, Any]]) -> dict[str, Any]:
    latencies = [sample["latency_ms"] for sample in samples]
    response_bytes = [sample["response_bytes"] for sample in samples]
    return {
        "samples": len(samples),
        "tool_calls_per_sample": samples[0]["tool_calls"],
        "latency_ms_median": round(statistics.median(latencies), 3),
        "latency_ms_min": round(min(latencies), 3),
        "latency_ms_max": round(max(latencies), 3),
        "response_bytes_median": round(statistics.median(response_bytes)),
        "contract_complete": all(sample["contract_complete"] for sample in samples),
        "contract_flags": samples[-1]["contract_flags"],
        "last_trace": samples[-1]["trace"],
        "result_status": samples[-1].get("result_status"),
        "verification_plan": samples[-1].get("verification_plan"),
    }


def comparison(legacy: dict[str, Any], workflow: dict[str, Any]) -> dict[str, Any]:
    legacy_latency = legacy["latency_ms_median"]
    workflow_latency = workflow["latency_ms_median"]
    legacy_bytes = legacy["response_bytes_median"]
    workflow_bytes = workflow["response_bytes_median"]
    return {
        "tool_call_reduction": legacy["tool_calls_per_sample"]
        - workflow["tool_calls_per_sample"],
        "tool_call_reduction_percent": round(
            (1 - workflow["tool_calls_per_sample"] / legacy["tool_calls_per_sample"]) * 100, 2),
        "latency_delta_ms": round(workflow_latency - legacy_latency, 3),
        "latency_delta_percent": round((workflow_latency / legacy_latency - 1) * 100, 2),
        "response_bytes_delta": workflow_bytes - legacy_bytes,
        "response_bytes_delta_percent": round((workflow_bytes / legacy_bytes - 1) * 100, 2),
        "claim_scope": "deterministic MCP calls only; no model-token or answer-accuracy claim",
    }


def main() -> int:
    args = arguments()
    if args.samples < 1:
        raise ValueError("--samples must be positive")
    project = args.project.resolve()
    command = [str(Path(args.quill).resolve())] if Path(args.quill).exists() else [args.quill]
    legacy_samples: list[dict[str, Any]] = []
    workflow_samples: list[dict[str, Any]] = []
    verification: dict[str, Any]
    with WorkflowClient(command, project, args.request_timeout) as client:
        for _ in range(args.samples):
            legacy_samples.append(run_path(
                client, legacy_calls(args.target, args.limit), "legacy"))
            workflow_samples.append(run_path(
                client, workflow_calls(args.target, args.change, args.limit), "workflow"))
        verification_payload, verification_trace = client.call("verify_change", {
            "targets": [args.target], "limit": args.limit,
        })
        verification = {
            "verdict": verification_payload.get("verdict"),
            "verified": verification_payload.get("verified"),
            "blocker_codes": [item.get("code")
                              for item in verification_payload.get("blockers", [])],
            "next_actions": [item.get("action")
                             for item in verification_payload.get("next_actions", [])],
            "trace": verification_trace,
        }

    legacy = aggregate(legacy_samples)
    workflow = aggregate(workflow_samples)
    result = {
        "schema_version": 1,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "project": str(project),
        "project_metadata": project_metadata(project),
        "target": args.target,
        "change": args.change,
        "legacy": legacy,
        "workflow": workflow,
        "comparison": comparison(legacy, workflow),
        "verification": verification,
    }
    output = args.output or Path("target/benchmarks") / (
        f"quill-workflow-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(result["comparison"], indent=2))
    print(f"Legacy contract complete: {legacy['contract_complete']}")
    print(f"Workflow contract complete: {workflow['contract_complete']}")
    print(f"Verification verdict: {verification['verdict']}")
    print(f"Report: {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
