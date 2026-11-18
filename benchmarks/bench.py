#!/usr/bin/env python3
"""One entry point for the paired benchmarks: Cryostat (Quarkus) and Apollo (Spring).

Every new run lives in benchmarks/results/<suite>/<run>/:
  run.json        what was asked: cases, clients, arm, repeats, Quill commit and native binary
  run.log         one line per session, ending with "-> <session dir>"
  sessions/       <case>-<client>-<arm>-r<N>/  (session.json, turns.json, transport/, mcp/ ...)
  summary.json    per session: tokens, cost, Quill calls, rubric verdict and missed facts

  python3.14 benchmarks/bench.py new cryostat NAME [--cases CQ-005,CQ-011] [--clients codex,claude]
                                                   [--arm with_quill] [--repeats 3] [--seed N]
  python3.14 benchmarks/bench.py check   SUITE NAME     free: fixture, isolation and client preflights
  python3.14 benchmarks/bench.py start   SUITE NAME     paid: runs every planned session
  python3.14 benchmarks/bench.py summarize SUITE NAME   writes summary.json (start does it too)
  python3.14 benchmarks/bench.py import  SUITE NAME PATH   summary.json for a run kept in an older layout
  python3.14 benchmarks/bench.py compare SUITE RUN [RUN ...] [--cases]   first run is the reference

The suites differ only inside: Cryostat freezes a copy of its harness into the run (new_run.py, run.py),
Apollo runs arm.py per session. Both work on disposable fixtures under /tmp and never on the original
checkouts. Results are never deleted or reused: a new name is a new run.
"""
import argparse, json, re, subprocess, sys, time
from collections import defaultdict
from pathlib import Path

BENCH = Path(__file__).resolve().parent
REPO = BENCH.parent
RESULTS = BENCH / 'results'
HARNESS = {'cryostat': BENCH / 'harness/cryostat-paired', 'apollo': BENCH / 'harness/apollo-paired'}
PREFIX = {'cryostat': 'CQ-', 'apollo': 'AQ-'}


def run_dir(suite, name):
    return RESULTS / suite / name


def rubric(suite):
    ns = {}
    exec((HARNESS[suite] / 'rubric.py').read_text(), ns)
    return ns['R']


# ---------------------------------------------------------------------------------------------- new/check/start

def new(suite, name, cases, clients, arm, repeats, seed):
    d = run_dir(suite, name)
    if suite == 'cryostat':
        argv = [sys.executable, str(HARNESS[suite] / 'new_run.py'), name, str(seed or int(time.strftime('%Y%m%d%H')))]
        if cases:
            argv.append(','.join(cases))
        argv += ['--arm', arm, '--repeats', str(repeats)]
        if clients and len(clients) == 1:
            argv += ['--client', clients[0]]
        subprocess.run(argv, check=True)
        (d / 'run.json').write_text(json.dumps({
            'suite': suite, 'cases': cases or 'all', 'clients': clients or ['codex', 'claude'], 'arm': arm,
            'repeats': repeats, 'created': time.strftime('%Y-%m-%dT%H:%M:%S'),
            'quill_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
            'frozen_harness': 'run.py, base/, runtime/ in this directory (new_run.py)'}, indent=1) + '\n')
    else:
        sys.path.insert(0, str(HARNESS[suite]))
        import run_cases  # noqa: E402  (freshness rules shared with the Cryostat new_run.py)
        if arm == 'with_quill':
            run_cases.fresh_or_exit()
        questions = json.loads((HARNESS[suite] / 'data/questions.json').read_text())['cases']
        cases = cases or sorted(questions)
        unknown = [c for c in cases if c not in questions]
        if unknown:
            sys.exit(f'unknown cases: {unknown}')
        if d.exists():
            sys.exit(f'{d} exists; a run is never reused')
        (d / 'sessions').mkdir(parents=True)
        fixture = Path('/tmp/quill-apollo-paired-v1/fixture.json')
        (d / 'run.json').write_text(json.dumps({
            'suite': suite, 'cases': cases, 'clients': clients or ['codex', 'claude'], 'arm': arm,
            'repeats': repeats, 'created': time.strftime('%Y-%m-%dT%H:%M:%S'),
            'quill_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
            'fixture': json.loads(fixture.read_text()) if fixture.exists() else None}, indent=1) + '\n')
    print(d)


