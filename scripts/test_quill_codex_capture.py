"""Actual frozen CLI against TLS local fake upstream. Zero paid/model calls."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import ssl
import struct
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from quill_codex_capture import CodexCapture, instrument_codex, sha, wire_usage

BINARY = Path('/tmp/quill-cq050-runtime/codex')
MITM = Path('/tmp/quill-codex-wire-tools-v1/bin/mitmdump')
OUTPUT = Path(os.environ.get('QUILL_CAPTURE_TEST_OUTPUT', 'benchmarks/results/codex-wire-capture-local-v1')).resolve()


def events(number, warm=False):
    message = {'id': f'msg_{number}', 'type': 'message', 'role': 'assistant', 'status': 'completed',
               'content': [{'type': 'output_text', 'text': 'LOCAL_OK', 'annotations': []}]}
    response = {'id': f'resp_local_{number}', 'status': 'completed', 'output': [] if warm else [message],
                'usage': {'input_tokens': 20, 'input_tokens_details': {'cached_tokens': 0},
                          'output_tokens': 0 if warm else 2, 'output_tokens_details': {'reasoning_tokens': 0},
                          'total_tokens': 20 if warm else 22}}
    result = [{'type': 'response.created', 'response': {**response, 'status': 'in_progress', 'output': []}}]
    if not warm:
        result.append({'type': 'response.output_item.done', 'output_index': 0, 'item': message})
    return result + [{'type': 'response.completed', 'response': response}]


class Fake(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *args):
        pass

    def record(self, payload, protocol):
        assert self.headers['Authorization'] == 'Bearer local-probe-credential'
        self.server.records.append({'body': payload, 'protocol': protocol})
        return len(self.server.records)

    def do_POST(self):
        assert self.path == '/v1/responses'
        body = self.rfile.read(int(self.headers['Content-Length']))
        number = self.record(body, 'HTTP')
        payload = ''.join('data: ' + json.dumps(e) + '\n\n' for e in events(number)).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        # Several chunks so a buffering/nontransparent observer cannot hide behind one write.
        for start in range(0, len(payload), 97):
            self.wfile.write(payload[start:start + 97])
            self.wfile.flush()

    def do_GET(self):
        assert self.path == '/v1/responses'
        key = self.headers['Sec-WebSocket-Key']
        accept = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        self.send_response(101)
        self.send_header('Upgrade', 'websocket')
        self.send_header('Connection', 'Upgrade')
        self.send_header('Sec-WebSocket-Accept', accept)
        self.end_headers()

        def exact(n):
            body = b''
            while len(body) < n:
                chunk = self.rfile.read(n - len(body))
                if not chunk:
                    raise EOFError()
                body += chunk
            return body

        def send(payload, opcode=1):
            n = len(payload)
            header = bytes([128 | opcode, n]) if n < 126 else bytes([128 | opcode, 126]) + struct.pack('!H', n)
            self.wfile.write(header + payload)
            self.wfile.flush()

        while True:
            try:
                flags, length = exact(2)
            except EOFError:
                return
            size = length & 127
            if size == 126:
                size = struct.unpack('!H', exact(2))[0]
            elif size == 127:
                size = struct.unpack('!Q', exact(8))[0]
            assert not flags & 112 and flags & 128  # Fake server did not negotiate compression/fragmentation.
            mask = exact(4) if length & 128 else None
            body = exact(size)
            if mask:
                body = bytes(value ^ mask[i % 4] for i, value in enumerate(body))
            opcode = flags & 15
            if opcode == 8:
                send(body, 8)
                return
            if opcode == 9:
                send(body, 10)
                continue
            assert opcode == 1
            number = self.record(body, 'WS')
            warm = json.loads(body).get('generate') is False
            for event in events(number, warm):
                send(json.dumps(event).encode())


def main():
    OUTPUT.mkdir(parents=True, exist_ok=False)
    results = {}
    with tempfile.TemporaryDirectory(prefix='quill-wire-fake-') as tmp:
        temp = Path(tmp)
        cert, key = temp / 'server.crt', temp / 'server.key'
        subprocess.run(['/opt/homebrew/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes',
                        '-keyout', str(key), '-out', str(cert), '-days', '1', '-subj', '/CN=127.0.0.1',
                        '-addext', 'subjectAltName=IP:127.0.0.1'], check=True, capture_output=True)
        for mode in ['sse', 'ws']:
            d = OUTPUT / mode
            d.mkdir()
            server = ThreadingHTTPServer(('127.0.0.1', 0), Fake)
            server.records = []
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.load_cert_chain(cert, key)
            server.socket = context.wrap_socket(server.socket, server_side=True)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            home, project = temp / (mode + '-home'), temp / (mode + '-project')
            home.mkdir(); project.mkdir()
            try:
                with CodexCapture(d / 'wire', MITM, '127.0.0.1', cert) as capture:
                    env = capture.environment(os.environ)
                    env.update(CODEX_HOME=str(home), OPENAI_API_KEY='local-probe-credential',
                               CODEX_ROLLOUT_TRACE_ROOT=str(d / 'native-trace'))
                    settings = {'model_provider': 'localprobe', 'model_providers.localprobe.name': 'Local probe',
                                'model_providers.localprobe.base_url': f'https://127.0.0.1:{server.server_port}/v1',
                                'model_providers.localprobe.wire_api': 'responses',
                                'model_providers.localprobe.env_key': 'OPENAI_API_KEY',
                                'model_providers.localprobe.supports_websockets': mode == 'ws',
                                'model_providers.localprobe.request_max_retries': 0,
                                'model_providers.localprobe.stream_max_retries': 0,
                                'features.apps': False, 'features.plugins': False}
                    args = ['/usr/bin/sandbox-exec', '-p', '(version 1)(allow default)(deny network-outbound)(allow network-outbound (remote ip "localhost:*"))', str(BINARY)]
                    for k, v in settings.items():
                        args += ['-c', k + '=' + json.dumps(v)]
                    args += ['exec', '--skip-git-repo-check', '--json', '--model', 'gpt-6.1-sol', 'LOCAL_PROBE_ONLY']
                    r = subprocess.run(args, cwd=project, env=env, input='', capture_output=True, text=True, timeout=45)
                    (d / 'stdout.jsonl').write_text(r.stdout)
                    (d / 'stderr.log').write_text(r.stderr)
                    assert r.returncode == 0 and 'LOCAL_OK' in r.stdout, r.stderr
                    assert 'falling back to HTTP' not in r.stderr
            finally:
                server.shutdown(); server.server_close()
            assert server.records
            captured = []
            if mode == 'sse':
                captured = [p.read_bytes() for p in (d / 'wire').glob('*/request.body')]
            else:
                for p in sorted((d / 'wire').glob('*/ws-*.json')):
                    if json.loads(p.read_text())['from_client']:
                        captured.append(p.with_suffix('.body').read_bytes())
            ledger=wire_usage(d/'wire');assert ledger['usage_complete'],ledger
            assert ledger['totals']['input']==(20 if mode=='sse' else 40)
            (d/'wire-usage.json').write_text(json.dumps(ledger,indent=2)+'\n')
            actual = [row['body'] for row in server.records]
            assert sorted(map(sha, captured)) == sorted(map(sha, actual)), 'Observer lost or rewrote a request'
            for i, body in enumerate(actual):
                (d / f'upstream-request-{i:02}.json').write_bytes(body)
            for p in (d / 'wire').rglob('*'):
                if p.is_file():
                    assert b'local-probe-credential' not in p.read_bytes(), p
                    assert b'BEGIN PRIVATE KEY' not in p.read_bytes(), p
            trace = next((d / 'native-trace').glob('*/trace.jsonl'))
            starts = [json.loads(line)['payload'] for line in trace.read_text().splitlines() if json.loads(line).get('payload', {}).get('type') == 'inference_started']
            cli_usage = next(json.loads(line)['usage'] for line in r.stdout.splitlines() if json.loads(line).get('type') == 'turn.completed')
            if mode == 'sse':
                assert len(actual) == len(starts) == 1
                logical = json.loads((trace.parent / starts[0]['request_payload']['path']).read_text())
                assert logical == json.loads(actual[0])
            else:
                assert len(actual) == 2 and len(starts) == 1
                assert json.loads(actual[0])['generate'] is False
                assert json.loads(actual[1])['previous_response_id'] == 'resp_local_1'
                assert cli_usage['input_tokens'] == 20  # Fake's prewarm20 is absent from turn counters.
            results[mode] = {'requests': len(actual), 'native_trace_inferences': len(starts),
                             'cli_usage': cli_usage, 'synthetic_wire_input_tokens': 20 * len(actual),
                             'transport_preserved': True, 'all_request_payloads_byte_identical': True,
                             'auth_delivered_to_upstream_but_not_persisted': True, 'external_provider_calls': 0}
            print(mode, results[mode], flush=True)
    (OUTPUT / 'results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
