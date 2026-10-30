#!/usr/bin/env python3
"""Offline, conservative response-reuse audit. Never executes captured commands/code.

A clean result means no detected flag within supported captures, not proof of model
understanding. Dynamic code and semantic/source overlap without coverage need review.
"""
import argparse
import ast
from collections import Counter
import hashlib
import html
import json
from pathlib import Path
import re


def load(path):
    return json.loads(Path(path).read_text())


def canonical(value):
    # JSON number 1.0 may become 1 in a client round-trip; booleans stay distinct.
    def normalized(item):
        if type(item) is float and item.is_integer():
            return int(item)
        if isinstance(item, dict):
            return {k: normalized(v) for k, v in item.items()}
        if isinstance(item, list):
            return [normalized(v) for v in item]
        return item
    return json.dumps(normalized(value), sort_keys=True, ensure_ascii=False, separators=(',', ':'))


def objects(value):
    """Decode embedded JSON, never repair it or evaluate provider text."""
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)
    elif isinstance(value, str):
        # shortcut: payloads >2MB are unsupported; upgrade with bounded streaming parsing.
        if len(value) > 2_000_000:
            raise ValueError('embedded_payload_limit; coverage unknown')
        decoder = json.JSONDecoder()
        cursor = 0; attempts = 0
        while cursor < len(value):
            match = re.compile(r'[\[{]').search(value, cursor)
            if not match:
                break
            attempts += 1
            if attempts > 4096:
                raise ValueError('embedded_json_fragment_limit; coverage unknown')
            start = match.start()
            try:
                decoded, end = decoder.raw_decode(value, start)
            except RecursionError as error:
                raise ValueError('embedded_json_depth_limit') from error
            except ValueError:
                cursor = start + 1
            else:
                cursor = end
                yield from objects(decoded)


# A literal-only lexer. Identifiers/expressions as values are unresolved, not eval'd.
TOKEN = re.compile(r'''\s+|//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|[A-Za-z_$][\w$]*|-?\d+(?:\.\d+)?|[^\s]''')


def literal(tokens, index):
    token = tokens[index]
    if token == '{':
        result = {}; index += 1
        while tokens[index] != '}':
            key = tokens[index]
            if key.startswith(('"', "'")):
                key = ast.literal_eval(key)
            elif not re.fullmatch(r'[A-Za-z_$][\w$]*', key):
                raise ValueError('dynamic object key')
            if tokens[index + 1] != ':':
                raise ValueError('dynamic object/spread')
            value, index = literal(tokens, index + 2); result[key] = value
            if tokens[index] == ',':
                index += 1
            elif tokens[index] != '}':
                raise ValueError('object syntax')
        return result, index + 1
    if token == '[':
        result = []; index += 1
        while tokens[index] != ']':
            value, index = literal(tokens, index); result.append(value)
            if tokens[index] == ',':
                index += 1
            elif tokens[index] != ']':
                raise ValueError('array syntax')
        return result, index + 1
    if token.startswith(('"', "'")):
        return ast.literal_eval(token), index + 1
    if token.startswith('`') and '${' not in token and '\\' not in token:
        return token[1:-1], index + 1
    if token in ('true', 'false', 'null'):
        return {'true': True, 'false': False, 'null': None}[token], index + 1
    if re.fullmatch(r'-?\d+(?:\.\d+)?', token):
        return json.loads(token), index + 1
    raise ValueError('dynamic argument')


def js_calls(code):
    tokens = [m.group() for m in TOKEN.finditer(code)
              if not m.group().isspace() and not m.group().startswith(('//', '/*'))]
    found = []
    for i in range(len(tokens) - 3):
        if tokens[i:i + 2] != ['tools', '.'] or tokens[i + 3] != '(':
            continue
        name = tokens[i + 2]
        try:
            args, end = literal(tokens, i + 4)
            if tokens[end] != ')' or not isinstance(args, dict):
                raise ValueError('nonliteral call')
        except (ValueError, IndexError, SyntaxError):
            args = None
        found.append({'name': name, 'arguments': args})
    # Catalog text is not a call. Aliases/computed dispatch cannot be cleared as unused.
    for i in range(len(tokens) - 2):
        if tokens[i:i + 2] == ['tools', '['] or (tokens[i:i + 2] == ['tools', '.'] and tokens[i + 2].startswith('mcp__quill__') and tokens[i + 3:i + 4] != ['(']):
            found.append({'name': 'dynamic_dispatch', 'arguments': None})
    if 'ALL_TOOLS' in tokens:
        found.append({'name': 'tool_catalog_inspection', 'arguments': {}})
    return found


