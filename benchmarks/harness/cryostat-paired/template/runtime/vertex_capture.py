"""Loopback-only transparent Vertex transport; credentials stay outside persisted records."""
import hashlib
import http.client
import json
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SAFE_HEADERS = {'anthropic-version', 'anthropic-beta', 'content-type', 'user-agent',
                'x-stainless-runtime', 'x-stainless-runtime-version', 'x-goog-api-client'}
HOP_HEADERS = {'host', 'content-length', 'connection', 'transfer-encoding', 'accept-encoding', 'authorization'}


def adc_token():
    result = subprocess.run(['gcloud', 'auth', 'application-default', 'print-access-token'],
                            capture_output=True, text=True, timeout=30)
    if result.returncode or not result.stdout.strip():
        raise RuntimeError('ADC access token unavailable; no credential values logged')
    return result.stdout.strip()


def cache_state(first_usage, regime):
    """Classify observations, never manufacture cache state from a fresh CLI home."""
    read = first_usage['cache_read_input_tokens']
    write = first_usage['cache_creation_input_tokens']
    if first_usage.get('cache_creation', {}).get('ephemeral_1h_input_tokens', 0):
        raise ValueError('One-hour cache observed: five-minute cooldown is insufficient')
    valid = read == 0 and write > 0 if regime == 'cold' else read > 0 and write == 0
    if regime not in ('cold', 'warm') or not valid:
        raise ValueError('Observed cache state does not match declared regime; preserve, do not retry')
    return True


class VertexCapture:
    def __init__(self, directory, project, region, token_provider=adc_token, connection_factory=http.client.HTTPSConnection):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=False)
        if not re.fullmatch(r'[A-Za-z0-9_-]+', project) or not re.fullmatch(r'[a-z0-9-]+', region):
            raise ValueError('Invalid Vertex project/region')
        self.prefix = f'/v1/projects/{project}/locations/{region}/publishers/anthropic/models/'
        self.host = f'{region}-aiplatform.googleapis.com'
        self.token_provider, self.connection_factory = token_provider, connection_factory
        self.lock, self.sequence, self.errors = threading.Lock(), 0, []
        self.last_response_at = None
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def do_POST(self):
                suffix = self.path.removeprefix(owner.prefix)
                if not self.path.startswith(owner.prefix) or suffix not in (
                        'claude-opus-4-6:streamRawPredict', 'count-tokens:rawPredict'):
                    self.send_error(403, 'Vertex capture endpoint not allowed')
                    return
                with owner.lock:
                    owner.sequence += 1
                    number = owner.sequence
                record = owner.directory / f'{number:03}'
                record.mkdir()
                meta = {'path': self.path, 'upstream_host': owner.host, 'started_at': time.time(),
                        'headers': {k.lower(): v for k, v in self.headers.items() if k.lower() in SAFE_HEADERS}}
                conn = None
                try:
                    size = int(self.headers.get('Content-Length', '0'))
                    if not 0 < size <= 20_000_000:
                        raise ValueError('Invalid request size')
                    body = self.rfile.read(size)
                    json.loads(body)
                    (record / 'request.json').write_bytes(body)
                    meta['incoming_body_sha256'] = hashlib.sha256(body).hexdigest()
                    headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
                    headers['Authorization'] = 'Bearer ' + owner.token_provider()
                    headers['Content-Length'] = str(len(body))
                    conn = owner.connection_factory(owner.host, timeout=300)
                    conn.request('POST', self.path, body=body, headers=headers)
                    meta['forwarded_body_sha256'] = hashlib.sha256(body).hexdigest()
                    response = conn.getresponse()
                    meta['status'] = response.status
                    self.send_response(response.status)
                    for key, value in response.getheaders():
                        if key.lower() not in HOP_HEADERS:
                            self.send_header(key, value)
                    self.send_header('Connection', 'close')
                    self.end_headers()
                    with (record / 'response.raw').open('wb') as output:
                        while chunk := response.read1(65536):
                            output.write(chunk)
                            output.flush()
                            self.wfile.write(chunk)
                            self.wfile.flush()
                    meta['completed'] = True
                except Exception as error:
                    # Record only the type: exception messages can contain auth values.
                    meta['error_type'] = type(error).__name__
                    owner.errors.append({'request': number, 'error_type': type(error).__name__})
                    if 'status' not in meta:
                        self.send_error(502, 'Vertex capture transport failed')
                finally:
                    meta['finished_at'] = time.time()
                    owner.last_response_at = meta['finished_at']
                    (record / 'transport.json').write_text(json.dumps(meta, indent=2) + '\n')
                    self.close_connection = True
                    if conn is not None:
                        conn.close()

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    @property
    def url(self):
        return f'http://127.0.0.1:{self.server.server_port}'

    @property
    def sdk_url(self):
        return self.url + '/v1'

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
