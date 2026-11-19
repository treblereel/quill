import importlib.util,sys,subprocess,json,tempfile,unittest
from pathlib import Path
ISOLATION=Path('benchmarks/results/cdi-quarkus-050-cryostat-v1/runtime/isolation.py')
# The runner isolation module is local benchmark data, and Seatbelt exists only on macOS.
if not ISOLATION.exists() or not Path('/usr/bin/sandbox-exec').exists() or not Path('/tmp/quill-codex-wire-tools-v1/bin/mitmdump').exists(): raise unittest.SkipTest('needs the local runner isolation module, mitmdump and macOS Seatbelt')
sys.path.insert(0,'scripts');from quill_codex_capture import CodexCapture,capture_profile
s=importlib.util.spec_from_file_location('isolation','benchmarks/results/cdi-quarkus-050-cryostat-v1/runtime/isolation.py');m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
# Generate original policy BEFORE observer, exactly as real runner.isolated does.
base=m.profile([Path.cwd()]);out=Path(__import__('os').environ.get('QUILL_CA_POLICY_OUTPUT','benchmarks/results/codex-wire-capture-local-v1/ca-policy-check'))
with CodexCapture(out,'/tmp/quill-codex-wire-tools-v1/bin/mitmdump','127.0.0.1') as cap:
 profile=Path('/tmp/codex-capture-ca-policy.sb');profile.write_text(capture_profile(base,cap.ca))
 allowed=subprocess.run(['/usr/bin/sandbox-exec','-f',str(profile),'/bin/cat',str(cap.ca)],capture_output=True);assert allowed.returncode==0,allowed.stderr
 key=cap.ca.parent/'mitmproxy-ca.pem';blocked=subprocess.run(['/usr/bin/sandbox-exec','-f',str(profile),'/bin/cat',str(key)],capture_output=True);assert blocked.returncode!=0
 write=subprocess.run(['/usr/bin/sandbox-exec','-f',str(profile),'/bin/sh','-c','echo changed >> "$1"','probe',str(cap.ca)],capture_output=True);assert write.returncode!=0
 (out/'permission-proof.json').write_text(json.dumps({'original_profile_created_before_CA':True,'public_ca_readable':True,'private_key_unreadable':True,'CA_writes_denied':True,'provider_calls':0},indent=2)+'\n')
print('Actual runner Seatbelt CA read/key isolation/write denial verified')
