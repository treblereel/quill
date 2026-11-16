#!/usr/bin/env python3
"""Runs one Apollo session in one of four arms: {codex, claude} x {without_quill, with_quill}.

  python3.14 arm.py preflight <client> <arm>          # client/MCP startup only, no model turn
  python3.14 arm.py ask <client> <arm> PROMPT [...]   # one session, one turn per prompt (paid)
  python3.14 arm.py ask <client> <arm> --case AQ-031  # a benchmark case from data/questions.json (paid)

Each session restores the disposable fixture from prepare_fixture.py, applies the arm
(Quill index + managed configs, or none), and runs the native CLI under the same Seatbelt
read/write isolation as the Cryostat harness. Client drivers are the Cryostat harness's
runtime/session.py codex()/claude(), pointed at the Apollo fixture.
Results: benchmarks/results/apollo-paired/<stamp>-<client>-<arm>[-preflight]/, or --out DIR
"""
import importlib.util, json, os, shutil, subprocess, sys, tempfile, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
F = Path('/tmp/quill-apollo-paired-v1')
ROOT = F / 'project'
INDEX = F / 'ready-index'
TARGETS = F / 'ready-target'
RUNTIME = F / 'runtime'
NATIVE = F / 'native/quill'
CRYOSTAT = REPO / 'benchmarks/harness/cryostat-paired/template'
RESULTS = REPO / 'benchmarks/results/apollo-paired'
CONFIG = ['.claude/settings.local.json', '.claude/quill-permissions.json', 'AGENTS.md', 'CLAUDE.md',
          '.mcp.json', '.codex/config.toml', '.codex/hooks.json', '.claude/settings.json',
          '.mvn/extensions.xml']
CLIENTS, ARMS = ('codex', 'claude'), ('without_quill', 'with_quill')

sys.path.insert(0, str(REPO / 'scripts'))  # quill_cli_hooks
spec = importlib.util.spec_from_file_location('cryostat_session', CRYOSTAT / 'runtime/session.py')
session = importlib.util.module_from_spec(spec); spec.loader.exec_module(session)
session.ROOT = ROOT  # codex()/claude()/rpc_args() read this module global
from isolation import profile, probe  # noqa: E402  (on sys.path via session.py)

load, write, digest = session.load, session.write, session.digest


def parameters():
    base = load(CRYOSTAT / 'data/baseline-parameters.json')
    return {'models': base['models'], 'vertex_project': base['vertex_project'],
            'vertex_region': base['vertex_region'], 'native_binary': str(NATIVE),
            'codex_binary': str(RUNTIME / 'codex'), 'claude_binary': str(RUNTIME / 'claude'),
            'managed_configs': load(F / 'managed-configs.json'), 'fixture': load(F / 'fixture.json')}


def setup(entry, d, p):
    """Restores sources, build output and arm configuration; never touches the original."""
    fx = p['fixture']
    subprocess.run(['git', 'reset', '-q', '--hard', fx['revision']], cwd=ROOT, check=True)
    subprocess.run(['git', 'clean', '-q', '-fdx', '-e', 'target', '-e', '.quill', '-e', '.mvn/maven.config'],
                   cwd=ROOT, check=True)
    (ROOT / '.mvn/maven.config').write_text(fx['maven_config'])
    # macOS /usr/bin/rsync truncates mtimes to seconds, which makes classes look older than
    # sources compiled in the same second; an APFS clone keeps nanosecond times.
    for rel in fx['module_targets']:
        shutil.rmtree(ROOT / rel, ignore_errors=True)
        subprocess.run(['/bin/cp', '-cRp', str(TARGETS / rel), str(ROOT / rel)], check=True)
    for rel in fx['build_extras']:
        subprocess.run(['/bin/cp', '-cp', str(F / 'ready-extra' / rel), str(ROOT / rel)], check=True)
    sources = load(F / 'source-files.json')
    for name, expected in sources.items():
        assert digest(ROOT / name) == expected, name
    # Apollo tracks its own AGENTS.md: the clean arm keeps the project's version, the Quill arm
    # gets the quill-init version (project text plus the managed section).
    generated = [n for n in CONFIG if n not in sources]
    for name in generated:
        (ROOT / name).unlink(missing_ok=True)
    shutil.rmtree(ROOT / '.quill', ignore_errors=True)
    if entry['arm'] == 'with_quill':
        subprocess.run(['/bin/cp', '-cRp', str(INDEX), str(ROOT / '.quill')], check=True)
        for name, text in p['managed_configs'].items():
            f = ROOT / name; f.parent.mkdir(parents=True, exist_ok=True); f.write_text(text)
    else:
        # The Maven extension leaves quill-* receipts in each target/; the clean arm sees none.
        for rel in fx['module_targets']:
            for path in (ROOT / rel).glob('quill-*'):
                path.unlink() if path.is_file() else shutil.rmtree(path)
        assert not any((ROOT / n).exists() for n in generated)
    for name, times in load(F / 'file-times.json').items():
        f = ROOT / name
        if f.exists():
            os.utime(f, ns=tuple(times))
    d.mkdir(parents=True, exist_ok=False)
    if entry['arm'] == 'with_quill':
        write(d / 'native-profile.json', {'profile': entry['client'], 'basis': 'Native-generated client configuration'})


