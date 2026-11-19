import json,queue,subprocess,threading
from pathlib import Path
def write(path,value):path.write_text(json.dumps(value,indent=2)+"\n")
class RPC:
 def __init__(self,d,args,cwd):
  self.d=d;self.q=queue.Queue();self.events=[];self.i=0;self.err=(d/'stderr.log').open('w');write(d/'argv.json',args)
  self.p=subprocess.Popen(args,cwd=cwd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.err,text=True)
  def reader():
   with (d/'server.jsonl').open('w') as log:
    for line in self.p.stdout:
     log.write(line);log.flush();self.q.put(json.loads(line))
   self.q.put(None)
  threading.Thread(target=reader,daemon=True).start()
 def send(self,v):
  with (self.d/'client.jsonl').open('a') as f:f.write(json.dumps(v)+'\n')
  self.p.stdin.write(json.dumps(v)+'\n');self.p.stdin.flush()
 def event(self,timeout=660):
  e=self.q.get(timeout=timeout)
  if e is None:raise RuntimeError('app-server EOF')
  self.events.append(e)
  if 'method' in e and 'id' in e:raise RuntimeError('Unexpected server request: '+str(e['method']))
  return e
 def request(self,method,params,timeout=300):
  self.i+=1;ident=self.i;self.send({'id':ident,'method':method,'params':params})
  while True:
   e=self.event(timeout)
   if e.get('id')==ident:
    if 'error' in e:raise RuntimeError(e['error'])
    return e['result']
 def close(self):
  self.p.stdin.close()
  try:self.p.wait(timeout=15)
  except subprocess.TimeoutExpired:self.p.terminate();self.p.wait(timeout=15)
  self.err.close()
