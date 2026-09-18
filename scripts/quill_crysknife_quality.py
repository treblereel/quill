#!/usr/bin/env python3
"""Run deterministic Quill quality checks against the pinned Crysknife revision."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import time
from typing import Any

from quill_benchmark import IndexSandbox, McpClient, output_of


PINNED_REVISION = "c1a1b734a01364abc65501b4edc916fd05b1907c"
SCOPED_GENERATOR = "processor/src/main/java/io/crysknife/generator/ScopedBeanGenerator.java"
SERVICE_DESCRIPTOR = (
    "processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor")

CALLS: dict[str, tuple[str, dict[str, Any]]] = {
    "constructor_dependencies": ("get_dependencies", {
        "target": "io.crysknife.task.BeanProcessorTask", "direction": "inbound",
        "depth": 1, "limit": 20,
    }),
    "constructor_position": ("get_symbol_at_position", {
        "path": "processor/src/main/java/io/crysknife/ApplicationProcessor.java",
        "line": 113, "column": 35,
    }),
    "service_descriptor": ("inspect_service_descriptors", {
        "service": "javax.annotation.processing.Processor",
    }),
    "deleted_entities": ("resolve_entities", {
        "targets": [SCOPED_GENERATOR, "TemplatedGenerator.java"],
    }),
    "dependencies": ("get_project_dependencies", {
        "module": "processor", "limit": 100,
    }),
    "external_members": ("search_external_symbols", {
        "pattern": "builder", "kind": "method",
        "class_name": "com.google.common.collect.ImmutableList",
        "library": "guava", "limit": 20,
    }),
}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, type=Path)
    parser.add_argument("--quill", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--reindex", action="store_true",
                        help="Build and temporarily use a fresh index, restoring the old one")
    parser.add_argument("--allow-revision-mismatch", action="store_true")
    return parser.parse_args()


def call(client: McpClient, request_id: int, tool: str,
         tool_arguments: dict[str, Any]) -> dict[str, Any]:
    started = time.perf_counter()
    client.send(request_id, "tools/call", {"name": tool, "arguments": tool_arguments})
    client.flush()
    response = client.receive({request_id}, 30)[request_id]
    result = response.get("result", {})
    content = result.get("structuredContent")
    return {
        "tool": tool,
        "latency_ms": round((time.perf_counter() - started) * 1000, 3),
        "output_bytes": len(json.dumps(content, separators=(",", ":")).encode("utf-8")),
        "is_error": bool(result.get("isError")) or content is None,
        "content": content,
    }


def run_calls(quill: Path, project: Path) -> dict[str, dict[str, Any]]:
    client = McpClient([str(quill)], project, 30)
    try:
        client.send(1, "initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "quill-crysknife-quality", "version": "1"},
        })
        client.flush()
        if "result" not in client.receive({1}, 30)[1]:
            raise RuntimeError("MCP initialization failed")
        assert client.process.stdin is not None
        client.process.stdin.write(
            '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n')
        client.flush()
        return {name: call(client, index, tool, params)
                for index, (name, (tool, params)) in enumerate(CALLS.items(), 10)}
    finally:
        client.close()


def evaluate(calls: dict[str, dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    for name, result in calls.items():
        if result.get("is_error"):
            failures.append(f"{name}: MCP call failed")

    dependencies = calls["constructor_dependencies"]["content"]
    if dependencies.get("metrics", {}).get("fan_in") != 2:
        failures.append("constructor_dependencies: expected fan_in=2")
    constructs = {(edge.get("class"), tuple(edge.get("evidence_lines", [])))
                  for edge in dependencies.get("depended_by", [])
                  if edge.get("kind") == "CONSTRUCTS"}
    expected_constructs = {
        ("io.crysknife.ApplicationProcessor", (113,)),
        ("io.crysknife.AfterBurnFactoryProcessor", (84,)),
    }
    if not expected_constructs.issubset(constructs):
        failures.append("constructor_dependencies: constructor call-site evidence is incomplete")

    position = calls["constructor_position"]["content"]
    selected = position.get("selected", {})
    if selected.get("kind") != "CONSTRUCTOR" or selected.get("parameter_count") != 2:
        failures.append("constructor_position: two-argument constructor was not selected")

    descriptors = calls["service_descriptor"]["content"].get("descriptors", [])
    descriptor = next((item for item in descriptors if item.get("file") == SERVICE_DESCRIPTOR), None)
    expected_providers = [
        "io.crysknife.ApplicationProcessor",
        "io.crysknife.AfterBurnFactoryProcessor",
        "io.crysknife.BeanManagerGeneratorProcessor",
    ]
    providers = [] if descriptor is None else [item.get("provider")
                                                for item in descriptor.get("providers", [])]
    if providers != expected_providers or not (descriptor or {}).get(
            "order_can_affect_execution", False):
        failures.append("service_descriptor: current provider order or sensitivity is wrong")

    entities = calls["deleted_entities"]["content"].get("entities", [])
    by_target = {item.get("target"): item for item in entities}
    for target in (SCOPED_GENERATOR, "TemplatedGenerator.java"):
        entity = by_target.get(target, {})
        if entity.get("current") or not entity.get("historical") or not entity.get("deleted"):
            failures.append(f"deleted_entities: {target} is not historical-only")

    artifacts = {item.get("group", "") + ":" + item.get("artifact", ""): item
                 for item in calls["dependencies"]["content"].get("dependencies", [])}
    if artifacts.get("com.google.guava:guava", {}).get("directness") != "direct":
        failures.append("dependencies: guava must be direct")
    if artifacts.get("com.google.guava:failureaccess", {}).get("directness") != "transitive":
        failures.append("dependencies: failureaccess must be transitive")

    members = calls["external_members"]["content"].get("symbols", [])
    names = [member.get("name") for member in members]
    declaring = {member.get("class_name") for member in members}
    if names != ["builder", "builderWithExpectedSize"] \
            or declaring != {"com.google.common.collect.ImmutableList"}:
        failures.append("external_members: exact-class builder methods changed")
    return failures


def execute(args: argparse.Namespace) -> dict[str, Any]:
    project = args.project.resolve()
    quill = args.quill.resolve()
    revision = output_of(["git", "rev-parse", "HEAD"], project)
    if revision != PINNED_REVISION and not args.allow_revision_mismatch:
        raise RuntimeError(f"Expected Crysknife {PINNED_REVISION}, got {revision}")

    def measured() -> dict[str, Any]:
        calls = run_calls(quill, project)
        failures = evaluate(calls)
        return {"schema_version": 1, "project": str(project), "revision": revision,
                "passed": not failures, "failures": failures, "calls": calls}

    if not args.reindex:
        return measured()
    with IndexSandbox(project / ".quill", keep_benchmark_index=False):
        process = subprocess.run(
            [str(quill), "init", "--project", str(project), "--index-only"],
            cwd=project, text=True, capture_output=True)
        if process.returncode:
            raise RuntimeError("Quill indexing failed:\n" + process.stderr)
        return measured()


def main() -> int:
    args = arguments()
    result = execute(args)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Crysknife quality gate: " + ("PASS" if result["passed"] else "FAIL"))
    for failure in result["failures"]:
        print("- " + failure)
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
