#!/usr/bin/env python3
"""Transparent stdio relay. Persist receipts, never RPC payloads or credentials."""
import json
import os
import re
import subprocess
import sys
import threading
import time


class Receipts:
    def __init__(self, emit, clock=time.monotonic):
        self.emit, self.clock = emit, clock
        self.pending = {}
        self.lock = threading.Lock()

    def observe(self, raw, response=False):
        try:
            message = json.loads(raw)
        except (ValueError, UnicodeError):
            return
        if not isinstance(message, dict) or not isinstance(message.get('id'), (str, int)):
            return
        now = self.clock()
        with self.lock:
            if not response:
                method = message.get('method')
                if method not in {'initialize', 'tools/list', 'tools/call'}:
                    return
                params = message.get('params', {})
                name = params.get('name', '') if isinstance(params, dict) else ''
                name = name if isinstance(name, str) and re.fullmatch(r'[a-z][a-z0-9_]{0,79}', name) else '<unknown>'
                self.pending[message['id']] = (method, name, now)
                self.emit({'event': 'request', 'method': method, 'tool': name if method == 'tools/call' else None,
                           'monotonic_seconds': now})
                return
            request = self.pending.pop(message['id'], None)
            if request is None:
                return
            method, name, started = request
            result = message.get('result')
            success = isinstance(result, dict) and 'error' not in message and not result.get('isError', False)
            receipt = {'event': 'response', 'method': method, 'tool': name if method == 'tools/call' else None,
                       'success': success, 'monotonic_seconds': now,
                       'duration_ms': round((now - started) * 1000, 3)}
            if method == 'tools/list' and success:
                tools = result.get('tools')
                if isinstance(tools, list):
                    receipt.update(tool_count=len(tools), has_overview=any(
                        isinstance(t, dict) and t.get('name') == 'get_overview' for t in tools),
                        has_more=bool(result.get('nextCursor')))
            self.emit(receipt)


def main():
    trace, command = sys.argv[1], sys.argv[2:]
    if not command:
        raise SystemExit('Observer requires the original launcher')
    descriptor = os.open(trace, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    def emit(receipt):
        os.write(descriptor, (json.dumps(receipt) + '\n').encode())
    receipts = Receipts(emit)
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    def relay(source, destination, direction=None):
        try:
            for line in iter(source.readline, b''):
                if direction is False:
                    receipts.observe(line, response=direction)
                destination.write(line)
                destination.flush()
                if direction is True:
                    receipts.observe(line, response=True)
        except (BrokenPipeError, OSError):
            pass
        finally:
            if destination is process.stdin:
                destination.close()
    stdin = threading.Thread(target=relay, args=(sys.stdin.buffer, process.stdin, False), daemon=True)
    stdout = threading.Thread(target=relay, args=(process.stdout, sys.stdout.buffer, True), daemon=True)
    stderr = threading.Thread(target=relay, args=(process.stderr, sys.stderr.buffer), daemon=True)
    for thread in (stdin, stdout, stderr):
        thread.start()
    code = process.wait()
    stdout.join(timeout=5)
    stderr.join(timeout=5)
    os.close(descriptor)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
