"""Offline acquisition guards. Fake MCP only; no model/client startup."""
import importlib.util,json,os,queue,subprocess,sys,tempfile,threading,unittest
from pathlib import Path
from unittest.mock import patch
O=Path(__file__).resolve().parent;sp=importlib.util.spec_from_file_location('runner',O/'run.py');m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m)
class Guards(unittest.TestCase):
 def test_schedule_is_complete_balanced_and_unchanged(self):
  ref=m.load(m.O/'protocol.json');plan=m.measurement.make_plan(ref,3,20261101);self.assertEqual(plan['sessions'],528);self.assertEqual(plan['answers'],588);self.assertEqual(len({e['key'] for e in plan['schedule']}),528)
  for case in {e['case'] for e in plan['schedule']}:
   for cli in ['codex','claude']:
    rs=[e for e in plan['schedule'] if e['case']==case and e['client']==cli];self.assertEqual(len(rs),6);self.assertEqual(rs[0]['arm'],rs[3]['arm']);self.assertEqual(len({json.dumps(e['prompts']) for e in rs}),1)
 def test_full_hash_guard_detects_changed_bytes_even_with_restored_mtime(self):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'input';p.write_text('sealed');stamp=p.stat();manifest={str(p):m.sha(p)};proto={'schedule':[{'prompts':['p']*6} for _ in range(12)]+[{'prompts':['p']} for _ in range(516)],'measurement_contract':m.measurement.CONTRACT,'source_files':{}}
   with patch.object(m,'load',side_effect=lambda path:manifest if Path(path).name=='frozen-inputs.json' else proto):
    m.checked_stamps.clear();m.check();p.write_text('spoilt');os.utime(p,ns=(stamp.st_atime_ns,stamp.st_mtime_ns))
    with self.assertRaises(AssertionError):m.check()
 def test_source_client_has_no_quill_or_hooks_and_candidate_enables_hooks(self):
  p={'codex_binary':'codex','native_binary':'quill'};a=m.r.rpc_args(p,O,False,O/'sandbox');b=m.r.rpc_args(p,O,True,O/'sandbox');self.assertIn('features.hooks=false',a);self.assertIn('features.hooks=true',b);self.assertFalse(any('mcp_servers.quill' in x for x in a));self.assertTrue(any('mcp_servers.quill.command' in x for x in b))
 def test_native_maven_exception_and_global_quill_exclusion_are_arm_scoped(self):
  try:
   m.active_arm='without_quill';source=m.profile([m.ROOT]);self.assertNotIn('quill-maven-classpath',source);self.assertIn('/opt/homebrew/Cellar/quill',source)
   m.active_arm='with_quill';candidate=m.profile([m.ROOT]);self.assertIn('quill-maven-classpath',candidate)
  finally:m.active_arm=None
 def test_only_model_tool_inputs_can_identify_new_tmp_helpers(self):
  with tempfile.TemporaryDirectory() as t:
   d=Path(t);rows=[{'type':'assistant','message':{'content':[{'type':'tool_use','input':{'command':'cat /tmp/example-owned.py'}}]}},{'type':'user','message':{'content':[{'type':'tool_result','content':'/tmp/unrelated-output.py'}]}}];(d/'events.jsonl').write_text('\n'.join(json.dumps(x) for x in rows));self.assertEqual(list(m.model_tmp_paths(d)),[Path('/private/tmp/example-owned.py')])
 def test_patch_fixture_is_restored_and_validated_before_next_inference(self):
  with tempfile.TemporaryDirectory() as t:
   base=Path(t);root=base/'project';root.mkdir();ready=base/'ready-target';ready.mkdir();(ready/'Good.class').write_bytes(b'prepared-class');src=root/'Good.java';src.write_text('original');expected=m.sha(src)
   subprocess.run(['git','init','-q',str(root)],check=True);subprocess.run(['git','-C',str(root),'add','.'],check=True);subprocess.run(['git','-C',str(root),'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','baseline'],check=True);rev=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
   src.write_text('legitimate CQ049 patch');(root/'Added.java').write_text('patch test')
   proto={'schedule':[{'prompts':['p']*6} for _ in range(12)]+[{'prompts':['p']} for _ in range(516)],'measurement_contract':m.measurement.CONTRACT,'source_files':{'Good.java':expected}}
   with patch.object(m,'ROOT',root),patch.object(m,'load',side_effect=lambda path:{} if Path(path).name=='frozen-inputs.json' else proto):m.check()
   params={'cryostat_revision':rev,'source_files':{'Good.java':expected},'file_times_ns':{}}
   with patch.object(m.r,'ROOT',root),patch.object(m.r,'INDEX',base/'ready-index'):
    m.base_setup({'arm':'without_quill'},base/'next-session',params)
   self.assertEqual(m.sha(src),expected);self.assertFalse((root/'Added.java').exists());self.assertEqual((root/'target/Good.class').read_bytes(),b'prepared-class')
 def test_saved_timeout_is_retained_without_erasing_an_answer(self):
  hooks=json.loads((O/'fixtures/codex-hook-timeout.json').read_text())
  with tempfile.TemporaryDirectory() as t:
   d=Path(t);(d/'answer.txt').write_text('actual answer');e=m.r.save_prefetch_evidence(d,d,'session',hooks);self.assertEqual(e['hook_status'],'timed_out');self.assertEqual(e['delivery'],'absent_unknown');self.assertTrue(e['hook_failures']);self.assertEqual((d/'answer.txt').read_text(),'actual answer')
 def test_batched_probe_checks_denials_and_new_helpers_in_one_sandbox(self):
  with tempfile.TemporaryDirectory() as t:
   d=Path(t);good=d/'good';bad=d/'blocked';good.write_text('ok');bad.write_text('not-readable');profile=d/'test.sb';profile.write_text('(version 1)\n(allow default)\n(deny file-read-data (literal '+json.dumps(str(bad.resolve()))+'))\n')
   result=m.r.probe(profile,[good],[bad],d);self.assertEqual([x['readable'] for x in result],[True,False])
 def test_proxy_waits_for_fresh_generation_and_forwards_native_catalogue_unchanged(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t);d=root/'session';d.mkdir();(d/'native-profile.json').write_text('{"profile":"codex"}');fake=root/'fake-native';fake.write_text('#!'+sys.executable+'\n'+'''import sys,json
n=0
for line in sys.stdin:
 x=json.loads(line)
 if 'id' not in x:continue
 if x['method']=='initialize':r={'protocolVersion':'2025-06-18','capabilities':{},'serverInfo':{'name':'fake','version':'1'},'instructions':'unaltered native instructions'}
 elif x['method']=='tools/list':r={'tools':[{'name':'native','inputSchema':{'type':'object'}}]}
 else:
  n+=1;r={'structuredContent':{'_meta':{'stale_scope':'repository' if n==1 else 'current','answer_confidence':'low' if n==1 else 'high','stale_warning':n==1,'freshness_basis':'previous_prepared_generation' if n==1 else 'current'}}}
 print(json.dumps({'jsonrpc':'2.0','id':x['id'],'result':r}),flush=True)
''');fake.chmod(0o755)
   child=subprocess.Popen([sys.executable,str(O/'runtime/mcp_tap.py'),str(fake),str(root),str(d/'mcp')],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True);q=queue.Queue()
   threading.Thread(target=lambda:[q.put(json.loads(l)) for l in child.stdout],daemon=True).start()
   def send(x):child.stdin.write(json.dumps(x)+'\n');child.stdin.flush()
   try:
    send({'jsonrpc':'2.0','id':1,'method':'initialize','params':{}});self.assertEqual(q.get(timeout=10)['result']['instructions'],'unaltered native instructions');send({'jsonrpc':'2.0','method':'notifications/initialized','params':{}});send({'jsonrpc':'2.0','id':2,'method':'tools/list','params':{}});r=q.get(timeout=10);self.assertEqual(r['result']['tools'][0]['name'],'native');ready=json.loads((d/'mcp/readiness.json').read_text());self.assertTrue(ready['ready']);self.assertGreaterEqual(ready['seconds'],.8);self.assertFalse(ready['overview']['_meta']['stale_warning'])
   finally:child.stdin.close();child.wait(timeout=10)
   self.assertEqual(child.returncode,0,child.stderr.read())
if __name__=='__main__':unittest.main()