def worktree(index, tree=None):
    """Writes the work tree (tracked and non-ignored untracked files) through a private index.

    Called once right after setup, so the arm's own files (quill-init AGENTS.md/.gitignore, client
    configs) are part of the baseline; called again with the baseline tree to get the session's
    changes, including files the model created. The fixture's real index is never touched."""
    env = {**os.environ, 'GIT_INDEX_FILE': str(index)}
    if tree is None:
        subprocess.run(['git', 'read-tree', 'HEAD'], cwd=ROOT, env=env, check=True)
    subprocess.run(['git', 'add', '-A'], cwd=ROOT, env=env, check=True)
    if tree is None:
        return subprocess.check_output(['git', 'write-tree'], cwd=ROOT, env=env, text=True).strip()
    return subprocess.check_output(['git', 'diff', '--cached', '--binary', tree], cwd=ROOT, env=env)


def sandbox_text(entry, allowed, writable):
    paths = [*allowed, F / 'build-settings']
    if entry['arm'] == 'with_quill':
        paths.append(F / 'native')
    text = profile(paths)
    if entry['arm'] == 'with_quill':
        # Native Maven observation uses the JVM temp directory, independently of TMPDIR.
        text = text.replace('(require-not (require-any ',
                            '(require-not (require-any (regex #"^/private/var/folders/.*/T/quill-maven-classpath-[0-9]+(/.*)?$") ', 1)
    else:
        q = lambda p: json.dumps(str(Path(p).resolve()))
        text += (f'\n(deny file-read-data (subpath "/opt/homebrew/Cellar/quill"))'
                 f'\n(deny process-exec (subpath "/opt/homebrew/Cellar/quill"))'
                 f'\n(deny process-exec (subpath {q(F / "native")}))'
                 f'\n(deny file-read-data (subpath {q(Path.home() / ".m2/repository/org/treblereel")}))\n')
    text += '\n(deny file-write* ' + ' '.join(f'(subpath {json.dumps(str(x.resolve()))})' for x in
                                              [F / 'native', RUNTIME, INDEX, TARGETS, F / 'ready-extra', F / 'build-settings']) + ')\n'
    text += ('\n(deny file-write* (require-all (subpath "/Users") (require-not (require-any '
             + ' '.join(f'(subpath {json.dumps(str(x.resolve()))})' for x in writable) + '))))\n')
    if entry['client'] == 'claude':
        text += '\n(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n'
    return text


