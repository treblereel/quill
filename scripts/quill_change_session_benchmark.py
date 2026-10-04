#!/usr/bin/env python3
"""Compare split change planning/verification with the combined stateless session."""

import argparse
import datetime as dt
import json
import statistics
import sys
from pathlib import Path
from typing import Any

from quill_workflow_benchmark import WorkflowClient, project_metadata


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--quill", default="quill")
    parser.add_argument("--target", default="io.casehub.engine.internal.engine.DefaultCaseDefinitionRegistry")
    parser.add_argument("--change", default="Add validation while preserving registry behavior")
    parser.add_argument("--limit", type=int, default=10)
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--request-timeout", type=float, default=30.0)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def semantic_contract(plan: dict[str, Any], verification: dict[str, Any],
                      session: dict[str, Any]) -> dict[str, bool]:
    return {
        "primary_changes": plan.get("primary_changes")
        == session.get("plan", {}).get("primary_changes"),
        "dependency_review": plan.get("dependency_review")
        == session.get("plan", {}).get("dependency_review"),
        "verdict": verification.get("verdict")
        == session.get("verification", {}).get("verdict"),
        "blockers": verification.get("blockers")
        == session.get("verification", {}).get("blockers"),
        "verification_plan": verification.get("verification_plan")
        == session.get("verification_plan"),
        "next_actions": actions_match(plan, verification, session),
    }


def summary_contract(plan: dict[str, Any], verification: dict[str, Any],
                     session: dict[str, Any]) -> dict[str, bool]:
    split_primary = [(item.get("class"), item.get("file"))
                     for item in plan.get("primary_changes", [])]
    session_primary = [(item.get("class"), item.get("file"))
                       for item in session.get("plan", {}).get("primary_changes", [])]
    scopes = [item.get("scope")
              for item in session.get("verification_plan", {}).get("commands", [])]
    return {
        "primary_files": split_primary == session_primary,
        "verdict": verification.get("verdict")
        == session.get("verification", {}).get("verdict"),
        "blockers": verification.get("blockers")
        == session.get("verification", {}).get("blockers"),
        "next_actions": actions_match(plan, verification, session),
        "quick_compile_only": all(scope == "quick_compile" for scope in scopes),
        "omissions_declared": bool(session.get("omitted_sections")),
    }


def auto_contract(plan: dict[str, Any], verification: dict[str, Any],
                  session: dict[str, Any]) -> dict[str, bool]:
    planned = session.get("phase") == "planned"
    expected_view = "plan" if planned else "verification"
    return {
        "phase_view": session.get("view") == expected_view,
        "single_phase_payload": ("plan" in session) != ("verification" in session),
        "next_actions": actions_match(plan, verification, session),
        "omissions_declared": bool(session.get("omitted_sections")),
    }


def actions_match(plan: dict[str, Any], verification: dict[str, Any],
                  session: dict[str, Any]) -> bool:
    expected = (plan.get("sequence", []) if session.get("phase") == "planned"
                else verification.get("next_actions", []))
    if session.get("phase") == "review_required":
        review_reasons = {"UNRESOLVED_TARGETS", "INCOMPLETE_TEST_COVERAGE",
                          "TARGET_INFERENCE_TRUNCATED", "WORKTREE_TRUNCATED",
                          "MODULE_SELECTION_INCOMPLETE"}
        expected = sorted(expected, key=lambda action: action.get("reason") not in review_reasons)
    def normalize(actions):
        return [{key: value for key, value in action.items()
                 if key not in {"action_id", "order"}} for action in actions]
    return normalize(expected) == normalize(session.get("next_actions", []))


def aggregate(samples: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "samples": len(samples),
        "tool_calls_per_sample": samples[0]["tool_calls"],
        "latency_ms_median": round(statistics.median(
            sample["latency_ms"] for sample in samples), 3),
        "response_bytes_median": round(statistics.median(
            sample["response_bytes"] for sample in samples)),
    }


