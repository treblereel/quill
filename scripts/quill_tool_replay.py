#!/usr/bin/env python3
"""Replay recorded Quill tool calls against a Quill MCP server and compare response sizes.

Reads the tools/call requests and responses captured in benchmark sessions
(sessions/*/mcp/client-to-native.jsonl and native-to-client.jsonl), sends each distinct call
once to a server started on the same project, and reports size changes per tool. It never
contacts a model, so it measures what a change does to Quill's output, not to model usage.

    quill_tool_replay.py --sessions RESULTS/sessions --out replay.json -- \
        /path/to/quill --mcp --project /path/to/project

Sizes count the payload a client passes to the model: compact structuredContent JSON when
present, otherwise the text content. Approximate tokens are characters / 4.
"""
import argparse
from collections import Counter, defaultdict
import contextlib
import json
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import threading
import time


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def jsonl(path):
    for line in Path(path).read_text(errors='replace').splitlines():
        try:
            yield json.loads(line)
        except json.JSONDecodeError:
            continue


def effective_tool(name, arguments):
    """execute_tool delegates to another tool; report the delegated one."""
    if name == 'execute_tool' and isinstance(arguments.get('name'), str):
        return arguments['name']
    return name


def payload(result):
    if not isinstance(result, dict):
        return ''
    if result.get('structuredContent') is not None:
        return json.dumps(result['structuredContent'], ensure_ascii=False, separators=(',', ':'))
    return ''.join(c.get('text', '') for c in result.get('content') or [] if c.get('type') == 'text')


def failed(result):
    if not isinstance(result, dict) or result.get('isError'):
        return True
    structured = result.get('structuredContent')
    return isinstance(structured, dict) and ('error' in structured or 'error_code' in structured)


def tree_state(root):
    """Size and mtime of every file under root, keyed by relative path."""
    root = Path(root)
    return {str(p.relative_to(root)): (p.lstat().st_size, p.lstat().st_mtime_ns)
            for p in root.rglob('*') if p.is_file() or p.is_symlink()}


class GuardedProject:
    """Lets a server run on a shared fixture and puts the fixture back afterwards.

    Freshness checks embed the project's absolute path (index metadata, build fingerprints,
    compiler settings), so a copy reports a stale index and withholds source evidence. The
    replay therefore runs on the original: .quill, which a server may refresh, is restored from
    a backup, and any other change to the tree fails the run.
    """

    def __init__(self, project, backup):
        self.project = Path(project)
        self.quill = self.project / '.quill'
        self.backup = Path(backup) / 'quill-index-backup'

    def __enter__(self):
        if self.backup.exists():
            raise RuntimeError(f'backup already exists: {self.backup}')
        self.before = tree_state(self.project)
        shutil.copytree(self.quill, self.backup, symlinks=True, copy_function=shutil.copy2)
        return self

    def __exit__(self, *exc):
        shutil.rmtree(self.quill)
        shutil.copytree(self.backup, self.quill, symlinks=True, copy_function=shutil.copy2)
        shutil.rmtree(self.backup)
        after = tree_state(self.project)
        changed = sorted(k for k in set(self.before) | set(after) if self.before.get(k) != after.get(k))
        if changed:
            raise RuntimeError('fixture changed outside .quill: ' + ', '.join(changed[:20]))


def recorded_calls(sessions):
    """Calls in session order, each with the response recorded for it."""
    calls = []
    for session in sorted(Path(sessions).iterdir()):
        requests_path = session / 'mcp/client-to-native.jsonl'
        responses_path = session / 'mcp/native-to-client.jsonl'
        if not requests_path.is_file() or not responses_path.is_file():
            continue
        responses = {m.get('id'): m for m in jsonl(responses_path) if 'id' in m}
        for message in jsonl(requests_path):
            if message.get('method') != 'tools/call':
                continue
            params = message.get('params') or {}
            arguments = params.get('arguments') or {}
            response = responses.get(message.get('id'), {})
            calls.append({'session': session.name, 'name': params.get('name'), 'arguments': arguments,
                          'tool': effective_tool(params.get('name'), arguments),
                          'recorded': response.get('result'), 'recorded_rpc_error': response.get('error')})
    return calls


