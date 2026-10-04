#!/usr/bin/env python3
"""Separate native project-config loading from unprompted Quill tool adoption."""

import argparse
import json
import os
import shutil
import tempfile
import tomllib
from pathlib import Path

import quill_change_loop_e2e as loop
from quill_native_client_probe import events, invoke
from quill_adoption_scenarios import SCENARIOS, evaluate_capture, fixture_snapshot, prompt_for, summarize
from quill_workflow_benchmark import WorkflowClient


PROMPT = prompt_for("change_plan")  # Compatibility for external imports; all tasks use SCENARIOS.


def metadata_contract(initialized, catalog):
    instructions = initialized.get("instructions")
    tools = catalog.get("tools", [])
    return {
        "server_instructions": isinstance(instructions, str) and "get_overview" in instructions
            and "change_session" in instructions,
        "query_safety_hints": bool(tools) and all(
            isinstance(tool.get("annotations"), dict)
            and tool["annotations"].get("readOnlyHint") is True
            and tool["annotations"].get("destructiveHint") is False
            and tool["annotations"].get("openWorldHint") is False for tool in tools),
    }


def plan_expectations(planned):
    action = planned["directive"]["primary_action"]
    expected = {"phase": planned["phase"], "primary_action": action["action"],
                "verified": planned.get("verification_receipt", {}).get("verified", False)}
    description = action.get("description")
    variants = [{**expected, "primary_action": description},
                {**expected, "primary_action": action["action"] + " — " + description}] if description else []
    return expected, variants


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--wire-only", action="store_true", help="Check native wire metadata without inference")
    parser.add_argument("--cases", nargs="+", choices=["project_read_only", "transport_read_only",
                        "project_workspace_write", "claude_project", "profile_read_only"],
                        default=["project_read_only", "transport_read_only",
                                 "project_workspace_write", "claude_project"])
    parser.add_argument("--scenarios", nargs="+", choices=list(SCENARIOS), default=list(SCENARIOS),
                        help="Unprompted tasks to run (default: all; each case/sample/task uses inference)")
    args = parser.parse_args()
    report = {"schema_version": 2, "scope": "native CLI fixture tasks; no causal or general adoption claim",
              "permissions": "Codex read-only or selected workspace-write; Claude Read/Grep/Glob, "
                              "narrow read-only Git Bash prefixes and Quill MCP; no unrestricted Bash approval",
              "config_invariance_scope": "Codex user config hash and fixture files for both clients; "
                                         "Claude global configuration is not checked",
              "mode": "wire_only" if args.wire_only else "native_inference",
              "scenarios": {name: {"prompt": prompt_for(name), "expected_tools": SCENARIOS[name]["tools"]}
                            for name in args.scenarios}, "runs": []}
    quill, maven = args.quill.resolve(), shutil.which("mvn")
    if not quill.is_file() or not maven or args.samples < 1:
        raise ValueError("Native Quill, system Maven and positive samples required")
    expected_runs = 0 if args.wire_only else len(args.cases) * len(args.scenarios) * args.samples
    report["summary"] = summarize([], expected_runs)
    report["client_versions"] = {}
    with tempfile.TemporaryDirectory(prefix="quill-adoption-diagnostic-") as temporary:
        project = Path(temporary).resolve()
        loop.write_fixture(project)
        (project / "src/main/java/org/example/GreetingController.java").write_text(
            "package org.example;\npublic class GreetingController {\n"
            "  private final GreetingService service = new GreetingService();\n"
            "  public String hello(String name) { return service.greet(name); }\n}\n")
        for argv in (["git", "init", "-q"],
                     ["git", "config", "user.name", "Quill diagnostic"],
                     ["git", "config", "user.email", "diagnostic@example.invalid"],
                     ["git", "add", "."], ["git", "commit", "-q", "-m", "Fixture"]):
            loop.run(argv, project)
        source = project / "src/main/java/org/example/GreetingService.java"
        source.write_text(source.read_text().replace('"Hello, "', '"Welcome, "'))
        for argv in (["git", "add", str(source)],
                     ["git", "commit", "-q", "-m", "Use a welcoming greeting"],
                     [maven, "-q", "test-compile"],
                     [str(quill), "init", "--project", str(project)]):
            loop.run(argv, project)
        with WorkflowClient([str(quill)], project, 60) as wire:
            initialized = wire.initialized
            catalog = wire.request("tools/list", {})
            planned, _ = wire.call("change_session", {"targets": [loop.TARGET], "change": loop.CHANGE})
        expected_plan, expected_plan_variants = plan_expectations(planned)
        report["server_instructions"] = initialized.get("instructions")
        report["wire_contract"] = metadata_contract(initialized, catalog)
        report["tools"] = [{"name": item["name"], "annotations": item.get("annotations")}
                           for item in catalog["tools"]]
        report["guidance"] = {name: (project / name).read_text()
                              for name in ("AGENTS.md", "CLAUDE.md")}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        if args.wire_only:
            print(json.dumps(report["wire_contract"]), flush=True)
            return 0 if all(report["wire_contract"].values()) else 1
        configured = tomllib.loads((project / ".codex/config.toml").read_text())["mcp_servers"]["quill"]
        overrides = ["mcp_servers.quill." + key + "=" + json.dumps(configured[key])
                     for key in ("command", "args", "cwd")]
        for case in args.cases:
            client = "claude" if case == "claude_project" else "codex"
            if not shutil.which(client):
                for scenario in args.scenarios:
                    for sample in range(args.samples):
                        report["runs"].append({"case": case, "client": client, "scenario": scenario,
                                               "sample": sample + 1, "unavailable": True})
                continue
            if client not in report["client_versions"]:
                report["client_versions"][client] = loop.run([client, "--version"], project).stdout.strip()
            for scenario in args.scenarios:
                for sample in range(args.samples):
                    print(f"Case {case}, scenario {scenario}, sample {sample + 1}", flush=True)
                    before = fixture_snapshot(project)
                    prompt = prompt_for(scenario)
                    if case == "profile_read_only":
                        # Explicitly selected temporary profile; never rewrite global config.toml.
                        config_home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
                        with tempfile.NamedTemporaryFile(mode="w", prefix="quill-diagnostic-",
                                suffix=".config.toml", dir=config_home) as profile:
                            profile.write('[projects.' + json.dumps(str(project)) + ']\n'
                                          'trust_level = "trusted"\n')
                            profile.flush()
                            capture = invoke(client, project, prompt, args.timeout,
                                profile=Path(profile.name).name.removesuffix(".config.toml"))
                    else:
                        capture = invoke(client, project, prompt, args.timeout, trusted=client == "codex",
                                         edit=case == "project_workspace_write",
                                         config_overrides=overrides if case == "transport_read_only" else (),
                                         source_reads=True)
                    unchanged = before == fixture_snapshot(project)
                    evaluation = evaluate_capture(client, events(capture["stdout"]), capture, scenario,
                        expected_plan if scenario == "change_plan" else SCENARIOS[scenario]["expected"], unchanged,
                        expected_plan_variants if scenario == "change_plan" else ())
                    # Keep normalized evidence, not raw client stderr or indexed source contents.
                    run = {key: value for key, value in capture.items() if key not in {"stdout", "stderr"}}
                    run.update({"case": case, "client": client, "scenario": scenario,
                                "sample": sample + 1, "evaluation": evaluation})
                    report["runs"].append(run)
                    report["summary"] = summarize(report["runs"], expected_runs)
                    args.output.write_text(json.dumps(report, indent=2) + "\n")
                    print(json.dumps({"passed": evaluation["passed"], "criteria": evaluation["criteria"]}), flush=True)
                    if not unchanged or capture.get("user_config_unchanged") is False:
                        print("Stopping: fixture or user configuration changed; no automatic rollback", flush=True)
                        return 1
    report["summary"] = summarize(report["runs"], expected_runs)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print("Report: " + str(args.output), flush=True)
    return 0 if report["summary"]["all_passed"] and all(report["wire_contract"].values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
