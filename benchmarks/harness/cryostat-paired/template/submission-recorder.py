"""Read-only CQ049 sidecar: preserve model-created disposable-clone submissions before cleanup.
Never starts a CLI/model, changes a checkout, or feeds observations back to an agent.
"""
import fcntl,hashlib,json,os,re,subprocess,time
from pathlib import Path
O=Path(__file__).resolve().parent
ROOT=Path('/tmp/quill-cryostat-full-paired-v7/project')
BASE='b19ddcfadf44a880550751a594fabd1769704dd4'
SOURCE=Path('src/main/java/io/cryostat/security/rbac/PermissionMapper.java')

def git(root,*args):return subprocess.run(['git','--no-optional-locks','-C',str(root),*args],capture_output=True,timeout=20)
def roots(d,started_at=0):
 result={ROOT};sb=d/'isolation.sb'
 if sb.exists():
  for name in set(re.findall(r'"([^"\n]*quill-cdi50-home-[^"\n]*/scratch)"',sb.read_text())):
   scratch=Path(name)
   if not scratch.exists():continue
   for current,dirs,files in os.walk(scratch):
    current=Path(current)
    if (current/SOURCE).is_file() and (current/'.git').exists():result.add(current);dirs[:]=[];continue
    dirs[:]=[] if len(current.relative_to(scratch).parts)>=4 else [n for n in dirs if n not in ['.git','target','node_modules','repository']]
 # Literal /tmp clone paths must come from this actual session's captured commands/output.
 for log in [d/'server.jsonl',d/'events.jsonl',d/'stream.jsonl',d/'stdout.jsonl',*d.glob('turn-*/events.jsonl'),*d.glob('turn-*/stream.jsonl')]:
  if not log.exists():continue
  for raw in set(re.findall(r'/(?:private/)?tmp/[A-Za-z0-9_./-]+',log.read_text(errors='replace'))):
   path=Path(raw)
   for parent in [path,*path.parents]:
    if parent in [Path('/'),Path('/tmp'),Path('/private'),Path('/private/tmp')]:break
    if (parent/SOURCE).is_file() and (parent/'.git').exists():
     if parent==ROOT or parent.stat().st_ctime>=started_at-1:result.add(parent)
     break
 return result

def capture(root,key):
 if not (root/SOURCE).is_file():return
 diff=git(root,'diff','--binary',BASE,'--','src/main','src/test')
 if diff.returncode:return
 patch=diff.stdout
 extra=git(root,'ls-files','-z','--others','--exclude-standard','--','src/main','src/test')
 if extra.returncode:return
 for raw in extra.stdout.split(b'\0'):
  if not raw:continue
  name=os.fsdecode(raw);p=root/name
  if p.is_file() and not p.is_symlink() and p.stat().st_size<2_000_000:
   x=git(root,'diff','--no-index','--binary','--','/dev/null',name)
   if x.returncode in (0,1):patch+=x.stdout
 if not patch.strip():return
 h=hashlib.sha256(patch).hexdigest();d=O/'submitted-snapshots'/key/h
 if d.exists():return
 d.mkdir(parents=True);(d/'submitted.patch').write_bytes(patch)
 head=git(root,'rev-parse','HEAD')
 (d/'provenance.json').write_text(json.dumps({'session':key,'source_root':str(root),'baseline':BASE,'head':head.stdout.decode().strip(),'observed_at':time.time(),'patch_sha256':h,'readonly':True,'paid_calls':0,'policy':'Exact diff against frozen baseline plus actual new source/test files; no answer/patch repairs; model never receives this sidecar.'},indent=2)+'\n')
 print(key,h,flush=True)

def main():
 lock=(O/'submission-recorder.lock').open('w');fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 while True:
  state=json.loads((O/'manager.json').read_text())
  if state['status'] in ['completed_schedule','stopped_error']:break
  row=state['sessions'][-1] if state['sessions'] else None
  if row and row['case']=='CQ-049' and row['status']=='started':
   try:
    for root in roots(O/'sessions'/row['key'],row['started_at']):capture(root,row['key'])
   except (OSError,subprocess.TimeoutExpired) as error:print(type(error).__name__,flush=True)
   time.sleep(1)
  else:time.sleep(5)
if __name__=='__main__':main()
