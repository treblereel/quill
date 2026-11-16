"""Frozen paired Cryostat acquisition; no model hints, rerolls or answer repairs."""
import fcntl,hashlib,importlib.util,json,os,re,shutil,subprocess,sys,threading,time
from pathlib import Path
O=Path(__file__).resolve().parent;REPO=next(p for p in O.parents if (p/'quill-app').is_dir());F=Path('/tmp/quill-cryostat-full-paired-v7');ROOT=F/'project';NATIVE=F/'native/quill';INDEX=F/'ready-index';RUNTIME=F/'runtime';MITM=Path('/tmp/quill-codex-wire-tools-v1/bin/mitmdump')
sys.path.insert(0,str(O/'frozen-scripts'));sys.path.insert(0,str(O/'runtime'))
import session as r
import quill_codex_capture as capture
import quill_measurement_report as measurement
sp=importlib.util.spec_from_file_location('recorder',O/'submission-recorder.py');recorder=importlib.util.module_from_spec(sp);sp.loader.exec_module(recorder)
def load(p):return json.loads(Path(p).read_text())
def put(p,x):r.write(p,x)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def hashes(root,pattern):return {str(p.relative_to(root)):sha(p) for p in root.rglob(pattern) if p.is_file()}
def git(*args):return subprocess.check_output(['git','-C',str(ROOT),*args],text=True).strip()

def parameter(arm):
 p=dict(load(O/'baseline-parameters.json'))
 p.update(native_binary=str(NATIVE),codex_binary=str(RUNTIME/'codex'),claude_binary=str(RUNTIME/'claude'),variant='candidate' if arm=='with_quill' else 'source',managed_configs=load(O/'managed-configs.json'),file_times_ns=load(O/'file-times.json'),source_files=load(O/'protocol.json')['source_files'])
 return p

base_setup=r.setup_entry
active_arm=None
def setup(entry,d,p):
 base_setup(entry,d,p)
 assert (ROOT/'src/main/webui/.git').is_file(),'Canonical submodule metadata lost'
 build=d/'build-env';build.mkdir();(build/'tmp').mkdir()
 os.environ['JAVA_HOME']='/Users/treblereel/.sdkman/candidates/java/25.0.1-graalce'
 os.environ['MAVEN_ARGS']='--settings '+str(F/'build-settings/settings.xml')+' --toolchains '+str(F/'build-settings/toolchains.xml')
 os.environ['MAVEN_OPTS']='-Djava.io.tmpdir='+str(build/'tmp')+' -Djansi.tmpdir='+str(build/'tmp')
 # mvnw reads MAVEN_CONFIG, not MAVEN_ARGS; forked test JVMs ignore MAVEN_OPTS (Apollo AQ-049).
 os.environ['MAVEN_CONFIG']=os.environ['MAVEN_ARGS']
 os.environ['JAVA_TOOL_OPTIONS']='-Djava.io.tmpdir='+str(build/'tmp')
 if entry['case']=='CQ-049':
  repo=build/'repository';repo.mkdir();subprocess.run(['/bin/cp','-cR',str(Path.home()/'.m2/repository')+'/.',str(repo)],check=True)
  os.environ['MAVEN_ARGS']+=' -o -Dmaven.repo.local='+str(repo)
  os.environ['MAVEN_CONFIG']=os.environ['MAVEN_ARGS']
  put(build/'cache-policy.json',{'private_writable_apfs_clone':True,'same_in_both_arms':True,'offline':True,'repository':str(repo)})
 if entry['arm']=='without_quill':
  for path in (ROOT/'target').glob('quill-*'):
   assert path.is_file(),path;path.unlink()
  assert not (ROOT/'.quill').exists() and not any((ROOT/n).exists() for n in r.CONFIG)
 else:put(d/'native-profile.json',{'profile':entry['client'],'basis':'Native-generated client configuration'})
 put(d/'managed-input-hashes.json',{'.gitignore':sha(ROOT/'.gitignore')})
