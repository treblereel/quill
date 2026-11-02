import copy
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import quill_measurement_report as m
from test_quill_response_reuse_audit import frame


U = {'input':100,'cached_input':40,'uncached_input':60,'output':10,'all':110}


def record(key='session', correct=True, usage=True):
    return {'key':key,'case':'portable-case','client':'codex','arm':'with_quill','track':'independent',
            'repeat':1,'prompts':['question'],'status':'completed','seconds':2,
            'usage':dict(U,usage_complete=usage),'structure':{'passed':True},
            'grade':{'turns':[{'correct':correct}]}}


class MeasurementTest(unittest.TestCase):
    def test_unknown_is_not_zero_and_bad_arithmetic_rejected(self):
        self.assertIsNone(m.totals([U,None]))
        self.assertIsNone(m.totals([]))
        for u in [dict(U,all=1),dict(U,input=True),dict(U,cached_input=101)]:
            self.assertFalse(m.valid_usage(u))
        self.assertEqual(m.totals([U,U])['all'],220)

    def test_prewarm_first_request_continuation_and_no_double_charge(self):
        fs=[frame(index=i) for i in range(3)]
        fs[0]['request']['generate']=False
        fs[2]['response']['output']=[]
        rows,dupes=m.cycle_ledger(fs,[(1,5)])
        self.assertEqual([r['stage'] for r in rows],['prewarm','first_request','continuation'])
        self.assertEqual(rows[-1]['triggering_previous_actions'],['quill_query'])
        self.assertIsNone(rows[-1]['attributed_waste_tokens'])
        self.assertEqual(m.totals([r['usage'] for r in rows])['all'],330)
        rows,dupes=m.cycle_ledger(fs+[fs[2]],[(1,5)])
        self.assertEqual(len(rows),3)
        self.assertTrue(dupes)

    def test_claude_cache_creation_is_uncached_and_missing_is_unknown(self):
        rows,_=m.cycle_ledger([frame('claude')],[(1,0)])
        self.assertEqual(rows[0]['cache_creation_input'],40)
        self.assertEqual(rows[0]['new_input_excluding_cache_creation'],20)
        self.assertEqual(rows[0]['usage']['uncached_input'],60)
        f=frame('claude');del f['response']['usage']['cache_creation_input_tokens']
        self.assertIsNone(m.cycle_ledger([f],[(1,0)])[0][0]['usage'])

    def test_failures_remain_in_cost_per_correct_and_unknown_price_not_free(self):
        with tempfile.TemporaryDirectory() as d:
            s=Path(d)/'summary.json';s.write_text('{}')
            a=m.record_report(record(),s,{})
            self.assertFalse(a['reuse_review']['available'])
            self.assertIsNone(a['reuse_review']['coverage_complete'])
            b=m.record_report(dict(record('failed',False),status='failed'),s,{})
            total=m.aggregate([a,b])
            self.assertEqual(total['usage']['all'],220)
            self.assertEqual(total['all_tokens_per_correct_final_including_all_attempts'],220)
            self.assertEqual(total['failed_or_incomplete_sessions'],['failed'])
            self.assertIsNone(total['provider_cost_usd_estimate'])
            self.assertIsNone(m.aggregate([b])['all_tokens_per_correct_final_including_all_attempts'])
            b['usage_complete']=False
            self.assertIsNone(m.aggregate([a,b])['usage'])

    def test_raw_mismatch_blocks_totals_and_grade_hash_mismatch_blocks_accuracy(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);d=root/'sessions/session';(d/'wire').mkdir(parents=True)
            (d/'turns.json').write_text(json.dumps([{'answer':'saved'}]))
            r=record();r['grade']['turns'][0]['answer_sha256']='wrong'
            with patch.object(m.reuse,'cycles',return_value=[frame()]):
                r['usage']['all']=111
                got=m.record_report(r,root/'summary.json',{})
            self.assertFalse(got['usage_complete']);self.assertFalse(got['grades_complete'])
            self.assertIn('grade_answer_hash_mismatch',got['raw_issues'])

    def test_bad_grade_does_not_erase_known_spend(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);d=root/'sessions/session';(d/'wire').mkdir(parents=True)
            (d/'turns.json').write_text(json.dumps([{'answer':'saved'}]));r=record()
            r['grade']['turns'][0]['answer_sha256']='wrong'
            with patch.object(m.reuse,'cycles',return_value=[frame()]):
                got=m.record_report(r,root/'summary.json',{})
            self.assertTrue(got['usage_complete']);self.assertFalse(got['grades_complete'])
            total=m.aggregate([got]);self.assertEqual(total['usage']['all'],110)
            self.assertIsNone(total['all_tokens_per_correct_final_including_all_attempts'])

    def test_linked_questions_keep_history_and_only_one_prewarm(self):
        fs=[frame(index=i) for i in range(4)];fs[0]['request']['generate']=False
        rows,_=m.cycle_ledger(fs,[(1,5),(2,25)])
        self.assertEqual([r['question'] for r in rows],[None,1,1,2])
        self.assertEqual(rows[-1]['stage'],'first_request')
        self.assertEqual(m.totals([r['usage'] for r in rows])['all'],440)

    def test_proven_absent_stage_is_zero_missing_capture_remains_unknown(self):
        with tempfile.TemporaryDirectory() as root:
            root=Path(root);d=root/'sessions/session';(d/'wire').mkdir(parents=True)
            (d/'turns.json').write_text(json.dumps([{'answer':'saved'}]))
            (d/'turn-01').mkdir();(d/'turn-01/started.json').write_text(json.dumps({'prompt':'question','at':0}))
            f=frame();f['response']['output']=[]
            with patch.object(m.reuse,'cycles',return_value=[f]):
                r=m.record_report(record(),root/'summary.json',{})
            self.assertEqual(r['stage_totals']['prewarm']['all'],0)
            self.assertEqual(r['stage_totals']['continuation']['all'],0)
            self.assertTrue(r['answers'][0]['correct_on_first_request'])
            self.assertIsNone(r['answers'][0]['tokens_to_first_correct_answer'])
            self.assertEqual(r['provider_cache_control'],'uncontrolled')
            historical=m.record_report(record('absent'),root/'summary.json',{})
            self.assertIsNone(historical['stage_totals']['prewarm'])
            self.assertIsNone(m.aggregate([])['provider_cost_usd_estimate'])

    def test_duplicate_pair_is_not_cherry_picked_but_all_costs_remain(self):
        with tempfile.TemporaryDirectory() as root:
            p=Path(root)/'summary.json';a=record();b=dict(record('source'),arm='without_quill');c=dict(b,key='retry')
            p.write_text(json.dumps({'records':[a,b,c]}));r=m.report(p)
            self.assertFalse(r['pairs'][0]['same_prompts_and_track'])
            self.assertEqual(next(v for v in r['tables'] if v['arm']=='without_quill')['usage']['all'],220)
            self.assertIsNone(r['paired_comparisons'][0]['source_divided_by_quill'])

    def test_ratios_use_sums_not_mean_of_case_ratios(self):
        with tempfile.TemporaryDirectory() as root:
            p=Path(root)/'summary.json';q=record('q1');b=dict(record('b1'),arm='without_quill')
            q['usage']={k:v//10 for k,v in U.items()};q['usage']['usage_complete']=True
            q2=dict(record('q2'),case='second');b2=dict(record('b2'),arm='without_quill',case='second')
            for r in (q2,b2):r['usage']={k:v*10 for k,v in U.items()};r['usage']['usage_complete']=True
            p.write_text(json.dumps({'records':[q,b,q2,b2]}));r=m.report(p)
            self.assertAlmostEqual(r['paired_comparisons'][0]['source_divided_by_quill']['all'],1210/1111)
            self.assertIsNone(r['paired_comparisons'][0]['repeat_ratio_range_not_confidence_interval'])

    def test_component_bytes_are_not_tokens_and_no_raw_values_leak(self):
        p=m.prefix_components({'tools':[{'name':'tool'}],'instructions':'private text',
                               'input':[{'role':'developer','content':'managed instructions'}]})
        self.assertGreater(p['tools']['canonical_json_bytes'],0)
        self.assertNotIn('private text',json.dumps(p))
        self.assertNotIn('tokens',p['tools'])

    def test_balanced_preregistered_plan_no_calls_and_no_prompt_rewrite(self):
        ref={'schedule':[record()]}
        a=m.make_plan(ref,3,7);b=m.make_plan(ref,3,7)
        self.assertEqual(a,b);self.assertEqual(a['provider_calls'],0)
        self.assertTrue(a['planning_only']);self.assertEqual(a['sessions'],6)
        self.assertEqual(a['schedule'][0]['arm'],a['schedule'][3]['arm'])
        self.assertTrue(all(r['prompts']==['question'] for r in a['schedule']))
        self.assertEqual(len({r['key'] for r in a['schedule']}),6)
        wrong=copy.deepcopy(ref);wrong['schedule'].append(dict(record(),prompts=['different']))
        with self.assertRaises(ValueError):m.make_plan(wrong,3,7)
        with self.assertRaises(ValueError):m.make_plan(ref,1,7)

    def test_historical_reference_stays_diagnostic_and_tracks_separate(self):
        with tempfile.TemporaryDirectory() as root:
            p=Path(root)/'summary.json';a=record();b=dict(record('source'),arm='without_quill',historical=True)
            linked=dict(record('linked'),track='linked',case='linked',prompts=['a','b'])
            p.write_text(json.dumps({'records':[a,b,linked]}));r=m.report(p)
            self.assertEqual(len(r['tables']),3)
            self.assertEqual(r['pairs'][0]['role'],'historical_diagnostic')
            self.assertFalse(r['automatic_acceptance'])
            self.assertEqual(r['tables'][0]['raw_sessions_available'],0)
            self.assertIn('null',json.dumps(r))

    def test_cli_refuses_overwrite_or_output_in_acquisition(self):
        with tempfile.TemporaryDirectory() as root:
            p=Path(root)/'input';p.mkdir();s=p/'summary.json';s.write_text('{"records":[]}')
            with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):m.main(['--summary',str(s),'--output',str(p/'new')])
            out=Path(root)/'out';out.mkdir()
            with contextlib.redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):m.main(['--summary',str(s),'--output',str(out)])


if __name__=='__main__':unittest.main()
