#!/usr/bin/env python3
"""Fixture-only, fail-open context hook. No source reads, builds or MCP queries."""
import hashlib
import json
from pathlib import Path
import sys

def emit(project, ledger, payload, output):
    if not isinstance(payload, dict) or payload.get("hook_event_name") != "SessionStart":
        return False
    if payload.get("source") not in {"startup", "resume", "clear"}:
        return False
    cwd = payload.get("cwd")
    if not isinstance(cwd, str) or not cwd or Path(cwd).resolve() != project.resolve():
        return False
    if ledger.resolve().is_relative_to(project.resolve()):
        return False  # Benchmark evidence must never mutate the measured fixture.
    from quill_adoption_diagnostic import OVERVIEW_FIRST_GUIDANCE
    context = OVERVIEW_FIRST_GUIDANCE
    print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart",
        "additionalContext": context}}), file=output, flush=True)
    with ledger.open("a") as evidence:
        evidence.write(json.dumps({"context_sha256": hashlib.sha256(context.encode()).hexdigest()}) + "\n")
    return True


def main(argv=None):
    try:
        project, ledger = map(Path, sys.argv[1:] if argv is None else argv)
        raw = sys.stdin.read(65537)
        if len(raw) > 65536:
            return 0
        payload = json.loads(raw)
        emit(project, ledger, payload, sys.stdout)
    except Exception:
        pass  # No stderr, input/transcript logging, blocking decision or secret exposure.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
