#!/usr/bin/env python3
"""Score pinned real-project MCP facts independently of Quill's answers.

Uses an existing index; never builds, initializes, edits, or invokes a model.
Run on disposable project copies because normal MCP startup may refresh an index.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import time
from typing import Any

from quill_benchmark import latency_summary, project_metadata
from quill_workflow_benchmark import WorkflowClient

TOOLS = {"get_symbol_at_position", "find_symbol_usages", "get_call_hierarchy",
         "find_impacted_tests", "plan_change", "search_symbols", "find_usages"}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_path(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or Path(relative).is_absolute():
        raise ValueError("Evidence path escapes project root")
    return path


def validate_manifest(manifest: dict[str, Any]) -> None:
    if manifest.get("schema_version") != 1 or not manifest.get("projects"):
        raise ValueError("Expected schema_version=1 and nonempty projects")
    ids: set[str] = set()
    for project in manifest["projects"]:
        name = project["id"]
        if name in ids or not isinstance(name, str) or not name:
            raise ValueError("Duplicate or invalid project id")
        ids.add(name)
        revision = project["revision"]
        if not isinstance(revision, str) or len(revision) != 40 or any(c not in "0123456789abcdef" for c in revision):
            raise ValueError("Pin projects to a full Git commit")
        if not project.get("cases"):
            raise ValueError("Project has no cases")
        cases: set[str] = set()
        for case in project["cases"]:
            if not isinstance(case.get("id"), str) or not case["id"] or case["id"] in cases:
                raise ValueError("Duplicate or invalid case id")
            cases.add(case["id"])
            if case.get("tool") not in TOOLS or not isinstance(case.get("arguments"), dict):
                raise ValueError("Unsupported tool or invalid arguments")
            if not case.get("evidence") or not case.get("assertions"):
                raise ValueError("Every case requires independent source evidence and assertions")
            for evidence in case["evidence"]:
                if not isinstance(evidence.get("path"), str) or not evidence["path"]:
                    raise ValueError("Evidence path is required")
                sha = evidence.get("sha256", "")
                if not isinstance(sha, str) or len(sha) != 64 or any(c not in "0123456789abcdef" for c in sha):
                    raise ValueError("Evidence requires a SHA-256 pin")
            for assertion in case["assertions"]:
                selector = assertion.get("path")
                if (not isinstance(selector, list) or not selector or
                        any(not isinstance(s, str) or not s for s in selector)):
                    raise ValueError("Assertion path must be a nonempty string-segment array")
                if assertion.get("mode") not in {"equals", "contains", "excludes"} or "expected" not in assertion:
                    raise ValueError("Invalid assertion")
                if assertion["mode"] != "equals" and not isinstance(assertion["expected"], list):
                    raise ValueError("Set assertions require an expected array")


def select(value: Any, path: list[str]) -> Any:
    """Strict projection: an empty collection is valid; a missing field is not."""
    if not path:
        return value
    segment, *rest = path
    if segment == "*":
        if not isinstance(value, list):
            raise ValueError("Wildcard requires an array")
        return [select(item, rest) for item in value]
    if not isinstance(value, dict) or segment not in value:
        raise ValueError("Missing assertion field")
    return select(value[segment], rest)


def typed_equal(left: Any, right: Any) -> bool:
    # JSON booleans must not satisfy integer expectations (True == 1 in Python).
    if type(left) is not type(right):
        return False
    if isinstance(left, list):
        return len(left) == len(right) and all(typed_equal(a, b) for a, b in zip(left, right))
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(typed_equal(left[k], right[k]) for k in left)
    return left == right


def incomplete(payload: dict[str, Any]) -> str | None:
    if payload.get("error") or payload.get("error_code"):
        return "tool_error"
    meta = payload.get("_meta")
    if not isinstance(meta, dict):
        return "freshness_unavailable"
    if any(meta.get(key) is True for key in ("stale_warning", "structure_stale", "commit_stale")):
        return "stale_index"
    if any(payload.get(key) is True for key in ("has_more", "truncated", "normalization_truncated", "traversal_truncated")):
        return "incomplete_response"
    if payload.get("answer_complete") is False:
        return "incomplete_response"
    coverage = payload.get("test_index_coverage")
    if isinstance(coverage, dict) and coverage.get("complete") is not True:
        return "test_coverage_incomplete"
    plan = payload.get("test_plan")
    if isinstance(plan, dict) and (plan.get("answer_complete") is False or plan.get("has_more") is True
                                 or plan.get("test_index_coverage", {}).get("complete") is False):
        return "test_plan_incomplete"
    return None


def score(payload: dict[str, Any], assertions: list[dict[str, Any]]) -> dict[str, Any]:
    reason = incomplete(payload)
    results = []
    for assertion in assertions:
        try:
            actual = select(payload, assertion["path"])
            expected, mode = assertion["expected"], assertion["mode"]
            if mode == "equals":
                passed = typed_equal(actual, expected)
            else:
                if not isinstance(actual, list):
                    raise ValueError("Set assertion requires an array")
                present = [any(typed_equal(value, wanted) for value in actual) for wanted in expected]
                passed = all(present) if mode == "contains" else not any(present)
            results.append({"status": "passed" if passed else "failed", "path": assertion["path"],
                            "mode": mode, "expected": expected, "actual": actual})
        except ValueError:
            results.append({"status": "unknown", "reason": "projection_unavailable", "path": assertion["path"]})
    statuses = {item["status"] for item in results}
    status = "failed" if "failed" in statuses else "unknown" if "unknown" in statuses else "passed"
    # Retain observed mismatches for diagnosis, but never attest stale/unscoped facts.
    return {"status": "unknown" if reason else status, "observed_fact_status": status,
            **({"reason": reason} if reason else {}), "assertions": results}


def complete_change_plan_tests(client: Any, payload: dict[str, Any]) -> tuple[dict[str, Any], float]:
    """Follow only Quill's exact affected-test continuation; reject moving or partial evidence."""
    plan = payload.get("test_plan")
    if not isinstance(plan, dict) or not plan.get("has_more"):
        return payload, 0.0
    tests = plan.get("tests")
    if not isinstance(tests, list) or not isinstance(plan.get("total"), int):
        raise RuntimeError("Incomplete change-plan test page")
    expected_index = payload.get("_meta", {}).get("index_id")
    if not expected_index:
        raise RuntimeError("Change-plan index identity missing")
    seen = {(test.get("class"), test.get("file")) for test in tests}
    total = plan["total"]
    extra_ms = 0.0
    while plan.get("has_more"):
        continuation = plan.get("next_page_request")
        if not isinstance(continuation, dict) or continuation.get("tool") != "find_impacted_tests":
            raise RuntimeError("Change-plan continuation missing")
        args = continuation.get("arguments")
        if not isinstance(args, dict) or args.get("offset") != len(tests) or args.get("limit", 0) < 1:
            raise RuntimeError("Change-plan continuation invalid")
        page, timing = client.call("find_impacted_tests", args)
        extra_ms += timing["latency_ms"]
        if (page.get("_meta", {}).get("index_id") != expected_index
                or page.get("targets") != args.get("targets")
                or page.get("offset") != args["offset"] or page.get("total") != total
                or page.get("answer_complete") is not True
                or page.get("test_index_coverage", {}).get("complete") is not True
                or not isinstance(page.get("tests"), list)
                or page.get("showing") != len(page["tests"])):
            raise RuntimeError("Change-plan continuation evidence changed")
        for test in page["tests"]:
            identity = (test.get("class"), test.get("file"))
            if identity in seen:
                raise RuntimeError("Duplicate affected test across pages")
            seen.add(identity)
            tests.append(test)
        if len(tests) > total or page.get("has_more") and page.get("next_offset") != len(tests):
            raise RuntimeError("Change-plan continuation did not advance")
        plan["has_more"] = page.get("has_more")
        plan["next_page_request"] = ({"tool": "find_impacted_tests", "arguments": {
            **args, "offset": page["next_offset"]}} if page.get("has_more") else None)
    if len(tests) != total:
        raise RuntimeError("Affected-test pagination did not cover the total")
    plan["showing"] = total
    plan["pagination_complete"] = True
    plan.pop("next_page_request", None)
    plan.pop("next_offset", None)
    return payload, extra_ms


