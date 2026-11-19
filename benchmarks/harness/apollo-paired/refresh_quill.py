#!/usr/bin/env python3
"""Puts the current native Quill into the prepared Apollo fixture for a new with_quill run.

Steps 5-6 of prepare_fixture.py with quill-app/target/quill, without the clone and the online
build: quill init, an offline build with the extension, quill update, a clean doctor, then fresh
ready-index/, ready-target/, ready-extra/, managed configs and file times. The previous artifacts
are kept beside them as NAME.prev-<native sha8>, and the vault is not touched, so the recorded
without_quill baseline (and `vault.py restore` after a reboot) still use the pinned fixture.

Run: python3.14 refresh_quill.py
"""
import json, shutil, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import prepare_fixture as p  # noqa: E402

F, ROOT = p.F, p.ROOT
KEPT = ['native', 'ready-index', 'ready-target', 'ready-extra', 'managed-configs.json',
        'file-times.json', 'fixture.json']


def main():
    fixture = json.loads((F / 'fixture.json').read_text())
    new = p.REPO / 'quill-app/target/quill'
    if p.sha(new) == fixture['native_sha256']:
        sys.exit('quill-app/target/quill is already the fixture binary')
    old = fixture['native_sha256'][:8]
    for name in KEPT:
        src, dst = F / name, F / f'{name}.prev-{old}'
        if src.exists() and not dst.exists():
            subprocess.run(['/bin/cp', '-cRp', str(src), str(dst)], check=True)
    # A new inode: macOS kills a signed binary overwritten in place (its cached signature no longer matches).
    (F / 'native/quill').unlink(missing_ok=True)
    shutil.copy(new, F / 'native/quill')

    # The clean source state with the recorded build output, as each session starts.
    subprocess.run(['git', 'checkout', '-q', '--', '.'], cwd=ROOT, check=True)
    tracked = set(subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0'))
    for n in p.CONFIG:
        if n not in tracked:
            (ROOT / n).unlink(missing_ok=True)
    shutil.rmtree(ROOT / '.quill', ignore_errors=True)
    for rel in p.module_targets():
        shutil.rmtree(ROOT / rel)
    ready = F / 'ready-target'
    for target in sorted(t for t in ready.rglob('target') if t.is_dir() and 'target' not in t.relative_to(ready).parts[:-1]):
        rel = target.relative_to(ready)
        subprocess.run(['/bin/cp', '-cRp', str(target), str(ROOT / rel)], check=True)
    p.write_maven_config()

    env = p.build_env()
    p.run([str(F / 'native/quill'), 'init', '--project', str(ROOT)], log='quill-init.log', env=env)
    p.run(['./mvnw', '-B', '-o', 'test-compile'], cwd=ROOT, log='build-with-extension.log', env=env)
    p.run([str(F / 'native/quill'), 'update', '--project', str(ROOT)], log='quill-update.log', env=env)
    import os
    os.environ['JAVA_HOME'] = str(p.JAVA_HOME)
    p.warm(str(F / 'native/quill'), str(ROOT), 600)
    doctor = subprocess.run([str(F / 'native/quill'), 'doctor', '--project', str(ROOT)],
                            env=env, capture_output=True, text=True).stdout
    (F / 'quill-doctor.log').write_text(doctor)
    stale = [l for l in doctor.splitlines() if l.startswith('[WARN]') or l.startswith('[FAIL]')]
    assert not stale, 'Quill index not ready:\n' + '\n'.join(stale)
    managed = {n: (ROOT / n).read_text() for n in [*p.CONFIG, '.gitignore'] if (ROOT / n).is_file()}
    p.put(F / 'managed-configs.json', managed)
    shutil.rmtree(F / 'ready-index', ignore_errors=True)
    subprocess.run(['/bin/cp', '-cRp', str(ROOT / '.quill'), str(F / 'ready-index')], check=True)
    p.snapshot_targets(F / 'ready-target')
    extras = p.build_extras()
    shutil.rmtree(F / 'ready-extra', ignore_errors=True)
    for rel in extras:
        (F / 'ready-extra' / rel).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['/bin/cp', '-cp', str(ROOT / rel), str(F / 'ready-extra' / rel)], check=True)

    subprocess.run(['git', 'checkout', '-q', '--', '.'], cwd=ROOT, check=True)
    for n in p.CONFIG:
        if n not in tracked:
            (ROOT / n).unlink(missing_ok=True)
    shutil.rmtree(ROOT / '.quill')
    sources = json.loads((F / 'source-files.json').read_text())
    assert all(p.sha(ROOT / n) == h for n, h in sources.items()), 'source drift after init'
    times = {}
    for file in ROOT.rglob('*'):
        rel = file.relative_to(ROOT)
        if file.is_file() and not p.SKIP_PARTS.intersection(rel.parts):
            st = file.stat()
            times[str(rel)] = [st.st_atime_ns, st.st_mtime_ns]
    p.put(F / 'file-times.json', times)
    fixture.update({
        'native_sha256': p.sha(F / 'native/quill'),
        'module_targets': [str(t) for t in p.module_targets()],
        'build_extras': extras,
        'quill_source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=p.REPO, text=True).strip(),
        'quill_refreshed_at': datetime.now(timezone.utc).isoformat(timespec='seconds'),
        'previous_native_sha256': fixture['native_sha256'],
    })
    p.put(F / 'fixture.json', fixture)
    print('fixture now uses native', fixture['native_sha256'][:12], 'from', fixture['quill_source_commit'][:8])


if __name__ == '__main__':
    main()