def normalize(name, args):
    name = name.removeprefix('mcp__quill__').removeprefix('quill_')
    if name == 'execute_tool' and isinstance(args, dict):
        nested = args.get('arguments')
        if isinstance(nested, str):
            try:
                nested = json.loads(nested)
            except ValueError:
                nested = None
        return args.get('name', 'unknown_execute_tool'), nested
    return name, args


def sse(path, client):
    raw = Path(path).read_text(); events = []
    if raw.lstrip().startswith('{'):
        return load(path)
    for block in re.split(r'\r?\n\r?\n', raw):
        data = '\n'.join(line[5:].strip() for line in block.splitlines() if line.startswith('data:'))
        if data and data != '[DONE]':
            events.append(json.loads(data))
    if client == 'codex':
        done = [x['response'] for x in events if x.get('type') == 'response.completed']
        if len(done) != 1:
            raise ValueError('missing/ambiguous response.completed')
        return done[0]
    result = {}; blocks = {}; fragments = {}; stopped = False
    for event in events:
        kind = event.get('type')
        if kind == 'message_start':
            result = event['message']; result['content'] = []
        elif kind == 'content_block_start':
            blocks[event['index']] = event['content_block']
        elif kind == 'content_block_delta':
            delta = event['delta']; index = event['index']
            if delta.get('type') == 'input_json_delta':
                fragments[index] = fragments.get(index, '') + delta['partial_json']
        elif kind == 'message_delta':
            result.setdefault('usage', {}).update(event.get('usage', {}))
        elif kind == 'message_stop':
            stopped = True
    if not stopped:
        raise ValueError('missing message_stop')
    for index, block in sorted(blocks.items()):
        if block.get('type') in ['tool_use', 'server_tool_use'] and index in fragments:
            try:
                block['input'] = json.loads(fragments[index])
            except ValueError:
                block['input'] = None
        result['content'].append(block)
    return result


def cycles(directory, issues):
    """Read real model bodies, not CLI-normalized approximations."""
    directory = Path(directory); result = []
    for request in sorted((directory / 'transport').glob('*/request.json')):
        try:
            meta = load(request.parent / 'transport.json')
            if not meta.get('completed') or meta.get('status') != 200 or meta.get('incoming_body_sha256') != meta.get('forwarded_body_sha256'):
                issues.append('incomplete_or_mutated_claude_transport:' + str(request))
            if hashlib.sha256(request.read_bytes()).hexdigest() != meta.get('forwarded_body_sha256'):
                issues.append('claude_request_hash_mismatch:' + str(request))
            result.append({'client': 'claude', 'request': load(request), 'response': sse(request.parent / 'response.raw', 'claude'),
                           'source': str(request), 'time': meta['started_at'], 'seconds': meta.get('finished_at', meta['started_at']) - meta['started_at']})
        except (OSError, ValueError, KeyError) as error:
            issues.append(f'unreadable_claude_capture:{request}:{error}')
    for flow in sorted((directory / 'wire').glob('*/http.json')):
        meta = load(flow); pending = None
        if not meta.get('completed') or not meta.get('request_unchanged') or (flow.parent / 'incomplete.json').exists():
            issues.append('incomplete_codex_transport:' + str(flow))
        if meta.get('method') == 'POST':
            try:
                if meta.get('status') != 200:
                    raise ValueError('HTTP status')
                result.append({'client': 'codex', 'request': load(flow.parent / 'request.json'),
                               'response': sse(flow.parent / 'response.raw', 'codex'), 'source': str(flow.parent / 'request.json'),
                               'time': meta['started_at'], 'seconds': meta.get('completed_at', meta['started_at']) - meta['started_at']})
            except (OSError, ValueError, KeyError) as error:
                issues.append(f'unreadable_codex_capture:{flow}:{error}')
            continue
        if meta.get('status') != 101:
            issues.append('unsupported_ws_status:' + str(flow))
        for frame in sorted(flow.parent.glob('ws-*.json')):
            try:
                info = load(frame); body = frame.with_suffix('.body').read_bytes()
                if hashlib.sha256(body).hexdigest() != info['sha256']:
                    raise ValueError('WS payload hash mismatch')
                value = json.loads(body)
                if info['from_client'] and value.get('type') == 'response.create':
                    if pending:
                        issues.append('overlapping_codex_requests:' + str(frame))
                    pending = (value, frame, info['timestamp'])
                elif not info['from_client'] and value.get('type') == 'response.completed':
                    if not pending:
                        raise ValueError('response without captured request')
                    request, source, started = pending; pending = None
                    result.append({'client': 'codex', 'request': request, 'response': value['response'],
                                   'source': str(source), 'time': started, 'seconds': info['timestamp'] - started})
            except (OSError, ValueError, KeyError) as error:
                issues.append(f'unreadable_ws_capture:{frame}:{error}')
        if pending:
            issues.append('codex_request_without_completion:' + str(pending[1]))
    return sorted(result, key=lambda x: (x['time'], x['source']))


