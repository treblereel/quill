#!/usr/bin/env python3
"""Creates a frozen run directory under benchmarks/results from this template.

usage: new_run.py <run-name> <seed> [CQ-001,CQ-002,...]
A case list makes a targeted run: same protocol and pairing, only those cases. Then, from the run directory: run.py prepare, preflight, preflight-client, plan, start.
The copy is what gets frozen and hashed, so later edits here never touch a started run.
No model is called until `start`.
"""
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TEMPLATE = HERE / 'template'
REPO = HERE.parents[2]
CAPTURE_VERSION = '12.3.1'
MITMDUMP = Path.home() / '.cache/quill/capture-12.3.1-py312-20261104/bin/mitmdump'
FROZEN_SCRIPTS = ['quill_cli_hooks', 'quill_codex_capture', 'quill_measurement_report',
                  'quill_response_reuse_audit', 'quill_turn_budget']


def main(name, seed, cases=None):
    if cases:
        known = json.loads((TEMPLATE / 'data/oracles.json').read_text())['cases']
        unknown = sorted(set(cases) - set(known))
        if unknown:
            raise SystemExit(f'Unknown cases: {unknown}')
    run = REPO / 'benchmarks/results' / name
    if run.exists():
        raise SystemExit(f'{run} exists; a started run is never reused')
    if subprocess.run(['git', 'status', '--porcelain', '--', 'quill-app/src', 'quill-core/src'],
                      cwd=REPO, capture_output=True, text=True, check=True).stdout.strip():
        raise SystemExit('Quill sources have uncommitted changes; the run could not name its source')
    native = REPO / 'quill-app/target/quill'
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    (run / 'runtime').mkdir(parents=True)
    base = run / 'base'
    (base / 'runtime').mkdir(parents=True)
    for target in (run, base):
        (target / 'frozen-scripts').mkdir()
        for script in FROZEN_SCRIPTS:
            shutil.copy2(REPO / 'scripts' / f'{script}.py', target / 'frozen-scripts')
        for source in (TEMPLATE / 'runtime').glob('*.py'):
            shutil.copy2(source, target / 'runtime')
        for file in ['preflight.py', 'preflight-client.py', 'submission-recorder.py', 'test-runner.py']:
            shutil.copy2(TEMPLATE / file, target)
        shutil.copy2(TEMPLATE / 'data/baseline-parameters.json', target)
        shutil.copy2(TEMPLATE / 'data/oracles.json', target)
        shutil.copy2(TEMPLATE / 'data/not-applicable.json', target)
    shutil.copy2(TEMPLATE / 'base_run.py', base / 'run.py')
    shutil.copy2(TEMPLATE / 'data/reference-protocol.json', base / 'protocol.json')
    # The fixture restore at the start of `prepare` puts these v7 inputs back.
    for file in ['managed-configs.json', 'file-times.json']:
        shutil.copy2(TEMPLATE / 'data' / file, base)
    (run / 'run.py').write_text((TEMPLATE / 'run.py').read_text().replace('@RUN@', name).replace('@SEED@', str(seed)).replace('@CASES@', repr(cases)))
    shutil.copy2(TEMPLATE / 'data/capture-requirements.txt', run)
    for target in (run, base):
        shutil.copytree(TEMPLATE / 'data/fixtures', target / 'fixtures')
    (run / 'previous-helper-quarantine.json').write_text('[]\n')
    # The transparency proof was made against this same capture binary, with synthetic traffic.
    # The version check below is what ties it to the binary this run will use.
    shutil.copytree(TEMPLATE / 'data/capture-preflight', run / 'capture-preflight')
    version = subprocess.check_output([str(MITMDUMP), '--version'], text=True)
    if not version.startswith(f'Mitmproxy: {CAPTURE_VERSION}'):
        raise SystemExit(f'Capture proof covers mitmproxy {CAPTURE_VERSION}, found: {version.splitlines()[0]}')
    environment = {'mitmdump': str(MITMDUMP), 'version': CAPTURE_VERSION, 'persistent_environment': True,
                   'local_transparency_test': 'capture-preflight/results.json',
                   'local_transparency_passed': True, 'transparency_test_reused_from': 'cryostat-full-paired-v14-20261104'}
    (run / 'capture-environment.json').write_text(json.dumps(environment, indent=2) + '\n')
    (base / 'previous-helper-quarantine.json').write_text('[]\n')
    (run / 'amendments.json').write_text('{}\n')
    (run / 'source-manifest.json').write_text(json.dumps({'commit': commit}, indent=2) + '\n')
    proof = {'commit': commit, 'native_sha256': hashlib.sha256(native.read_bytes()).hexdigest(),
             'dirty_worktree': False, 'source_manifest': 'source-manifest.json'}
    (run / 'native-build-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(run)


if __name__ == '__main__':
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    main(sys.argv[1], int(sys.argv[2]), sys.argv[3].split(',') if len(sys.argv) == 4 else None)
