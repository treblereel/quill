#!/usr/bin/env python3
"""Freshness check for Apollo runs; the runs themselves are started by benchmarks/bench.py.

Formerly ran cases with repeats into one named run, then scored it.

  python3.14 run_cases.py NAME [--cases AQ-005,AQ-011] [--clients codex,claude] [--repeats 3]
                          [--arm with_quill] [--allow-stale]

Without --cases every applicable case runs. Each session line goes to
results/apollo-paired/NAME/run.log (`AQ-005 claude r2 rc=0 51s ... -> <session dir>`), and the
rubric table is printed at the end (score.py --cases can compare it with other runs).

Before any paid session, a with_quill run checks, like the Cryostat new_run.py, that Quill sources
are committed and that the fixture's native binary is quill-app/target/quill built from HEAD
(run refresh_quill.py after rebuilding). --allow-stale skips both checks for a diagnostic run.
"""
import argparse, json, subprocess, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
RESULTS = REPO / 'benchmarks/results/apollo-paired'
F = Path('/tmp/quill-apollo-paired-v1')
sys.path.insert(0, str(HERE))
import prepare_fixture as p  # noqa: E402


def fresh_or_exit():
    dirty = subprocess.run(['git', 'status', '--porcelain', '--', 'quill-core/src', 'quill-app/src', 'quill-java-semantic/src',
                            'quill-kotlin-semantic/src', 'quill-kotlin-semantic-21/src', 'pom.xml'],
                           cwd=REPO, capture_output=True, text=True, check=True).stdout.strip()
    if dirty:
        sys.exit('uncommitted Quill sources; commit them first:\n' + dirty)
    if not (F / 'fixture.json').exists():
        subprocess.run([sys.executable, str(HERE / 'vault.py'), 'restore'], check=True)
    fixture = json.loads((F / 'fixture.json').read_text())
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    built = REPO / 'quill-app/target/quill'
    problems = []
    if fixture.get('quill_source_commit') != head:
        problems.append(f"fixture Quill is from {str(fixture.get('quill_source_commit'))[:8]}, HEAD is {head[:8]}")
    if not built.exists() or p.sha(built) != fixture['native_sha256']:
        problems.append('quill-app/target/quill differs from the fixture binary')
    if problems:
        sys.exit('stale fixture: ' + '; '.join(problems) + '\nrebuild native, then run refresh_quill.py')


def main(argv):
    sys.exit('runs moved to benchmarks/bench.py: new apollo NAME [--cases ...] [--repeats N], check, start, compare')


if __name__ == '__main__':
    main(sys.argv[1:])