r.setup_entry=setup
original_profile=r.profile
def profile(paths):
 paths=[*paths,F/'build-settings']
 if active_arm=='with_quill':paths.append(F/'native')
 text=original_profile(paths)
 if active_arm=='with_quill':
  # Native Maven observation uses the JVM temp directory, independently of TMPDIR.
  text=text.replace('(require-not (require-any ', '(require-not (require-any (regex #\"^/private/var/folders/.*/T/quill-maven-classpath-[0-9]+(/.*)?$\") ')
 if active_arm=='without_quill':
  text+='\n(deny file-read-data (subpath \"/opt/homebrew/Cellar/quill\"))\n(deny process-exec (subpath \"/opt/homebrew/Cellar/quill\"))\n(deny process-exec (subpath '+json.dumps(str((F/'native').resolve()))+'))\n(deny file-read-data (subpath '+json.dumps(str((Path.home()/'.m2/repository/org/treblereel').resolve()))+'))\n'
 text+='\n(deny file-write* '+ ' '.join('(subpath '+json.dumps(str(x.resolve()))+')' for x in [F/'native',RUNTIME,INDEX,F/'ready-target',F/'build-settings']) +')\n'
 return text
r.profile=profile

def model_tmp_paths(d):
 """Only literal paths in model tool calls; never collect names from ls/tool outputs."""
 texts=[]
 p=d/'rollout.jsonl'
 if p.exists():
  for line in p.read_text().splitlines():
   x=json.loads(line);v=x.get('payload',{})
   if x.get('type')=='response_item' and v.get('type') in ['custom_tool_call','function_call']:texts.append(v.get('input',v.get('arguments','')))
 p=d/'events.jsonl'
 if p.exists():
  for line in p.read_text().splitlines():
   x=json.loads(line)
   if x.get('type')=='assistant':
    texts += [json.dumps(b.get('input',{})) for b in x.get('message',{}).get('content',[]) if isinstance(b,dict) and b.get('type')=='tool_use']
 for text in texts:
  for raw in re.findall(r'/(?:private/)?tmp/[A-Za-z0-9_./-]+',str(text)):
   path=Path(raw).resolve()
   if path.is_relative_to(Path('/private/tmp')) and len(path.parts)>=4:yield Path(*path.parts[:4])

def retain_helpers(d,key,before):
 rows=[]
 for path in sorted(set(model_tmp_paths(d))):
  if path in before or not path.exists() or path.is_symlink() or path.name.startswith(('quill-','sqlite-','hsperfdata')):continue
  dest=F/'retained-helpers'/key/path.name;dest.parent.mkdir(parents=True,exist_ok=True);assert not dest.exists();path.rename(dest)
  rows.append({'original':str(path),'retained':str(dest),'basis':'New during this session and literal in its model tool call; moved only after client exit. No model feedback.'})
 put(d/'retained-helpers.json',rows)

def snapshot(entry,d,stop):
 while not stop.is_set():
  try:
   for root in recorder.roots(d,entry['started_at']):recorder.capture(root,entry['key'])
  except (OSError,subprocess.TimeoutExpired) as error:print('recorder',type(error).__name__,flush=True)
  stop.wait(.5)

def isolated(entry,d):
 global active_arm
 active_arm=entry['arm'];before={p.resolve() for p in Path('/private/tmp').iterdir()};old=dict(os.environ);stop=threading.Event();thread=None
 if entry['case']=='CQ-049':thread=threading.Thread(target=snapshot,args=(entry,d,stop),daemon=True);thread.start()
 try:r.isolated(entry,d,parameter(entry['arm']))
 finally:
  stop.set()
  if thread:thread.join(timeout=25)
  if d.exists():
   if (d/'managed-input-hashes.json').exists():
    assert all((ROOT/n).is_file() and sha(ROOT/n)==h for n,h in load(d/'managed-input-hashes.json').items()),'Model changed managed ignore input'
   if entry['case']=='CQ-049':
    for root in recorder.roots(d,entry['started_at']):recorder.capture(root,entry['key'])
   retain_helpers(d,entry['key'],before)
   # Patches are captured above; each clone is about 100k files that nothing reads afterwards.
   shutil.rmtree(d/'build-env',ignore_errors=True)
  os.environ.clear();os.environ.update(old);active_arm=None


