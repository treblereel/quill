"""Local failure/unknown accounting and new-runner adapter checks. No model/network."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import quill_codex_capture as c

SOURCE=Path('benchmarks/results/codex-wire-capture-local-v1')
sse=c.wire_usage(SOURCE/'sse/wire');ws=c.wire_usage(SOURCE/'ws/wire')
assert sse['usage_complete'] and sse['totals']['all']==22
assert ws['usage_complete'] and ws['totals']['all']==42
assert len(ws['requests'])==2 and sum(r['prewarm'] for r in ws['requests'])==1
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp);f=d/'flow';f.mkdir();meta={'method':'POST','status':200,'completed':True,'request_unchanged':True}
 def write(name,v):(f/name).write_text(json.dumps(v))
 write('http.json',meta);write('request.json',{})
 (f/'response.raw').write_text('data: '+json.dumps({'type':'response.completed','response':{'id':'r','usage':{'input_tokens':10,'output_tokens':1,'input_tokens_details':{'cached_tokens':3}}}})+'\n\n')
 assert c.wire_usage(d)['totals']['all']==11
 meta['completed']=False;write('http.json',meta);assert not c.wire_usage(d)['usage_complete']
 meta['completed']=True;write('http.json',meta);write('incomplete.json',{'complete':False});assert not c.wire_usage(d)['usage_complete'];(f/'incomplete.json').unlink()
 (f/'response.raw').write_text('data: '+json.dumps({'type':'response.completed','response':{'id':'r','usage':{'input_tokens':10,'output_tokens':1}}})+'\n\n');assert c.wire_usage(d)['totals'] is None
 (f/'response.raw').write_text('invalid');assert c.wire_usage(d)['totals'] is None
 (f/'http.json').write_text('bad json');assert not c.wire_usage(d)['usage_complete']
 x=c.CodexCapture(d/'not-started','/tmp/quill-codex-wire-tools-v1/bin/mitmdump');x.port=12345;x.ca=d/'ca'
 env=x.environment({});assert env['WSS_PROXY']=='http://127.0.0.1:12345' and env['https_proxy']==env['HTTPS_PROXY']
 try:x.environment({'HTTPS_PROXY':'existing-proxy'})
 except RuntimeError:pass
 else:raise AssertionError('Existing proxy silently replaced')
 # Adapter: original model argv unchanged; CA read allowed, direct network denied;
 # env applied to child only, restored even if the measured runner raises.
 original_capture=c.CodexCapture
 class Dummy:
  def __init__(self,d,*a):self.directory=Path(d);self.ca=Path(tmp)/'ca'
  def __enter__(self):return self
  def environment(self,env):return x.environment(env)
  def __exit__(self,*a):pass
 c.CodexCapture=Dummy
 calls=[]
 def args(*a):return ['codex','app-server','--listen','stdio://']
 def runner(*a):
  cmd=r.rpc_args(1,2,3,a[4]);assert cmd[-4:]==args();assert c.os.environ.get('WSS_PROXY')!=x.environment({})['WSS_PROXY'];calls.append(cmd)
  raise ValueError('deliberate runner failure')
 r=SimpleNamespace(codex=runner,rpc_args=args);profile=d/'base.sb';profile.write_text('(version 1)(allow default)')
 try:
  with c.instrument_codex(r,'unused'):
   try:r.codex({}, {}, d, {}, profile)
   except ValueError:pass
   else:raise AssertionError('Original runner error swallowed')
  assert r.codex is runner and r.rpc_args is args and len(calls)==1
  assert json.loads((d/'wire-usage.json').read_text())['usage_complete'] is False
  assert 'deny network-outbound' in (d/'capture-isolation.sb').read_text()
 finally:c.CodexCapture=original_capture
print('Transport audit and adapter checks passed;0provider calls')

# Successful adapter uses complete wire totals, retains original CLI counters,
# preserves return value, and refuses to overwrite a previously started capture.
import shutil
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp);profile=d/'base.sb';profile.write_text('(version 1)(allow default)')
 class Copied(Dummy):
  def __enter__(self):shutil.copytree(SOURCE/'ws/wire',self.directory);return self
 c.CodexCapture=Copied
 def succeeded(p,entry,directory,env,sandbox,preflight=False):
  if preflight:return 'preflight'
  (directory/'session.json').write_text(json.dumps({'usage':{'inputTokens':20,'cachedInputTokens':0,'outputTokens':2}}))
  return 'completed'
 r=SimpleNamespace(codex=succeeded,rpc_args=args)
 try:
  with c.instrument_codex(r,'unused'):
   assert r.codex({}, {}, d, {}, profile, True)=='preflight'
   assert r.codex({}, {}, d, {}, profile)=='completed'
   ledger=json.loads((d/'wire-usage.json').read_text());assert ledger['totals']['input']==40 and ledger['wire_minus_cli']['input']==20
   try:r.codex({}, {}, d, {}, profile)
   except FileExistsError:pass
   else:raise AssertionError('Started capture reused')
  assert r.codex is succeeded and r.rpc_args is args
 finally:c.CodexCapture=original_capture
print('Success/preflight/prewarm-ledger/reuse guards passed')

# Same response replayed on another HTTP flow is not silently counted twice.
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp)
 for name in ['first','replayed']:
  f=d/name;f.mkdir();(f/'http.json').write_text(json.dumps({'method':'POST','status':200,'completed':True,'request_unchanged':True}));(f/'request.json').write_text('{}');(f/'response.raw').write_text('data: '+json.dumps({'type':'response.completed','response':{'id':'same-response','usage':{'input_tokens':10,'output_tokens':1,'input_tokens_details':{'cached_tokens':0}}}})+'\n\n')
 ledger=c.wire_usage(d);assert ledger['usage_complete'] is False and ledger['totals'] is None
print('Duplicate response replay is unknown, not double-counted')
