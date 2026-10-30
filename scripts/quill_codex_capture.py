"""Opt-in measurement observer. Original provider URLs/auth/transports stay on the CLI.

Only model bodies are persisted, never auth/cookie headers. The temporary CA is
trusted by the measured child only; no system trust or frozen runner is edited.
"""
import contextlib
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time


def save(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def sha(body):
    return hashlib.sha256(body).hexdigest()


class CaptureAddon:
    def load(self, loader):
        loader.add_option('quill_capture_dir', str, '', 'Private body-only measurement directory')
        loader.add_option('quill_capture_test_host', str, '', 'Explicit local fake-upstream host')

    def running(self):
        from mitmproxy import ctx
        self.root = Path(ctx.options.quill_capture_dir)
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.streams = {}
        save(self.root / 'ready.json', {'port': ctx.options.listen_port, 'headers_persisted': False})

    def selected(self, flow):
        from mitmproxy import ctx
        hosts = {'chatgpt.com', 'api.openai.com'}
        if ctx.options.quill_capture_test_host:
            hosts.add(ctx.options.quill_capture_test_host)
        path = flow.request.path.split('?', 1)[0]
        return flow.request.host in hosts and path.rstrip('/').endswith(('/responses', '/responses/compact'))

    def directory(self, flow):
        d = self.root / flow.id
        d.mkdir(mode=0o700, exist_ok=True)
        return d

    def request(self, flow):
        if not self.selected(flow):
            return
        d = self.directory(flow)
        raw = flow.request.raw_content or b''
        save(d / 'http.json', {'method': flow.request.method, 'path': flow.request.path.split('?', 1)[0],
                              'host': flow.request.host, 'started_at': flow.request.timestamp_start,
                              'client_http_version': flow.request.http_version, 'request_raw_sha256': sha(raw), 'request_encoding': flow.request.headers.get('content-encoding', '')})
        if raw:
            (d / 'request.body').write_bytes(raw)
            body = flow.request.get_content(strict=True)
            json.loads(body)  # Reject unusable capture; do not repair or rewrite the forwarded request.
            (d / 'request.json').write_bytes(body)

    def responseheaders(self, flow):
        if not self.selected(flow) or flow.response.status_code == 101:
            return
        d = self.directory(flow)
        output = (d / 'response.body').open('wb')
        self.streams[flow.id] = output

        def observe(chunk):
            output.write(chunk)
            output.flush()
            return chunk
        flow.response.stream = observe

    def response(self, flow):
        if not self.selected(flow):
            return
        d = self.directory(flow)
        output = self.streams.pop(flow.id, None)
        if output:
            output.close()
            from mitmproxy.net import encoding
            body = encoding.decode((d / 'response.body').read_bytes(), flow.response.headers.get('content-encoding', 'identity'))
            (d / 'response.raw').write_bytes(body)
        meta = json.loads((d / 'http.json').read_text())
        meta.update(status=flow.response.status_code, completed=True,
                    response_started_at=flow.response.timestamp_start,
                    completed_at=flow.response.timestamp_end,
                    request_unchanged=sha(flow.request.raw_content or b'') == meta['request_raw_sha256'])
        save(d / 'http.json', meta)

    def websocket_message(self, flow):
        if not self.selected(flow):
            return
        message = flow.websocket.messages[-1]
        d = self.directory(flow)
        # Payload bytes after WS framing/deflate; forward the original message untouched.
        content = message.content
        count = sum(1 for _ in d.glob('ws-*.body'))
        name = f'ws-{count:04d}'
        (d / (name + '.body')).write_bytes(content)
        save(d / (name + '.json'), {'from_client': message.from_client,
                                   'timestamp': message.timestamp, 'sha256': sha(content)})

    def websocket_end(self, flow):
        if self.selected(flow):
            save(self.directory(flow) / 'websocket-end.json', {'ended': True})

    def error(self, flow):
        if not self.selected(flow):
            return
        output = self.streams.pop(flow.id, None)
        if output:
            output.close()
        save(self.directory(flow) / 'incomplete.json', {'complete': False, 'kind': 'transport_error'})


addons = [CaptureAddon()]


class CodexCapture:
    def __init__(self, directory, mitmdump, test_host='', upstream_ca=None):
        self.directory = Path(directory).resolve()
        self.mitmdump = str(Path(mitmdump).resolve())
        if not os.access(self.mitmdump, os.X_OK):
            raise ValueError('mitmdump executable unavailable')
        assert not test_host or test_host in {'127.0.0.1', 'localhost'}, 'Test upstream must be local'
        self.test_host, self.upstream_ca = test_host, upstream_ca

    def __enter__(self):
        self.started_at = time.monotonic()
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.private = tempfile.TemporaryDirectory(prefix='quill-codex-wire-ca-', dir='/tmp')
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            self.port = s.getsockname()[1]
        args = [self.mitmdump, '-q', '--listen-host', '127.0.0.1', '--listen-port', str(self.port),
                '-s', str(Path(__file__).resolve()), '--set', 'confdir=' + self.private.name,
                '--set', 'quill_capture_dir=' + str(self.directory), '--set', 'ssl_insecure=false']
        if self.test_host:
            args += ['--set', 'quill_capture_test_host=' + self.test_host]
        if self.upstream_ca:
            args += ['--set', 'ssl_verify_upstream_trusted_ca=' + str(self.upstream_ca)]
        self.log = (self.directory / 'observer.log').open('w')
        if self.test_host:
            args = ['/usr/bin/sandbox-exec', '-p', '(version 1)(allow default)(deny network-outbound)(allow network-outbound (remote ip "localhost:*"))', *args]
        self.process = subprocess.Popen(args, stdout=self.log, stderr=subprocess.STDOUT, umask=0o077)
        try:
            deadline = time.monotonic() + 20
            self.ca = (Path(self.private.name) / 'mitmproxy-ca-cert.pem').resolve()
            while not (self.directory / 'ready.json').exists() or not self.ca.exists():
                if self.process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Body observer failed to start; inspect private observer.log')
                time.sleep(0.05)
            self.startup_seconds = time.monotonic() - self.started_at
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def environment(self, original):
        inherited = [original.get(k) for k in ['HTTPS_PROXY', 'HTTP_PROXY', 'ALL_PROXY', 'WSS_PROXY', 'https_proxy', 'http_proxy', 'all_proxy', 'wss_proxy']]
        if any(inherited):
            raise RuntimeError('Existing upstream proxy requires explicit chaining; refusing to replace it')
        env = dict(original)
        proxy = f'http://127.0.0.1:{self.port}'
        env.update(HTTPS_PROXY=proxy, HTTP_PROXY=proxy, ALL_PROXY=proxy, WSS_PROXY=proxy,
                   NO_PROXY='', CODEX_CA_CERTIFICATE=str(self.ca))
        for key in ['https_proxy','http_proxy','all_proxy','wss_proxy','no_proxy']:
            env[key] = env[key.upper()]
        return env

    def __exit__(self, *unused):
        self.process.terminate()
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait()
        self.log.close()
        self.private.cleanup()
        errors = [p for p in self.directory.rglob('incomplete.json')]
        save(self.directory / 'capture.json', {'headers_persisted': False, 'system_trust_changed': False,
                                              'incomplete_flows': len(errors), 'mode': 'HTTP_SSE_and_WebSocket_bodies',
                                              'observer_startup_seconds': getattr(self, 'startup_seconds', None)})


def capture_profile(original, ca):
    ca = Path(ca).resolve()
    literal = '(literal ' + json.dumps(str(ca)) + ')'
    directory = '(subpath ' + json.dumps(str(ca.parent)) + ')'
    marker = '(deny file-read-data (require-all '
    if marker in original:
        if original.count(marker) != 1:
            raise ValueError('Unsupported isolation profile; refuse ambiguous CA read exception')
        original = original.replace(marker, marker + '(require-not ' + literal + ') ', 1)
    elif '(deny file-read' in original:
        raise ValueError('Unsupported read isolation; refusing to broaden permissions')
    return original + '\n(deny file-read-data (require-all ' + directory + ' (require-not ' + literal + ')))\n(deny file-write* ' + directory + ')\n(deny network-outbound)\n(allow network-outbound (remote ip "localhost:*"))\n'


@contextlib.contextmanager
def instrument_codex(runner, mitmdump):
    """Wrap a NEW runner invocation; immutable historic files/schedules are not edited."""
    original = runner.codex
    original_args = runner.rpc_args

    def measured(p, entry, directory, env, sandbox, preflight=False):
        if preflight:
            return original(p, entry, directory, env, sandbox, preflight)
        if (Path(directory) / 'wire').exists():
            raise FileExistsError('Never reuse a started capture directory')
        capture = CodexCapture(Path(directory) / 'wire', mitmdump)
        try:
            with capture:
                local = capture.environment(env)
                local['CODEX_ROLLOUT_TRACE_ROOT'] = str(Path(directory) / 'native-trace')
                profile = Path(directory) / 'capture-isolation.sb'
                profile.write_text(capture_profile(Path(sandbox).read_text(), capture.ca))
                # Keep the existing CLI/model/MCP argv; add transport env to this child only.
                keys = ['HTTPS_PROXY','HTTP_PROXY','ALL_PROXY','WSS_PROXY','NO_PROXY','CODEX_CA_CERTIFICATE',
                        'https_proxy','http_proxy','all_proxy','wss_proxy','no_proxy','CODEX_ROLLOUT_TRACE_ROOT']
                variables = {k: local[k] for k in keys}
                def args(*a):
                    a = list(a)
                    a[-1] = profile
                    return ['/usr/bin/env', *(k + '=' + v for k, v in variables.items()), *original_args(*a)]
                runner.rpc_args = args
                try:
                    result = original(p, entry, directory, env, sandbox, preflight)
                finally:
                    runner.rpc_args = original_args
        finally:
            ledger = wire_usage(Path(directory) / 'wire')
            session = Path(directory) / 'session.json'
            if session.exists():
                cli = json.loads(session.read_text()).get('usage', {})
                if all(type(cli.get(k)) is int for k in ['inputTokens','cachedInputTokens','outputTokens']):
                    ledger['cli_turn_usage'] = cli
                    if ledger['usage_complete']:
                        ledger['wire_minus_cli'] = {k: ledger['totals'][k]-cli[c] for k,c in [('input','inputTokens'),('cached_input','cachedInputTokens'),('output','outputTokens')]}
            save(Path(directory) / 'wire-usage.json', ledger)
        if not ledger['usage_complete']:
            raise RuntimeError('Incomplete wire usage; preserve this attempt, do not replay')
        return result
    runner.codex = measured
    try:
        yield
    finally:
        runner.codex = original
        runner.rpc_args = original_args


def _wire_usage(directory):
    """All model requests including prewarm. Missing/failed usage is unknown, never zero."""
    directory = Path(directory)
    requests = []
    responses = {}
    problems = []

    def response(event, source):
        if event.get('type') != 'response.completed':
            return
        r = event.get('response', {})
        key = r.get('id')
        if not r.get('id') or not isinstance(r.get('usage'), dict):
            problems.append('response_usage_missing')
            return
        usage = r['usage']
        values = {'input': usage.get('input_tokens'), 'output': usage.get('output_tokens'),
                  'cached_input': usage.get('input_tokens_details', {}).get('cached_tokens')}
        if any(type(v) is not int or v < 0 for v in values.values()) or values['cached_input'] > values['input']:
            problems.append('response_usage_unknown')
            return
        if key in responses and responses[key] != values:
            problems.append('conflicting_response_usage')
        responses[key] = values

    for d in directory.iterdir():
        if not d.is_dir() or not (d / 'http.json').exists():
            continue
        meta = json.loads((d / 'http.json').read_text())
        if (d / 'incomplete.json').exists() or not meta.get('completed') or not meta.get('request_unchanged'):
            problems.append('incomplete_or_mutated_transport')
        if meta['method'] == 'POST':
            if not (d / 'request.json').exists() or not (d / 'response.raw').exists() or meta.get('status') != 200:
                problems.append('incomplete_http_model_request')
                continue
            requests.append({'transport': 'HTTP', 'flow': d.name, 'prewarm': False})
            text = (d / 'response.raw').read_text()
            if text.lstrip().startswith('{'):
                value = json.loads(text)
                response({'type': 'response.completed', 'response': value}, d.name)
            else:
                for line in text.splitlines():
                    if line.startswith('data:') and line[5:].strip() != '[DONE]':
                        response(json.loads(line[5:].strip()), d.name)
        elif meta.get('status') == 101:
            for p in sorted(d.glob('ws-*.json')):
                m = json.loads(p.read_text())
                body = p.with_suffix('.body').read_bytes()
                if sha(body) != m['sha256']:
                    problems.append('payload_hash_mismatch')
                value = json.loads(body)
                if m['from_client']:
                    if value.get('type') != 'response.create':
                        problems.append('unsupported_client_ws_message')
                    requests.append({'transport': 'WS', 'flow': d.name, 'prewarm': value.get('generate') is False})
                else:
                    response(value, d.name)
    if not requests or len(requests) != len(responses):
        problems.append('request_response_count_mismatch')
    complete = not problems
    totals = {k: sum(r[k] for r in responses.values()) for k in ['input', 'output', 'cached_input']}
    totals['uncached_input'] = totals['input'] - totals['cached_input']
    totals['all'] = totals['input'] + totals['output']
    return {'usage_complete': complete, 'requests': requests, 'responses': len(responses),
            'totals': totals if complete else None, 'observed_lower_bound': totals, 'problems': problems,
            'scope': 'all captured response.create/HTTP requests including prewarm; no billing-price claim'}


def wire_usage(directory):
    try:
        return _wire_usage(directory)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {'usage_complete': False, 'totals': None, 'problems': ['unreadable_or_invalid_capture']}
