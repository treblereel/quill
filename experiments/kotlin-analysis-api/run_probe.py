#!/usr/bin/env python3
"""Validate retained Analysis API sessions against isolated fresh CLI FIR states."""
import argparse
import json
from pathlib import Path
import statistics
import subprocess
import tempfile
import time



def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--classpath-file', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent
    repository = directory.parents[1]
    paths = Path(args.classpath_file).read_text().strip().split(':')
    java = str(Path(args.java_home) / 'bin/java')
    cases = json.loads((directory / "src/test/resources/mutations.json").read_text())
    with tempfile.TemporaryDirectory(prefix='quill-api-matrix-') as temporary:
        definition = Path(temporary) / 'cases.json'
        definition.write_text(json.dumps(cases))
        probe = subprocess.run([java, '-Xmx512m', '-Xss8m', '-cp', str(directory / 'target/classes') + ':' + ':'.join(paths),
                                'ProbeKt', str(definition)], capture_output=True, text=True, timeout=60, check=True)
        report = json.loads(probe.stdout)
    assert len(report['cases']) == len(cases), report
    report['runtime_artifacts'] = len(paths)
    report['runtime_bytes'] = sum(Path(path).stat().st_size for path in paths)
    report['classpath_isolation'] = 'Separate process; unshaded IntelliJ core, no production worker changes'
    report['fresh_cli_reference'] = []
    stdlib = next(path for path in paths if '/kotlin-stdlib/2.4.21/' in path)
    # Identical unchanged states share one fresh reference; every distinct mutation is checked.
    references = {}
    with tempfile.TemporaryDirectory(prefix='quill-api-reference-') as temporary:
        root = Path(temporary)
        for case in cases:
            key = json.dumps(case['sources'], sort_keys=True)
            if key not in references:
                for source in root.rglob('*.kt'):
                    source.unlink()
                for name, text in case['sources'].items():
                    source = root / name
                    source.parent.mkdir(parents=True, exist_ok=True)
                    source.write_text(text)
                source = root / case['query_file']
                request = {'module': temporary, 'file': str(source),
                           'offset': source.read_text().index('/*query*/') + len('/*query*/'),
                           'sources': [str(root / name) for name in case['sources']],
                           'classpath': [stdlib], 'source_set': 'main'}
                begin = time.perf_counter()
                result = subprocess.run([java, '-Xmx512m', '-Xss8m', '-jar', str(repository /
                    'quill-kotlin-semantic/target/quill-kotlin-semantic-0.1-SNAPSHOT-all.jar')],
                    input=json.dumps(request), capture_output=True, text=True, timeout=30, check=True)
                value = json.loads(result.stdout)
                elapsed = (time.perf_counter() - begin) * 1000
                if case['expected_descriptor'] is None:
                    assert value['status'] == 'analysis_error' and any(
                        f"Unresolved reference '{case['expected_missing_name']}'" in message
                        for message in value.get('diagnostics', [])), (case['case'], value)
                else:
                    assert value['status'] == 'resolved', (case['case'], value)
                    if case.get('expected_kind') == 'PROPERTY':
                        assert value['kind'] == 'PROPERTY', (case['case'], value)
                        # Worker exposes property identity, but no property type. Query an inferred
                        # wrapper separately with fresh FIR to independently check its type.
                        source.write_text(source.read_text() + '\nfun __quill_type_check() = run()')
                        request['offset'] = source.read_text().rindex('run()')
                        check = subprocess.run([java, '-Xmx512m', '-Xss8m', '-jar', str(repository /
                            'quill-kotlin-semantic/target/quill-kotlin-semantic-0.1-SNAPSHOT-all.jar')],
                            input=json.dumps(request), capture_output=True, text=True, timeout=30, check=True)
                        typed = json.loads(check.stdout)
                        assert typed['status'] == 'resolved' and typed.get('descriptor') == case['expected_descriptor'], (case['case'], typed)
                        value['type_reference_descriptor'] = typed['descriptor']
                    else:
                        assert value.get('descriptor') == case['expected_descriptor'], (case['case'], value)
                references[key] = {field: value.get(field) for field in ('descriptor', 'owner', 'name', 'status', 'type_reference_descriptor')}
                references[key]['process_ms'] = elapsed
                references[key]['declaration_file'] = Path(value['source_file']).name if value.get('source_file') else None
            report['fresh_cli_reference'].append(dict(case=case['case'], **references[key]))
    for retained, fresh in zip(report['cases'], report['fresh_cli_reference']):
        if retained['negative_control'] or not retained['correct']:
            continue
        if fresh['status'] == 'resolved':
            assert retained['callable'].rsplit('/', 1)[-1] == fresh['name'], (retained, fresh)
            assert retained.get('declaration_file') == fresh['declaration_file'], (retained, fresh)
    controls = [case for case in report['cases'] if case['negative_control']]
    assert len(controls) == 1 and controls[0]['actual'] == 'kotlin/Int' and not controls[0]['psi_matches_disk'], controls
    assert all(case['correct'] for case in report['cases'] if case['case'] == 'cold' or case['case'].startswith('unchanged_'))
    required = [case for case in report['cases'] if not case['negative_control']]
    report['mutable_session_correct'] = all(case['correct'] for case in required)
    report['required_cases'] = len(required)
    report['passed_required_cases'] = sum(case['correct'] for case in required)
    report['promotion_gate'] = 'more_validation_required' if report['mutable_session_correct'] else 'blocked'
    report['scope'] = 'One source module, stdlib only; cross-file signatures/imports/aliases/properties/overloads/source add-delete-rename. Global invalidation and full provider-index rebuild; not per-function incremental analysis.'
    # Small-fixture observations only; no latency comparison is promoted when correctness fails.
    if report['mutable_session_correct']:
        edited = [case for case in required if case['changed_psi_files']]
        report['small_fixture_median_edit_ms'] = statistics.median(case['refresh_ms'] + case['query_ms'] for case in edited)
        report['fresh_process_median_ms'] = statistics.median(value['process_ms'] for value in references.values())
        report['timing_limitations'] = 'Correctness runs may overlap across JDKs; fresh reference includes JVM startup; no large-module or multi-module benchmark, no selective FIR invalidation.'
    Path(args.output).write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({field: report[field] for field in ('java', 'required_cases', 'passed_required_cases', 'promotion_gate', 'runtime_bytes')}))


if __name__ == '__main__':
    main()
