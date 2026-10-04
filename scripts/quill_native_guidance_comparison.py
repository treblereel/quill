#!/usr/bin/env python3
"""Opt-in paired Claude guidance experiment; consumes configured native client usage."""
import argparse
import hashlib
import json
from pathlib import Path

import quill_adoption_diagnostic as diagnostic
from quill_profile_adoption import safe_fixture, summarize as profile_summary


def summarize(runs, planned):
    # Reuse the exact-cell rubric, not a second definition of success.
    def cell(item):
        return {**item, "profile": item["variant"]}
    return profile_summary([cell(item) for item in runs], [cell(item) for item in planned], [])


def experiment_controls(child, variant):
    """Verify the experiment changed only the intended prefix, not prompts/catalog/agent guidance."""
    claude = child["guidance"]["CLAUDE.md"]
    if variant in {"overview_first", "routing_file"}:
        prefix = diagnostic.ROUTING_GUIDANCE if variant == "routing_file" else diagnostic.OVERVIEW_FIRST_GUIDANCE
        if not claude.startswith(prefix):
            raise ValueError("Experimental guidance prefix missing")
        claude = claude[len(prefix):]
    if variant in {"session_start", "routing_hook"}:
        context = diagnostic.ROUTING_GUIDANCE if variant == "routing_hook" else diagnostic.OVERVIEW_FIRST_GUIDANCE
        if child.get("hook", {}).get("context_sha256") != hashlib.sha256(context.encode()).hexdigest():
            raise ValueError("Hook context does not match the paired instruction text")
    def digest(value):
        return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
    return {"baseline_claude": digest(claude), "agents": digest(child["guidance"]["AGENTS.md"]),
            "catalog": digest(child["tools"]), "server_instructions": digest(child["server_instructions"]),
            "prompts": digest(child["scenarios"]), "rubric": child.get("rubric", "legacy"),
            "fixture_kind": child.get("fixture_kind", "single"),
            "client_versions": child.get("client_versions", {})}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--wire-only", action="store_true", help="No native model requests")
    parser.add_argument("--rubric", choices=["legacy", "routing"], default="legacy")
    parser.add_argument("--fixture-kind", choices=["single", "multimodule"], default="single")
    parser.add_argument("--capture-chain-evidence", action="store_true")
    parser.add_argument("--variants", nargs="+", choices=diagnostic.GUIDANCE_VARIANTS,
                        default=["baseline", "overview_first"], help="Fixture-only treatments to compare")
    parser.add_argument("--scenarios", nargs="+", choices=list(diagnostic.SCENARIOS),
                        default=["navigation", "change_plan"],
                        help="Paired tasks to compare; every sample/variant/task consumes client usage")
    args = parser.parse_args(argv)
    if args.samples < 1 or args.timeout < 1:
        raise ValueError("Positive samples and timeout required")
    if len(set(args.scenarios)) != len(args.scenarios):
        raise ValueError("Duplicate scenarios cannot form independent paired task cells")
    if len(set(args.variants)) != len(args.variants) or len(args.variants) < 2:
        raise ValueError("At least two distinct variants required")
    if args.rubric == "legacy" and any(diagnostic.SCENARIOS[name].get("route") == "source" for name in args.scenarios):
        raise ValueError("Source-control scenarios require the routing rubric")
    if args.fixture_kind == "multimodule" and set(args.scenarios) - {"call_chain", "usages", "dependencies", "change_plan"}:
        raise ValueError("Multimodule fixture supports semantic tasks only")
    binary = args.quill.resolve()
    with binary.open("rb") as source:
        binary_hash = hashlib.file_digest(source, "sha256").hexdigest()
    planned = [{"variant": variant, "case": "claude_project", "scenario": scenario, "sample": sample}
               for sample in range(1, args.samples + 1)
               for variant in args.variants
               for scenario in args.scenarios]
    report = {"schema_version": 1, "mode": "wire_only" if args.wire_only else "native_inference",
              "scope": "fixture-only Claude guidance; full catalog, unchanged paired prompts; no causal claim",
              "limitations": ["Inherited client models/auth and uncontrolled caches",
                              "Fresh conversations and fixtures; fixed task order, rotated variant order",
                              "Claude global config invariance is not measured",
                              "No production instruction or default-profile change"],
              "quill_binary_sha256": binary_hash,
              "scenarios": args.scenarios,
              "variants": args.variants,
              "rubric": args.rubric,
              "fixture_kind": args.fixture_kind,
              "planned": [] if args.wire_only else planned, "runs": [], "wire": []}
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report["summary"] = summarize(report["runs"], report["planned"])
        report["summary"]["wire_passed"] = len(report["wire"]) == len(args.variants) * args.samples and all(
            item["valid"] for item in report["wire"])
        report["summary"]["all_passed"] &= report["summary"]["wire_passed"]
        output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")

    save()
    for sample in range(1, args.samples + 1):
        variants = list(args.variants)
        if sample % 2 == 0:
            variants.reverse()
        for variant in variants:
            child_path = output.with_name(output.stem + f"-{sample}-{variant}.json")
            child_args = ["--quill", str(binary), "--output", str(child_path),
                          "--guidance-variant", variant, "--tool-profile", "full",
                          "--cases", "claude_project", "--scenarios", *args.scenarios,
                          "--samples", "1", "--timeout", str(args.timeout)]
            child_args.extend(["--rubric", args.rubric])
            child_args.extend(["--fixture-kind", args.fixture_kind])
            if args.capture_chain_evidence:
                child_args.append("--capture-chain-evidence")
            if args.wire_only:
                child_args.append("--wire-only")
            try:
                diagnostic.main(child_args)
                child = json.loads(child_path.read_text())
                valid = bool(child["wire_contract"]) and all(child["wire_contract"].values())
                valid &= child.get("guidance_variant") == variant and child.get("tool_profile") == "full"
                valid &= child.get("rubric", "legacy") == args.rubric
                valid &= child.get("fixture_kind", "single") == args.fixture_kind
                controls = experiment_controls(child, variant)
                if "controls" not in report:
                    report["controls"] = controls
                valid &= controls == report["controls"]
                report["wire"].append({"variant": variant, "sample": sample, "valid": valid,
                    "contract": child["wire_contract"], "controls_match": controls == report["controls"],
                    "guidance_sha256": child.get("guidance_sha256"),
                    "hook": child.get("hook"),
                    "chain_wire_evidence": child.get("chain_wire_evidence"),
                    "catalog_tools": len(child["tools"]), "client_versions": child.get("client_versions", {})})
                report["runs"].extend({**run, "variant": variant, "sample": sample} for run in child["runs"])
                save()
                if not valid or (child["runs"] and not safe_fixture(child)):
                    report["stopped"] = "Wire contract or fixture invariance failed"
                    save()
                    return 1
            except Exception as failure:
                report["failure"] = type(failure).__name__
                save()
                return 1
    save()
    print(json.dumps(report["summary"], indent=2), flush=True)
    return 0 if (report["summary"]["wire_passed"] if args.wire_only
                 else report["summary"]["all_passed"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
