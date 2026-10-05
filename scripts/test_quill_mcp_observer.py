import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from quill_mcp_observer import Receipts


class McpObserverTest(unittest.TestCase):
    def test_catalog_receipt_without_payloads(self):
        records = []
        observer = Receipts(records.append, clock=iter([1.0, 1.25]).__next__)
        observer.observe(json.dumps({'id': 1, 'method': 'tools/list', 'params': {'secret': 'PRIVATE'}}))
        observer.observe(json.dumps({'id': 1, 'result': {'tools': [
            {'name': 'get_overview', 'description': 'PRIVATE'}]}}), response=True)
        self.assertEqual(250, records[-1]['duration_ms'])
        self.assertEqual(1, records[-1]['tool_count'])
        self.assertTrue(records[-1]['has_overview'])
        self.assertNotIn('PRIVATE', json.dumps(records))

    def test_errors_and_unknown_responses_do_not_prove_catalog_availability(self):
        records = []
        observer = Receipts(records.append)
        observer.observe('[]')
        observer.observe('not JSON')
        observer.observe(json.dumps({'id': 0, 'result': {'tools': []}}), response=True)
        self.assertEqual([], records)
        observer.observe(json.dumps({'id': 1, 'method': 'tools/list'}))
        observer.observe(json.dumps({'id': 1, 'error': {'message': 'PRIVATE'}}), response=True)
        self.assertFalse(records[-1]['success'])
        self.assertNotIn('tool_count', records[-1])
        self.assertNotIn('PRIVATE', json.dumps(records))

    def test_proxy_preserves_wire_bytes_and_exits_at_eof(self):
        with tempfile.TemporaryDirectory() as temporary:
            trace = Path(temporary) / 'receipts.jsonl'
            wire = b'{"id":1,"method":"tools/call","params":{"name":"get_overview","secret":"PRIVATE"}}\n'
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('quill_mcp_observer.py')),
                                     str(trace), sys.executable, '-c',
                                     'import sys; sys.stdout.buffer.write(sys.stdin.buffer.read()); sys.stdout.flush()'],
                                    input=wire, capture_output=True, timeout=10)
            self.assertEqual(0, result.returncode)
            self.assertEqual(wire, result.stdout)
            self.assertNotIn('PRIVATE', trace.read_text())
            self.assertEqual(0o600, trace.stat().st_mode & 0o777)


if __name__ == '__main__':
    unittest.main()