def prepare_case(root: Path, case: dict[str, Any]) -> dict[str, Any]:
    for evidence in case["evidence"]:
        if digest(source_path(root, evidence["path"])) != evidence["sha256"]:
            raise ValueError("Source evidence differs from pinned input")
    arguments = dict(case["arguments"])
    if "position" in case:
        position = case["position"]
        file = source_path(root, position["path"])
        if position["path"] not in {e["path"] for e in case["evidence"]}:
            raise ValueError("Position source must be pinned as evidence")
        text = file.read_text(encoding="utf-8")
        anchor, token = position["anchor"], position["token"]
        if not anchor or not token or text.count(anchor) != 1 or anchor.count(token) != 1:
            raise ValueError("Position anchor and token must be unique")
        offset = text.index(anchor) + anchor.index(token)
        arguments.update(path=position["path"], line=text.count("\n", 0, offset) + 1,
                         # MCP positions count UTF-16 code units, as Java String does.
                         column=len(text[text.rfind("\n", 0, offset) + 1:offset].encode("utf-16-le")) // 2 + 1)
    return arguments


def input_state(root: Path, project: dict[str, Any]) -> dict[str, Any]:
    evidence = {e["path"] for c in project["cases"] for e in c["evidence"]}
    status = subprocess.run(["git", "status", "--porcelain=v1", "-z"], cwd=root,
                            capture_output=True, check=True, timeout=30).stdout
    outputs = hashlib.sha256()
    classes = sorted(file for file in root.rglob("*.class") if file.is_file())
    for file in classes:
        outputs.update(str(file.relative_to(root)).encode() + b"\0")
        outputs.update(bytes.fromhex(digest(file)))
    return {"git": project_metadata(root), "git_status_sha256": hashlib.sha256(status).hexdigest(),
            "compiled_classes": len(classes), "compiled_sha256": outputs.hexdigest(),
            "evidence": {p: digest(source_path(root, p)) for p in sorted(evidence)}}


def run_project(command: list[str], root: Path, project: dict[str, Any], samples: int,
                timeout: int, client_factory=WorkflowClient) -> dict[str, Any]:
    report: dict[str, Any] = {"id": project["id"], "cases": [], "planned_cases": len(project["cases"])}
    before = input_state(root, project)
    report["input"] = before
    if before["git"]["git_head"] != project["revision"]:
        raise ValueError("Project Git revision differs from manifest")
    if not before["compiled_classes"]:
        raise ValueError("No compiled classes available")
    prepared = [(case, prepare_case(root, case)) for case in project["cases"]]
    with client_factory(command, root, timeout) as client:
        overview, _ = client.call("get_overview", {})
        report["overview_freshness"] = overview.get("_meta", {})
        report["overview_project"] = overview.get("project", {})
        for case, arguments in prepared:
            scores, times, transport_errors = [], [], []
            for sample in range(samples):
                started = time.perf_counter()
                try:
                    payload, timing = client.call(case["tool"], arguments)
                    extra_ms = 0.0
                    if case["tool"] == "plan_change":
                        payload, extra_ms = complete_change_plan_tests(client, payload)
                    result = score(payload, case["assertions"])
                    elapsed = timing["latency_ms"] + extra_ms
                    transport_errors.append(False)
                except (RuntimeError, TimeoutError, OSError) as error:
                    # Never persist raw server messages, which may contain private code.
                    result = {"status": "unknown", "reason": type(error).__name__}
                    elapsed = (time.perf_counter() - started) * 1000
                    transport_errors.append(True)
                scores.append(result)
                times.append(elapsed)
            statuses = {s["status"] for s in scores}
            report["cases"].append({"id": case["id"], "tool": case["tool"],
                "status": "failed" if "failed" in statuses else "unknown" if "unknown" in statuses else "passed",
                "first_request_ms": times[0], "warm_latency": latency_summary(times[1:], errors=sum(transport_errors[1:])) if len(times) > 1 else None,
                "samples": scores})
    report["inputs_unchanged"] = input_state(root, project) == before
    if not report["inputs_unchanged"]:
        for case in report["cases"]:
            case.update(status="unknown", reason="inputs_changed_during_run")
    report["counts"] = {status: sum(c["status"] == status for c in report["cases"])
                        for status in ("passed", "failed", "unknown")}
    report["all_passed"] = report["inputs_unchanged"] and report["counts"]["passed"] == report["planned_cases"]
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--project", action="append", required=True, help="manifest-id=/absolute/project/path")
    parser.add_argument("--quill", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=6)
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.samples < 1 or args.timeout < 1:
        parser.error("samples and timeout must be positive")
    manifest = json.loads(args.manifest.read_text())
    validate_manifest(manifest)
    roots: dict[str, Path] = {}
    for mapping in args.project:
        name, separator, path = mapping.partition("=")
        if not separator or not path or name in roots:
            parser.error("Use unique project-id=path mappings")
        roots[name] = Path(path).resolve()
    if set(roots) != {p["id"] for p in manifest["projects"]}:
        parser.error("Provide every manifest project exactly once")
    command = [str(args.quill.resolve(strict=True))]
    version = subprocess.run(command + ["--version"], text=True, capture_output=True, check=True, timeout=10).stdout.strip()
    report: dict[str, Any] = {"schema_version": 1, "quill_version": version,
        "binary_sha256": digest(Path(command[0])), "manifest_sha256": digest(args.manifest),
        "host": {"system": platform.system(), "machine": platform.machine()},
        "projects": [], "limitations": ["Selected source-confirmed facts, not exhaustive usages or impact coverage",
            "No model evaluation or project tests executed", "First request may benefit from MCP startup warmup"]}
    for project in manifest["projects"]:
        try:
            report["projects"].append(run_project(command, roots[project["id"]], project, args.samples, args.timeout))
        except (OSError, ValueError, RuntimeError, TimeoutError) as error:
            report["projects"].append({"id": project["id"], "all_passed": False,
                "status": "unavailable", "reason": type(error).__name__, "planned_cases": len(project["cases"])})
    report["all_passed"] = all(p["all_passed"] for p in report["projects"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"all_passed": report["all_passed"], "output": str(args.output)}))
    return 0 if report["all_passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
