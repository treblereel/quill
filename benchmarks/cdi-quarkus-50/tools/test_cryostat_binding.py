import copy
import gzip
import json
from pathlib import Path
import unittest
from validate_cryostat import load, read, validate

ROOT=Path(__file__).resolve().parents[1]

class CryostatBindingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data=load(ROOT/'cryostat')
        cls.suite=read(ROOT/'suite.json')
        cls.facts=json.loads(gzip.decompress((ROOT/'cryostat/javac-facts.json.gz').read_bytes()))

    def test_complete_binding(self):
        self.assertEqual(44,validate(self.data,self.suite,self.facts)['applicable'])

    def test_generated_scope_is_not_guessed_from_class_name(self):
        scope=self.data['oracles']['cases']['CQ-050']['expected']['step_facts']['2']['effective_scope']
        self.assertTrue(scope.startswith('@Singleton'))
        bytecode=(ROOT/'cryostat/bytecode/io.cryostat.reports.AnalysisReportAggregator_Bean.txt').read_text()
        self.assertIn('jakarta/inject/Singleton',bytecode)

    def test_rejects_missing_oracle(self):
        d=copy.deepcopy(self.data);del d['oracles']['cases']['CQ-006']
        with self.assertRaises(AssertionError):validate(d,self.suite,self.facts)

    def test_rejects_duplicate_id(self):
        d=copy.deepcopy(self.data);d['bound']['cases'][1]['id']='CQ-001'
        with self.assertRaises(AssertionError):validate(d,self.suite,self.facts)

    def test_rejects_false_na(self):
        facts=copy.deepcopy(self.facts)
        facts['declarations'].append({'file':'src/main/java/New.java','kind':'METHOD','name':'init','annotations':[{'type':'jakarta.inject.Inject'}]})
        with self.assertRaises(AssertionError):validate(self.data,self.suite,facts)

    def test_rejects_incomplete_attribution(self):
        f=copy.deepcopy(self.facts);f['errors']=[{'code':'unresolved'}]
        with self.assertRaises(AssertionError):validate(self.data,self.suite,f)

    def test_rejects_oracle_leak(self):
        d=copy.deepcopy(self.data);d['public']['tasks'][0]['expected']={'gold':'value'}
        with self.assertRaises(AssertionError):validate(d,self.suite,self.facts)

    def test_rejects_dropped_method_references(self):
        d=copy.deepcopy(self.data);d['oracles']['cases']['CQ-049']['expected']['updated_references']=d['oracles']['cases']['CQ-049']['expected']['updated_references'][:1]
        with self.assertRaises(AssertionError):validate(d,self.suite,self.facts)

    def test_preserves_cdi_vs_rest_annotation_identity(self):
        declarations=self.facts['declarations']
        cdi=[d for d in declarations if d['file'].startswith('src/main/') and any(a['type']=='jakarta.enterprise.inject.Produces' for a in d['annotations'])]
        rest=[d for d in declarations if d['file'].startswith('src/main/') and any(a['type']=='jakarta.ws.rs.Produces' for a in d['annotations'])]
        self.assertEqual(15,len(cdi));self.assertTrue(rest)
        self.assertTrue(all(d['kind']=='METHOD' for d in cdi))

if __name__=='__main__':unittest.main()
