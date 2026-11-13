#!/usr/bin/env python3
"""Builds the disposable Apollo fixture shared by the clean and Quill arms.

Layout under F (same shape as the Cryostat v7 fixture):
  project/          disposable clone of the original checkout at REV, compiled
  build-settings/   Maven settings/toolchains plus the pinned OpenAPI spec
  runtime/          codex / claude CLIs, copied from the Cryostat vault
  native/quill      current native Quill
  ready-target/     every module's target/ after the build (restored per session)
  ready-index/      .quill after `quill init` (copied in for the Quill arm only)
  managed-configs.json, source-files.json, file-times.json, fixture.json

The original checkout is only read. Run: python3 prepare_fixture.py [--force]
"""
import hashlib, json, os, shutil, subprocess, sys, urllib.request
from pathlib import Path

ORIGINAL = Path('/Users/treblereel/workspace/llm/apollo')
REV = 'e31c48097be6b679fc91ea3a2c67640afc512d31'
F = Path('/tmp/quill-apollo-paired-v1')
ROOT = F / 'project'
REPO = Path(__file__).resolve().parents[3]
VAULT = Path.home() / '.cache/quill-cryostat-paired-v7'
JAVA_HOME = Path.home() / '.sdkman/candidates/java/25.0.1-graalce'
SPEC_URL = 'https://raw.githubusercontent.com/apolloconfig/apollo-openapi/v0.3.12/apollo-openapi.yaml'
# Fixture-only Maven flags, shared by both arms; excluded from git so sources stay pristine.
MAVEN_CONFIG = '.mvn/maven.config'
CONFIG = ['.claude/settings.local.json', '.claude/quill-permissions.json', 'AGENTS.md', 'CLAUDE.md',
          '.mcp.json', '.codex/config.toml', '.codex/hooks.json', '.claude/settings.json',
          '.mvn/extensions.xml']
SKIP_PARTS = {'.git', '.quill', 'target', 'node_modules'}
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mcp_warm import warm  # noqa: E402


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def put(p, v):
    Path(p).write_text(json.dumps(v, ensure_ascii=False, indent=2) + '\n')


def run(args, cwd=None, log=None, env=None):
    print('+', ' '.join(map(str, args)), flush=True)
    out = (F / log).open('w') if log else None
    try:
        subprocess.run(args, cwd=cwd, env=env, check=True, stdout=out, stderr=subprocess.STDOUT if out else None)
    finally:
        if out:
            out.close()


def build_env():
    env = dict(os.environ, JAVA_HOME=str(JAVA_HOME))
    env['PATH'] = f"{JAVA_HOME}/bin:{env['PATH']}"
    env['MAVEN_ARGS'] = (f"--settings {F}/build-settings/settings.xml "
                         f"--toolchains {F}/build-settings/toolchains.xml")
    return env


def module_targets():
    return sorted(p.relative_to(ROOT) for p in ROOT.rglob('target')
                  if p.is_dir() and (p.parent / 'pom.xml').is_file() and '.git' not in p.parts)


def snapshot_targets(dest):
    if dest.exists():
        shutil.rmtree(dest)
    for rel in module_targets():
        (dest / rel).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['/bin/cp', '-cRp', str(ROOT / rel), str(dest / rel)], check=True)


def build_extras():
    ignored = subprocess.check_output(['git', 'ls-files', '-z', '-o', '-i', '--exclude-standard'], cwd=ROOT).decode().split('\0')
    return sorted(n for n in ignored if n and not SKIP_PARTS.intersection(Path(n).parts)
                  and n not in CONFIG and n != MAVEN_CONFIG and not n.startswith('.claude/'))


def write_maven_config():
    # mvnw runs maven-wrapper.jar, which ignores MAVEN_ARGS; maven.config is always read.
    # Without this, builds read the user's ~/.m2/settings.xml (credentials, unreadable in
    # the session sandbox) and Quill records it as a compilation input.
    spec = F / 'build-settings/apollo-openapi.yaml'
    (ROOT / MAVEN_CONFIG).write_text(f'-Dapollo.openapi.spec.url={spec}\n'
                                     f'--settings={F}/build-settings/settings.xml\n'
                                     f'--toolchains={F}/build-settings/toolchains.xml\n')


