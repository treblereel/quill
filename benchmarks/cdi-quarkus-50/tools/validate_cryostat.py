#!/usr/bin/env python3
"""Validate frozen Cryostat bindings locally; no Quill, network or provider calls."""
import argparse
import collections
import gzip
import hashlib
import json
import re
import subprocess
from pathlib import Path


def read(path):
    return json.loads(path.read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate(data, templates, facts):
    bound, public, oracles, absence, probes = (data[k] for k in ['bound', 'public', 'oracles', 'absence', 'probes'])
    cases = bound['cases']
    assert len(cases) == 50, '50 IDs must remain visible'
    assert [c['id'] for c in cases] == [f'CQ-{n:03}' for n in range(1, 51)], 'IDs duplicated or missing'
    active = {c['id'] for c in cases if c['status'] == 'applicable'}
    inactive = {c['id'] for c in cases if c['status'] == 'not_applicable'}
    assert len(active) == 44 and inactive == {'CQ-008','CQ-017','CQ-019','CQ-021','CQ-022','CQ-034'}, 'Unexpected capability classification'
    assert set(oracles['cases']) == active, 'A scored case lacks an oracle, or an N/A was scored'
    assert set(absence) == inactive, 'N/A reasons missing'
    public_ids = [t['id'] for t in public['tasks']]
    assert len(public_ids) == len(active) and set(public_ids) == active, 'Public tasks do not match applicable IDs'
    assert not facts['errors'], 'javac attribution is incomplete'
    by_id = {t['id']: t for t in templates['tasks']}
    prod = [d for d in facts['declarations'] if d['file'].startswith('src/main/')]
    ann = lambda d: {a['type'] for a in d['annotations']}
    absent_hits = {
        'CQ-008': [d for d in prod if d['kind']=='METHOD' and d.get('name')!='<init>' and 'jakarta.inject.Inject' in ann(d)],
        'CQ-017': [d for d in prod if d['kind']=='FIELD' and 'jakarta.enterprise.inject.Produces' in ann(d)],
        'CQ-019': [d for d in prod if any(any(a['type']=='jakarta.enterprise.inject.Disposes' for a in p['annotations']) for p in d.get('parameters',[]))],
        'CQ-021': [d for d in prod if any(a.startswith(('io.quarkus.arc.profile.IfBuildProfile','io.quarkus.arc.profile.UnlessBuildProfile')) for a in ann(d))],
        'CQ-022': [d for d in prod if any(a.startswith(('io.quarkus.arc.properties.IfBuildProperty','io.quarkus.arc.properties.UnlessBuildProperty')) for a in ann(d))],
        'CQ-034': [d for d in prod if any(a.startswith(('org.eclipse.microprofile.reactive.messaging.Incoming','org.eclipse.microprofile.reactive.messaging.Outgoing')) for a in ann(d))],
    }
    assert all(not hits for hits in absent_hits.values()), 'N/A contradicted by a real application declaration'
    for c in cases:
        if c['status']=='not_applicable':
            proof=absence[c['id']]
            assert proof['predicate'] and proof['scope'] and proof['counterexamples_not_equivalent']
            assert proof['matches']==[], 'N/A proof contains matches'
            assert c['bindings'] is None, 'N/A must not have a fabricated anchor'
            continue
        template=by_id[c['id']]
        assert set(c['bindings'])==set(template['parameters']), c['id']
        for lang, prompt in c['prompts'].items():
            assert prompt==template['prompts'][lang].format(**c['bindings']), 'Bound question changed semantics'
            assert not re.search(r'\{[a-z_]+\}',prompt), 'Unbound parameter'
        expected=oracles['cases'][c['id']]
        assert set(expected['expected'])==set(template['expected_fields']), 'Incomplete expected facts'
        assert expected['evidence'] and expected['review_basis'], 'Oracle lacks source evidence'
    for t in public['tasks']:
        assert set(t)<= {'id','category','mode','turns','prompts','steps'}, 'Private data leaked to public input'
        assert 'oracle' not in json.dumps(t).lower(), 'Oracle reference in public input'
    linked=next(c for c in cases if c['id']=='CQ-050')
    assert len(linked['steps'])==6 and linked['turns']==6
    assert sum(c['turns'] for c in cases if c['status']=='applicable')==49
    assert all(p['passed']==12 and p['compile']=='passed' for p in probes['patch'])
    assert {p['variant'] for p in probes['patch']}=={'baseline','reference_rename'}
    assert probes['config']['value']==25 and probes['config']['ambient_sources_loaded'] is False
    refs=oracles['cases']['CQ-049']['expected']['updated_references']
    assert len(refs)==3, 'Two method references plus one direct call must all be counted'
    assert any(r['tree_kind']=='MEMBER_REFERENCE' and r['to']=='io.cryostat.security.rbac.PermissionMapper#parse(java.lang.String)' for r in facts['references']), 'Method-reference evidence missing'
    return {'all_ids':50, 'applicable':44, 'not_applicable':6, 'applicable_turns':49, 'javac_files':facts['files'], 'compiler_errors':0, 'new_model_calls':0}


def load(folder):
    return {
        'bound':read(folder/'bound-suite.json'),
        'public':read(folder/'public-tasks.json'),
        'oracles':read(folder/'oracles.json'),
        'absence':read(folder/'not-applicable.json'),
        'probes':read(folder/'local-probes.json'),
    }


def verify_inputs(project, folder):
    revision = subprocess.check_output(['git','rev-parse','HEAD'],cwd=project,text=True).strip()
    preflight=read(folder/'preflight.json')
    assert revision==preflight['project_revision'], 'Cryostat revision changed'
    manifest=read(folder/'source-manifest.json')
    for path, expected in manifest.items():
        assert digest(project/path)==expected, 'Input changed: '+path
    for path, expected in preflight['generated_artifacts'].items():
        assert digest(project/path)==expected, 'Generated artifact changed: '+path
    for entry in read(folder/'dependency-manifest.json'):
        assert digest(Path(entry['path']))==entry['sha256'], 'Dependency or compiled input changed: '+entry['path']
    untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard','--','src','schema-generator'],cwd=project,text=True).strip()
    assert not untracked, 'New untracked application input requires re-binding'
    for entry in read(folder/'oracles.json')['cases'].values():
        for evidence in entry['evidence']:
            assert manifest[evidence['path']]==evidence['sha256'], 'Oracle/source mismatch'
    return len(manifest)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',type=Path,required=True)
    parser.add_argument('--data',type=Path,default=Path(__file__).resolve().parents[1]/'cryostat')
    args=parser.parse_args()
    folder=args.data
    facts=json.loads(gzip.decompress((folder/'javac-facts.json.gz').read_bytes()))
    result=validate(load(folder),read(folder.parent/'suite.json'),facts)
    result['input_files_verified']=verify_inputs(args.project,folder)
    result['status']='passed'
    result['runtime_application_tests']='not_run'
    result['quill_verification']='Connected MCP points to a different stale main checkout and cannot attest benchmark-only files. No JVM production changes; local data/probe checks are recorded without claiming a native verification receipt.'
    result['artifact_sha256']={p.relative_to(folder).as_posix():digest(p) for p in sorted(folder.rglob('*')) if p.is_file() and p.name!='validation.json'}
    (folder/'validation.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in result.items() if k!='artifact_sha256'},ensure_ascii=False))
