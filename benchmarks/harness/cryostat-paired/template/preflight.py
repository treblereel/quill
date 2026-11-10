"""Zero-model preflight: strict fresh snapshot, sandbox, source-only exclusion, hook review."""
import importlib.util,json,os,shutil,sys,tempfile,time,subprocess,uuid,hashlib
from pathlib import Path
O=Path(__file__).resolve().parent
sp=importlib.util.spec_from_file_location('runner',O/'run.py');m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
sys.path.insert(0,str(m.REPO/'scripts'));from quill_workflow_benchmark import WorkflowClient
p=m.check();proof=[]
for client,arm in [('codex','without_quill'),('codex','with_quill'),('claude','with_quill')]:
 d=O/'preflight'/f'{client}-{arm}';e={'case':'preflight','client':client,'arm':arm,'prompts':[]};m.active_arm=arm;before=dict(os.environ)
 try:
  m.setup(e,d,m.parameter(arm));os.environ['TMPDIR']=str(d/'build-env/tmp');assert m.hashes(m.ROOT/'target','*.class')==p['classfiles']
  paths=[m.ROOT,m.RUNTIME,O/'runtime',O/'frozen-scripts',d,Path.home()/'.m2/repository',Path.home()/'.m2/wrapper',Path.home()/'.sdkman',Path.home()/'Library/Java/JavaVirtualMachines',Path.home()/'Library/Caches/JNA']
  paths += [Path.home()/x for x in ['.zshrc','.zprofile','.zshenv','.gitconfig','.config/git','.config/jgit/config']]
  sb=d/'isolation.sb';text=m.profile(paths)+'\n(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n';sb.write_text(text)
  blocked=[O/'oracles.json',O/'protocol.json',Path('/Users/treblereel/workspace/redhat/cryostat/pom.xml'),m.INDEX/'refs.json']
  if arm=='without_quill':blocked.extend([m.NATIVE,Path('/opt/homebrew/bin/quill').resolve()])
  checks=m.r.probe(sb,[m.ROOT/'pom.xml',m.ROOT/'target/classes/io/cryostat/security/rbac/PermissionMapper.class'],blocked,d)
  if arm=='with_quill':
   captures=list((m.ROOT/'target').glob('quill-java-*.properties'));inputs=set()
   for capture in captures:
    for line in capture.read_text().splitlines():
     if line.startswith('buildInput.path.'):
      path=Path(line.split('=',1)[1])
      if path.is_file():inputs.add(path)
   checks+=m.r.probe(sb,sorted(inputs),[],d)
   argv=[sys.executable,str(O/'runtime/mcp_tap.py'),str(m.NATIVE),str(m.ROOT),str(d/'mcp')]
   code='import os;os.execv('+repr(sys.executable)+','+repr(argv)+')'
   with WorkflowClient(['/usr/bin/sandbox-exec','-f',str(sb),sys.executable,'-c',code],m.ROOT,240) as q:
    q.request('tools/list',{});data=m.load(d/'mcp/readiness.json');assert data['ready'];meta=data['overview']['_meta'];assert not meta.get('stale_warning') and meta['answer_confidence']=='high';overview=q.request('tools/call',{'name':'execute_tool','arguments':{'name':'get_overview','arguments':{}}});assert not overview.get('isError');assert not overview['structuredContent']['_meta'].get('stale_warning')
   assert 'Operation not permitted' not in (d/'mcp/native-stderr.log').read_text()
   assert 'dependency index degraded' not in (d/'mcp/native-stderr.log').read_text()
   # Exercise the installed handler under the actual sandbox, with no provider/client turn.
   probe=next((e for e in p['schedule'] if e['case']=='CQ-033'),p['schedule'][0]);question=probe['prompts'][0];session='offline-'+uuid.uuid4().hex
   cfg=json.loads(m.parameter(arm)['managed_configs']['.codex/hooks.json'] if client=='codex' else m.parameter(arm)['managed_configs']['.claude/settings.json']);hook=cfg['hooks']['UserPromptSubmit'][0]['hooks'][0]
   payload={'hook_event_name':'UserPromptSubmit','session_id':session,'cwd':str(m.ROOT),'prompt':question};started=time.monotonic()
   code="import json,subprocess,sys,time;p=json.loads(sys.argv[1]);s=time.monotonic();r=subprocess.run(['/bin/sh','-c',p['command']],input=json.dumps(p['payload']),capture_output=True,text=True,timeout=p['timeout']);print(json.dumps({'stdout':r.stdout,'stderr':r.stderr,'returncode':r.returncode,'seconds':time.monotonic()-s}))"
   outer=subprocess.run(['/usr/bin/sandbox-exec','-f',str(sb),sys.executable,'-c',code,json.dumps({'command':hook['command'],'payload':payload,'timeout':hook['timeout']})],capture_output=True,text=True,timeout=180+hook['timeout'],cwd=m.ROOT);assert outer.returncode==0,outer.stderr
   result=__import__('types').SimpleNamespace(**json.loads(outer.stdout))
   (d/'hook.stdout').write_text(result.stdout);(d/'hook.stderr').write_text(result.stderr);context=json.loads(json.loads(result.stdout)['hookSpecificOutput']['additionalContext']);assert result.returncode==0 and context.get('producer')=='quill.prefetch' and context.get('facts')
   m.put(d/'hook-health.json',{'passed':True,'case':probe['case'],'seconds':result.seconds,'including_sandbox_startup_seconds':time.monotonic()-started,'deadline':hook['timeout'],'facts':len(context['facts']),'model_calls':0})
   for suffix in ['.json','.lock']:(m.ROOT/'.quill/prefetch'/(hashlib.sha256(session.encode()).hexdigest()+suffix)).unlink(missing_ok=True)
   proof.append({'client':client,'arm':arm,'native_ready':True,'checks':checks,'metadata':meta,'provider_calls':0})
  else:proof.append({'client':client,'arm':arm,'no_mcp_or_index':True,'checks':checks,'provider_calls':0})
 finally:os.environ.clear();os.environ.update(before);m.active_arm=None
