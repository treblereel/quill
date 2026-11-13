#!/usr/bin/env python3
"""Builds data/questions.json (public) and data/oracles.json + data/not-applicable.json (private).

Evidence is read from the fixture clone with `git show REV:path`, so the worktree state does not
matter. Every evidence regex must match at the pinned revision (its line numbers are recorded), and
every Apollo class named in a prompt must exist there; any miss fails the build.
Run: python3 build_data.py
"""
import hashlib, json, re, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from questions import CONTEXT, QUESTIONS, NOT_APPLICABLE_IDS  # noqa: E402
from oracles import ORACLES  # noqa: E402
from not_applicable import NOT_APPLICABLE  # noqa: E402
from prepare_fixture import REV, ROOT  # noqa: E402

DATA = HERE / 'data'
REVIEW_BASIS = ('Inspected source at the pinned revision, tracked-file inventories and javap of packaged '
                'framework jars; no Quill answer used.')


def show(path):
    return subprocess.run(['git', 'show', f'{REV}:{path}'], cwd=ROOT, capture_output=True, check=True).stdout


def tracked():
    return subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', REV], cwd=ROOT, text=True).split('\n')


def resolve(path, patterns):
    blob = show(path)
    lines = blob.decode().split('\n')
    found = []
    for pattern in patterns:
        hits = [i + 1 for i, line in enumerate(lines) if re.search(pattern, line)]
        if not hits:
            raise SystemExit(f'evidence pattern not found: {path}: {pattern}')
        found.append(hits[0])
    return {'path': path, 'sha256': hashlib.sha256(blob).hexdigest(), 'declaration_lines': sorted(set(found))}


def check_prompt_symbols(files):
    """Every com.ctrip.framework.apollo FQCN in a prompt must be a tracked or generated type."""
    generated = ROOT / 'apollo-portal/target/generated-sources/openapi/src/main/java'
    names = {f[:-5].split('/java/', 1)[1].replace('/', '.') for f in files if f.endswith('.java') and '/java/' in f}
    missing = []
    for case, (_, prompts) in QUESTIONS.items():
        for fqcn in re.findall(r'com\.ctrip\.framework\.apollo(?:\.[a-z][a-zA-Z0-9]*)*\.[A-Z][A-Za-z0-9]*', ' '.join(prompts)):
            if fqcn not in names and not (generated / (fqcn.replace('.', '/') + '.java')).is_file():
                missing.append(f'{case}: {fqcn}')
    if missing:
        raise SystemExit('prompt names unknown classes:\n' + '\n'.join(missing))


def main():
    files = tracked()
    check_prompt_symbols(files)
    expected = set(QUESTIONS)
    if set(ORACLES) != expected:
        raise SystemExit(f'oracle/question mismatch: missing {sorted(expected - set(ORACLES))}, '
                         f'extra {sorted(set(ORACLES) - expected)}')
    if set(NOT_APPLICABLE) != set(NOT_APPLICABLE_IDS):
        raise SystemExit('not-applicable ids differ from questions.NOT_APPLICABLE_IDS')
    DATA.mkdir(exist_ok=True)
    questions = {'context': CONTEXT, 'revision': REV,
                 'cases': {c: {'track': t, 'prompts': p} for c, (t, p) in QUESTIONS.items()}}
    oracles = {'project_revision': REV,
               'policy': 'Private, never available to tested model. Compare facts separately from answer format. '
                         'Uncertainty accepted only for the explicitly bounded unknowns.',
               'cases': {}}
    for case, o in ORACLES.items():
        oracles['cases'][case] = {
            'expected': o['expected'],
            'evidence': [resolve(path, patterns) for path, patterns in o['evidence']],
            'framework_evidence': o['framework_evidence'],
            'forbidden_claims': o['forbidden_claims'], 'limitations': o['limitations'],
            'review_basis': REVIEW_BASIS, 'runtime_proven': o['runtime_proven']}
    (DATA / 'questions.json').write_text(json.dumps(questions, ensure_ascii=False, indent=1) + '\n')
    (DATA / 'oracles.json').write_text(json.dumps(oracles, ensure_ascii=False, indent=1) + '\n')
    (DATA / 'not-applicable.json').write_text(json.dumps(NOT_APPLICABLE, ensure_ascii=False, indent=1) + '\n')
    print(f'{len(QUESTIONS)} questions, {len(ORACLES)} oracles, {len(NOT_APPLICABLE)} not applicable -> {DATA}')


if __name__ == '__main__':
    main()