def check(suite, name):
    d = run_dir(suite, name)
    if suite == 'cryostat':
        for step in ('prepare', 'preflight', 'preflight-client'):
            subprocess.run([sys.executable, 'run.py', step], cwd=d, check=True)
        subprocess.run([sys.executable, 'base/test-runner.py'], cwd=d, check=True)
        subprocess.run([sys.executable, 'run.py', 'plan'], cwd=d, check=True)
    else:
        plan = json.loads((d / 'run.json').read_text())
        if plan['arm'] == 'with_quill':
            # refresh_quill.py replaced the vault's binary and index on purpose: check it matches HEAD instead.
            sys.path.insert(0, str(HARNESS[suite]))
            import run_cases  # noqa: E402
            run_cases.fresh_or_exit()
        else:
            # The without_quill baseline compares only on the pinned (vaulted) fixture.
            subprocess.run([sys.executable, str(HARNESS[suite] / 'vault.py'), 'check'], check=True)
        for client in plan['clients']:
            subprocess.run([sys.executable, str(HARNESS[suite] / 'arm.py'), 'preflight', client, plan['arm'],
                            '--out', str(d / 'preflight' / client)], check=True)
        print(f'{len(plan["cases"]) * len(plan["clients"]) * plan["repeats"]} sessions planned')


def start(suite, name):
    d = run_dir(suite, name)
    if suite == 'cryostat':
        with (d / 'start.log').open('a') as log:
            subprocess.run([sys.executable, 'run.py', 'start'], cwd=d, check=True, stdout=log, stderr=subprocess.STDOUT)
        # The frozen run writes sessions/<key>; run.log names each one like the Apollo runs do.
        with (d / 'run.log').open('w') as log:
            for s in sorted((d / 'sessions').iterdir()):
                if (s / 'session.json').exists():
                    case, client = s.name[:6].upper(), s.name.split('-')[2]
                    log.write(f'{case} {client} -> {s}\n')
    else:
        plan = json.loads((d / 'run.json').read_text())
        # A restarted run continues where it stopped: a finished session (session.json) is kept, and a
        # session cut off mid-way must be removed by hand first, since arm.py never reuses a directory.
        cut = sorted(s.name for s in (d / 'sessions').glob('*')
                     if s.is_dir() and not (s / 'session.json').exists()) if (d / 'sessions').exists() else []
        if cut:
            sys.exit('unfinished sessions; remove them to rerun: ' + ', '.join(str(d / 'sessions' / n) for n in cut))
        with (d / 'run.log').open('a') as log:
            for repeat in range(1, plan['repeats'] + 1):
                for case in plan['cases']:
                    for client in plan['clients']:
                        out = d / 'sessions' / f'{case.lower()}-{client}-{plan["arm"]}-r{repeat}'
                        if (out / 'session.json').exists():
                            continue
                        began = time.time()
                        r = subprocess.run([sys.executable, str(HARNESS[suite] / 'arm.py'), 'ask', client, plan['arm'],
                                            '--case', case, '--out', str(out)], capture_output=True, text=True)
                        log.write(f'{case} {client} r{repeat} rc={r.returncode} {int(time.time() - began)}s -> {out}\n')
                        log.flush()
    summarize(suite, name)


# ---------------------------------------------------------------------------------------------- summaries

def usage(session_dir):
    s = json.loads((session_dir / 'session.json').read_text())
    if 'usage' in s:  # codex
        u = s['usage']
        return {'input': u.get('inputTokens', 0), 'cached': u.get('cachedInputTokens', 0),
                'output': u.get('outputTokens', 0), 'cost': None}
    u = next(iter(s.get('cumulative_model_usage', {}).values()), {})  # claude
    return {'input': u.get('inputTokens', 0) + u.get('cacheReadInputTokens', 0) + u.get('cacheCreationInputTokens', 0),
            'cached': u.get('cacheReadInputTokens', 0), 'output': u.get('outputTokens', 0),
            'cost': s.get('cumulative_cost_usd_estimate')}


def quill_calls(session_dir):
    calls = 0
    for wire in (session_dir / 'mcp/client-to-native.jsonl',):
        if wire.exists():
            for line in wire.read_text().splitlines():
                try:
                    calls += json.loads(line).get('method') == 'tools/call'
                except ValueError:
                    pass
    return calls


def answers(session_dir):
    if (session_dir / 'turns.json').exists():
        return '\n'.join(t.get('answer') or '' for t in json.loads((session_dir / 'turns.json').read_text()))
    return '\n'.join(f.read_text() for f in sorted(session_dir.glob('turn-*/answer.txt')))


def session_rows(suite, sessions):
    """[(case, client, arm, repeat, dir)] -> summary records."""
    rules = rubric(suite)
    rows = []
    for case, client, arm, repeat, s in sessions:
        if not (s / 'session.json').exists():
            continue
        text = answers(s)
        missed = [fact for fact, rx in rules.get(case, {}).items() if not re.search(rx, text, re.I)]
        rows.append({'case': case, 'client': client, 'arm': arm, 'repeat': repeat, 'dir': str(s),
                     **usage(s), 'quill_calls': quill_calls(s), 'scored': case in rules,
                     'strict': case in rules and not missed, 'missed': missed})
    return rows