def freeze():
 paths=[*(O/'fixtures').glob('*.json'),*(F/'build-settings').glob('*.xml'),O/'previous-helper-quarantine.json',O/'preflight-client.py',O/'baseline-parameters.json',O/'preflight.py',O/'test-runner.py',O/'run.py',O/'submission-recorder.py',O/'protocol.json',O/'oracles.json',O/'amendments.json',O/'not-applicable.json',O/'managed-configs.json',O/'file-times.json',O/'source-inputs.json',NATIVE,MITM,*RUNTIME.iterdir(),*(O/'runtime').glob('*.py'),*(O/'frozen-scripts').glob('*.py')]
 paths += list(INDEX.rglob('*.db'))+list(INDEX.glob('*.json'))
 put(O/'frozen-inputs.json',{str(p.resolve()):sha(p) for p in paths if p.is_file()})
checked_stamps={}
def check():
 for name,digest in load(O/'frozen-inputs.json').items():
  stat=Path(name).stat();stamp=(stat.st_ino,stat.st_size,stat.st_mtime_ns,stat.st_ctime_ns)
  if checked_stamps.get(name)!=stamp:
   assert sha(name)==digest,name
   checked_stamps[name]=stamp
 p=load(O/'protocol.json');assert len(p['schedule'])==p.get('sessions',528) and sum(len(r['prompts']) for r in p['schedule'])==p.get('answers',588)
 assert p['measurement_contract']==measurement.CONTRACT
 # Mutable fixture is restored and source-validated by setup_entry before each model session.
 return p

def prepare():
 assert not (O/'protocol.json').exists() and (ROOT/'.quill/refs.json').exists()
 ref=load(REPO/'benchmarks/results/cryostat-full-native-current-v4-quill-only/protocol.json');plan=measurement.make_plan(ref,3,20261101)
 config={n:(ROOT/n).read_text() for n in [*r.CONFIG,'.gitignore'] if (ROOT/n).is_file()}
 import tomllib
 assert Path(tomllib.loads(config['.codex/config.toml'])['mcp_servers']['quill']['command']).resolve()==NATIVE.resolve()
 assert Path(json.loads(config['.mcp.json'])['mcpServers']['quill']['command']).resolve()==NATIVE.resolve()
 assert tomllib.loads(config['.codex/config.toml'])['mcp_servers']['quill']['args']==['--mcp','--tools','codex']
 assert json.loads(config['.mcp.json'])['mcpServers']['quill']['args']==['--mcp','--tools','claude']
 # Fresh metadata belongs to this fixture; never reuse path-bound captures from the user's checkout.
 expected={n:h for n,h in ref['source_files'].items() if n!='.gitignore'}
 files={n:sha(ROOT/n) for n in expected};assert files==expected,'Frozen Cryostat source differs'
 put(O/'managed-source-exclusions.json',{'.gitignore':{'reason':'Native-generated integration ignore entry differs by arm; application source/resources/POM/wrapper unchanged. Separately guarded after setup.','original_source_sha256':ref['source_files']['.gitignore']}})
 put(O/'source-inputs.json',files);put(O/'managed-configs.json',config)
 for name in ['ready-target','ready-index']:
  src=ROOT/('target' if name=='ready-target' else '.quill');dest=F/name;assert not dest.exists();subprocess.run(['/bin/cp','-cR',str(src),str(dest)],check=True)
 for path in (INDEX/'prefetch').glob('*') if (INDEX/'prefetch').exists() else []:
  if path.is_file():path.unlink()
 times={}
 for p in ROOT.rglob('*'):
  rel=p.relative_to(ROOT)
  if p.is_file() and not any(x in rel.parts for x in ['.git','.quill','node_modules','.yarn','.quinoa']):times[str(rel)]=[p.stat().st_atime_ns,p.stat().st_mtime_ns]
 put(O/'file-times.json',times)
 versions={c:subprocess.check_output([str(RUNTIME/c),'--version'],text=True).strip() for c in ['codex','claude']}
 p={**ref,**{k:plan[k] for k in ['measurement_contract','schedule','sessions','answers','repeats','seed']},'arms':['without_quill','with_quill'],'native_binary':str(NATIVE),'native_sha256':sha(NATIVE),'models':ref['models'],'cli_versions_actual':versions,'source_commit':load(O/'preparation/native-build-proof.json')['commit'],'fixture_revision':git('rev-parse','HEAD'),'source_files':files,'classfiles':hashes(F/'ready-target','*.class'),'index':str(INDEX),'scope':'44applicable cases,3paired repeats,Codex/Claude with and without current Quill;528sessions588answers. Native client profiles/hooks; no hints, forced calls, caps, retries or repairs.','order':'Preregistered seed20261101; randomized pair order, AB/BA alternating across repeats.','historical_baseline':None,'paired_baseline':'Fresh without_quill sessions in this schedule; historical v4 is separate diagnostic only.','cache':'Provider cache observed/uncontrolled; fresh conversations are not claimed cold. All prewarm included.','continuation':'Started sessions never replayed; explicit continue permits only never-started suffix. External failures retained; two consecutive failures without successful session stop spending.','JAVA_HOME':'/Users/treblereel/.sdkman/candidates/java/25.0.1-graalce','original_project':'/Users/treblereel/workspace/redhat/cryostat','permission_policy':'Private CLI homes and OS isolation; source arm no MCP/index/managed guidance/native binary. Shared writable private Maven cache only for patch sessions, same policy both arms.','preparation':'Copied current native/CLIs; fresh canonical disposable clone built and initialized separately from original project. One real MCP per Quill session. Readiness and restoration proved offline.'}
 p.update(native_build_proof=load(O/'preparation/native-build-proof.json'),codex_binary=str(RUNTIME/'codex'),claude_binary=str(RUNTIME/'claude'))
 put(O/'protocol.json',p);freeze();print('Prepared528sessions588answers;0provider calls',flush=True)

