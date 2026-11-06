"""OS-enforced read isolation inherited by Codex, code-mode tools and MCP."""
import json, subprocess, tempfile, uuid, sys
from pathlib import Path

def profile(allowed):
    # Deny data rather than metadata so ordinary path traversal still works.
    # Snapshot existing temporary entries; future files must remain usable by tools.
    blocked = [Path('/Users'),Path('/private/var/folders')]
    for directory in [Path('/private/tmp')]:
        blocked += list(directory.iterdir())
    rules = ['(version 1)', '(allow default)']
    paths = lambda values: ' '.join('(subpath '+json.dumps(str(Path(p).resolve()))+')' for p in values)
    # SQLite JDBC extracts its platform library outside TMPDIR; allow only that binary.
    libraries = '(regex #"^/private/(var/folders/.*/T|tmp)/(sqlite-[^/]*libsqlitejdbc[.]dylib|quill-java-worker-[0-9]+/worker[.]jar|quill-watch-health-[A-Za-z0-9_-]+(/(heartbeat|read-[0-9]+))?)$")'
    rules += ['(deny file-read-data (require-all (require-any '+paths(blocked)+') (require-not (require-any '+paths(allowed)+' '+libraries+'))))']
    return '\n'.join(rules)+'\n'

def probe(profile_path, readable, blocked, scratch):
    # Compile Seatbelt once per probe, rather than once per file or shell check.
    code = r"""
import json,subprocess,sys,uuid
from pathlib import Path
p=json.loads(sys.argv[1]);checks=[]
for name,expected in [(x,True) for x in p['readable']]+[(x,False) for x in p['blocked']]:
 try:
  with open(name,'rb') as f:f.read(1)
  actual=True
 except OSError:actual=False
 checks.append({'path':name,'readable':actual,'expected':expected})
 assert actual==expected,checks[-1]
r=subprocess.run(['/bin/sh','-c','echo session-owned > "$1/helper.txt"; cat "$1/helper.txt"','probe',p['scratch']],capture_output=True)
assert r.returncode==0 and r.stdout==b'session-owned\n',r.stderr
fresh=Path('/tmp')/('quill-isolation-new-'+uuid.uuid4().hex+'.py')
try:
 command='cat > "$1" <<\'EOF\'\nprint("new-helper-ok")\nEOF\n/opt/homebrew/bin/python3 "$1"'
 r=subprocess.run(['/bin/zsh','-lc',command,'probe',str(fresh)],capture_output=True)
 assert r.returncode==0 and r.stdout==b'new-helper-ok\n',r.stderr
finally:fresh.unlink(missing_ok=True)
print(json.dumps(checks))
"""
    payload={'readable':[str(x) for x in readable],'blocked':[str(x) for x in blocked],'scratch':str(scratch)}
    result=subprocess.run(['/usr/bin/sandbox-exec','-f',str(profile_path),sys.executable,'-c',code,json.dumps(payload)],capture_output=True,text=True,timeout=180)
    assert result.returncode==0,result.stderr
    return json.loads(result.stdout)
