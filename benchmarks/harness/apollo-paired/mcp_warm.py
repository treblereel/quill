#!/usr/bin/env python3
"""Starts Quill MCP on a project and polls get_overview until the index is current.

Consumes pending build events the CLI leaves for the server, so the snapshotted index
needs no refresh at session start. Exits non-zero if not ready within the timeout.
Usage: mcp_warm.py <quill-binary> <project> [timeout-seconds]
"""
import json, subprocess, sys, time


def warm(binary, project, timeout=600, tolerated_stale=()):
    """tolerated_stale: modules whose build_required warning is a known Quill defect."""
    child = subprocess.Popen([binary, '--mcp', '--tools', 'codex', '--project', project],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    ids = iter(range(1, 10**6))

    def call(method, params=None, notify=False):
        msg = {'jsonrpc': '2.0', 'method': method, **({'params': params} if params is not None else {})}
        if not notify:
            msg['id'] = next(ids)
        child.stdin.write(json.dumps(msg) + '\n'); child.stdin.flush()
        if notify:
            return None
        while True:
            reply = json.loads(child.stdout.readline())
            if reply.get('id') == msg['id']:
                assert 'error' not in reply, reply
                return reply['result']

    try:
        call('initialize', {'protocolVersion': '2025-06-18', 'capabilities': {},
                            'clientInfo': {'name': 'apollo-fixture-warm', 'version': '1'}})
        call('notifications/initialized', notify=True)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            data = call('tools/call', {'name': 'execute_tool',
                                       'arguments': {'name': 'get_overview', 'arguments': {}}}).get('structuredContent', {})
            meta = data.get('_meta', {})
            warnings = [w for w in data.get('project_warnings', [])
                        if w.get('status') in ('build_required', 'index_required', 'refresh_pending')
                        and not (w.get('status') == 'build_required' and w.get('stale_modules')
                                 and set(w['stale_modules']) <= set(tolerated_stale))]
            if meta.get('stale_scope') == 'current' and meta.get('answer_confidence') == 'high' and not warnings:
                return data
            time.sleep(1)
        raise TimeoutError(f'Quill not current after {timeout}s: {json.dumps(meta)} {json.dumps(warnings)[:1500]}')
    finally:
        child.stdin.close(); child.wait(timeout=30)


if __name__ == '__main__':
    data = warm(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 600)
    print(json.dumps({'ready': True, 'project': data.get('project')}))
