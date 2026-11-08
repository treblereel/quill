"""Frozen native CLI schedule. No answer hints, projections, retries or response repairs."""
import argparse,fcntl,hashlib,importlib.util,json,os,queue,shutil,signal,subprocess,sys,tempfile,threading,time
from pathlib import Path
OUT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(OUT/'runtime'))
sys.path.insert(0,str(OUT/'frozen-scripts'))
from quill_cli_hooks import trust_reviewed_hooks
from rpc import RPC
from vertex_capture import VertexCapture,adc_token
from isolation import profile,probe
ROOT=Path('/tmp/quill-cryostat-full-paired-v7/project')
RUNTIME=Path('/tmp/quill-cryostat-full-paired-v7/runtime')
INDEX=Path('/tmp/quill-cryostat-full-paired-v7/ready-index')
CONFIG=['.claude/settings.local.json','.claude/quill-permissions.json','AGENTS.md','CLAUDE.md','.mcp.json','.codex/config.toml','.codex/hooks.json','.claude/settings.json','.mvn/extensions.xml']
def load(p):return json.loads(Path(p).read_text())
def write(p,v):Path(p).parent.mkdir(parents=True,exist_ok=True);Path(p).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def digest(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def rpc_args(p,d,quill,sandbox):
 args=['/usr/bin/sandbox-exec','-f',str(sandbox),p['codex_binary']]
 def cfg(k,v):args.extend(['-c',k+'='+json.dumps(v)])
 for k,v in {'features.apps':False,'features.plugins':False,'features.multi_agent':False,'features.hooks':quill}.items():cfg(k,v)
 args.extend(['-c','projects={'+','.join(json.dumps(str(x))+'={trust_level="trusted"}' for x in dict.fromkeys([ROOT,ROOT.resolve()]))+'}'])
 cfg('model_reasoning_effort','medium')
 if quill:
  for k,v in {'command':sys.executable,'args':[str(OUT/'runtime/mcp_tap.py'),p['native_binary'],str(ROOT),str(d/'mcp')],'cwd':str(ROOT),'required':True,'startup_timeout_sec':600,'tool_timeout_sec':180}.items():cfg('mcp_servers.quill.'+k,v)
 return args+['app-server','--listen','stdio://']

def save_prefetch_evidence(root,d,thread,hooks):
 state=root/'.quill/prefetch'/(hashlib.sha256(thread.encode()).hexdigest()+'.json')
 failed=[e['params']['run'] for e in hooks if e['params']['run'].get('status')!='completed']
 timed_out=any('timed out' in str(run.get('entries',[])).lower() for run in failed)
 evidence={'state_present':state.exists(),'hook_failures':failed,'hook_status':'timed_out' if timed_out else 'failed' if failed else 'completed','delivery':'state_present' if state.exists() else 'absent_unknown','scope':'Hook evidence only; model-visible context audited independently from raw wire.'}
 write(d/'native-prefetch-delivery.json',evidence)
 if state.exists():shutil.copy2(state,d/'native-prefetch-state.json')
 return evidence

def codex(p,entry,d,env,sandbox,preflight=False):
 # RPC inherits only this case's private HOME/configuration from the parent context.
 started=time.perf_counter();rpc=RPC(d,rpc_args(p,d,entry['arm']=='with_quill',sandbox),ROOT);turns=[];thread=None;previous={'inputTokens':0,'cachedInputTokens':0,'outputTokens':0}
 try:
  write(d/'initialize.json',rpc.request('initialize',{'clientInfo':{'name':'quill_cdi50_baseline','version':'1'},'capabilities':{'experimentalApi':True}}));rpc.send({'method':'initialized','params':{}})
  if entry['arm']=='with_quill':write(d/'hook-trust.json',trust_reviewed_hooks(rpc,ROOT,json.loads(p['managed_configs']['.codex/hooks.json'])))
  response=rpc.request('thread/start',{'cwd':str(ROOT),'model':p['models']['codex'],'approvalPolicy':'never','sandbox':'danger-full-access','historyMode':'legacy','experimentalRawEvents':True},timeout=660)
  write(d/'thread-start.json',response);thread=response['thread']['id']
  if entry['arm']=='with_quill':
   while not any(e.get('method')=='mcpServer/startupStatus/updated' and e.get('params',{}).get('threadId')==thread and e['params'].get('name')=='quill' and e['params'].get('status')=='ready' for e in rpc.events):
    event=rpc.event(timeout=660)
    if event.get('method')=='mcpServer/startupStatus/updated' and event['params'].get('status')=='failed':raise RuntimeError('MCP startup failed before model')
   assert load(d/'mcp/readiness.json')['ready']
  startup=time.perf_counter()-started
  if preflight:
   models=rpc.request('model/list',{'limit':100});write(d/'models.json',models)
   write(d/'preflight.json',{'passed':True,'model_turns':0,'thread':thread,'startup_seconds':startup});return
  for number,prompt in enumerate(entry['prompts'],1):
   td=d/f'turn-{number:02}';td.mkdir();write(td/'started.json',{'prompt':prompt,'at':time.time()});offset=len(rpc.events);t=time.perf_counter()
   tid=rpc.request('turn/start',{'threadId':thread,'input':[{'type':'text','text':prompt}],'effort':'medium'})['turn']['id']
   while not any(e.get('method')=='turn/completed' and e['params']['turn']['id']==tid for e in rpc.events[offset:]):rpc.event()
   events=rpc.events[offset:];finish=next(e['params']['turn'] for e in events if e.get('method')=='turn/completed')
   messages=[e['params']['item'] for e in events if e.get('method')=='item/completed' and e['params']['item']['type']=='agentMessage']
   usage=[e['params']['tokenUsage'] for e in events if e.get('method')=='thread/tokenUsage/updated']
   write(td/'events.json',events)
   if not usage:raise RuntimeError('No token usage; preserve attempt, no replay')
   total=usage[-1]['total'];delta={k:total[k]-previous[k] for k in previous};previous={k:total[k] for k in previous}
   assert all(v>=0 for v in delta.values()),delta
   answer=messages[-1]['text'] if messages else '';(td/'answer.txt').write_text(answer)
   turns.append({'number':number,'answer':answer,'seconds':time.perf_counter()-t,'usage':delta,'cumulative_usage':total,'status':finish['status'],'turn_id':tid});write(d/'turns.json',turns)
   if finish['status']!='completed':raise RuntimeError('Model turn did not complete; no retry')
  if entry['arm']=='with_quill':
   hooks=[e for e in rpc.events if e.get('method')=='hook/completed'];write(d/'hook-events.json',hooks);assert len(hooks)>=len(entry['prompts'])+1
   save_prefetch_evidence(ROOT,d,thread,hooks)
  write(d/'session.json',{'status':'completed','thread':thread,'startup_seconds':startup,'wall_seconds':time.perf_counter()-started,'turns':len(turns),'usage':previous,'usage_complete':True})
 finally:
  rpc.close()
  if thread:
   paths=list((Path(env['CODEX_HOME'])/'sessions').rglob('*'+thread+'*.jsonl'))
   if len(paths)==1:shutil.copy2(paths[0],d/'rollout.jsonl')
   elif not preflight:write(d/'rollout-audit-error.json',{'matching_rollouts':len(paths)})

def claude(p,entry,d,env,sandbox,preflight=False):
 mcp={'mcpServers':{}}
 if entry['arm']=='with_quill':mcp['mcpServers']['quill']={'alwaysLoad':True,'command':sys.executable,'args':[str(OUT/'runtime/mcp_tap.py'),p['native_binary'],str(ROOT),str(d/'mcp')]}
 write(d/'mcp.json',mcp)
 args=['/usr/bin/sandbox-exec','-f',str(sandbox),p['claude_binary'],'-p','--input-format','stream-json','--output-format','stream-json','--include-partial-messages','--verbose','--model',p['models']['claude'],'--effort','medium','--setting-sources','project','--settings',json.dumps({'disableAllHooks':entry['arm']!='with_quill','autoMemoryEnabled':False}),'--strict-mcp-config','--mcp-config',str(d/'mcp.json'),'--tools','Read,Bash,Glob,Grep,ToolSearch,Edit,Write','--allowedTools','Read,Bash,Glob,Grep,ToolSearch,Edit,Write,mcp__quill__*','--permission-mode','bypassPermissions','--no-session-persistence','--disable-slash-commands']
 write(d/'argv.json',args);events=queue.Queue();turns=[];start=time.perf_counter();stderr=(d/'stderr.log').open('w')
 with VertexCapture(d/'transport',p['vertex_project'],p['vertex_region'],adc_token) as capture:
  local=dict(env);local.update(CLAUDE_CODE_USE_VERTEX='1',ANTHROPIC_VERTEX_PROJECT_ID=p['vertex_project'],CLOUD_ML_REGION=p['vertex_region'],ANTHROPIC_VERTEX_BASE_URL=capture.sdk_url,CLAUDE_CODE_SKIP_VERTEX_AUTH='1',DISABLE_AUTOUPDATER='1',CLAUDE_CODE_DISABLE_AUTO_MEMORY='1',CLAUDE_CODE_DISABLE_BUNDLED_SKILLS='1',CLAUDE_CODE_DISABLE_GIT_INSTRUCTIONS='1',CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1',ENABLE_TOOL_SEARCH='auto',MCP_TIMEOUT='600000',MCP_TOOL_TIMEOUT='180000')
  if entry['arm']=='without_quill':local['CLAUDE_CODE_DISABLE_CLAUDE_MDS']='1'
  child=subprocess.Popen(args,cwd=ROOT,env=local,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=stderr,text=True,start_new_session=True)
  def reader():
   try:
    with (d/'events.jsonl').open('w') as log:
     for line in child.stdout:
      log.write(line);log.flush();events.put(json.loads(line))
   finally:events.put(None)
  thread=threading.Thread(target=reader,daemon=True);thread.start()
  try:
   # Standard Agent SDK control handshake; no prompt, hook override or model request.
   init={'type':'control_request','request_id':'native-startup','request':{'subtype':'initialize'}}
   child.stdin.write(json.dumps(init)+'\n');child.stdin.flush();write(d/'control-initialize.json',init)
   while True:
    event=events.get(timeout=660)
    if event is None:raise RuntimeError('Claude EOF before control initialization')
    if event.get('type')=='control_response' and event.get('response',{}).get('request_id')=='native-startup':
     assert event['response'].get('subtype')=='success',event;write(d/'control-initialized.json',event);break
   if entry['arm']=='with_quill':
    deadline=time.monotonic()+600
    while not (d/'mcp/readiness.json').exists():
     if child.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Claude MCP not ready before first prompt')
     time.sleep(.1)
    assert load(d/'mcp/readiness.json')['ready']
   if preflight:
    write(d/'preflight.json',{'passed':True,'questions_submitted':0,'provider_requests':capture.sequence,'ready_before_first_prompt':True});return
   for number,prompt in enumerate(entry['prompts'],1):
    td=d/f'turn-{number:02}';td.mkdir();write(td/'started.json',{'prompt':prompt,'at':time.time()});t=time.perf_counter()
    child.stdin.write(json.dumps({'type':'user','message':{'role':'user','content':prompt}})+'\n');child.stdin.flush()
    while True:
     event=events.get(timeout=300)
     if event is None:raise RuntimeError('Claude exited before result')
     if event.get('type')=='system' and event.get('subtype')=='init':
      servers=event.get('mcp_servers',[])
      assert (any(s.get('name')=='quill' and s.get('status')=='connected' for s in servers) if entry['arm']=='with_quill' else not servers),servers
     if event.get('type')=='result':
      write(td/'result.json',event);(td/'answer.txt').write_text(event.get('result',''))
      turns.append({'number':number,'seconds':time.perf_counter()-t,'answer':event.get('result',''),'event':event});write(d/'turns.json',turns)
      if event.get('is_error') or event.get('permission_denials'):raise RuntimeError('Claude error/permission denial; no repair/retry')
      break
   # A session may leave a background task that Claude must stop after the answer.
   child.stdin.close();child.wait(timeout=180);thread.join(timeout=5)
   assert child.returncode==0
   write(d/'session.json',{'status':'completed','session_id':turns[-1]['event'].get('session_id'),'wall_seconds':time.perf_counter()-start,'turns':len(turns),'cumulative_model_usage':turns[-1]['event'].get('modelUsage'),'cumulative_cost_usd_estimate':turns[-1]['event'].get('total_cost_usd'),'usage_complete':not capture.errors})
  finally:
   if child.poll() is None:os.killpg(child.pid,signal.SIGTERM);child.wait(timeout=10)
   stderr.close()


def setup_entry(entry,d,p):
 # The base checkout is disposable. Restore only it; original Cryostat is never modified.
 subprocess.run(['git','reset','--hard',p['cryostat_revision']],cwd=ROOT,capture_output=True,check=True)
 subprocess.run(['git','clean','-fdx','-e','target','-e','.quill'],cwd=ROOT,capture_output=True,check=True)
 subprocess.run(['/usr/bin/rsync','-a','--delete',str(INDEX.parent/'ready-target')+'/',str(ROOT/'target')+'/'],check=True,capture_output=True)
 for name,expected in p['source_files'].items():assert digest(ROOT/name)==expected,name
 for name in CONFIG:(ROOT/name).unlink(missing_ok=True)
 if (ROOT/'.quill').exists():shutil.rmtree(ROOT/'.quill')
 if entry['arm']=='with_quill':
  subprocess.run(['/bin/cp','-cR',str(INDEX),str(ROOT/'.quill')],check=True)
  for name,text in p['managed_configs'].items():
   f=ROOT/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text)
 for name,times in p['file_times_ns'].items():
  f=ROOT/name
  if f.exists():os.utime(f,ns=tuple(times))
 d.mkdir(parents=True,exist_ok=False)


def isolated(entry,d,p,preflight=False):
 setup_entry(entry,d,p)
 old_env=dict(os.environ)
 with tempfile.TemporaryDirectory(prefix='quill-cdi50-home-') as tmp:
  temp=Path(tmp);home=temp/'codex';home.mkdir(mode=0o700);claude_home=temp/'claude';claude_home.mkdir(mode=0o700);scratch=temp/'scratch';scratch.mkdir()
  auth=Path(old_env.get('CODEX_HOME',str(Path.home()/'.codex')))/'auth.json'
  shutil.copy2(auth,home/'auth.json');(home/'auth.json').chmod(0o600)
  (home/'config.toml').write_text('')
  env=dict(old_env);env.update(CODEX_HOME=str(home),CLAUDE_CONFIG_DIR=str(claude_home),TMPDIR=str(scratch),CLAUDE_CODE_TMPDIR=str(scratch))
  allowed=[ROOT,d,home,claude_home,scratch,OUT/'runtime',RUNTIME,Path.home()/'.m2/repository',Path.home()/'.m2/wrapper',Path.home()/'.sdkman',Path.home()/'Library/Java/JavaVirtualMachines',Path.home()/'Library/Caches/JNA']
  allowed+=[Path.home()/f for f in ['.zshrc','.zprofile','.zshenv','.gitconfig','.config/git','.config/jgit/config']]
  sb=d/'isolation.sb';text=profile(allowed)
  # Prevent writes to the actual checkout or user data, while allowing this disposable copy.
  paths=' '.join('(subpath '+json.dumps(str(f.resolve()))+')' for f in [ROOT,d,home,claude_home,scratch])
  text+='\n(deny file-write* (require-all (subpath "/Users") (require-not (require-any '+paths+'))))\n'
  if entry['client']=='claude':text+='\n(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n'
  sb.write_text(text)
  blocked=[OUT/'protocol.json',OUT.parents[1]/'cdi-quarkus-50/cryostat/oracles.json',Path('/Users/treblereel/workspace/redhat/cryostat/pom.xml')]
  for f in blocked:assert f.exists(),f
  checks=probe(sb,[ROOT/'pom.xml',ROOT/'target/classes/io/cryostat/security/rbac/PermissionMapper.class'],blocked,scratch);write(d/'isolation-probe.json',checks)
  os.environ.clear();os.environ.update(env)
  try:
   if entry['client']=='codex':codex(p,entry,d,env,sb,preflight)
   else:claude(p,entry,d,env,sb,preflight)
  finally:
   os.environ.clear();os.environ.update(old_env)
   (d/'patch.diff').write_bytes(subprocess.check_output(['git','diff','--binary'],cwd=ROOT))
   changed=[name for name,expected in p['source_files'].items() if not (ROOT/name).exists() or digest(ROOT/name)!=expected]
   write(d/'source-changes.json',changed)
   if entry['case']!='CQ-049':assert not changed,'Readonly task changed tracked source'
   if entry['arm']=='with_quill':
    requests=[json.loads(line) for line in (d/'mcp/client-to-native.jsonl').read_text().splitlines()]
    assert sum(r.get('method')=='initialize' for r in requests)==1,'MCP initialized more than once'
    assert load(d/'mcp/readiness.json')['ready']