def main(force):
    if F.exists():
        if not force:
            sys.exit(f'{F} exists; pass --force to rebuild')
        shutil.rmtree(F)
    F.mkdir(parents=True)

    # 1. Disposable clone; the original is never written.
    run(['git', 'clone', '-q', '--no-hardlinks', str(ORIGINAL), str(ROOT)])
    run(['git', 'checkout', '-q', REV], cwd=ROOT)
    run(['git', 'remote', 'set-url', 'origin', 'https://github.com/apolloconfig/apollo.git'], cwd=ROOT)
    with (ROOT / '.git/info/exclude').open('a') as f:
        f.write(f'\n{MAVEN_CONFIG}\n')

    # 2. Shared tools and settings.
    shutil.copytree(VAULT / 'build-settings', F / 'build-settings', copy_function=shutil.copy)
    shutil.copytree(VAULT / 'runtime', F / 'runtime', copy_function=shutil.copy)
    (F / 'native').mkdir()
    shutil.copy(REPO / 'quill-app/target/quill', F / 'native/quill')
    with urllib.request.urlopen(SPEC_URL, timeout=60) as r:
        (F / 'build-settings/apollo-openapi.yaml').write_bytes(r.read())
    write_maven_config()

    # 3. Pre-index source state: tracked files and their hashes.
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    source_files = {n: sha(ROOT / n) for n in tracked if n and (ROOT / n).is_file()}
    put(F / 'source-files.json', source_files)

    # 4. Build once online so ~/.m2 holds every plugin and dependency, then prove offline.
    env = build_env()
    run(['./mvnw', '-B', '-DskipTests', 'install'], cwd=ROOT, log='build-online.log', env=env)
    run(['./mvnw', '-B', '-o', '-DskipTests', 'test-compile'], cwd=ROOT, log='build-offline.log', env=env)

    # 5. Quill init writes managed configs, the build extension and the index. The extension
    # only records module classpaths on a build that runs after it is installed, so build
    # again, refresh, and require a clean doctor before snapshotting.
    run([str(F / 'native/quill'), 'init', '--project', str(ROOT)], log='quill-init.log', env=env)
    run(['./mvnw', '-B', '-o', 'test-compile'], cwd=ROOT, log='build-with-extension.log', env=env)
    run([str(F / 'native/quill'), 'update', '--project', str(ROOT)], log='quill-update.log', env=env)
    # Build events are consumed only by the MCP server, not by `quill update`.
    os.environ['JAVA_HOME'] = str(JAVA_HOME)
    warm(str(F / 'native/quill'), str(ROOT), 600)
    doctor = subprocess.run([str(F / 'native/quill'), 'doctor', '--project', str(ROOT)],
                            env=env, capture_output=True, text=True).stdout
    (F / 'quill-doctor.log').write_text(doctor)
    stale = [l for l in doctor.splitlines() if l.startswith('[WARN]') or l.startswith('[FAIL]')]
    assert not stale, 'Quill index not ready:\n' + '\n'.join(stale)
    managed = {n: (ROOT / n).read_text() for n in [*CONFIG, '.gitignore'] if (ROOT / n).is_file()}
    put(F / 'managed-configs.json', managed)
    subprocess.run(['/bin/cp', '-cRp', str(ROOT / '.quill'), str(F / 'ready-index')], check=True)
    snapshot_targets(F / 'ready-target')
    # Ignored build output outside target/ (e.g. flatten-plugin's .flattened-pom.xml) is a
    # recorded compilation input; sessions git-clean it away, so keep a copy to restore.
    extras = build_extras()
    for rel in extras:
        (F / 'ready-extra' / rel).parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['/bin/cp', '-cp', str(ROOT / rel), str(F / 'ready-extra' / rel)], check=True)

    # 6. Back to the clean source state; both arms start from here.
    run(['git', 'checkout', '-q', '--', '.'], cwd=ROOT)
    for n in CONFIG:
        if n not in tracked:
            (ROOT / n).unlink(missing_ok=True)
    shutil.rmtree(ROOT / '.quill')
    assert all(sha(ROOT / n) == h for n, h in source_files.items()), 'source drift after init'

    times = {}
    for file in ROOT.rglob('*'):
        rel = file.relative_to(ROOT)
        if file.is_file() and not SKIP_PARTS.intersection(rel.parts):
            st = file.stat()
            times[str(rel)] = [st.st_atime_ns, st.st_mtime_ns]
    put(F / 'file-times.json', times)

    put(F / 'fixture.json', {
        'original': str(ORIGINAL), 'revision': REV, 'java_home': str(JAVA_HOME),
        'openapi_spec': {'url': SPEC_URL, 'sha256': sha(F / 'build-settings/apollo-openapi.yaml')},
        'maven_config': (ROOT / MAVEN_CONFIG).read_text(),
        'native_sha256': sha(F / 'native/quill'),
        'cli_versions': {n: subprocess.check_output([str(F / 'runtime' / n), '--version'], text=True).strip()
                         for n in ('codex', 'claude')},
        'module_targets': [str(p) for p in module_targets()],
        'build_extras': extras,
        'quill_source_commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip(),
    })
    print(f'Fixture ready at {F}', flush=True)


if __name__ == '__main__':
    main('--force' in sys.argv[1:])
