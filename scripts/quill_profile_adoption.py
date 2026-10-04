#!/usr/bin/env python3
"""Opt-in native-agent comparison of full/core/router; consumes configured client usage."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import statistics

import quill_adoption_diagnostic as diagnostic
from quill_adoption_scenarios import DEFAULT_SCENARIOS, SCENARIOS

PROFILES = ("full", "core", "router")
CASES = ("project_read_only", "claude_project")


def supported(profile, scenario):
    # This declared exclusion is checked against each live wire catalog before inference.
    return not (profile == "core" and scenario == "history")


def cell_summary(runs, planned):
    """Keep task denominators and missing/duplicate coverage explicit at every level."""
    key = lambda item: (item["profile"], item["case"], item["scenario"], item["sample"])
    expected = {key(item) for item in planned}
    actual = [key(item) for item in runs]
    group = {"planned": len(planned), "completed": len(runs),
             "remaining": max(0, len(planned) - len(runs)),
             "coverage_complete": len(actual) == len(expected) and set(actual) == expected,
             "passed": 0, "failed": 0, "answer_correct": 0,
             "task_tool_succeeded": 0, "overview_first": 0, "unavailable": 0,
             "criteria_passed": {}}
    for item in runs:
        unavailable = item.get("unavailable") is True
        passed = not unavailable and item.get("evaluation", {}).get("passed") is True
        group["unavailable"] += int(unavailable)
        group["passed"] += int(passed)
        group["failed"] += int(not unavailable and not passed)
        criteria = item.get("evaluation", {}).get("criteria", {}) if not unavailable else {}
        for name in ("answer_correct", "task_tool_succeeded", "overview_first"):
            group[name] += int(criteria.get(name) is True)
        for name, value in criteria.items():
            group["criteria_passed"][name] = group["criteria_passed"].get(name, 0) + int(value is True)
    measured = [item["elapsed_seconds"] for item in runs
                if type(item.get("elapsed_seconds")) in {int, float}]
    group["elapsed_seconds"] = {"samples": len(measured), "median": statistics.median(measured)} if measured else None
    group["usage"] = {}
    for name in {name for item in runs for name in item.get("usage", {})}:
        measured = [item["usage"][name] for item in runs if name in item.get("usage", {})]
        group["usage"][name] = {"samples": len(measured), "median": statistics.median(measured)}
    return group


def summarize(runs, planned, unsupported):
    groups = {}
    expected_keys = {(item["profile"], item["case"], item["scenario"], item["sample"]) for item in planned}
    actual_keys = [(item["profile"], item["case"], item["scenario"], item["sample"]) for item in runs]
    for profile, case in sorted({(item["profile"], item["case"]) for item in planned}):
        values = [item for item in runs if item["profile"] == profile and item["case"] == case]
        cells = [item for item in planned if item["profile"] == profile and item["case"] == case]
        group = cell_summary(values, cells)
        group["by_scenario"] = {scenario: cell_summary(
            [item for item in values if item["scenario"] == scenario],
            [item for item in cells if item["scenario"] == scenario])
            for scenario in sorted({item["scenario"] for item in cells})}
        groups[profile + ":" + case] = group
    complete = len(actual_keys) == len(expected_keys) and set(actual_keys) == expected_keys
    return {"planned": len(planned), "completed": len(runs), "remaining": max(0, len(planned) - len(runs)),
            "unsupported": len(unsupported), "coverage_complete": complete,
            "all_passed": bool(planned) and complete and all(
                not item.get("unavailable") and item.get("evaluation", {}).get("passed") is True
                for item in runs), "groups": groups}


def safe_fixture(report):
    return all(run.get("evaluation", {}).get("criteria", {}).get(
        "fixture_unchanged") is True and (run.get("client") != "codex" or
        run.get("user_config_unchanged") is True) for run in report.get("runs", []) if not run.get("unavailable"))


def catalog_support(catalog, scenario):
    if SCENARIOS[scenario].get("route") == "source":
        return True
    names = {item.get("name") for item in catalog}
    return bool(names.intersection(SCENARIOS[scenario]["tools"])) or {"search_tools", "execute_tool"}.issubset(names)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--quill", type=Path, default=Path("quill-app/target/quill"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--cases", nargs="+", choices=CASES, default=list(CASES))
    parser.add_argument("--profiles", nargs="+", choices=PROFILES, default=list(PROFILES))
    parser.add_argument("--scenarios", nargs="+", choices=list(DEFAULT_SCENARIOS), default=list(DEFAULT_SCENARIOS))
    parser.add_argument("--wire-only", action="store_true", help="Validate profile coverage without model requests")
    args = parser.parse_args(argv)
    if args.samples < 1 or args.timeout < 1 or any(len(values) != len(set(values))
            for values in (args.profiles, args.cases, args.scenarios)):
        raise ValueError("Positive samples/timeout and unique selections are required")
    planned, unsupported = [], []
    for sample in range(1, args.samples + 1):
        for profile in args.profiles:
            for case in args.cases:
                for scenario in args.scenarios:
                    item = {"profile": profile, "case": case, "scenario": scenario, "sample": sample}
                    (planned if supported(profile, scenario) else unsupported).append(item)
    binary = args.quill.resolve()
    with binary.open("rb") as source:
        binary_hash = hashlib.file_digest(source, "sha256").hexdigest()
    report = {"schema_version": 1, "mode": "wire_only" if args.wire_only else "native_inference",
              "created_at": datetime.now(timezone.utc).isoformat(),
              "host": platform.platform(), "quill_binary_sha256": binary_hash,
              "scope": "bounded native CLI fixtures; inherited models/auth; not causal or statistical evidence",
              "usage_scope": "provider-reported counts with missing samples explicit; not comparable across providers",
              "profile_scope": "generated fixture configs and independent wire catalog; not runtime client catalog enumeration",
              "config_invariance_scope": "fixture inputs/outputs and Codex user config hash; Claude global config not checked",
              "planned": [] if args.wire_only else planned, "unsupported": unsupported,
              "runs": [], "wire": [], "summary": {}}
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        report["summary"] = summarize(report["runs"], report["planned"], unsupported)
        report["summary"]["wire_passed"] = len(report["wire"]) == len(args.profiles) * args.samples and all(
            item["valid"] for item in report["wire"])
        report["summary"]["all_passed"] &= report["summary"]["wire_passed"]
        output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")

    save()
    for sample in range(1, args.samples + 1):
        offset = (sample - 1) % len(args.profiles)
        for profile in args.profiles[offset:] + args.profiles[:offset]:
            # An independent fixture per profile/sample prevents cross-profile conversation/index state reuse.
            child_path = output.with_name(output.stem + f"-{sample}-{profile}.json")
            tasks = [name for name in args.scenarios if supported(profile, name)]
            child_args = ["--quill", str(binary), "--output", str(child_path), "--tool-profile", profile,
                          "--cases", *args.cases, "--scenarios", *(tasks or args.scenarios),
                          "--samples", "1", "--timeout", str(args.timeout)]
            if args.wire_only or not tasks:
                child_args.append("--wire-only")
            try:
                diagnostic.main(child_args)
                child = json.loads(child_path.read_text())
                valid = all(child["wire_contract"].values()) and all(
                    catalog_support(child["tools"], name) == supported(profile, name) for name in args.scenarios)
                report["wire"].append({"profile": profile, "sample": sample, "valid": valid,
                    "contract": child["wire_contract"], "catalog_tools": len(child["tools"]),
                    "client_versions": child.get("client_versions", {})})
                for run in child["runs"]:
                    report["runs"].append({**run, "profile": profile, "sample": sample})
                save()
                if not valid or (not args.wire_only and child["runs"] and not safe_fixture(child)):
                    report["stopped"] = "Wire contract, fixture or configuration invariance failed"
                    save()
                    return 1
            except Exception as failure:
                report["failure"] = type(failure).__name__  # No remote error body or credentials.
                save()
                return 1
    save()
    print(json.dumps(report["summary"], indent=2), flush=True)
    return 0 if (report["summary"]["wire_passed"] if args.wire_only else report["summary"]["all_passed"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
