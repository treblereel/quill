#!/usr/bin/env python3
"""Keeps the Apollo fixture outside /tmp so a reboot does not change what later arms run against.

  python3 vault.py save      # APFS-clone /tmp/quill-apollo-paired-v1 into the vault, record hashes
  python3 vault.py restore   # recreate /tmp/quill-apollo-paired-v1 from the vault (arm.py does this
                             # automatically when the fixture is missing)
  python3 vault.py check     # compare the live fixture with the vault manifest

The fixture keeps its /tmp path because sandbox profiles, managed configs, the Quill index and
recorded answers all name it. The vault pins the exact Quill binary, client CLIs, index and
build output of the baseline run; prepare_fixture.py --force would rebuild them with whatever
is installed at that time, which is a different experiment. No model is called.
"""
import hashlib, json, shutil, subprocess, sys
from pathlib import Path

F = Path('/tmp/quill-apollo-paired-v1')
VAULT = Path.home() / '.cache/quill-apollo-paired-v1'
PINNED = ['native/quill', 'runtime/codex', 'runtime/claude', 'fixture.json', 'managed-configs.json',
          'source-files.json', 'file-times.json', 'build-settings/settings.xml', 'build-settings/toolchains.xml']
TREES = ['ready-index', 'ready-target', 'ready-extra']


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


def tree(root):
    """Hash of relative paths, sizes and contents; mtimes are restored separately by arm.py."""
    h = hashlib.sha256()
    for p in sorted(x for x in root.rglob('*') if x.is_file()):
        h.update(f'{p.relative_to(root)}\0{p.stat().st_size}\0{sha(p)}\n'.encode())
    return h.hexdigest()


def manifest(root):
    return {'files': {rel: sha(root / rel) for rel in PINNED if (root / rel).exists()},
            'trees': {rel: tree(root / rel) for rel in TREES}}


def clone(src, dst):
    # cp -c makes APFS clones (no extra space, nanosecond mtimes kept); /tmp and ~ share the volume.
    subprocess.run(['/bin/cp', '-cRp', str(src), str(dst)], check=True)


def save():
    assert (F / 'fixture.json').exists(), f'{F} is not prepared; run prepare_fixture.py first'
    if VAULT.exists():
        shutil.rmtree(VAULT)
    VAULT.parent.mkdir(parents=True, exist_ok=True)
    clone(F, VAULT)
    m = manifest(VAULT)
    assert m == manifest(F), 'vault copy differs from the live fixture'
    (VAULT / 'vault-manifest.json').write_text(json.dumps(m, indent=1) + '\n')
    print(f'Vaulted {F} -> {VAULT}')


def restore():
    m = json.loads((VAULT / 'vault-manifest.json').read_text())
    assert manifest(VAULT) == m, f'{VAULT} no longer matches its manifest'
    if F.exists():
        shutil.rmtree(F)
    clone(VAULT, F)
    (F / 'vault-manifest.json').unlink()
    assert manifest(F) == m
    print(f'Restored {F} from {VAULT}')


def check():
    m = json.loads((VAULT / 'vault-manifest.json').read_text())
    live = manifest(F)
    bad = [k for kind in ('files', 'trees') for k in m[kind] if live[kind].get(k) != m[kind][k]]
    print('fixture matches the vault' if not bad else f'differs from the vault: {bad}')
    return not bad


if __name__ == '__main__':
    cmd = sys.argv[1:] or ['check']
    if cmd[0] == 'save':
        save()
    elif cmd[0] == 'restore':
        restore()
    elif cmd[0] == 'check':
        sys.exit(0 if check() else 1)
    else:
        sys.exit(__doc__)
