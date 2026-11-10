"""Full 528-session paired acquisition using the existing isolated v7 harness."""
import re
import fcntl, hashlib, importlib.util, json, os, shutil, subprocess, sys, time
from pathlib import Path

O = Path(__file__).resolve().parent
BASE = O / 'base'
REPO = O.parents[2]
F = Path('/tmp/quill-@RUN@')
CASES = @CASES@  # None runs every applicable case; a list makes a targeted run.
ARM = @ARM@  # A single arm or repeat gives up the pairing: diagnostics only, never acceptance.
REPEAT = @REPEAT@
REPEATS = @REPEATS@  # Paired repeats per case and client; more narrow the noise of a targeted run.
CLIENT = @CLIENT@  # One client keeps the arm pairing within it; still a diagnostic, never acceptance.
spec = importlib.util.spec_from_file_location('full_runner', BASE / 'run.py')
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
r = m.r
original_setup = m.setup
original_parameter = m.parameter
MITM=Path('/Users/treblereel/.cache/quill/capture-12.3.1-py312-20261104/bin/mitmdump')
original_profile = m.profile
original_check = m.check
ROOT = m.ROOT  # Retain the disposable fixture's canonical compiler-receipt paths.
NATIVE = F / 'native/quill'
INDEX = F / 'ready-index'
RUNTIME = F / 'runtime'
put, load, sha = m.put, m.load, m.sha

def configure():
    m.O, m.F, m.NATIVE, m.INDEX, m.RUNTIME = O, F, NATIVE, INDEX, RUNTIME
    m.recorder.O = O
    m.MITM = MITM
    r.OUT, r.INDEX, r.RUNTIME = O, INDEX, RUNTIME
    def profile(paths):
        # Compiler receipts reference these unchanged private settings from fixture preparation.
        text = original_profile([*paths, Path('/tmp/quill-cryostat-full-paired-v7/build-settings')])
        # Group historical task helpers under stricter prefix denials; keep all other rules.
        pattern = r'\(subpath ("/private/tmp/quill-p[0-9]+-[^"\n]+")\)'
        entries = re.findall(pattern, text)
        prefixes = sorted({re.match(r'/private/tmp/quill-p[0-9]+-', json.loads(e)).group() for e in entries})
        text = re.sub(pattern, '', text)
        compact = ' '.join('(regex #"^'+prefix+'")' for prefix in prefixes)
        text = text.replace('(require-any ', '(require-any '+compact+' ', 1)
        return text
    r.profile = profile
    m.profile = profile

def parameter(arm):
    p = dict(load(BASE / 'baseline-parameters.json'))
    p.update(native_binary=str(NATIVE), codex_binary=str(RUNTIME/'codex'),
             claude_binary=str(RUNTIME/'claude'), variant='candidate' if arm=='with_quill' else 'source',
             managed_configs=load(O/'managed-configs.json'), file_times_ns=load(O/'file-times.json'),
             source_files=load(O/'protocol.json')['source_files'])
    return p

def check():
    return original_check()