def response_calls(cycle):
    response = cycle['response']; result = []
    for block in response.get('output', response.get('content', [])):
        kind = block.get('type')
        if kind in ['tool_use', 'server_tool_use']:
            result.append({'name': block['name'], 'arguments': block.get('input'), 'id': block['id']})
        elif kind == 'function_call':
            if block.get('name') in ['exec', 'functions.exec']:
                code = block.get('arguments', '')
                try:
                    decoded = json.loads(code)
                    if isinstance(decoded, dict):
                        code = decoded.get('code', '')
                except ValueError:
                    pass
                result.extend({**call, 'id': block.get('call_id')} for call in js_calls(code))
                continue
            try:
                args = json.loads(block['arguments'])
            except (ValueError, KeyError):
                args = None
            result.append({'name': block['name'], 'arguments': args, 'id': block.get('call_id')})
        elif kind == 'custom_tool_call':
            result.extend({**call, 'id': block.get('call_id')} for call in js_calls(block.get('input', '')))
    return result


def native_receipts(directory):
    pending = {}; receipts = {}; requests = []
    path = Path(directory) / 'mcp/upstream.jsonl'
    if not path.exists():
        return receipts, requests
    for line in path.read_text().splitlines():
        event = json.loads(line); m = event['message']
        if event.get('own_probe'):
            continue
        if event['direction'] == 'to_native' and m.get('method') == 'tools/call':
            name, args = normalize(m['params']['name'], m['params'].get('arguments'))
            pending[m['id']] = (name, args); requests.append((name, args))
        elif event['direction'] == 'from_native' and m.get('id') in pending:
            name, args = pending.pop(m['id']); result = m.get('result', {})
            payload = result.get('structuredContent')
            if isinstance(payload, dict):
                key = canonical(payload)
                item = {'name': name, 'arguments': args, 'result': payload, 'origin': str(path), 'native_response_time': event.get('time')}
                receipts.setdefault(key, []).append(item)
            else:
                raise ValueError('unsupported_native_payload_without_structured_content:' + str(path))
    return receipts, requests


def coverage_status(result):
    meta = result.get('_meta', {})
    if meta.get('structure_stale') or meta.get('stale_warning') or result.get('status') in ['index_stale', 'source_hash_unavailable_or_changed']:
        return 'stale'
    if result.get('error_code') or result.get('error') or result.get('has_more') or result.get('truncated'):
        return 'partial'
    if any(obj.get('status') in ['packet_source_limit', 'prefetch_source_limit', 'source_file_limit', 'source_unavailable', 'inputs_changed'] for obj in objects(result)):
        return 'partial'
    return 'available'


def request_objects(cycle):
    request = cycle['request']
    # Exclude tool definitions and assistant reasoning/code: these are not delivered results.
    messages = request.get('messages', request.get('input', []))
    for message in messages:
        if message.get('role') == 'assistant' or message.get('type') in ['custom_tool_call', 'function_call']:
            continue
        value = message.get('content', message.get('output', ''))
        hook = message.get('role') == 'developer' or 'UserPromptSubmit hook additional context:' in str(value)
        for obj in objects(value):
            yield obj, hook


