"""Real Claude control initialization, zero questions, upstream connection hard blocked."""
import importlib.util,json,os
from pathlib import Path
O=Path(__file__).resolve().parent;s=importlib.util.spec_from_file_location('runner',O/'run.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
real=m.r.VertexCapture;attempts=[]
def forbidden(*args,**kwargs):
 attempts.append(True);raise AssertionError('No upstream connection allowed during zero-model startup')
def local_only(directory,project,region,*args,**kwargs):return real(directory,project,region,lambda:'local-only-no-credential',connection_factory=forbidden)
m.r.VertexCapture=local_only
proof=[]
for arm in ['without_quill','with_quill']:
 entry={'case':'preflight','client':'claude','arm':arm,'prompts':[]};d=O/'preflight-client'/arm;m.active_arm=arm;old=dict(os.environ)
 try:m.r.isolated(entry,d,m.parameter(arm),preflight=True)
 finally:os.environ.clear();os.environ.update(old);m.active_arm=None
 p=m.load(d/'preflight.json');assert p['questions_submitted']==0 and p['provider_requests']==0 and not attempts;proof.append({'arm':arm,**p})
m.put(O/'preflight-client.json',{'passed':True,'questions_submitted':0,'provider_requests':0,'outbound_connection_attempts':len(attempts),'checks':proof})
print('Real Claude startup passed both arms; MCP ready before question;0questions/0provider requests/0outbound attempts',flush=True)