def prepare():
    assert not (O/'protocol.json').exists()
    # Restore only the stopped suite's disposable fixture, preserving its archived evidence.
    m.setup({'case':'preparation','client':'codex','arm':'without_quill'}, O/'restore', original_parameter('without_quill'))
    assert MITM.is_file() and os.access(MITM,os.X_OK), 'Capture prerequisite unavailable'
    F.mkdir(); (F/'native').mkdir()
    shutil.copy2(REPO/'quill-app/target/quill', NATIVE)
    shutil.copytree(Path('/tmp/quill-cryostat-full-paired-v7/runtime'), RUNTIME)
    shutil.copytree(Path('/tmp/quill-cryostat-full-paired-v7/build-settings'), F/'build-settings')
    configure()
    env = dict(os.environ, JAVA_HOME='/Users/treblereel/.sdkman/candidates/java/25.0.1-graalce')
    env['MAVEN_ARGS']='--settings /tmp/quill-cryostat-full-paired-v7/build-settings/settings.xml --toolchains /tmp/quill-cryostat-full-paired-v7/build-settings/toolchains.xml'
    with (O/'init.log').open('w') as log:
        subprocess.run([str(NATIVE),'init','--project',str(ROOT)],env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
    ref = load(BASE/'protocol.json')
    assert all(sha(ROOT/n)==h for n,h in ref['source_files'].items())
    configs={n:(ROOT/n).read_text() for n in [*r.CONFIG,'.gitignore'] if (ROOT/n).is_file()}
    put(O/'managed-configs.json',configs)
    for name, source in [('ready-target',ROOT/'target'),('ready-index',ROOT/'.quill')]:
        subprocess.run(['/bin/cp','-cR',str(source),str(F/name)],check=True)
    times={}
    for file in ROOT.rglob('*'):
        rel=file.relative_to(ROOT)
        if file.is_file() and not any(n in rel.parts for n in ['.git','.quill','node_modules','.yarn','.quinoa']):
            times[str(rel)]=[file.stat().st_atime_ns,file.stat().st_mtime_ns]
    put(O/'file-times.json',times)
    if CASES:
        ref = {**ref, 'schedule': [e for e in ref['schedule'] if e['case'] in CASES]}
        assert {e['case'] for e in ref['schedule']} == set(CASES)
    plan = m.measurement.make_plan(ref, REPEATS, @SEED@)
    if ARM or REPEAT or CLIENT:
        plan['schedule'] = [e for e in plan['schedule'] if (not ARM or e['arm'] == ARM) and (not REPEAT or e['repeat'] == REPEAT)
                            and (not CLIENT or e['client'] == CLIENT)]
        plan['repeats'] = len({e['repeat'] for e in plan['schedule']})
        plan['sessions'] = len(plan['schedule'])
        plan['answers'] = sum(len(e['prompts']) for e in plan['schedule'])
    p={**ref, **{k:plan[k] for k in ('measurement_contract','schedule','sessions','answers','repeats','seed')},
       'native_binary':str(NATIVE),'native_sha256':sha(NATIVE),
       'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=REPO,text=True).strip(),
       'scope':(f'Targeted run of {sorted(CASES)}; ' if CASES else '44 applicable scenarios of 50; ')+(f'diagnostic, arm {ARM}, repeat {REPEAT or "all"}, unpaired; ' if ARM else f'diagnostic, repeat {REPEAT} only, paired within it; ' if REPEAT else f'{REPEATS} paired repeats; ')+(f'{CLIENT} only; ' if CLIENT else 'Codex and Claude; ')+f'{plan["sessions"]} sessions and {plan["answers"]} answers.',
       'run_filters':{'cases':CASES,'arm':ARM,'repeat':REPEAT,'client':CLIENT,'acceptance_evidence':not (CASES or ARM or REPEAT or CLIENT)},
       'fixture_policy':'Reuse canonical disposable compiled Cryostat fixture; fresh current-native init/index; application sources unchanged.',
       'selection':'Preregistered seed @SEED@; randomized case/client pair order, AB/BA alternated across repeats.',
       'cache':'Provider cache uncontrolled; all captured requests including prewarm and failed attempts retained.',
       'historical_baseline':None,
       'paired_baseline':'Fresh without_quill sessions from the same schedule; earlier results are diagnostics only.',
       'native_build_proof':load(O/'native-build-proof.json'),
       'codex_binary':str(RUNTIME/'codex'),'claude_binary':str(RUNTIME/'claude'),
       'cli_versions_actual':{name:subprocess.check_output([str(RUNTIME/name),'--version'],text=True).strip() for name in ('codex','claude')}}
    assert CASES or ARM or REPEAT or CLIENT or REPEATS != 3 or (p['sessions']==528 and p['answers']==588)
    assert p['sessions']==len(p['schedule'])
    put(O/'protocol.json',p)
    put(O/'source-inputs.json',ref['source_files'])
    m.freeze()
    frozen=load(O/'frozen-inputs.json')
    for path in [O/'source-manifest.json',O/'native-build-proof.json',REPO/'scripts/quill_manual_grades.py',BASE/'run.py',BASE/'runtime/session.py',BASE/'submission-recorder.py',BASE/'baseline-parameters.json',BASE/'protocol.json',O/'capture-environment.json',O/'capture-requirements.txt',O/'capture-preflight/results.json',*list((BASE/'runtime').glob('*.py')),*list((BASE/'frozen-scripts').glob('*.py'))]:
        frozen[str(path.resolve())]=sha(path)
    put(O/'frozen-inputs.json',frozen)
    print(f"Prepared{p['sessions']}sessions{p['answers']}answers;0provider calls",flush=True)

def main(mode):
    if mode in ('start','continue','plan'):
        configure();m.main(mode);return
    lock=Path('/tmp/quill-cryostat-full-suite.lock').open('a')
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    if mode=='prepare':prepare();return
    configure()
    assert mode in ('preflight','preflight-client')
    source=(O/(mode+'.py')).read_text()
    if mode=='preflight':
        source=source.replace("sp=importlib.util.spec_from_file_location('runner',O/'run.py');m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)",'m=runner')
    else:
        source=source.replace("O=Path(__file__).resolve().parent;s=importlib.util.spec_from_file_location('runner',O/'run.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)",'O=Path(__file__).resolve().parent;m=runner')
    exec(compile(source,str(O/(mode+'.py')),'exec'),{'__file__':str(O/(mode+'.py')),'runner':m})

m.check=check
m.parameter=parameter
m.setup=original_setup
if __name__=='__main__':main(sys.argv[1])