def main(mode):
 lock=(O/'runner.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 global_lock=Path('/tmp/quill-cryostat-full-suite.lock').open('a');fcntl.flock(global_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 if mode=='prepare':prepare();return
 p=check()
 if mode=='plan':print(json.dumps({'sessions':p['sessions'],'answers':p['answers'],'N/A':6,'provider_calls':0}));return
 assert mode in ['start','continue'] and load(O/'preflight.json')['passed'] and load(O/'preflight-client.json')['passed']
 if mode=='start':
  assert not (O/'manager.json').exists(),'Never replay an existing acquisition';state={'status':'running','planned_sessions':p['sessions'],'planned_answers':p['answers'],'started_at':time.time(),'sessions':[],'consecutive_failures':0}
 else:
  state=load(O/'manager.json');assert state['status'] in ['stopped_error','interrupted'];assert [r['key'] for r in state['sessions']]==[r['key'] for r in p['schedule'][:len(state['sessions'])]];assert all(r['status'] in ['completed','failed','interrupted'] for r in state['sessions']);state['status']='running'
 put(O/'manager.json',state)
 try:
  with capture.instrument_codex(r,MITM):
   for entry in p['schedule'][len(state['sessions']):]:
    check();row={**entry,'status':'started','started_at':time.time()};state['sessions'].append(row);put(O/'manager.json',state)
    try:isolated(row,O/'sessions'/row['key']);row.update(status='completed',finished_at=time.time());state['consecutive_failures']=0
    except Exception as error:
     row.update(status='failed',finished_at=time.time(),error=str(error)[:2000])
     # An agent editing a read-only task or hitting a denied command is a result of that session, not a broken run.
     behavior=any(x in str(error) for x in ['Readonly task changed tracked source','Claude error/permission denial'])
     if not behavior:
      state['consecutive_failures']+=1
      external=any(x in str(error).lower() for x in ['connection reset','connection aborted','service unavailable','502','503','overloaded'])
      if not external or state['consecutive_failures']>=2:state['status']='stopped_error';raise
    finally:put(O/'manager.json',state)
    print(row['key'],row['status'],len(state['sessions']),flush=True)
  state.update(status='completed_schedule',finished_at=time.time())
 except BaseException as error:
  if state['status']=='running':state.update(status='interrupted' if isinstance(error,KeyboardInterrupt) else 'stopped_error',error=str(error)[:2000],stopped_at=time.time())
  raise
 finally:
  put(O/'manager.json',state);subprocess.run(['git','-C',str(ROOT),'reset','--hard',p['fixture_revision']],capture_output=True)
  for helper in load(O/'previous-helper-quarantine.json'):
   original=Path(helper['original']);retained=Path(helper['retained'])
   if retained.exists() and not original.exists():retained.rename(original)
if __name__=='__main__':main(sys.argv[1])