# hooks/list and config/batchWrite do not start a thread or issue a provider prewarm.
d=O/'preflight/codex-hooks';m.active_arm='with_quill';m.setup({'case':'preflight','client':'codex','arm':'with_quill','prompts':[]},d,m.parameter('with_quill'))
with tempfile.TemporaryDirectory(prefix='quill-preflight-auth-') as temp:
 home=Path(temp);auth=Path(os.environ.get('CODEX_HOME',str(Path.home()/'.codex')))/'auth.json';shutil.copy2(auth,home/'auth.json');(home/'config.toml').write_text('');env=dict(os.environ);os.environ['CODEX_HOME']=str(home)
 try:
  paths=[m.ROOT,m.RUNTIME,O/'runtime',O/'frozen-scripts',d,home,Path.home()/'.sdkman',Path.home()/'.m2/repository'];sb=d/'isolation.sb';sb.write_text(m.profile(paths))
  rpc=m.r.RPC(d,m.r.rpc_args(m.parameter('with_quill'),d,True,sb),m.ROOT)
  try:
   rpc.request('initialize',{'clientInfo':{'name':'quill_zero_model_preflight','version':'1'},'capabilities':{'experimentalApi':True}});rpc.send({'method':'initialized','params':{}})
   trust=m.r.trust_reviewed_hooks(rpc,m.ROOT,json.loads(m.parameter('with_quill')['managed_configs']['.codex/hooks.json']));m.put(d/'hook-trust.json',trust)
   models=rpc.request('model/list',{'limit':100});m.put(d/'models.json',models)
  finally:rpc.close()
 finally:os.environ.clear();os.environ.update(env);m.active_arm=None
assert not any('thread/start' in line or 'turn/start' in line for line in (d/'client.jsonl').read_text().splitlines())
assert m.hashes(m.ROOT/'target','*.class')==p['classfiles']
m.put(O/'preflight.json',{'passed':True,'provider_calls':0,'thread_starts':0,'checks':proof,'exact_native_hooks_trusted':True,'classfiles_unchanged':m.hashes(m.ROOT/'target','*.class')==p['classfiles']})
print('Preflight passed:source isolation,2native profiles,3restores,hooks review,0provider calls')