def deliveries(cycle, receipts):
    delivered = []; seen = set()
    for obj, hook in request_objects(cycle):
        key = canonical(obj)
        if key in receipts and key not in seen:
            eligible = [r for r in receipts[key] if r['native_response_time'] is not None and r['native_response_time'] <= cycle['time']]
            if eligible:
                item = dict(eligible[-1])
                if len({canonical([r['name'], r['arguments']]) for r in eligible}) > 1:
                    item['name'] = None; item['arguments'] = None
                delivered.append(item); seen.add(key)
        if hook and isinstance(obj.get('facts'), list) and obj.get('index_id') and obj.get('status'):
            for fact in obj['facts']:
                if fact.get('tool') and isinstance(fact.get('arguments'), dict):
                    name, args = normalize(fact['tool'], fact['arguments']); payload = fact.get('evidence', {})
                elif fact.get('kind') == 'configuration' and fact.get('evidence', {}).get('key'):
                    payload = fact['evidence']; name = 'find_configuration_references'
                    args = {'key': payload['key'], 'view': payload.get('view', 'index')}
                else:
                    name, args, payload = None, None, fact
                item = {'name': name, 'arguments': args, 'result': payload, 'origin': 'native_hook_context',
                        'index_id': obj['index_id'], 'packet_status': obj['status']}
                identity = canonical(item)
                if identity not in seen:
                    delivered.append(item); seen.add(identity)
    return delivered


def relation(previous, name, args):
    """Only prove containment for the same query; other semantic overlap is unresolved."""
    if previous['name'] != name:
        return None
    old = dict(previous['arguments'] or {}); new = dict(args or {}); result = previous['result']
    if previous.get('invalidated'):
        return ('unresolved', 'inputs_may_have_changed_since_delivery')
    status = coverage_status(result)
    if status == 'stale' or previous.get('packet_status', 'ready') != 'ready':
        return ('justified_followup', 'previous_evidence_stale_or_partial')
    if status == 'partial':
        if (result.get('error_code') or result.get('error')) and old == new:
            if result.get('error_code') in ['INVALID_ARGUMENT', 'INVALID_ARGUMENTS', 'SYMBOL_NOT_FOUND', 'CLASS_NOT_FOUND', 'INVALID_TARGET', 'TOOL_INVOCATION_ERROR', 'VALIDATION_ERROR', 'UNKNOWN_ARGUMENT']:
                return ('red_flag', 'same_deterministically_failed_query_repeated')
            return ('unresolved', 'repeat_after_error_requires_review')
        if old == new and (result.get('has_more') or result.get('truncated')):
            return ('red_flag', 'same_incomplete_page_repeated')
        return ('justified_followup', 'previous_result_missing_pages_or_failed')
    # Defaults cannot be assumed for arbitrary tools. Only discard pagination when complete.
    pagination = {'limit', 'offset'}
    if name == 'find_configuration_references':
        old.setdefault('view', result.get('view', 'index')); new.setdefault('view', 'index')
        old.setdefault('kind', result.get('kind', 'all')); new.setdefault('kind', 'all')
        for key in ['class_name', 'module', 'project']:
            if old.get(key) is None: old.pop(key, None)
            if new.get(key) is None: new.pop(key, None)
        if old.get('key') == new.get('key') and (new.get('view') == 'effective' or 'profile' in new):
            return ('red_flag', 'unsupported_runtime_requery')
        if old.get('view') == 'index' and new.get('view') == 'declared':
            if any('declared_source' in obj for obj in objects(result)):
                return ('unresolved', 'declared_facts_present_despite_index_label')
            return ('justified_followup', 'source_values_not_in_index_view')
    old_page = {k: v for k, v in old.items() if k not in pagination}
    new_page = {k: v for k, v in new.items() if k not in pagination}
    if name == 'find_configuration_references' and isinstance(old.get('key'), str) and isinstance(new.get('key'), str) and '*' in old['key'] and '*' not in new['key']:
        pattern = re.escape(old['key']).replace(r'\*', '.*')
        if re.fullmatch(pattern, new['key']) and result.get('has_more') is False and {k: v for k, v in old_page.items() if k != 'key'} == {k: v for k, v in new_page.items() if k != 'key'}:
            return ('red_flag', 'narrower_key_already_in_complete_query')
    if old_page == new_page:
        if new.get('offset', 0) != old.get('offset', 0):
            return ('justified_followup', 'different_page')
        if old == new or (result.get('has_more') is False and not result.get('truncated')):
            return ('red_flag', 'covered_query_repeated')
        return ('unresolved', 'pagination_coverage_not_proven')
    for selector in ['key', 'target', 'class_name', 'method', 'path']:
        if selector in old and selector in new and old[selector] != new[selector]:
            if new[selector] in selectors(result) or (selector in ['target', 'class_name', 'path'] and isinstance(old[selector], str) and isinstance(new[selector], str) and re.split(r'[./]', old[selector])[-1] == re.split(r'[./]', new[selector])[-1]):
                return ('unresolved', 'selector_alias_or_fact_overlap_requires_review')
            return ('justified_followup', 'different_explicit_selector')
    if old.get('include_members') is False and new.get('include_members') is True:
        return ('justified_followup', 'members_not_previously_requested')
    return ('unresolved', 'different_arguments_do_not_prove_missing_facts')


