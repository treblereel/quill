#!/usr/bin/env python3
"""Measure how much of each benchmark answer Quill's prefetch puts in context before turn one.

4x on ALL tokens needs most questions answered on the first model request, from facts the
prefetch hook delivered. This runs the real ``quill prefetch`` hook on each benchmark prompt
(no model, no MCP server) and checks which identifiers of the private oracle answer the
delivered pack mentions: qualified names, members, configuration keys and paths.

Mention coverage is a lower bound on answerability, not a grade: a pack can name every
identifier and still miss a relation, and the oracle's prose facts are not checked. Use it to
rank question classes and compare prefetch changes; grading still needs the answers.

The hook writes delivery state under the project's .quill; with --guard the fixture's .quill
is restored afterwards and any other change fails the run (see quill_tool_replay.py).
"""
import argparse
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path

from quill_tool_replay import GuardedProject

# Identifiers that a correct pack must name: dotted or member-qualified names, file paths and
# configuration keys. Prose words and bare simple words are not counted.
IDENTIFIER = re.compile(r"[A-Za-z_$][\w$-]*(?:(?:\.|#|/|\$)[A-Za-z_$][\w$-]*)+")
IGNORED = re.compile(r"^(?:java|javax|jakarta)\.lang\.|^(?:e\.g|i\.e)$")


def identifiers(value, found=None):
    """Distinct identifiers in every string of an oracle's expected answer."""
    found = set() if found is None else found
    if isinstance(value, dict):
        for key, child in value.items():
            identifiers(key, found)
            identifiers(child, found)
    elif isinstance(value, list):
        for child in value:
            identifiers(child, found)
    elif isinstance(value, str):
        for match in IDENTIFIER.findall(value):
            token = match.strip(".-/")
            if IGNORED.search(token) or re.fullmatch(r"\d[\d.]*", token):
                continue
            # "dynamic/reflection" or "logger/EventBus" is prose; a path names a file.
            if "/" in token and not re.search(r"\.[A-Za-z]\w*$", token):
                continue
            found.add(token)
    return found


def mentioned(token, text):
    """A member reference counts when its owner and member both appear.

    Oracles write members as Class#method(args) or, in prose, Owner.member with a lower-case
    member (Credentials.onStart, target.jvmId); packs name the same member as pkg.Owner#member.
    """
    if token in text:
        return True
    owner, _, member = token.partition("#")
    if not member and "." in token and "/" not in token:
        head, _, tail = token.rpartition(".")
        if tail[:1].islower() and head.rsplit(".", 1)[-1][:1].isalpha():
            owner, member = head, tail
    if member:
        name = member.split("(")[0].split(":")[0]
        return owner in text and re.search(r"(?<![\w$])" + re.escape(name) + r"(?![\w$])", text) is not None
    return False


def prefetch(command, project, session, prompt, timeout):
    hook = {"hook_event_name": "UserPromptSubmit", "session_id": session, "cwd": str(project),
            "prompt": prompt}
    result = subprocess.run([*command, "prefetch", "--project", str(project)],
                            input=json.dumps(hook), capture_output=True, text=True,
                            timeout=timeout, cwd=project)
    if not result.stdout.strip():
        return ""
    try:
        return json.loads(result.stdout)["hookSpecificOutput"]["additionalContext"]
    except (json.JSONDecodeError, KeyError, TypeError):
        return result.stdout


def pack_gaps(pack):
    """Why a pack left something out: its unavailable statuses and lookup error codes."""
    try:
        data = json.loads(pack)
    except (json.JSONDecodeError, TypeError):
        return []
    gaps = [item.get("status") or item.get("error_code") for item in data.get("unavailable", [])
            if isinstance(item, dict)]
    if data.get("status") not in (None, "ready"):
        gaps.append("pack_" + data["status"])
    return [gap for gap in gaps if gap]


def case_prompts(manager):
    """Exact prompts per case, in turn order, from one client's sessions of the run."""
    prompts = {}
    for session in manager["sessions"]:
        prompts.setdefault(session["case"], session["prompts"])
    return prompts


def measure(oracles, prompts, command, project, timeout):
    cases = []
    for case, oracle in sorted(oracles["cases"].items()):
        if case not in prompts:
            continue
        expected = sorted(identifiers(oracle.get("expected")))
        session = "answerability-" + uuid.uuid4().hex
        packs = [prefetch(command, project, session, prompt, timeout) for prompt in prompts[case]]
        text = "\n".join(packs)
        covered = [token for token in expected if mentioned(token, text)]
        gaps = sorted({gap for pack in packs for gap in pack_gaps(pack)})
        cases.append({
            "case": case, "turns": len(packs), "pack_chars": len(text),
            "pack_tokens_estimate": len(text) // 4, "packs_delivered": sum(bool(p) for p in packs),
            "identifiers": len(expected), "covered": len(covered),
            "coverage": round(len(covered) / len(expected), 3) if expected else None,
            "gaps": gaps, "missing": [token for token in expected if token not in covered][:30]})
    scored = [case for case in cases if case["coverage"] is not None]
    return {
        "cases": cases,
        "summary": {
            "cases": len(cases),
            "with_pack": sum(1 for case in cases if case["packs_delivered"]),
            "fully_covered": sum(1 for case in scored if case["coverage"] == 1.0),
            "mean_coverage": round(sum(case["coverage"] for case in scored) / len(scored), 3)
            if scored else None,
            "mean_pack_tokens": sum(case["pack_tokens_estimate"] for case in cases) // len(cases)
            if cases else 0}}


def print_report(report, out=None):
    out = out or sys.stdout
    summary = report["summary"]
    print(f"{summary['cases']} cases, pack delivered for {summary['with_pack']}, "
          f"fully covered {summary['fully_covered']}, mean coverage {summary['mean_coverage']}, "
          f"mean pack ≈{summary['mean_pack_tokens']} tokens", file=out)
    for case in report["cases"]:
        coverage = "n/a" if case["coverage"] is None else f"{case['coverage']:.0%}"
        gaps = f" gaps: {', '.join(case['gaps'])}" if case["gaps"] else ""
        print(f"  {case['case']}: {case['covered']}/{case['identifiers']} ({coverage}), "
              f"pack ≈{case['pack_tokens_estimate']} tokens{gaps}", file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--oracles", required=True, help="benchmark oracles.json")
    parser.add_argument("--manager", required=True, help="benchmark manager.json with the prompts")
    parser.add_argument("--project", required=True, help="indexed fixture the prompts ask about")
    parser.add_argument("--out", help="write the JSON report here")
    parser.add_argument("--timeout", type=float, default=60)
    parser.add_argument("--guard", metavar="BACKUP_DIR",
                        help="restore the fixture's .quill afterwards; fail on other changes")
    parser.add_argument("command", nargs="+", help="quill launcher, after --")
    args = parser.parse_args(argv)
    oracles = json.loads(Path(args.oracles).read_text())
    prompts = case_prompts(json.loads(Path(args.manager).read_text()))
    project = Path(args.project).resolve()
    if args.guard:
        with GuardedProject(project, args.guard):
            report = measure(oracles, prompts, args.command, project, args.timeout)
    else:
        report = measure(oracles, prompts, args.command, project, args.timeout)
    if args.out:
        Path(args.out).write_text(json.dumps(report, indent=1, ensure_ascii=False) + "\n")
    print_report(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
