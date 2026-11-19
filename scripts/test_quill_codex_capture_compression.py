"""Compressed Responses body/SSE transparency against local TLS upstream only."""
from compression import zstd
import gzip,http.client,json,ssl,subprocess,tempfile,threading
from pathlib import Path
from http.server import ThreadingHTTPServer
import unittest
from test_quill_codex_capture import Fake,events,MITM,OUTPUT
from quill_codex_capture import CodexCapture,wire_usage
class Compressed(Fake):
 def do_POST(self):
  body=self.rfile.read(int(self.headers['Content-Length']));n=self.record(body,'HTTP-zstd');plain=''.join('data: '+json.dumps(e)+'\n\n' for e in events(n)).encode();payload=gzip.compress(plain);self.server.sent=payload
  self.send_response(200);self.send_header('Content-Type','text/event-stream');self.send_header('Content-Encoding','gzip');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
# Writes into the local capture output and uses mitmdump and Homebrew OpenSSL; none exists on CI.
if not OUTPUT.is_dir() or not Path(MITM).exists() or not Path('/opt/homebrew/bin/openssl').exists(): raise unittest.SkipTest('needs the local capture output, mitmdump and Homebrew OpenSSL')
D=OUTPUT/'compression-check';D.mkdir()
with tempfile.TemporaryDirectory() as tmp:
 tmp=Path(tmp);cert,key=tmp/'server.crt',tmp/'server.key'
 subprocess.run(['/opt/homebrew/bin/openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(cert),'-days','1','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1'],check=True,capture_output=True)
 server=ThreadingHTTPServer(('127.0.0.1',0),Compressed);server.records=[];ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(cert,key);server.socket=ctx.wrap_socket(server.socket,server_side=True);threading.Thread(target=server.serve_forever,daemon=True).start()
 try:
  with CodexCapture(D/'wire',MITM,'127.0.0.1',cert) as capture:
   body=json.dumps({'model':'gpt-6.1-sol','input':[{'role':'user','content':'LOCAL_COMPRESSION_PROBE'}],'stream':True},ensure_ascii=False).encode();raw=zstd.compress(body)
   connection=http.client.HTTPSConnection('127.0.0.1',capture.port,context=ssl.create_default_context(cafile=str(capture.ca)));connection.set_tunnel('127.0.0.1',server.server_port)
   connection.request('POST','/v1/responses',body=raw,headers={'Authorization':'Bearer local-probe-credential','Content-Type':'application/json','Content-Encoding':'zstd'})
   response=connection.getresponse();received=response.read();assert response.status==200 and received==server.sent;connection.close()
  assert server.records[0]['body']==raw
  p=next((D/'wire').glob('*/request.json'));assert p.read_bytes()==body and p.with_name('request.body').read_bytes()==raw
  assert p.with_name('response.body').read_bytes()==received
  result=wire_usage(D/'wire');assert result['usage_complete'] and result['totals']['all']==22,result
  (D/'results.json').write_text(json.dumps({'zstd_request_bytes_unchanged':True,'decoded_request_identical':True,'gzip_response_bytes_unchanged':True,'wire_usage':result,'external_provider_calls':0},indent=2)+'\n')
 finally:server.shutdown();server.server_close()
print('zstd request and gzip SSE checks passed;0provider calls')
