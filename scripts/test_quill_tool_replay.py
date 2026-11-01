import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest

import quill_tool_replay as r

# Answers each tools/call with a structured payload whose size depends on the arguments;
# the first call is refresh_pending once, as a server still loading its index answers.
STUB = textwrap.dedent('''
    import json, sys
    pending = True
    for line in sys.stdin:
        m = json.loads(line)
        if 'id' not in m:
            continue
        if m['method'] == 'initialize':
            result = {'protocolVersion': '2025-11-25', 'capabilities': {}}
        else:
            name, args = m['params']['name'], m['params']['arguments']
            if pending:
                pending = False
                result = {'structuredContent': {'status': 'refresh_pending', 'retry_after_ms': 1}}
            elif args.get('fail'):
                result = {'isError': True, 'content': [{'type': 'text', 'text': 'boom'}]}
            else:
                result = {'structuredContent': {'tool': name, 'pad': 'x' * args.get('size', 1),
                                                'source_tokens': args.get('source_tokens')}}
        print(json.dumps({'jsonrpc': '2.0', 'id': m['id'], 'result': result}), flush=True)
''')


def session(root, name, calls):
    """calls: (request name, arguments, recorded structured payload or None for an error)."""
    mcp = Path(root) / name / 'mcp'
    mcp.mkdir(parents=True)
    requests, responses = [], []
    for i, (tool, arguments, recorded) in enumerate(calls):
        requests.append({'jsonrpc': '2.0', 'id': i, 'method': 'tools/call',
                         'params': {'name': tool, 'arguments': arguments}})
        result = {'isError': True, 'content': []} if recorded is None else {'structuredContent': recorded}
        responses.append({'jsonrpc': '2.0', 'id': i, 'result': result})
    (mcp / 'client-to-native.jsonl').write_text('\n'.join(map(json.dumps, requests)) + '\nnot json\n')
    (mcp / 'native-to-client.jsonl').write_text('\n'.join(map(json.dumps, responses)) + '\n')


class ReplayTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.stub = self.dir / 'stub.py'
        self.stub.write_text(STUB)
        self.sessions = self.dir / 'sessions'

    def run_replay(self, *extra):
        out = self.dir / 'report.json'
        with contextlib.redirect_stdout(io.StringIO()):
            code = r.main(['--sessions', str(self.sessions), '--out', str(out), *extra,
                           '--', sys.executable, str(self.stub)])
        return code, json.loads(out.read_text())

    def test_measures_delegated_tools_and_reuses_repeated_calls(self):
        recorded = {'pad': 'y' * 100}
        session(self.sessions, 'a-with_quill', [
            ('get_symbol_details', {'size': 10, 'source_tokens': 1}, recorded),
            ('execute_tool', {'name': 'list_beans', 'arguments': {}, 'size': 20}, recorded)])
        session(self.sessions, 'b-with_quill', [('get_symbol_details', {'size': 10, 'source_tokens': 1}, recorded)])
        code, report = self.run_replay()
        self.assertEqual(0, code)
        self.assertEqual(3, report['calls'])
        self.assertEqual(2, report['distinct_calls'])
        self.assertEqual({'get_symbol_details', 'list_beans'}, set(report['per_tool']))
        self.assertEqual(2, report['per_tool']['get_symbol_details']['calls'])
        self.assertLess(report['replay_chars'], report['recorded_chars'])
        self.assertEqual([], report['regressions'])
        self.assertEqual(2, len(report['symbol_details_larger_than_source']))

    def test_a_call_that_now_fails_is_a_regression_but_a_recorded_failure_is_not(self):
        session(self.sessions, 'a-with_quill', [
            ('list_beans', {'fail': True}, {'beans': []}),
            ('list_beans', {'fail': True, 'other': 1}, None)])
        code, report = self.run_replay()
        self.assertEqual(1, code)
        self.assertEqual(1, len(report['regressions']))
        self.assertEqual({'fail': True}, report['regressions'][0]['arguments'])

    def test_guard_restores_the_index_and_rejects_other_changes(self):
        project = self.dir / 'project'
        (project / '.quill').mkdir(parents=True)
        (project / '.quill/index.db').write_text('original')
        (project / 'Source.java').write_text('class Source {}')
        with r.GuardedProject(project, self.dir):
            (project / '.quill/index.db').write_text('refreshed')
            (project / '.quill/cache').write_text('new')
        self.assertEqual('original', (project / '.quill/index.db').read_text())
        self.assertFalse((project / '.quill/cache').exists())
        with self.assertRaisesRegex(RuntimeError, 'Source.java'):
            with r.GuardedProject(project, self.dir):
                (project / 'Source.java').write_text('class Source { int changed; }')
        self.assertEqual('original', (project / '.quill/index.db').read_text())

    def test_payload_prefers_structured_content(self):
        self.assertEqual('{"a":1}', r.payload({'structuredContent': {'a': 1}, 'content': [{'type': 'text', 'text': 'x'}]}))
        self.assertEqual('xy', r.payload({'content': [{'type': 'text', 'text': 'x'}, {'type': 'text', 'text': 'y'}]}))
        self.assertTrue(r.failed({'structuredContent': {'error_code': 'CLASS_NOT_FOUND'}}))


if __name__ == '__main__':
    unittest.main()
