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
from quill_native_client_probe import invoke
from quill_workflow_benchmark import WorkflowClient


PROMPT = ("Work only in this fixture; do not access credentials, other projects, or unrelated "
          "services, and do not delegate. What should I inspect before adding an enthusiastic "
          "greeting to org.example.GreetingService without changing its public signature? "
          "Do not modify files or run builds.")


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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--wire-only", action="store_true", help="Check native wire metadata without inference")
    parser.add_argument("--cases", nargs="+", default=["project_read_only", "transport_read_only",
                        "project_workspace_write", "claude_project"])
    args = parser.parse_args()
    report = {"schema_version": 1, "prompt": PROMPT, "runs": []}
    quill, maven = args.quill.resolve(), shutil.which("mvn")
    if not quill.is_file() or not maven or args.samples < 1:
        raise ValueError("Native Quill, system Maven and positive samples required")
    with tempfile.TemporaryDirectory(prefix="quill-adoption-diagnostic-") as temporary:
        project = Path(temporary).resolve()
        loop.write_fixture(project)
        for argv in (["git", "init", "-q"],
                     ["git", "config", "user.name", "Quill diagnostic"],
                     ["git", "config", "user.email", "diagnostic@example.invalid"],
                     [maven, "-q", "test-compile"], ["git", "add", "."],
                     ["git", "commit", "-q", "-m", "Fixture"],
                     [str(quill), "init", "--project", str(project)]):
            loop.run(argv, project)
        with WorkflowClient([str(quill)], project, 60) as wire:
            initialized = wire.initialized
            catalog = wire.request("tools/list", {})
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
            if case not in {"project_read_only", "transport_read_only", "project_workspace_write",
                            "claude_project", "profile_read_only"}:
                raise ValueError("Unknown case: " + case)
            client = "claude" if case == "claude_project" else "codex"
            if not shutil.which(client):
                report["runs"].append({"case": case, "unavailable": True})
                continue
            for sample in range(args.samples):
                print(f"Case {case}, sample {sample + 1}", flush=True)
                if case == "profile_read_only":
                    # A separate explicitly selected profile, never a change to global config.toml.
                    config_home = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))
                    with tempfile.NamedTemporaryFile(mode="w", prefix="quill-diagnostic-",
                            suffix=".config.toml", dir=config_home) as profile:
                        profile.write('[projects.' + json.dumps(str(project)) + ']\n'
                                      'trust_level = "trusted"\n')
                        profile.flush()
                        capture = invoke(client, project, PROMPT, args.timeout,
                            profile=Path(profile.name).name.removesuffix(".config.toml"))
                else:
                    capture = invoke(client, project, PROMPT, args.timeout, trusted=client == "codex",
                                     edit=case == "project_workspace_write",
                                     config_overrides=overrides if case == "transport_read_only" else ())
                capture.update({"case": case, "sample": sample + 1})
                report["runs"].append(capture)
                args.output.parent.mkdir(parents=True, exist_ok=True)
                args.output.write_text(json.dumps(report, indent=2) + "\n")
    print("Report: " + str(args.output), flush=True)
    return 1 if any(run.get("user_config_unchanged") is False for run in report["runs"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
