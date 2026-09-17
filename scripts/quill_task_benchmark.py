#!/usr/bin/env python3
"""Score paired, task-level agent runs performed with and without Quill."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Any


NUMERIC_FIELDS = (
    "duration_seconds",
    "input_tokens",
    "cached_input_tokens",
    "output_tokens",
    "model_requests",
    "requests",
    "manual_verification_steps",
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", type=Path, required=True,
                        help="Task suite containing prompts and expected facts")
    parser.add_argument("--with-quill", type=Path, required=True,
                        help="Captured results from the Quill-enabled run")
    parser.add_argument("--without-quill", type=Path, required=True,
                        help="Captured results from the baseline run")
    parser.add_argument("--output", type=Path,
                        help="Optional JSON report path")
    return parser.parse_args()


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object in {path}")
    return value


def _tasks_by_id(document: dict[str, Any], label: str) -> dict[str, dict[str, Any]]:
    tasks = document.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        raise ValueError(f"{label} must contain a non-empty tasks array")
    result: dict[str, dict[str, Any]] = {}
    for task in tasks:
        if not isinstance(task, dict) or not isinstance(task.get("id"), str):
            raise ValueError(f"Every {label} task must have a string id")
        task_id = task["id"]
        if task_id in result:
            raise ValueError(f"Duplicate task id in {label}: {task_id}")
        result[task_id] = task
    return result


def score_run(suite: dict[str, Any], run: dict[str, Any]) -> dict[str, Any]:
    expected_revision = suite.get("project_revision")
    if expected_revision and run.get("project_revision") != expected_revision:
        raise ValueError(
            f"Run revision {run.get('project_revision')} does not match suite "
            f"revision {expected_revision}")
    expected_tasks = _tasks_by_id(suite, "suite")
    actual_tasks = _tasks_by_id(run, "run")
    unknown = sorted(actual_tasks.keys() - expected_tasks.keys())
    if unknown:
        raise ValueError(f"Run contains unknown task ids: {', '.join(unknown)}")

    totals: dict[str, float] = {field: 0 for field in NUMERIC_FIELDS}
    totals.update({"correct_facts": 0, "incorrect_facts": 0,
                   "missing_facts": 0, "complete_tasks": 0})
    task_scores = []
    for task_id, expected_task in expected_tasks.items():
        expected = expected_task.get("expected")
        if not isinstance(expected, dict) or not expected:
            raise ValueError(f"Suite task {task_id} must contain expected facts")
        actual_task = actual_tasks.get(task_id, {})
        observed = actual_task.get("observed", {})
        if not isinstance(observed, dict):
            raise ValueError(f"Run task {task_id} observed must be an object")

        correct = []
        incorrect = []
        missing = []
        for fact, expected_value in expected.items():
            if fact not in observed:
                missing.append(fact)
            elif observed[fact] == expected_value:
                correct.append(fact)
            else:
                incorrect.append({"fact": fact, "expected": expected_value,
                                  "observed": observed[fact]})
        complete = not incorrect and not missing
        totals["correct_facts"] += len(correct)
        totals["incorrect_facts"] += len(incorrect)
        totals["missing_facts"] += len(missing)
        totals["complete_tasks"] += int(complete)
        measurements = {}
        for field in NUMERIC_FIELDS:
            value = actual_task.get(field, 0)
            if not isinstance(value, (int, float)) or value < 0:
                raise ValueError(f"Run task {task_id} {field} must be non-negative")
            measurements[field] = value
            totals[field] += value
        if measurements["cached_input_tokens"] > measurements["input_tokens"]:
            raise ValueError(
                f"Run task {task_id} cached_input_tokens exceeds input_tokens")
        task_scores.append({
            "task_id": task_id,
            "complete": complete,
            "correct_facts": correct,
            "incorrect_facts": incorrect,
            "missing_facts": missing,
            **measurements,
        })

    fact_count = (totals["correct_facts"] + totals["incorrect_facts"]
                  + totals["missing_facts"])
    totals["fact_accuracy"] = round(totals["correct_facts"] / fact_count, 4)
    totals["task_completion_rate"] = round(
        totals["complete_tasks"] / len(expected_tasks), 4)
    totals["total_tokens"] = totals["input_tokens"] + totals["output_tokens"]
    totals["uncached_tokens"] = (totals["input_tokens"]
                                 - totals["cached_input_tokens"]
                                 + totals["output_tokens"])
    return {
        "mode": run.get("mode", "unspecified"),
        "task_count": len(expected_tasks),
        "totals": totals,
        "tasks": task_scores,
    }


def compare(with_quill: dict[str, Any], without_quill: dict[str, Any]) -> dict[str, Any]:
    left = with_quill["totals"]
    right = without_quill["totals"]
    deltas = {}
    for field in ("fact_accuracy", "task_completion_rate", "duration_seconds",
                  "total_tokens", "uncached_tokens", "model_requests", "requests",
                  "manual_verification_steps",
                  "incorrect_facts", "missing_facts"):
        deltas[field] = round(left[field] - right[field], 4)
    return {
        "with_quill_minus_without_quill": deltas,
        "interpretation": {
            "positive_accuracy_delta_is_better": True,
            "negative_resource_delta_is_better": True,
            "compression_metadata_is_not_used_as_agent_token_savings": True,
        },
    }


def build_report(suite: dict[str, Any], with_run: dict[str, Any],
                 without_run: dict[str, Any]) -> dict[str, Any]:
    with_score = score_run(suite, with_run)
    without_score = score_run(suite, without_run)
    return {
        "schema_version": 1,
        "suite": suite.get("name", "unnamed"),
        "project_revision": suite.get("project_revision"),
        "with_quill": with_score,
        "without_quill": without_score,
        "comparison": compare(with_score, without_score),
    }


def print_report(report: dict[str, Any]) -> None:
    print(f"Task benchmark: {report['suite']}")
    print("mode           accuracy  complete  seconds  tokens  uncached  model req  tools  manual  wrong  missing")
    for key in ("with_quill", "without_quill"):
        totals = report[key]["totals"]
        print(f"{key:<14} {totals['fact_accuracy'] * 100:>7.1f}%  "
              f"{totals['task_completion_rate'] * 100:>7.1f}%  "
              f"{totals['duration_seconds']:>7.2f}  {totals['total_tokens']:>6.0f}  "
              f"{totals['uncached_tokens']:>8.0f}  {totals['model_requests']:>9.0f}  "
              f"{totals['requests']:>5.0f}  {totals['manual_verification_steps']:>6.0f}  "
              f"{totals['incorrect_facts']:>5.0f}  {totals['missing_facts']:>7.0f}")


def main() -> int:
    args = arguments()
    report = build_report(load_json(args.suite), load_json(args.with_quill),
                          load_json(args.without_quill))
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print_report(report)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Task benchmark failed: {exc}", file=sys.stderr)
        raise SystemExit(2)