def main() -> int:
    args = arguments()
    if args.samples < 1:
        raise ValueError("--samples must be positive")
    project = args.project.resolve()
    command = [str(Path(args.quill).resolve())] if Path(args.quill).exists() else [args.quill]
    split_samples: list[dict[str, Any]] = []
    session_samples: list[dict[str, Any]] = []
    summary_samples: list[dict[str, Any]] = []
    auto_samples: list[dict[str, Any]] = []
    contracts: list[dict[str, bool]] = []
    summary_contracts: list[dict[str, bool]] = []
    auto_contracts: list[dict[str, bool]] = []
    plan_args = {"targets": [args.target], "change": args.change, "limit": args.limit}
    session_args = {**plan_args, "detail": "full", "view": "all"}
    summary_args = {**session_args, "detail": "summary", "view": "all"}
    auto_args = {**session_args, "detail": "summary", "view": "auto"}
    verify_args = {"targets": [args.target], "limit": args.limit}

    with WorkflowClient(command, project, args.request_timeout) as client:
        for _ in range(args.samples):
            plan, plan_trace = client.call("plan_change", plan_args)
            verification, verify_trace = client.call("verify_change", verify_args)
            session, session_trace = client.call("change_session", session_args)
            summary, summary_trace = client.call("change_session", summary_args)
            auto, auto_trace = client.call("change_session", auto_args)
            split_samples.append({
                "tool_calls": 2,
                "latency_ms": plan_trace["latency_ms"] + verify_trace["latency_ms"],
                "response_bytes": plan_trace["response_bytes"] + verify_trace["response_bytes"],
            })
            session_samples.append({
                "tool_calls": 1,
                "latency_ms": session_trace["latency_ms"],
                "response_bytes": session_trace["response_bytes"],
            })
            summary_samples.append({
                "tool_calls": 1,
                "latency_ms": summary_trace["latency_ms"],
                "response_bytes": summary_trace["response_bytes"],
            })
            auto_samples.append({
                "tool_calls": 1,
                "latency_ms": auto_trace["latency_ms"],
                "response_bytes": auto_trace["response_bytes"],
            })
            contracts.append(semantic_contract(plan, verification, session))
            summary_contracts.append(summary_contract(plan, verification, summary))
            auto_contracts.append(auto_contract(plan, verification, auto))

    split = aggregate(split_samples)
    combined = aggregate(session_samples)
    summary = aggregate(summary_samples)
    auto = aggregate(auto_samples)
    comparison = {
        "tool_call_reduction": split["tool_calls_per_sample"]
        - combined["tool_calls_per_sample"],
        "latency_delta_ms": round(combined["latency_ms_median"]
                                  - split["latency_ms_median"], 3),
        "response_bytes_delta": combined["response_bytes_median"]
        - split["response_bytes_median"],
        "semantic_parity": all(all(contract.values()) for contract in contracts),
        "contract_flags": contracts[-1],
        "summary_response_bytes_delta": summary["response_bytes_median"]
        - split["response_bytes_median"],
        "summary_contract_complete": all(
            all(contract.values()) for contract in summary_contracts),
        "summary_contract_flags": summary_contracts[-1],
        "auto_response_bytes_delta": auto["response_bytes_median"]
        - split["response_bytes_median"],
        "auto_contract_complete": all(
            all(contract.values()) for contract in auto_contracts),
        "auto_contract_flags": auto_contracts[-1],
    }
    result = {
        "schema_version": 1,
        "generated_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "project": str(project),
        "project_metadata": project_metadata(project),
        "target": args.target,
        "change": args.change,
        "split": split,
        "session": combined,
        "summary": summary,
        "auto": auto,
        "comparison": comparison,
    }
    output = args.output or Path("target/benchmarks") / (
        f"quill-change-session-{dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(comparison, indent=2))
    print(f"Report: {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
