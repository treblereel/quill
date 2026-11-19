#!/usr/bin/env python3
"""Scores Apollo sessions against rubric.py and sums their cost.

  python3.14 score.py LOG [LOG ...]           one table row per log (a run.log or metrics.json)
  python3.14 score.py --cases LOG [LOG ...]   per-case strict rate, missed facts, input tokens

A LOG is a run log whose lines end with `-> <session dir>` (as written by run_cases.py and the
earlier loops) or a metrics.json list with a `dir` field. Several sessions of one case and client
(repeats) are all scored. Strict = every rubric fact of the case found in the joined answers.
"""
import json, re, sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE.parents[1] / 'results/apollo-paired'
sys.path.insert(0, str(HERE))
from rubric import R  # noqa: E402


def sessions(log):
    path = Path(log)
    if path.suffix == '.json':
        rows = json.loads(path.read_text())
        return [(r['case'], r['cl'], RESULTS / r['dir']) for r in rows if r.get('dir')]
    out = []
    for line in path.read_text().splitlines():
        if '-> ' not in line:
            continue
        parts = line.split()
        out.append((parts[0], parts[1], Path(line.rsplit('-> ', 1)[1].strip())))
    return out


def usage(d, client):
    s = json.loads((d / 'session.json').read_text())
    if client == 'codex':
        u = s.get('usage', {})
        return u.get('inputTokens', 0), u.get('outputTokens', 0), None
    u = next(iter(s['cumulative_model_usage'].values()))
    return (u['inputTokens'] + u['cacheReadInputTokens'] + u['cacheCreationInputTokens'],
            u['outputTokens'], s.get('cumulative_cost_usd_estimate'))


def answer(d):
    turns = json.loads((d / 'turns.json').read_text())
    return '\n'.join(t.get('answer') or '' for t in turns)


def score(case, d):
    text = answer(d)
    return [name for name, rx in R.get(case, {}).items() if not re.search(rx, text, re.I)]


def main(argv):
    per_case = argv[:1] == ['--cases']
    logs = argv[1:] if per_case else argv
    if not logs:
        sys.exit(__doc__)
    tables = {}
    for log in logs:
        rows = defaultdict(list)
        for case, client, d in sessions(log):
            if case not in R or not (d / 'turns.json').exists():
                continue
            tin, tout, cost = usage(d, client)
            rows[(case, client)].append({'missed': score(case, d), 'tin': tin, 'tout': tout, 'cost': cost or 0})
        tables[log] = rows
    if not per_case:
        print(f'{"log":48s} {"client":7s} {"sessions":>8s} {"strict":>11s} {"input":>11s} {"output":>9s} {"cost":>8s}')
        for log, rows in tables.items():
            for client in ('codex', 'claude'):
                runs = [r for (c, cl), rs in rows.items() if cl == client for r in rs]
                if not runs:
                    continue
                strict = sum(not r['missed'] for r in runs)
                print(f'{Path(log).name[:48]:48s} {client:7s} {len(runs):8d} {strict:5d}/{len(runs):<5d} '
                      f'{sum(r["tin"] for r in runs):11,d} {sum(r["tout"] for r in runs):9,d} {sum(r["cost"] for r in runs):8.2f}')
        return
    cases = sorted({k for rows in tables.values() for k in rows})
    for case, client in cases:
        cells = []
        for log, rows in tables.items():
            runs = rows.get((case, client), [])
            if not runs:
                cells.append('-')
                continue
            missed = sorted({m for r in runs for m in r['missed']})
            cells.append(f'{sum(not r["missed"] for r in runs)}/{len(runs)} {sum(r["tin"] for r in runs) // len(runs) // 1000}k'
                         + (f' miss {",".join(missed)}' if missed else ''))
        print(f'{case} {client:6s} | ' + ' | '.join(cells))


if __name__ == '__main__':
    main(sys.argv[1:])
