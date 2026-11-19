"""Readiness-gated stdio proxy: real lifecycle, demultiplexed probes, full upstream log."""
import json, os, selectors, subprocess, sys, time
from pathlib import Path

binary, root, target = sys.argv[1:]
directory = Path(target); directory.mkdir(parents=True, exist_ok=False)
cache=directory/'jna';cache.mkdir()
profile=json.loads((directory.parent/'native-profile.json').read_text())['profile'];assert profile in ['codex','claude']
child = subprocess.Popen([binary, '-Djna.tmpdir='+str(cache), '--mcp', '--tools',profile,'--project', root], stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=(directory/'native-stderr.log').open('ab'), bufsize=0)
(directory/'process.json').write_text(json.dumps({'pid':child.pid,'binary':binary})+'\n')
selector=selectors.DefaultSelector();selector.register(sys.stdin.fileno(),selectors.EVENT_READ,'client');selector.register(child.stdout.fileno(),selectors.EVENT_READ,'native')
buffers={'client':b'','native':b''};initialized=False;ready=False;deadline=None;due=None;pending=None;counter=0;held=[];client_list_ids=set();overview=None
logs={name:(directory/name).open('ab') for name in ['client-to-native.jsonl','native-to-client.jsonl','upstream.jsonl','warmup.jsonl']}
def record(direction, raw, own=False):
    logs['upstream.jsonl'].write(json.dumps({'time':time.time(),'direction':direction,'own_probe':own,'message':json.loads(raw)}).encode()+b'\n');logs['upstream.jsonl'].flush()
def upstream(raw, own=False):
    record('to_native',raw,own);child.stdin.write(raw);child.stdin.flush()
def downstream(raw):
    logs['native-to-client.jsonl'].write(raw);logs['native-to-client.jsonl'].flush();sys.stdout.buffer.write(raw);sys.stdout.buffer.flush()
def probe(name,args):
    global pending,counter
    counter+=1;request={'jsonrpc':'2.0','id':'__quill_benchmark_probe_'+str(counter),'method':'tools/call','params':{'name':name,'arguments':args}}
    pending=request;upstream((json.dumps(request)+'\n').encode(),True)
def accept_probe(message):
    global pending,due,ready,overview
    request=pending;pending=None
    logs['warmup.jsonl'].write(json.dumps({'request':request,'response':message}).encode()+b'\n');logs['warmup.jsonl'].flush()
    assert 'error' not in message,message
    data=message['result'].get('structuredContent',{})
    waiting=(data.get('automatic_retry') is True and data.get('action')=='wait') or data.get('error_code') in ('REFRESH_PENDING','INDEX_REFRESH_PENDING','INDEX_REFRESH_TIMEOUT') or data.get('status')=='refresh_pending' or any(w.get('status')=='refresh_pending' for w in data.get('project_warnings',[]))
    meta=data.get('_meta',{})
    waiting=waiting or meta.get('stale_warning') is True or meta.get('structure_stale') is True or meta.get('freshness_basis')=='previous_prepared_generation'
    if waiting:due=time.monotonic()+1;return
    assert not message['result'].get('isError') and not data.get('error_code'),data
    assert data.get('_meta',{}).get('stale_scope')=='current' and data.get('_meta',{}).get('answer_confidence')=='high',data
    assert not any(w.get('status') in ('build_required','index_required','refresh_pending') for w in data.get('project_warnings',[])),data.get('project_warnings')
    ready=True;overview=data
    (directory/'readiness.json').write_text(json.dumps({'ready':True,'seconds':time.monotonic()-(deadline-600),'overview':overview,'initialized_notifications':1,'timeout_seconds':600},indent=2)+'\n')
    for raw in held:downstream(raw)
    held.clear()

try:
    while selector.get_map():
        now=time.monotonic()
        if deadline and not ready:
            if now>=deadline:raise TimeoutError('MCP readiness exceeded600s')
            if due is not None and now>=due:due=None;probe('execute_tool',{'name':'get_overview','arguments':{}})
        for key,_ in selector.select(.2):
            side=key.data;data=os.read(key.fd,65536)
            if not data:
                selector.unregister(key.fd)
                if side=='client':child.stdin.close()
                elif not ready:raise RuntimeError('Native EOF before readiness')
                continue
            buffers[side]+=data
            while b'\n' in buffers[side]:
                raw,buffers[side]=buffers[side].split(b'\n',1);raw+=b'\n';message=json.loads(raw)
                if side=='client':
                    assert not str(message.get('id','')).startswith('__quill_benchmark_probe_'),'Probe ID collision'
                    logs['client-to-native.jsonl'].write(raw);logs['client-to-native.jsonl'].flush();upstream(raw)
                    if message.get('method')=='notifications/initialized':
                        assert not initialized,'Repeated client initialized';initialized=True;deadline=time.monotonic()+600;probe('execute_tool',{'name':'get_overview','arguments':{}})
                    elif message.get('method')=='tools/list':client_list_ids.add(message['id'])
                else:
                    own=pending is not None and message.get('id')==pending['id'];record('from_native',raw,own)
                    if own:accept_probe(message)
                    elif message.get('id') in client_list_ids and not ready and 'method' not in message:held.append(raw)
                    else:downstream(raw)
    child.wait(timeout=10)
    assert child.returncode==0,child.returncode
finally:
    if child.poll() is None:child.terminate();child.wait(timeout=10)
    for log in logs.values():log.close()
