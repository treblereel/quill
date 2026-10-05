#!/usr/bin/env python3
"""Compare native MCP profile catalogs and equivalent query paths without inference."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import statistics
import time

from quill_adoption_scenarios import fixture_snapshot
from quill_benchmark import project_metadata
from quill_workflow_benchmark import WorkflowClient


PROFILES = ("full", "core", "router")


def size(value):
    return len(json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


def semantic(value):
    """Exclude only index provenance; retain warnings, gates, facts and completeness."""
    if isinstance(value, dict):
        return {key: semantic(item) for key, item in value.items() if key != "_meta"}
    if isinstance(value, list):
        return [semantic(item) for item in value]
    return value


def digest(value):
    return hashlib.sha256(json.dumps(semantic(value), sort_keys=True,
                                    separators=(",", ":")).encode()).hexdigest()


def payload(result):
    if result.get("isError") is True:
        raise ValueError("MCP tool returned isError")
    value = result.get("structuredContent")
    if not isinstance(value, dict):
        for content in result.get("content", []):
            if content.get("type") == "text" and content.get("text"):
                value = json.loads(content["text"])
                break
    if not isinstance(value, dict) or not value or value.get("error") or value.get("error_code"):
        raise ValueError("Missing or failed semantic result")
    projects = value.get("projects")
    if isinstance(projects, list) and projects and all(
            item.get("error") or item.get("data", {}).get("error")
            or item.get("data", {}).get("error_code") for item in projects):
        raise ValueError("All projects returned semantic errors")
    return value


def measured_call(client, name, arguments):
    start = time.perf_counter()
    result = client.request("tools/call", {"name": name, "arguments": arguments})
    elapsed = (time.perf_counter() - start) * 1000
    return payload(result), {"tool": name, "latency_ms": round(elapsed, 3),
                             "serialized_result_bytes": size(result)}


def query(client, catalog, name, arguments):
    trace = []
    if name in catalog:
        value, measurement = measured_call(client, name, arguments)
    elif {"search_tools", "execute_tool"}.issubset(catalog):
        found, discovery = measured_call(client, "search_tools", {"query": name, "limit": 30})
        trace.append(discovery)
        if not any(tool.get("name") == name for tool in found.get("tools", [])):
            raise ValueError("Router discovery did not return the exact semantic tool")
        value, measurement = measured_call(client, "execute_tool", {"name": name, "arguments": arguments})
    else:
        return {"supported": False, "reason": "not_in_exposed_catalog", "trace": []}
    trace.append(measurement)
    return {"supported": True, "semantic_digest": digest(value), "trace": trace,
            "tool_calls": len(trace), "latency_ms": round(sum(call["latency_ms"] for call in trace), 3),
            "serialized_result_bytes": sum(call["serialized_result_bytes"] for call in trace)}


def workloads(target):
    return {
        "orientation": ("get_overview", {}),
        "navigation": ("get_symbol_details", {"target": target, "include_members": False}),
        "dependencies": ("get_dependencies", {"target": target}),
        "change_workflow": ("change_session", {"targets": [target],
                            "change": "Review an internal implementation change preserving public signatures"}),
        "history": ("get_recent_changes", {"commits": 1}),
    }


def run_profile(command, project, profile, timeout, target):
    start = time.perf_counter()
    client = WorkflowClient(command + ["--tools", profile], project, timeout)
    try:
        client.__enter__()
        startup_ms = (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        catalog = client.request("tools/list", {})
        catalog_ms = (time.perf_counter() - start) * 1000
        tools = catalog.get("tools")
        if not isinstance(tools, list) or not tools:
            raise ValueError("Empty or invalid tool catalog")
        names = {tool["name"] for tool in tools}
        result = {"profile": profile, "startup_ms": round(startup_ms, 3),
                  "catalog_latency_ms": round(catalog_ms, 3), "catalog_tools": len(tools),
                  "catalog_tools_bytes": size(tools),
                  "server_instructions_bytes": len(client.initialized.get("instructions", "").encode()),
                  "server_info": client.initialized.get("serverInfo"),
                  "protocol_version": client.initialized.get("protocolVersion"),
                  "workloads": {}}
        for workload, (name, arguments) in workloads(target).items():
            result["workloads"][workload] = query(client, names, name, arguments)
        return result
    finally:
        # Context-manager __exit__ is not invoked if initialization itself fails.
        client.client.close()


def summarize(runs, expected):
    grouped = {profile: [run for run in runs if run["profile"] == profile] for profile in PROFILES}
    summary = {"expected_runs": expected, "completed_runs": len(runs), "profiles": {},
               "equivalence": {}, "all_passed": False}
    for profile, samples in grouped.items():
        if not samples:
            continue
        fields = ("startup_ms", "catalog_latency_ms", "catalog_tools", "catalog_tools_bytes",
                  "server_instructions_bytes")
        aggregate = {key + "_median": statistics.median(item[key] for item in samples) for key in fields}
        aggregate["samples"] = len(samples)
        aggregate["workloads"] = {}
        for name in samples[0]["workloads"]:
            values = [sample["workloads"][name] for sample in samples]
            supported = all(value["supported"] for value in values)
            aggregate["workloads"][name] = {"supported": supported}
            if supported:
                aggregate["workloads"][name].update({key + "_median": statistics.median(
                    value[key] for value in values) for key in
                    ("latency_ms", "tool_calls", "serialized_result_bytes")})
        summary["profiles"][profile] = aggregate
    for name in workloads("unused"):
        participating = [profile for profile, samples in grouped.items()
                         if samples and all(sample["workloads"][name]["supported"] for sample in samples)]
        hashes = {sample["workloads"][name]["semantic_digest"] for profile in participating
                  for sample in grouped[profile]}
        expected_profiles = ["full", "router"] if name == "history" else list(PROFILES)
        summary["equivalence"][name] = {"profiles": participating,
            "expected_profiles": expected_profiles, "equal": len(hashes) == 1,
            "coverage_complete": set(participating) == set(expected_profiles)}
    summary["all_passed"] = len(runs) == expected and expected > 0 and all(
        group["equal"] and group["coverage_complete"] for group in summary["equivalence"].values())
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", type=Path, required=True)
    parser.add_argument("--target", required=True, help="Indexed class used by all semantic paths")
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--samples", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.samples < 1 or args.timeout < 1:
        raise ValueError("samples and timeout must be positive")
    project = args.project.resolve()
    output = args.output.resolve()
    if output.is_relative_to(project):
        raise ValueError("Report must be outside the measured project to preserve fixture inputs")
    with args.quill.resolve().open("rb") as binary:
        binary_hash = hashlib.file_digest(binary, "sha256").hexdigest()
    report = {"schema_version": 1, "scope": "native MCP only; no inference, tokenizer or client adoption claim",
              "created_at": datetime.now(timezone.utc).isoformat(),
              "host": {"platform": platform.platform(), "machine": platform.machine()},
              "project": project_metadata(project), "quill_binary_sha256": binary_hash,
              "latency_scope": "fresh process per run; existing index and uncontrolled OS caches, not cold indexing",
              "byte_scope": "UTF-8 compact JSON serialization, not raw wire bytes or model tokens",
              "equivalence_scope": "exact payload equality excluding only recursively nested _meta",
              "router_scope": "one uncached exact-name search_tools plus execute_tool; not model-driven discovery",
              "target": args.target, "runs": [], "summary": summarize([], args.samples * 3)}
    before = fixture_snapshot(project)
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        for sample in range(args.samples):
            # Rotate profile order; every path gets fresh process startup and first-query costs.
            order = PROFILES[sample % 3:] + PROFILES[:sample % 3]
            for profile in order:
                print(f"Sample {sample + 1}: {profile}", flush=True)
                run = run_profile([str(args.quill.resolve())], project, profile, args.timeout, args.target)
                run["sample"] = sample + 1
                report["runs"].append(run)
                report["summary"] = summarize(report["runs"], args.samples * 3)
                report["fixture_unchanged"] = before == fixture_snapshot(project)
                report["summary"]["all_passed"] &= report["fixture_unchanged"]
                output.write_text(json.dumps(report, indent=2) + "\n")
                if not report["fixture_unchanged"]:
                    return 1
    except Exception as failure:
        # Do not retain remote result bodies, arguments, source content, or launcher stderr.
        report["failure"] = type(failure).__name__
        report["fixture_unchanged"] = before == fixture_snapshot(project)
        report["summary"]["all_passed"] = False
        output.write_text(json.dumps(report, indent=2) + "\n")
        return 1
    print(json.dumps(report["summary"], indent=2))
    return 0 if report["summary"]["all_passed"] and report.get("fixture_unchanged") else 1


if __name__ == "__main__":
    raise SystemExit(main())