def paths_in(value):
    found = set()
    for obj in objects(value):
        for key in ['source_file', 'file', 'path', 'source']:
            text = obj.get(key)
            if isinstance(text, str) and re.search(r'\.(?:java|kt|properties|ya?ml|xml)$', text):
                found.add(text)
    return found


def source_read(name, args, delivered):
    if not isinstance(args, dict):
        return None
    path = args.get('file_path', args.get('path')) if name in ['Read', 'read_file'] else None
    command = args.get('cmd', args.get('command', '')) if name in ['exec_command', 'Bash', 'Grep', 'Glob'] else ''
    if not path and not command:
        return None
    if not delivered:
        return ('justified_followup', 'no_quill_source_facts_in_context', None)
    references = [(p, d) for d in delivered for p in paths_in(d['result'])
                  if (path and (str(path) == p or str(path).endswith('/' + p))) or (command and (p in command or Path(p).name in command))]
    if not references:
        return ('unresolved', 'source_read_coverage_not_proven', None)
    # A citation or scalar declaration is never proof that a whole file/body was supplied.
    for p, d in references:
        if d.get('invalidated') or coverage_status(d['result']) != 'available' or d.get('packet_status', 'ready') != 'ready':
            continue
        for obj in objects(d['result']):
            if path and name == 'Read' and obj.get('source_complete') is True and isinstance(obj.get('source'), str) and '\n' in obj['source']:
                start, end = obj.get('source_line'), obj.get('source_end_line')
                offset, limit = args.get('offset'), args.get('limit')
                if type(start) is int and type(end) is int and type(offset) is int and type(limit) is int and start <= offset and offset + limit - 1 <= end and obj.get('source_file') == p:
                    return ('red_flag', 'already_supplied_source_range', d)
    return ('unresolved', 'source_overlap_requires_intent_and_range_review', references[0][1])


def selectors(value):
    return {v for obj in objects(value) for k, v in obj.items()
            if k in ['key', 'target', 'className', 'class_name', 'declaring_class', 'method'] and isinstance(v, str)}


def result_envelopes(frame):
    for message in frame['request'].get('messages', frame['request'].get('input', [])):
        if message.get('type') in ['custom_tool_call_output', 'function_call_output']:
            yield message.get('call_id'), message.get('output')
        for block in message.get('content', []) if isinstance(message.get('content'), list) else []:
            if block.get('type') == 'tool_result':
                yield block.get('tool_use_id'), block


def evidence_ref(item):
    if not item:
        return None
    return {**{k: v for k, v in item.items() if k != 'result'},
            'result_sha256': hashlib.sha256(canonical(item['result']).encode()).hexdigest(),
            'result_coverage': coverage_status(item['result'])}


def usage(cycle):
    u = cycle['response'].get('usage', {})
    if cycle['client'] == 'codex':
        inp, cached, out = u.get('input_tokens'), u.get('input_tokens_details', {}).get('cached_tokens'), u.get('output_tokens')
    else:
        fresh, created, cached, out = [u.get(k) for k in ['input_tokens', 'cache_creation_input_tokens', 'cache_read_input_tokens', 'output_tokens']]
        inp = fresh + created + cached if all(type(x) is int for x in [fresh, created, cached]) else None
    if any(type(x) is not int or x < 0 for x in [inp, cached, out]) or cached > inp:
        return None
    return {'input': inp, 'cached_input': cached, 'uncached_input': inp - cached, 'output': out, 'all': inp + out}


