#!/usr/bin/env python3
"""Offline v2 accounting revision: full costs, separate workloads, no model execution.

Consumes an audited summary; raw capture parsing and conservative repeat classifications
are shared with quill_response_reuse_audit. Never changes a saved acquisition or grade.
"""
import argparse
from collections import defaultdict
import hashlib
import html
import json
import math
from pathlib import Path
import random

import quill_response_reuse_audit as reuse
import quill_turn_budget as budget

FIELDS = ('input', 'cached_input', 'uncached_input', 'output', 'all')
CONTRACT = {
    'version': 'v2-accounting-3',
    'workloads': ['independent', 'linked', 'patch'],
    'primary_cost': 'all attempts, including prewarm and failed/incorrect answers',
    'provider_cache': 'uncontrolled unless independently established; new session is not cold cache',
    'comparison': 'paired same client/case/repeat/prompts; balanced preregistered order',
    'fixed_inputs': ['source_files', 'classfiles', 'models', 'cli_versions', 'oracles',
                     'permissions', 'native_binary', 'client_configuration'],
    'repeats': 'fixed before acquisition; no reroll or exclusion by outcome',
    'acceptance': 'complete usage/grades/structure, no accuracy loss or ALL/uncached growth; reuse review required',
    'time': 'CLI/MCP readiness and all model work included; compile/index preparation separate',
    'cost': 'provider/CLI estimate when present; unknown is null, not zero; ALL is not money',
    'authorization': 'planning/reporting only; paid acquisition requires a separate user instruction',
}


def load(path):
    return json.loads(Path(path).read_text())


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def totals(usages):
    """A missing record invalidates totals; observed subtotals are never substituted."""
    if not usages or any(not valid_usage(u) for u in usages):
        return None
    return {k: sum(u[k] for u in usages) for k in FIELDS}


def valid_usage(u):
    return (isinstance(u, dict) and all(type(u.get(k)) is int and u[k] >= 0 for k in FIELDS)
            and u['cached_input'] <= u['input']
            and u['uncached_input'] == u['input'] - u['cached_input']
            and u['all'] == u['input'] + u['output'])


def actions(cycle):
    calls = reuse.response_calls(cycle)
    result = set()
    for call in calls:
        name = call['name']; args = call.get('arguments') or {}
        if 'search_tools' in name or name in ('ToolSearch', 'tool_catalog_inspection'):
            result.add('catalog_search')
        elif name.startswith(('mcp__quill__', 'quill_')):
            result.add('quill_query')
        elif name in ('Read', 'read_file'):
            result.add('source_read')
        elif name in ('Grep', 'Glob', 'source_search'):
            result.add('source_search')
        elif name in ('exec_command', 'Bash'):
            command = args.get('cmd', args.get('command', '')) if isinstance(args, dict) else ''
            result.add(budget.classify_command(command))
        else:
            result.add('other_tool')
    blocks = cycle['response'].get('output', cycle['response'].get('content', []))
    opaque_calls = any(b.get('type') in ('custom_tool_call', 'function_call', 'tool_use', 'server_tool_use') for b in blocks)
    return sorted(result or ({'unresolved_tool_call'} if opaque_calls else {'final_answer'}))


