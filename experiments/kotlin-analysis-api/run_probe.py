#!/usr/bin/env python3
"""Probe an isolated Analysis API session and compare edited files with fresh CLI FIR."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile


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
    probe = subprocess.run([java, '-Xmx512m', '-cp', str(directory / 'target/classes') + ':' + ':'.join(paths),
                            'ProbeKt'], capture_output=True, text=True, timeout=30, check=True)
    report = json.loads(probe.stdout)
    report['runtime_artifacts'] = len(paths)
    report['runtime_bytes'] = sum(Path(path).stat().st_size for path in paths)
    report['classpath_isolation'] = 'Separate process; unshaded IntelliJ core, no production worker changes'
    report['fresh_cli_reference'] = []
    stdlib = next(path for path in paths if '/kotlin-stdlib/2.4.21/' in path)
    with tempfile.TemporaryDirectory(prefix='quill-api-reference-') as temporary:
        source = Path(temporary) / 'Call.kt'
        for expression, expected in [('1000', '()I'), ('true', '()Z'), ('"s"', '()Ljava/lang/String;')]:
            source.write_text('fun pick() = ' + expression + '\nfun run() = pick()')
            request = {'module': temporary, 'file': str(source), 'offset': source.read_text().rindex('pick()'),
                       'sources': [str(source)], 'classpath': [stdlib], 'source_set': 'main'}
            result = subprocess.run([java, '-Xmx512m', '-Xss8m', '-jar', str(repository /
                'quill-kotlin-semantic/target/quill-kotlin-semantic-0.1-SNAPSHOT-all.jar')],
                input=json.dumps(request), capture_output=True, text=True, timeout=30, check=True)
            value = json.loads(result.stdout)
            assert value['status'] == 'resolved' and value['descriptor'] == expected, value
            report['fresh_cli_reference'].append({'expression': expression, 'descriptor': value['descriptor'],
                                                   'owner': value['owner'], 'status': value['status']})
    cases = report['cases']
    assert all(case['correct'] for case in cases if case['case'] in ('cold', 'unchanged'))
    # A false promotion gate is a useful experimental result, not a successful backend migration.
    report['mutable_session_correct'] = all(case['correct'] for case in cases)
    report['promotion_gate'] = 'blocked' if not report['mutable_session_correct'] else 'more_validation_required'
    report['scope'] = 'Single-file inferred type changes only; full corner-case matrix and large-module benchmarks remain prerequisites for promotion'
    Path(args.output).write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'java': report['java'], 'cases': len(cases), 'promotion_gate': report['promotion_gate'],
                      'runtime_bytes': report['runtime_bytes']}))


if __name__ == '__main__':
    main()