class McpServer:
    """Newline-delimited JSON-RPC over the stdio of one server process."""

    def __init__(self, command, timeout=120, cwd=None):
        self.timeout = timeout
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.DEVNULL, text=True, bufsize=1, cwd=cwd)
        self.lines = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.next_id = 0
        self.request('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                                    'clientInfo': {'name': 'quill-tool-replay', 'version': '1'}})
        self.send({'jsonrpc': '2.0', 'method': 'notifications/initialized'})

    def _read(self):
        for line in self.process.stdout:
            self.lines.put(line)
        self.lines.put(None)

    def send(self, message):
        self.process.stdin.write(json.dumps(message) + '\n')
        self.process.stdin.flush()

    def request(self, method, params):
        self.next_id += 1
        expected = self.next_id
        self.send({'jsonrpc': '2.0', 'id': expected, 'method': method, 'params': params})
        while True:
            line = self.lines.get(timeout=self.timeout)
            if line is None:
                raise RuntimeError('Quill server exited')
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            if message.get('id') == expected:
                if 'error' in message:
                    raise RuntimeError(message['error'].get('message', 'JSON-RPC error'))
                return message.get('result')

    def call(self, name, arguments):
        """tools/call, waiting out refresh_pending like clients that honour retry_after_ms."""
        deadline = time.monotonic() + self.timeout
        while True:
            result = self.request('tools/call', {'name': name, 'arguments': arguments})
            structured = (result or {}).get('structuredContent')
            if not (isinstance(structured, dict) and structured.get('status') == 'refresh_pending'):
                return result
            if time.monotonic() > deadline:
                raise RuntimeError('index still refresh_pending')
            time.sleep(min(structured.get('retry_after_ms', 1000), 5000) / 1000)

    def close(self):
        try:
            self.process.stdin.close()
            self.process.wait(timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def replay(calls, server):
    """Sends each distinct (name, arguments) once; repeated calls reuse that response."""
    responses = {}
    for call in calls:
        key = canonical([call['name'], call['arguments']])
        if key not in responses:
            try:
                responses[key] = {'result': server.call(call['name'], call['arguments'])}
            except (RuntimeError, queue.Empty) as error:
                responses[key] = {'error': str(error) or 'timeout'}
        call['replay'] = responses[key].get('result')
        call['replay_error'] = responses[key].get('error')
    return calls


def composition(text):
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        return {}
    if not isinstance(value, dict):
        return {}
    return {key: len(json.dumps(item, ensure_ascii=False, separators=(',', ':'))) for key, item in value.items()}


def source_tokens(text):
    try:
        value = json.loads(text)
    except json.JSONDecodeError:
        return None
    return value.get('source_tokens') if isinstance(value, dict) else None


def summarize(calls):
    tools = defaultdict(lambda: {'calls': 0, 'recorded_chars': 0, 'replay_chars': 0,
                                 'replay_fields': Counter()})
    regressions, larger_than_source = [], []
    for call in calls:
        recorded, replayed = payload(call['recorded']), payload(call.get('replay'))
        row = tools[call['tool']]
        row['calls'] += 1
        row['recorded_chars'] += len(recorded)
        row['replay_chars'] += len(replayed)
        row['replay_fields'].update(composition(replayed))
        if not failed(call['recorded']) and (call.get('replay_error') or failed(call.get('replay'))):
            regressions.append({'session': call['session'], 'tool': call['tool'], 'arguments': call['arguments'],
                                'error': call.get('replay_error') or replayed[:300]})
        source = source_tokens(replayed)
        if call['tool'] == 'get_symbol_details' and source and len(replayed) // 4 > source:
            larger_than_source.append({'session': call['session'], 'arguments': call['arguments'],
                                       'approx_tokens': len(replayed) // 4, 'source_tokens': source})
    per_tool = {}
    for name, row in sorted(tools.items(), key=lambda item: -item[1]['recorded_chars']):
        per_tool[name] = {'calls': row['calls'], 'recorded_chars': row['recorded_chars'],
                          'replay_chars': row['replay_chars'],
                          'replay_over_recorded': round(row['replay_chars'] / row['recorded_chars'], 3)
                          if row['recorded_chars'] else None,
                          'largest_replay_fields': dict(row['replay_fields'].most_common(5))}
    recorded = sum(r['recorded_chars'] for r in per_tool.values())
    replayed = sum(r['replay_chars'] for r in per_tool.values())
    return {'calls': len(calls),
            'distinct_calls': len({canonical([c['name'], c['arguments']]) for c in calls}),
            'recorded_chars': recorded, 'replay_chars': replayed,
            'approx_token_delta': (replayed - recorded) // 4,
            'replay_over_recorded': round(replayed / recorded, 3) if recorded else None,
            'per_tool': per_tool, 'regressions': regressions,
            'symbol_details_larger_than_source': larger_than_source,
            'scope': 'Quill response size only; model usage, call counts and answer accuracy need a model run'}


def print_report(report, out=None):
    out = out or sys.stdout
    print(f"calls {report['calls']} (distinct {report['distinct_calls']}): "
          f"{report['recorded_chars']:,} -> {report['replay_chars']:,} chars "
          f"(x{report['replay_over_recorded']}, ~{report['approx_token_delta']:+,} tokens)", file=out)
    for name, row in report['per_tool'].items():
        print(f"  {name:32} {row['calls']:4} calls {row['recorded_chars']:10,} -> {row['replay_chars']:10,}"
              f"  x{row['replay_over_recorded']}", file=out)
    print(f"regressions {len(report['regressions'])}; get_symbol_details larger than source "
          f"{len(report['symbol_details_larger_than_source'])}", file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--sessions', required=True, help='benchmark sessions directory')
    parser.add_argument('--out', help='write the JSON report here')
    parser.add_argument('--tool', action='append', help='replay only these (effective) tools')
    parser.add_argument('--timeout', type=float, default=120, help='seconds per call')
    parser.add_argument('--cwd', help='server working directory; benchmark servers run in the project root')
    parser.add_argument('--guard', metavar='BACKUP_DIR',
                        help='restore --cwd/.quill afterwards from a backup here; fail if anything else changed')
    parser.add_argument('command', nargs=argparse.REMAINDER, help='-- server command line')
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('server command is required after --')
    if args.guard and not args.cwd:
        parser.error('--guard requires --cwd')
    calls = recorded_calls(args.sessions)
    if args.tool:
        calls = [c for c in calls if c['tool'] in args.tool]
    guard = GuardedProject(args.cwd, args.guard) if args.guard else contextlib.nullcontext()
    with guard, McpServer(command, args.timeout, args.cwd) as server:
        replay(calls, server)
    report = summarize(calls)
    if args.out:
        Path(args.out).write_text(json.dumps(report, indent=1, ensure_ascii=False) + '\n')
    print_report(report)
    return 1 if report['regressions'] else 0


if __name__ == '__main__':
    sys.exit(main())