def audit(directory):
    directory = Path(directory); issues = []; frames = cycles(directory, issues)
    if len({c['client'] for c in frames}) > 1:
        issues.append('mixed_client_session')
    receipts, native_requests = native_receipts(directory); delivered = []; findings = []; seen_delivery = set(); attempted_native = []
    states = {}; first_seen = {}; observed_payloads = set(); last_mutation = -1; costs = []; ledger = []
    for index, cycle in enumerate(frames):
        request = cycle['request']; parent = request.get('previous_response_id')
        if cycle['client'] == 'codex' and parent:
            if parent not in states:
                issues.append('missing_codex_history_parent:' + str(parent)); delivered = []; seen_delivery = set()
            else:
                previous, identities = states[parent]
                delivered = [dict(d) for d in previous]; seen_delivery = set(identities)
        else:
            # Full histories (/clear/compaction included) replace, not append to, context.
            delivered = []; seen_delivery = set()
        costs.append(usage(cycle))
        for item in deliveries(cycle, receipts):
            if item['origin'] != 'native_hook_context':
                observed_payloads.add(canonical(item['result']))
            identity = canonical(item)
            if identity not in seen_delivery:
                index_id = item.get('index_id') or item['result'].get('_meta', {}).get('index_id')
                for previous in delivered:
                    old_index = previous.get('index_id') or previous['result'].get('_meta', {}).get('index_id')
                    if index_id and old_index and index_id != old_index and (previous.get('arguments') or {}).get('project') == (item.get('arguments') or {}).get('project'):
                        previous['invalidated'] = True
                first_seen.setdefault(identity, cycle['time'])
                observed = item.get('native_response_time', first_seen[identity])
                delivered.append({**item, 'delivered_at_cycle': index, 'delivery_request': cycle['source'], 'invalidated': last_mutation > observed}); seen_delivery.add(identity)
        ledger.append({'cycle': index, 'source': cycle['source'], 'client': cycle['client'], 'prewarm': request.get('generate') is False,
                       'observed_usage': costs[-1], 'provider_seconds': cycle['seconds']})
        for call_index, call in enumerate(response_calls(cycle)):
            name, args = normalize(call['name'], call['arguments']); result = None; prior = None
            if call['name'].startswith(('mcp__quill__', 'quill_')) or name in {q['name'] for q in delivered}:
                attempted_native.append((name, args))
                if args is None:
                    result = ('unresolved', 'dynamic_or_malformed_arguments')
                else:
                    candidates = [(relation(d, name, args), d) for d in reversed(delivered) if d['name'] == name]
                    candidates = [(r, d) for r, d in candidates if r]
                    if candidates:
                        # Prefer an actual covered match to a different-scope receipt.
                        result, prior = min(candidates, key=lambda x: {'red_flag': 0, 'unresolved': 1, 'justified_followup': 2}[x[0][0]])
                    else:
                        anchors = set().union(*(selectors([d['result'], d['arguments']]) for d in delivered)) if delivered else set()
                        prior_native = [r for values in receipts.values() for r in values if r['name'] == name and r['arguments'] == args and r['native_response_time'] is not None and r['native_response_time'] <= cycle['time']]
                        if prior_native:
                            result = ('unresolved', 'native_answer_not_proven_delivered')
                        elif delivered and (not selectors(args) or selectors(args) & anchors):
                            result = ('unresolved', 'cross_tool_or_ambiguous_coverage')
                        elif name == 'search_tools':
                            result = ('discovery', 'native_tool_discovery')
                        else:
                            result = ('justified_followup', 'no_matching_delivered_query')
            elif name in ['ToolSearch', 'tool_catalog_inspection'] or name.startswith('tool_search_tool_'):
                result = ('discovery', 'tool_catalog_inspection_not_assumed_redundant')
            elif name == 'dynamic_dispatch' or args is None:
                result = ('unresolved', 'unsupported_dynamic_dispatch')
            else:
                read = source_read(name, args, delivered)
                if read:
                    result, prior = read[:2], read[2]
            if result:
                findings.append({'cycle': index, 'call_index': call_index, 'call_id': call.get('id'), 'tool': name, 'arguments': args,
                                 'quill_call': call['name'].startswith(('mcp__quill__', 'quill_')) or name in {q['name'] for q in delivered},
                                 'classification': result[0], 'reason': result[1], 'attempt_request': cycle['source'],
                                 'previous_delivery': evidence_ref(prior), 'observed_cycle_usage': costs[-1], 'observed_provider_seconds': cycle['seconds'],
                                 'attributed_waste_tokens': None, 'model_intent': 'unknown',
                                 'outcome': 'not_inferred_from_absence_of_native_forwarding'})
            if name in ['apply_patch', 'Edit', 'Write', 'MultiEdit'] or (name in ['exec_command', 'Bash'] and
                    re.search(r'\b(?:python\d*|node|mvnw?|gradlew?|rm|mv|cp|touch|tee|apply_patch)\b|(?<![2])>|\bsed\s+-i|\bgit\s+(?:checkout|reset|apply)', str(args))):
                for item in delivered:
                    item['invalidated'] = True
                last_mutation = cycle['time'] + max(cycle['seconds'], 0.000001)
        states[cycle['response'].get('id')] = ([dict(d) for d in delivered], set(seen_delivery))
    # An explicit tool error in the next model input is stronger evidence than an
    # absent native RPC. Keep batch ambiguity visible rather than inventing success.
    errors = {}
    for frame_index, frame in enumerate(frames):
        for call_id, envelope in result_envelopes(frame):
            for obj in objects(envelope):
                if obj.get('error_code') == 'TOOL_INVOCATION_ERROR' and re.search(r'Unknown argument|Invalid|validation|required', obj.get('message', ''), re.I):
                    errors.setdefault(call_id, {'kind': 'client_schema_rejected_envelope', 'evidence': frame['source'], 'delivered_cycle': frame_index, 'message': obj['message'][:300]})
                elif obj.get('type') == 'tool_result' and obj.get('is_error'):
                    errors.setdefault(call_id, {'kind': 'tool_error_envelope', 'evidence': frame['source']})
    for finding in findings:
        if finding['call_id'] in errors:
            finding['outcome'] = errors[finding['call_id']]['kind'] if finding['quill_call'] else 'batch_contains_error_unattributed'; finding['error_evidence'] = errors[finding['call_id']]
        finding['id'] = hashlib.sha256(canonical([str(directory), finding['cycle'], finding['call_index'], finding['tool'], finding['arguments']]).encode()).hexdigest()[:20]
        if finding['quill_call'] and finding['arguments'] is not None:
            for prior in findings:
                error = errors.get(prior.get('call_id'), {})
                if prior['cycle'] < finding['cycle'] and error.get('kind') == 'client_schema_rejected_envelope' and error['delivered_cycle'] <= finding['cycle'] and prior['tool'] == finding['tool'] and prior['arguments'] == finding['arguments']:
                    finding['classification'] = 'red_flag'; finding['reason'] = 'same_client_rejected_arguments_repeated'; finding['previous_error'] = error
                    break
    # Unknown parser/transport coverage must never become a clean bill of health.
    unmatched = Counter(canonical(x) for x in native_requests) - Counter(canonical(x) for x in attempted_native)
    if unmatched:
        issues.append('native_calls_not_resolved_in_model_output:' + str(sum(unmatched.values())))
    for payload, origins in receipts.items():
        if payload not in observed_payloads:
            issues.append('native_answer_delivery_unverified:' + hashlib.sha256(payload.encode()).hexdigest()[:16])
    if not frames:
        issues.append('no_supported_model_wire_capture')
        # Preserve attempts from CLI logs, but never promote them to delivery proof.
        fallback = []
        rollout = directory / 'rollout.jsonl'
        events = directory / 'events.jsonl'
        if rollout.exists():
            for number, line in enumerate(rollout.read_text().splitlines(), 1):
                payload = json.loads(line).get('payload', {})
                calls = response_calls({'response': {'output': [payload]}})
                fallback.extend((c, f'{rollout}:{number}') for c in calls)
        elif events.exists():
            for number, line in enumerate(events.read_text().splitlines(), 1):
                event = json.loads(line)
                if event.get('type') == 'assistant':
                    calls = response_calls({'response': event.get('message', {})})
                    fallback.extend((c, f'{events}:{number}') for c in calls)
        for call, source in fallback:
            name, args = normalize(call['name'], call['arguments'])
            findings.append({'id': hashlib.sha256(canonical([source, name, args]).encode()).hexdigest()[:20],
                             'tool': name, 'arguments': args, 'classification': 'unresolved', 'reason': 'model_delivery_not_proven',
                             'attempt_request': source, 'observed_cycle_usage': None, 'attributed_waste_tokens': None,
                             'model_intent': 'unknown'})
    if any(x is None for x in costs):
        issues.append('unknown_provider_usage')
    counts = dict(Counter(x['classification'] for x in findings))
    clients = sorted({c['client'] for c in frames}) or (['codex'] if (directory / 'rollout.jsonl').exists() else ['claude'] if (directory / 'events.jsonl').exists() else [])
    return {'session': str(directory), 'clients': clients, 'coverage_complete': not issues,
            'issues': issues, 'counts': counts, 'outcomes': dict(Counter(x.get('outcome', 'unknown') for x in findings)), 'review_required': bool(issues or counts.get('red_flag') or counts.get('unresolved')),
            'deliveries': [evidence_ref(d) for d in delivered], 'findings': findings, 'cycles': ledger,
            'full_capture_usage_including_prewarm': {k: sum(u[k] for u in costs) for k in ['input', 'cached_input', 'uncached_input', 'output', 'all']} if frames and all(costs) and not issues else None,
            'limits': ['Semantic cross-tool equivalence and user intent are not inferred. Different arguments require scope review.',
                       'Shell/source overlap is unresolved unless an exact Read range was already supplied. Citations are not whole-source coverage.',
                       'Literal JS calls only; dynamic/computed dispatch and unmatched native calls block a clean result.',
                       'Cycle usage is observed cost, not counterfactual waste. No provider calls or captured-code execution.']}