def sessions_of(path):
    """Sessions of a run in either layout: a sessions/ directory (Cryostat, new runs) or a run log."""
    path = Path(path)
    found = []
    if path.is_dir() and (path / 'sessions').is_dir():
        for s in sorted((path / 'sessions').iterdir()):
            m = re.match(r'([a-z]q-\d+)-(codex|claude)-(with_quill|without_quill)-r(\d+)$', s.name)
            if m:
                found.append((m.group(1).upper(), m.group(2), m.group(3), int(m.group(4)), s))
        return found
    log = path if path.is_file() else path / 'run.log'
    for line in log.read_text().splitlines():
        if '-> ' not in line:
            continue
        parts = line.split()
        s = Path(line.rsplit('-> ', 1)[1].strip())
        arm = 'without_quill' if 'without_quill' in s.name else 'with_quill'
        m = re.search(r'-r(\d+)$', s.name) or re.search(r'\br(\d+)\b', line)
        found.append((parts[0], parts[1], arm, int(m.group(1)) if m else 1, s))
    return found


def write_summary(suite, name, rows, source):
    d = run_dir(suite, name)
    d.mkdir(parents=True, exist_ok=True)
    (d / 'summary.json').write_text(json.dumps({'suite': suite, 'run': name, 'source': str(source),
                                                'sessions': rows}, indent=1) + '\n')
    print(f'{d / "summary.json"}: {len(rows)} sessions')


def summarize(suite, name):
    write_summary(suite, name, session_rows(suite, sessions_of(run_dir(suite, name))), run_dir(suite, name))


def import_run(suite, name, path):
    """A run kept in an older layout (results/cryostat-full-.../, an Apollo run log) stays where it is."""
    write_summary(suite, name, session_rows(suite, sessions_of(path)), path)


# ---------------------------------------------------------------------------------------------- compare

def load_summary(suite, name):
    return json.loads((run_dir(suite, name) / 'summary.json').read_text())['sessions']


def compare(suite, names, per_case):
    runs = {n: load_summary(suite, n) for n in names}
    if not per_case:
        print(f'{"run":40s} {"client":6s} {"arm":13s} {"n":>4s} {"strict":>9s} {"input":>12s} {"output":>9s} {"cost":>8s} {"calls":>6s}')
        for n, rows in runs.items():
            groups = defaultdict(list)
            for r in rows:
                groups[(r['client'], r['arm'])].append(r)
            for (client, arm), rs in sorted(groups.items()):
                scored = [r for r in rs if r['scored']]
                print(f'{n[:40]:40s} {client:6s} {arm:13s} {len(rs):4d} {sum(r["strict"] for r in scored):4d}/{len(scored):<4d}'
                      f' {sum(r["input"] for r in rs):12,d} {sum(r["output"] for r in rs):9,d}'
                      f' {sum(r["cost"] or 0 for r in rs):8.2f} {sum(r["quill_calls"] for r in rs):6d}')
        return
    keys = sorted({(r['case'], r['client'], r['arm']) for rows in runs.values() for r in rows})
    reference = names[0]
    for case, client, arm in keys:
        cells, base = [], None
        for n, rows in runs.items():
            rs = [r for r in rows if (r['case'], r['client'], r['arm']) == (case, client, arm)]
            if not rs:
                cells.append('-')
                continue
            mean = sum(r['input'] for r in rs) / len(rs)
            if n == reference:
                base = mean
            missed = sorted({m for r in rs for m in r['missed']})
            ratio = f' x{mean / base:.2f}' if base and n != reference else ''
            cells.append(f'{sum(r["strict"] for r in rs)}/{len(rs)} {int(mean) // 1000}k{ratio}'
                         + (f' miss {",".join(missed)}' if missed else ''))
        print(f'{case} {client:6s} {arm:13s} | ' + ' | '.join(cells))


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='command', required=True)
    p = sub.add_parser('new')
    p.add_argument('suite', choices=HARNESS)
    p.add_argument('name')
    p.add_argument('--cases')
    p.add_argument('--clients')
    p.add_argument('--arm', default='with_quill', choices=['with_quill', 'without_quill'])
    p.add_argument('--repeats', type=int, default=3)
    p.add_argument('--seed', type=int)
    for command in ('check', 'start', 'summarize'):
        p = sub.add_parser(command)
        p.add_argument('suite', choices=HARNESS)
        p.add_argument('name')
    p = sub.add_parser('import')
    p.add_argument('suite', choices=HARNESS)
    p.add_argument('name')
    p.add_argument('path')
    p = sub.add_parser('compare')
    p.add_argument('suite', choices=HARNESS)
    p.add_argument('runs', nargs='+')
    p.add_argument('--cases', action='store_true')
    a = ap.parse_args(argv)
    if a.command == 'new':
        new(a.suite, a.name, a.cases.split(',') if a.cases else None,
            a.clients.split(',') if a.clients else None, a.arm, a.repeats, a.seed)
    elif a.command == 'check':
        check(a.suite, a.name)
    elif a.command == 'start':
        start(a.suite, a.name)
    elif a.command == 'summarize':
        summarize(a.suite, a.name)
    elif a.command == 'import':
        import_run(a.suite, a.name, a.path)
    else:
        compare(a.suite, a.runs, a.cases)


if __name__ == '__main__':
    main(sys.argv[1:])