def prefix_components(request):
    """Serialized component bytes/hashes, NOT tokens or counterfactual cache costs."""
    groups = defaultdict(list)
    for key in ('system', 'instructions', 'tools'):
        if key in request:
            groups[key].append(request[key])
    for item in request.get('messages', request.get('input', [])):
        kind = item.get('type', '')
        if kind.endswith('_output') or item.get('role') == 'tool':
            group = 'tool_results'
        elif item.get('role') == 'developer':
            group = 'developer_instructions'
        elif item.get('role') == 'assistant' or kind in ('function_call', 'custom_tool_call', 'reasoning'):
            group = 'assistant_history'
        elif any(b.get('type') == 'tool_result' for b in item.get('content', []) if isinstance(b, dict)):
            group = 'tool_result_bearing_messages'
        else:
            group = 'user_messages'
        groups[group].append(item)
    out = {}
    for key, value in groups.items():
        data = reuse.canonical(value).encode()
        out[key] = {'canonical_json_bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    out['prefetch_separation'] = 'not universally separable from developer/user messages; no token estimate'
    out['history_mode'] = 'previous_response_id' if request.get('previous_response_id') else 'full_or_initial'
    return out


def cycle_ledger(frames, boundaries, findings=()):
    rows = []; previous = {}; seen = set(); duplicate_ids = set(); flags = defaultdict(list)
    for f in findings:
        flags[f.get('cycle')].append({k: f.get(k) for k in ('tool', 'classification', 'reason', 'id')})
    for i, frame in enumerate(frames):
        identity = (frame['client'], frame['response'].get('id'))
        if identity[1] is None:
            raise ValueError('missing provider response identity')
        if identity in seen:
            duplicate_ids.add(identity); continue
        seen.add(identity)
        prewarm = frame['request'].get('generate') is False
        number = max((n for n, t in boundaries if t <= frame['time']), default=None)
        if prewarm:
            stage = 'prewarm'; number = None
        elif number is None:
            stage = 'unassigned_request'
        else:
            stage = 'continuation' if number in previous else 'first_request'
        chosen = actions(frame)
        raw_usage = frame['response'].get('usage', {})
        rows.append({'cycle': i, 'provider_response_id': identity[1], 'source': frame['source'],
                     'question': number, 'stage': stage, 'actions': chosen,
                     'triggering_previous_actions': previous.get(number) if stage == 'continuation' else None,
                     'usage': reuse.usage(frame), 'seconds': frame['seconds'],
                     'cache_creation_input': raw_usage.get('cache_creation_input_tokens'),
                     'new_input_excluding_cache_creation': raw_usage.get('input_tokens') if frame['client'] == 'claude' else None,
                     'prefix_components': prefix_components(frame['request']),
                     'reuse_observations': flags[i], 'attributed_waste_tokens': None})
        if number is not None and not prewarm:
            previous[number] = chosen
    return rows, sorted(str(x) for x in duplicate_ids)


def session_directory(record, summary):
    key = record['key']
    if Path(key).name != key:
        raise ValueError('unsafe session key')
    root = Path(record.get('artifact_root', summary.parent))
    for d in (root, root / key, root / 'sessions' / key, summary.parent / 'sessions' / key):
        if d.name == key and (d / 'turns.json').is_file():
            return d
    return None


def record_report(record, summary, reuse_reports):
    u = record.get('usage') or {}; grades = (record.get('grade') or {}).get('turns', [])
    expected = len(record['prompts']); grade_complete = len(grades) == expected and all(type(g.get('correct')) is bool for g in grades)
    d = session_directory(record, summary); issues = []; ledger = []; duplicates = []
    raw_present = d is not None and ((d / 'wire').exists() or (d / 'transport').exists())
    turns = load(d / 'turns.json') if d else []
    for g, t in zip(grades, turns):
        if g.get('answer_sha256') and g['answer_sha256'] != hashlib.sha256(t['answer'].encode()).hexdigest():
            issues.append('grade_answer_hash_mismatch'); grade_complete = False
    if raw_present:
        boundaries = []
        for n in range(1, expected + 1):
            f = d / f'turn-{n:02}' / 'started.json'
            if f.exists():
                start = load(f)
                if start['prompt'] != record['prompts'][n-1]:
                    issues.append('prompt_mismatch'); grade_complete = False
                boundaries.append((n, start['at']))
        try:
            frames = reuse.cycles(d, issues)
            rr = reuse_reports.get(record['key'], {})
            ledger, duplicates = cycle_ledger(frames, boundaries, rr.get('findings', []))
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            issues.append('raw_detail_unreadable; usage unknown')
        if duplicates:
            issues.append('duplicate_provider_response_ids')
        raw_total = totals([r['usage'] for r in ledger])
        if raw_total is None or any(raw_total[k] != u.get(k) for k in FIELDS):
            issues.append('raw_summary_usage_mismatch_or_unknown')
    # A bad grade/prompt alignment invalidates comparison, not observed money spent.
    token_issues=[v for v in issues if v not in ('grade_answer_hash_mismatch','prompt_mismatch')]
    known = u.get('usage_complete') is True and valid_usage(u) and not token_issues
    stage_totals = {}
    for stage in ('prewarm', 'first_request', 'continuation', 'unassigned_request'):
        values = [r['usage'] for r in ledger if r['stage'] == stage]
        # Zero only for a stage proven absent in a complete raw capture, not missing logs.
        stage_totals[stage] = totals(values) if values and known else dict.fromkeys(FIELDS,0) if not values and raw_present and known else None
    answers = []
    for n in range(1, expected + 1):
        rs = [r for r in ledger if r['question'] == n]
        answer_usage = totals([r['usage'] for r in rs]) if known else None
        correct = grades[n-1]['correct'] if grade_complete else None
        answers.append({'number': n, 'correct_final': correct, 'provider_requests': len(rs) if raw_present else None,
                        'provider_usage': answer_usage, 'session_prewarm_excluded_here_only': True,
                        'correct_on_first_request': (len(rs) == 1 and rs[0]['actions'] == ['final_answer'] and correct)
                        if rs and correct is not None and known else None,
                        'tokens_to_first_correct_answer': None,
                        'first_correct_limit': 'Only final answers graded; earlier partial text is not regraded or assumed wrong.'})
    meta = load(d / 'session.json') if d and (d / 'session.json').is_file() else {}
    return {'key': record['key'], 'case': record['case'], 'client': record['client'], 'arm': record['arm'],
            'track': record['track'], 'repeat': record.get('repeat', 1), 'status': record['status'],
            'original_grade':record.get('grade'),
            'historical': bool(record.get('historical')), 'questions': expected, 'grades_complete': grade_complete,
            'correct': sum(g['correct'] for g in grades) if grade_complete else None,
            'structure_complete': record.get('structure', {}).get('passed') is True,
            'usage_complete': known, 'usage': {k: u[k] for k in FIELDS} if known else None,
            'observed_completed_record_usage': {k: u.get(k) for k in FIELDS},
            'raw_available': raw_present, 'raw_issues': issues, 'cycles': ledger, 'stage_totals': stage_totals,
            'provider_requests':len(ledger) if raw_present and known else (u.get('provider_responses') if record['client']=='codex' else u.get('provider_messages')) if known else None,
            'answers': answers, 'wall_seconds_including_startup': record.get('seconds'),
            'cli_startup_seconds': meta.get('startup_seconds'),
            'preparation_seconds': None, 'preparation_limit': 'Compile/index preparation excluded, not recorded as zero.',
            'provider_cost_usd_estimate': u.get('cost_usd_estimate'),
            'cost_basis': u.get('cost_basis', 'unavailable; tokens are not billed money'),
            'cache_write_input': (u.get('raw_usage') or {}).get('write'),
            'new_input_excluding_cache_creation': (u.get('raw_usage') or {}).get('new'),
            'provider_cache_control': 'uncontrolled', 'observed_cached_fraction': u['cached_input']/u['input'] if known and u['input'] else None}


def aggregate(records):
    us = [r['usage'] if r['usage_complete'] else None for r in records]; total = totals(us)
    graded = bool(records) and all(r['grades_complete'] for r in records); correct = sum(r['correct'] for r in records) if graded else None
    answers = sum(r['questions'] for r in records)
    costs = [r['provider_cost_usd_estimate'] for r in records]
    counts = [r['provider_requests'] for r in records]
    splits = {}
    for name in ('cache_write_input','new_input_excluding_cache_creation'):
        values=[r[name] for r in records]
        splits[name]=sum(values) if values and all(type(v) is int and v>=0 for v in values) else None
    return {'sessions': len(records), 'answers': answers, 'correct': correct, **splits,
            'provider_requests':sum(counts) if counts and all(type(v) is int and v>=0 for v in counts) else None,
            'accuracy': correct/answers if graded and answers else None, 'usage': total,
            'usage_complete': total is not None, 'failed_or_incomplete_sessions': [r['key'] for r in records if r['status'] != 'completed'],
            'all_tokens_per_correct_final_including_all_attempts': total['all']/correct if total and correct else None,
            'provider_cost_usd_estimate': sum(costs) if costs and all(type(c) in (float, int) and math.isfinite(c) and c >= 0 for c in costs) else None,
            'cost_basis':sorted({r['cost_basis'] for r in records}),
            'wall_seconds_including_startup': sum(r['wall_seconds_including_startup'] for r in records)
            if records and all(type(r['wall_seconds_including_startup']) in (float, int) and math.isfinite(r['wall_seconds_including_startup']) and r['wall_seconds_including_startup']>=0 for r in records) else None,
            'stage_totals': {stage: totals([r['stage_totals'][stage] for r in records])
                             for stage in ('prewarm', 'first_request', 'continuation')},
            'raw_sessions_available': sum(r['raw_available'] for r in records),
            'first_request_correct': sum(a['correct_on_first_request'] is True for r in records for a in r['answers']),
            'first_request_evaluable': sum(a['correct_on_first_request'] is not None for r in records for a in r['answers'])}


def make_plan(reference, repeats, seed):
    if repeats < 2:
        raise ValueError('Use at least two preregistered paired repeats')
    cases = {}
    for r in reference['schedule']:
        key = (r['case'], r['client']); value = {k: r[k] for k in ('case', 'client', 'prompts', 'track')}
        if key in cases and cases[key] != value:
            raise ValueError('Reference arms have different prompts or workload')
        cases[key] = value
    rng = random.Random(seed); keys = sorted(cases); first = {k: rng.randrange(2) for k in keys}; rows = []
    for repeat in range(1, repeats+1):
        order = list(keys); rng.shuffle(order)
        for key in order:
            arms = ['without_quill', 'with_quill']
            if (first[key] + repeat) % 2:
                arms.reverse()
            for arm in arms:
                r = cases[key]; rows.append({**r, 'arm': arm, 'repeat': repeat,
                    'key': f'{r["case"].lower()}-{r["client"]}-{arm}-r{repeat}'})
    return {'planning_only': True, 'provider_calls': 0, 'measurement_contract': CONTRACT, 'seed': seed,
            'repeats': repeats, 'sessions': len(rows), 'answers': sum(len(r['prompts']) for r in rows),
            'schedule': rows, 'acquisition_preconditions': 'Explicit authorization and frozen inputs/readiness/isolation proof required; this file cannot start acquisition.'}


def report(summary, reuse_summary=None):
    saved = load(summary); rr = load(reuse_summary)['sessions'] if reuse_summary else []
    if any(r['arm'] not in ('without_quill','with_quill') for r in saved['records']):
        raise ValueError('This report compares with/without Quill; normalize other experiments explicitly')
    if len({r['variant'] for r in saved['records'] if r.get('variant')})>1:
        raise ValueError('Do not pool different Quill variants in the same arm')
    bykey = {Path(r['session']).name: r for r in rr}
    records = [record_report(r, summary, bykey) for r in saved['records']]
    alignment_issues={r['key'] for r in records if any(v in ('grade_answer_hash_mismatch','prompt_mismatch') for v in r['raw_issues'])}
    groups = defaultdict(list)
    for r in records:
        groups[(r['client'], r['track'], r['arm'])].append(r)
    tables = [{'client': c, 'track': t, 'arm': a, **aggregate(rs)} for (c,t,a),rs in sorted(groups.items())]
    source = defaultdict(list); candidates = defaultdict(list)
    identity = lambda r: (r['client'],r['case'],r.get('repeat',1))
    for r in saved['records']:
        (source if r['arm']=='without_quill' else candidates)[identity(r)].append(r)
    pairs = []; matched = set(); paired_keys = set()
    for q in (r for r in saved['records'] if r['arm']=='with_quill'):
        key = identity(q); bs = source.get(key,[])
        b = bs[0] if len(bs)==1 and len(candidates[key])==1 else None
        same = b is not None and q['prompts']==b['prompts'] and q['track']==b['track'] and not ({b['key'],q['key']} & alignment_issues)
        if same:
            matched.add(key); paired_keys.update([b['key'],q['key']])
        pairs.append({'client':key[0], 'case':key[1], 'repeat':key[2], 'same_prompts_and_track':same,
                      'historical': bool((b or {}).get('historical') or q.get('historical')),
                      'role':'historical_diagnostic' if (b or {}).get('historical') or q.get('historical') else 'paired_observation'})
    comparisons = []
    for client,track in sorted({(r['client'],r['track']) for r in records}):
        rs = [r for r in records if r['client']==client and r['track']==track and r['key'] in paired_keys]
        arms = {arm:aggregate([r for r in rs if r['arm']==arm]) for arm in ('without_quill','with_quill')}
        b,q = [arms[arm] for arm in ('without_quill','with_quill')]
        known = b['usage'] is not None and q['usage'] is not None
        ratios = {k:b['usage'][k]/q['usage'][k] if q['usage'][k] else None for k in ('all','uncached_input')} if known else None
        per_repeat = []
        for repeat in sorted({r['repeat'] for r in rs}):
            subset = {arm:aggregate([r for r in rs if r['arm']==arm and r['repeat']==repeat]) for arm in arms}
            old,new = [subset[arm]['usage'] for arm in ('without_quill','with_quill')]
            ratio = old['all']/new['all'] if old and new and new['all'] else None
            per_repeat.append({'repeat':repeat,'arms':subset,'source_divided_by_quill_all':ratio})
        spread = [r['source_divided_by_quill_all'] for r in per_repeat]
        comparisons.append({'client':client,'track':track,'arms':arms,'source_divided_by_quill':ratios,
                            'per_repeat_totals':per_repeat,'repeat_count':len(per_repeat),
                            'repeat_ratio_range_not_confidence_interval':[min(spread),max(spread)] if len(spread)>1 and all(v is not None for v in spread) else None,
                            'confirmation':'diagnostic; historical/cache controls, repeat spread and reuse need review'})
    return {'schema_version':1,'measurement_contract':CONTRACT,'summary':str(summary),'summary_sha256':digest(summary),
            'provider_calls':0,'tables':tables,'records':records,'pairs':pairs,'paired_comparisons':comparisons,
            'unmatched_source_keys':[list(k) for k in sorted(set(source)-matched)],
            'automatic_acceptance':False,'acceptance_reason':'Descriptive report; uncontrolled cache, historical references and unresolved reuse require review. No acquisition or original acceptance is rewritten.',
            'limits':['No averaging clients, workloads or per-case efficiency ratios. All supplied attempts remain in primary totals; external excluded-attempt ledgers are not silently imported.',
                      'No raw source captures: historical totals retained, detailed stages/first-answer statistics unknown.',
                      'Component bytes/hashes do not measure tokens or explain cache misses causally.',
                      'Cycle actions and previous actions are observations, not proof of missing facts or wasted tokens.',
                      'Final-answer grades cannot establish when an earlier intermediate answer first became correct.',
                      'No observed cache read is not proof of a cold provider cache. CLI cost estimates are not invoices.']}


def render(data):
    esc = html.escape; rows = []
    for r in data['tables']:
        u = r['usage'] or {}
        values = [r['client'],r['track'],r['arm'],r['sessions'],r['provider_requests'],f'{r["correct"]}/{r["answers"]}',
                  u.get('all'),u.get('cached_input'),u.get('uncached_input'),r['new_input_excluding_cache_creation'],r['cache_write_input'],u.get('output'),
                  r['all_tokens_per_correct_final_including_all_attempts'],r['provider_cost_usd_estimate'],r['wall_seconds_including_startup'],
                  f'{r["first_request_correct"]}/{r["first_request_evaluable"]} evaluated']
        rows.append('<tr>'+''.join('<td>'+esc(str(v))+'</td>' for v in values)+'</tr>')
    heads=['CLI','Workload','Arm','Sessions','Requests incl.prewarm','Correct','ALL','Cached input','Uncached input','New excl.write','Cache write','Output','ALL/correct including failures','USD estimate (not invoice)','Seconds incl.startup','Correct on first request']
    pairs=[]
    for r in data['paired_comparisons']:
        v=r['source_divided_by_quill'] or {}
        pairs.append('<tr>'+''.join('<td>'+esc(str(x))+'</td>' for x in [r['client'],r['track'],v.get('all'),v.get('uncached_input'),r['repeat_count']])+'</tr>')
    return ('<!doctype html><meta charset="utf-8"><title>Quill: honest session accounting</title>'
            '<style>body{font:15px system-ui;margin:24px}td,th{border:1px solid #bbb;padding:7px}table{border-collapse:collapse}pre{white-space:pre-wrap}</style>'
            '<h1>Quill: полный расход по типам работы</h1><p>Офлайн, новых provider calls:0. '
            'Independent — новая сессия на вопрос; linked — постоянная история; patch — изменение кода. '
            'Основные суммы включают prewarm, запуск и все неудачные ответы. Исторический baseline — диагностический.</p>'
            '<table><tr>'+''.join('<th>'+h+'</th>' for h in heads)+'</tr>'+''.join(rows)+'</table>'
            '<h2>Одинаковые пары: отношение сумм source / Quill</h2><table><tr><th>CLI</th><th>Workload</th><th>ALL</th><th>Uncached</th><th>Repeats</th></tr>'+''.join(pairs)+'</table>'
            '<p>Разложение по запросам, предыдущим действиям и префиксам: <a href="report.json">report.json</a>. '
            'Uncached не подменяет стоимость; неизвестные значения — null. Первый правильный ответ среди промежуточных текстов не установлен.</p>'
            '<pre>'+esc(json.dumps(data['limits'],ensure_ascii=False,indent=2))+'</pre>')


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__.splitlines()[0]);mode=p.add_mutually_exclusive_group(required=True)
    mode.add_argument('--summary',type=Path);mode.add_argument('--plan',type=Path,help='Reference protocol; generate a non-executable paired plan only')
    p.add_argument('--reuse-summary',type=Path);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--repeats',type=int,default=3);p.add_argument('--seed',type=int,default=20261101)
    args=p.parse_args(argv);source=args.summary or args.plan
    if args.output.resolve().is_relative_to(source.resolve().parent):
        p.error('Output must be outside the saved acquisition directory')
    if args.output.exists():
        p.error('Refusing to overwrite an existing report or attempt')
    data=report(args.summary,args.reuse_summary) if args.summary else make_plan(load(args.plan),args.repeats,args.seed)
    args.output.mkdir(parents=True)
    (args.output/'report.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    if args.summary:(args.output/'report.html').write_text(render(data))
    print(json.dumps({'output':str(args.output),'provider_calls':0,'mode':'report' if args.summary else 'planning_only'}))


if __name__=='__main__':
    main()
