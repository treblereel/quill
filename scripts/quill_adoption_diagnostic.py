#!/usr/bin/env python3
"""Separate native project-config loading from unprompted Quill tool adoption."""

import argparse
import json
import os
import shutil
import tempfile
import time
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


def configure_fixture_profile(project, profile):
    """Change only generated temporary fixture config, never user configuration."""
    if profile not in {"full", "core", "router"}:
        raise ValueError("Unknown tool profile")
    codex_path = project / ".codex/config.toml"
    parsed = tomllib.loads(codex_path.read_text())
    if set(parsed) != {"mcp_servers"} or set(parsed["mcp_servers"]) != {"quill"}:
        raise ValueError("Expected isolated generated fixture configuration")
    server = parsed["mcp_servers"]["quill"]
    if set(server) != {"command", "args", "cwd"}:
        raise ValueError("Unexpected fixture server fields")
    server["args"] = [*server["args"], "--tools", profile]
    codex_path.write_text("[mcp_servers.quill]\n" + "".join(
        key + " = " + json.dumps(server[key]) + "\n" for key in ("command", "args", "cwd")))
    claude_path = project / ".mcp.json"
    config = json.loads(claude_path.read_text())
    config["mcpServers"]["quill"]["args"] += ["--tools", profile]
    claude_path.write_text(json.dumps(config, indent=2) + "\n")


def client_usage(client, stream):
    """Only numeric provider-reported counts; missing values stay missing, not zero."""
    counts = {}
    keys = ("input_tokens", "cached_input_tokens", "output_tokens") if client == "codex" else (
        "input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")
    for event in stream:
        if event.get("type") != ("turn.completed" if client == "codex" else "result"):
            continue
        usage = event.get("usage", {})
        if not isinstance(usage, dict):
            continue
        for key in keys:
            value = usage.get(key)
            if type(value) is int and value >= 0:
                counts[key] = counts.get(key, 0) + value
    return counts


def profile_catalog_valid(profile, catalog):
    names = {tool.get("name") for tool in catalog.get("tools", [])}
    if profile == "router":
        return names == {"get_overview", "search_tools", "execute_tool"}
    return "get_overview" in names and all(
        bool(names.intersection(SCENARIOS[scenario]["tools"])) for scenario in SCENARIOS
        if scenario != "history") and (bool(names.intersection(SCENARIOS["history"]["tools"]))
        == (profile == "full")) and not {"search_tools", "execute_tool"}.intersection(names)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--wire-only", action="store_true", help="Check native wire metadata without inference")
    parser.add_argument("--tool-profile", choices=["full", "core", "router"], default="full",
                        help="MCP catalog selected only for this temporary fixture")
    parser.add_argument("--cases", nargs="+", choices=["project_read_only", "transport_read_only",
                        "project_workspace_write", "claude_project", "profile_read_only"],
                        default=["project_read_only", "transport_read_only",
                                 "project_workspace_write", "claude_project"])
    parser.add_argument("--scenarios", nargs="+", choices=list(SCENARIOS), default=list(SCENARIOS),
                        help="Unprompted tasks to run (default: all; each case/sample/task uses inference)")
    args = parser.parse_args(argv)
    report = {"schema_version": 2, "scope": "native CLI fixture tasks; no causal or general adoption claim",
              "permissions": "Codex read-only or selected workspace-write; Claude Read/Grep/Glob, "
                              "narrow read-only Git Bash prefixes and Quill MCP; no unrestricted Bash approval",
              "config_invariance_scope": "Codex user config hash and fixture files for both clients; "
                                         "Claude global configuration is not checked",
              "mode": "wire_only" if args.wire_only else "native_inference",
              "tool_profile": args.tool_profile,
              "scenarios": {name: {"prompt": prompt_for(name), "expected_tools": SCENARIOS[name]["tools"]}
                            for name in args.scenarios}, "runs": []}
    quill, maven = args.quill.resolve(), shutil.which("mvn")
    if not quill.is_file() or not maven or args.samples < 1 or args.timeout < 1:
        raise ValueError("Native Quill, system Maven and positive samples/timeout required")
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
        if args.tool_profile != "full":
            configure_fixture_profile(project, args.tool_profile)
        wire = WorkflowClient([str(quill), "--tools", args.tool_profile], project, 60)
        try:
            wire.__enter__()
            initialized = wire.initialized
            catalog = wire.request("tools/list", {})
            if args.tool_profile == "router":
                wire.call("search_tools", {"query": "change_session"})
                planned, _ = wire.call("execute_tool", {"name": "change_session",
                    "arguments": {"targets": [loop.TARGET], "change": loop.CHANGE}})
            else:
                planned, _ = wire.call("change_session", {"targets": [loop.TARGET], "change": loop.CHANGE})
        finally:
            wire.client.close()  # Also close the subprocess when __enter__ raises.
        expected_plan, expected_plan_variants = plan_expectations(planned)
        report["server_instructions"] = initialized.get("instructions")
        report["wire_contract"] = metadata_contract(initialized, catalog)
        report["wire_contract"]["profile_catalog"] = profile_catalog_valid(args.tool_profile, catalog)
        report["tools"] = [{"name": item["name"], "annotations": item.get("annotations")}
                           for item in catalog["tools"]]
        report["guidance"] = {name: (project / name).read_text()
                              for name in ("AGENTS.md", "CLAUDE.md")}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        if args.wire_only:
            print(json.dumps(report["wire_contract"]), flush=True)
            return 0 if all(report["wire_contract"].values()) else 1
        if not all(report["wire_contract"].values()):
            print("Stopping before inference: wire contract failed", flush=True)
            return 1
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
                    started = time.perf_counter()
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
                    stream = events(capture["stdout"])
                    evaluation = evaluate_capture(client, stream, capture, scenario,
                        expected_plan if scenario == "change_plan" else SCENARIOS[scenario]["expected"], unchanged,
                        expected_plan_variants if scenario == "change_plan" else ())
                    # Keep normalized evidence, not raw client stderr or indexed source contents.
                    run = {key: value for key, value in capture.items() if key not in {"stdout", "stderr"}}
                    run.update({"case": case, "client": client, "scenario": scenario,
                                "sample": sample + 1, "evaluation": evaluation,
                                "elapsed_seconds": round(time.perf_counter() - started, 3),
                                "usage": client_usage(client, stream)})
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