def isolated(entry, d, p, preflight):
    setup(entry, d, p)
    index = F / 'baseline.index'  # outside the sandbox's writable set; never in the session dir
    index.unlink(missing_ok=True)
    baseline = worktree(index)
    write(d / 'baseline.json', {'tree': baseline, 'revision': p['fixture']['revision'],
                                'basis': 'work tree right after arm setup; patch.diff is relative to it'})
    old_env = dict(os.environ)
    with tempfile.TemporaryDirectory(prefix='quill-apollo-home-') as tmp:
        temp = Path(tmp)
        home, claude_home, scratch, build_tmp = temp / 'codex', temp / 'claude', temp / 'scratch', temp / 'build-tmp'
        for x in (home, claude_home):
            x.mkdir(mode=0o700)
        scratch.mkdir(); build_tmp.mkdir()
        auth = Path(old_env.get('CODEX_HOME', str(Path.home() / '.codex'))) / 'auth.json'
        shutil.copy2(auth, home / 'auth.json'); (home / 'auth.json').chmod(0o600)
        (home / 'config.toml').write_text('')
        java = p['fixture']['java_home']
        env = dict(old_env)
        env.update(CODEX_HOME=str(home), CLAUDE_CONFIG_DIR=str(claude_home), TMPDIR=str(scratch),
                   CLAUDE_CODE_TMPDIR=str(scratch), JAVA_HOME=java, PATH=f"{java}/bin:{old_env['PATH']}",
                   MAVEN_ARGS=f"--settings {F}/build-settings/settings.xml --toolchains {F}/build-settings/toolchains.xml",
                   # mvnw (takari) ignores MAVEN_ARGS and reads MAVEN_CONFIG; a clone lacks the ignored
                   # .mvn/maven.config, so without this mvnw read the sandbox-denied ~/.m2/settings.xml.
                   MAVEN_CONFIG=f"--settings {F}/build-settings/settings.xml --toolchains {F}/build-settings/toolchains.xml",
                   MAVEN_OPTS=f'-Djava.io.tmpdir={build_tmp} -Djansi.tmpdir={build_tmp}',
                   # Forked test JVMs do not inherit MAVEN_OPTS; their default temp dir is denied, and
                   # Mockito failed there (AQ-049, both arms).
                   JAVA_TOOL_OPTIONS=f'-Djava.io.tmpdir={build_tmp}')
        allowed = [ROOT, d, home, claude_home, scratch, build_tmp, CRYOSTAT / 'runtime', RUNTIME,
                   Path.home() / '.m2/repository', Path.home() / '.m2/wrapper', Path.home() / '.sdkman',
                   Path.home() / 'Library/Java/JavaVirtualMachines', Path.home() / 'Library/Caches/JNA']
        allowed += [Path.home() / f for f in ['.zshrc', '.zprofile', '.zshenv', '.gitconfig', '.config/git', '.config/jgit/config']]
        sb = d / 'isolation.sb'
        sb.write_text(sandbox_text(entry, allowed, [ROOT, d, home, claude_home, scratch, build_tmp]))
        # The original checkout and this harness (future oracles) must stay unreadable.
        blocked = [Path(p['fixture']['original']) / 'pom.xml', HERE / 'arm.py', HERE / 'data/oracles.json']
        readable = [ROOT / 'pom.xml', ROOT / 'apollo-portal/target/classes/com/ctrip/framework/apollo/portal/PortalApplication.class']
        write(d / 'isolation-probe.json', probe(sb, readable, blocked, scratch))
        write(d / 'entry.json', entry)
        os.environ.clear(); os.environ.update(env)
        try:
            (session.codex if entry['client'] == 'codex' else session.claude)(p, entry, d, env, sb, preflight)
        finally:
            os.environ.clear(); os.environ.update(old_env)
            (d / 'patch.diff').write_bytes(worktree(index, baseline))
            index.unlink(missing_ok=True)
            if entry['arm'] == 'with_quill' and (d / 'mcp/client-to-native.jsonl').exists():
                requests = [json.loads(l) for l in (d / 'mcp/client-to-native.jsonl').read_text().splitlines()]
                assert sum(r.get('method') == 'initialize' for r in requests) == 1, 'MCP initialized more than once'


def main(argv):
    # --out DIR: the session directory (bench.py writes results/apollo/<run>/sessions/<case>-<client>-<arm>-r<N>).
    out = None
    if '--out' in argv:
        i = argv.index('--out')
        if i + 1 >= len(argv):
            sys.exit(__doc__)
        out = Path(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    if len(argv) < 3 or argv[0] not in ('preflight', 'ask') or argv[1] not in CLIENTS or argv[2] not in ARMS \
            or (argv[0] == 'ask') != (len(argv) > 3):
        sys.exit(__doc__)
    mode, client, arm, prompts = argv[0], argv[1], argv[2], argv[3:]
    case = 'adhoc'
    if prompts[:1] == ['--case']:
        if len(prompts) != 2:
            sys.exit(__doc__)
        case, questions = prompts[1], load(HERE / 'data/questions.json')
        if case not in questions['cases']:
            sys.exit(f'unknown case {case}')
        # Same shape as the Cryostat schedule: the context block prefixes the first turn only.
        first, *rest = questions['cases'][case]['prompts']
        prompts = [questions['context'] + '\n' + first, *rest]
    stamp = time.strftime('%Y%m%d-%H%M%S')
    label = f'{stamp}-{case.lower()}-{client}-{arm}' if case != 'adhoc' else f'{stamp}-{client}-{arm}'
    d = RESULTS / label if mode == 'ask' else RESULTS / f'{stamp}-{client}-{arm}-preflight'
    if out is not None:
        d = out
    entry = {'case': case, 'client': client, 'arm': arm, 'prompts': prompts or ['preflight']}
    if not (F / 'fixture.json').exists():  # /tmp was cleared (reboot): bring back the pinned fixture
        subprocess.run([sys.executable, str(HERE / 'vault.py'), 'restore'], check=True)
    isolated(entry, d, parameters(), mode == 'preflight')
    print(f'{mode} {client}/{arm}: OK -> {d}')


if __name__ == '__main__':
    main(sys.argv[1:])
