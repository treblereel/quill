#!/usr/bin/env python3
"""Runs Apollo cases with repeats into one named run, then scores it.

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
    ap = argparse.ArgumentParser()
    ap.add_argument('name')
    ap.add_argument('--cases')
    ap.add_argument('--clients', default='codex,claude')
    ap.add_argument('--repeats', type=int, default=1)
    ap.add_argument('--arm', default='with_quill', choices=['with_quill', 'without_quill'])
    ap.add_argument('--allow-stale', action='store_true')
    args = ap.parse_args(argv)
    if args.arm == 'with_quill' and not args.allow_stale:
        fresh_or_exit()
    questions = json.loads((HERE / 'data/questions.json').read_text())['cases']
    cases = args.cases.split(',') if args.cases else sorted(questions)
    unknown = [c for c in cases if c not in questions]
    if unknown:
        sys.exit(f'unknown cases: {unknown}')
    out = RESULTS / args.name
    out.mkdir(parents=True, exist_ok=False)
    log = out / 'run.log'
    (out / 'run.json').write_text(json.dumps({
        'cases': cases, 'clients': args.clients.split(','), 'repeats': args.repeats, 'arm': args.arm,
        'fixture': json.loads((F / 'fixture.json').read_text()) if (F / 'fixture.json').exists() else None,
        'started': time.strftime('%Y-%m-%dT%H:%M:%S')}, indent=1) + '\n')
    with log.open('a') as handle:
        for repeat in range(1, args.repeats + 1):
            for case in cases:
                for client in args.clients.split(','):
                    start = time.time()
                    r = subprocess.run([sys.executable, str(HERE / 'arm.py'), 'ask', client, args.arm, '--case', case],
                                       capture_output=True, text=True)
                    last = (r.stdout.strip().splitlines() or [r.stderr.strip()[-300:]])[-1]
                    handle.write(f'{case} {client} r{repeat} rc={r.returncode} {int(time.time() - start)}s {last}\n')
                    handle.flush()
    subprocess.run([sys.executable, str(HERE / 'score.py'), str(log)])
    subprocess.run([sys.executable, str(HERE / 'score.py'), '--cases', str(log)])


if __name__ == '__main__':
    main(sys.argv[1:])