def apply_reviews(reports, reviews):
    """Explicit human annotations do not change saved logs or automatic findings."""
    if not isinstance(reviews, dict) or not all(isinstance(k, str) for k in reviews):
        raise ValueError('reviews must map string finding IDs to review objects')
    remaining = dict(reviews)
    for report in reports:
        for finding in report.get('findings', []):
            review = remaining.pop(finding['id'], None)
            if review is None:
                continue
            if not isinstance(review, dict) or review.get('classification') not in ['red_flag', 'justified_followup', 'unresolved'] or not isinstance(review.get('reason'), str) or not review['reason'].strip() or not isinstance(review.get('evidence'), str) or not review['evidence'].strip():
                raise ValueError('review requires classification, reason and evidence: ' + finding['id'])
            finding['automatic_classification'] = finding['classification']; finding['automatic_reason'] = finding['reason']
            finding['review'] = review; finding['classification'] = review['classification']; finding['reason'] = 'human_review:' + review['reason']
        report['counts'] = dict(Counter(f['classification'] for f in report.get('findings', [])))
        report['review_required'] = bool(report['issues'] or report['counts'].get('red_flag') or report['counts'].get('unresolved'))
    if remaining:
        raise ValueError('unknown review finding IDs: ' + ','.join(remaining))


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('sessions', nargs='+', type=Path); parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--reviews', type=Path, help='Optional explicit human classifications keyed by finding ID; reason/evidence required')
    args = parser.parse_args()
    if any(args.output.resolve().is_relative_to(p.resolve()) for p in args.sessions):
        parser.error('output must be outside saved session directories')
    existing = args.output / 'summary.json'
    if existing.exists() and load(existing).get('audit_kind') != 'quill_response_reuse':
        parser.error('refusing to overwrite a non-audit summary')
    args.output.mkdir(parents=True, exist_ok=True)
    reports = []
    for directory in args.sessions:
        try:
            reports.append(audit(directory))
        except (OSError, ValueError, KeyError, TypeError, IndexError, RecursionError) as error:
            reports.append({'session': str(directory), 'coverage_complete': False, 'review_required': True, 'issues': [str(error)]})
    if args.reviews:
        apply_reviews(reports, load(args.reviews))
    result = {'schema_version': 1, 'audit_kind': 'quill_response_reuse', 'offline_only': True, 'sessions': reports, 'review_required': any(r['review_required'] for r in reports)}
    (args.output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    rows = ''.join('<tr>' + ''.join('<td>' + html.escape(str(v)) + '</td>' for v in [r.get('clients'), r['session'], r.get('counts', {}).get('red_flag', 0), r.get('counts', {}).get('unresolved', 0), r.get('counts', {}).get('justified_followup', 0), r.get('counts', {}).get('discovery', 0), r['coverage_complete'], r['review_required']]) + '</tr>' for r in reports)
    (args.output / 'report.html').write_text('<!doctype html><meta charset="utf-8"><title>Quill response reuse audit</title><style>body{font:15px system-ui;max-width:1300px;margin:30px auto}pre{white-space:pre-wrap}table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:8px;overflow-wrap:anywhere}</style><h1>Offline Quill response reuse audit</h1><p>Review required is not proof of model misunderstanding. Unknown coverage blocks automatic clearance; no paid calls.</p><table><tr><th>Client</th><th>Saved session</th><th>Red flags</th><th>Unresolved</th><th>Justified</th><th>Discovery</th><th>Capture parsed</th><th>Review required</th></tr>' + rows + '</table><pre>' + html.escape(json.dumps(result, ensure_ascii=False, indent=2)) + '</pre>')
    print(json.dumps([{'session': r['session'], 'counts': r.get('counts'), 'coverage_complete': r['coverage_complete'], 'review_required': r['review_required']} for r in reports]))


if __name__ == '__main__':
    main()
