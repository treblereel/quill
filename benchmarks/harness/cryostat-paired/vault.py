"""Seeds the vault outside /tmp from which run.py restores the shared CLIs and Maven settings.

usage: python3.14 vault.py [--expect FROZEN_INPUTS_JSON]
With --expect, every file must match the hash a finished run recorded for it, so a damaged
/tmp copy is never vaulted. No model is called."""
import hashlib, json, shutil, sys
from pathlib import Path

SHARED = Path('/tmp/quill-cryostat-full-paired-v7')
VAULT = Path.home() / '.cache/quill-cryostat-paired-v7'
VAULTED = ['runtime/claude', 'runtime/codex', 'runtime/codex-code-mode-host',
           'build-settings/settings.xml', 'build-settings/toolchains.xml']


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''): h.update(block)
    return h.hexdigest()


expected = {}
if '--expect' in sys.argv:
    frozen = json.loads(Path(sys.argv[sys.argv.index('--expect') + 1]).read_text())
    for rel in VAULTED:
        found = [h for p, h in frozen.items() if p.endswith('/' + rel)]
        assert found, f'{rel} not recorded in the given frozen inputs'
        expected[rel] = found[0]
manifest = {}
for rel in VAULTED:
    source, target = SHARED / rel, VAULT / rel
    digest = sha(source)
    assert rel not in expected or expected[rel] == digest, f'{source} differs from the recorded run'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(source, target)
    assert sha(target) == digest
    manifest[rel] = digest
(VAULT / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')
print(f'Vaulted {len(manifest)} files in {VAULT}')
