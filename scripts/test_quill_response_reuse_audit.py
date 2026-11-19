import json
from pathlib import Path
import tempfile
import unittest
import contextlib
import io
from unittest.mock import patch

import quill_response_reuse_audit as a


KEY = 'warehouse.cache.enabled'
ARGS = {'key': KEY, 'view': 'declared'}
RESULT = {'key': KEY, 'kind': 'all', 'view': 'declared', 'has_more': False,
          'references': [{'file': 'src/main/resources/application.properties', 'line': 4,
                          'declared_source': {'source_complete': True, 'declared_value': 'true'}}]}
PACKET = {'basis': 'literal indexed identifiers', 'index_id': 'fixture-index', 'status': 'ready',
          'facts': [{'tool': 'find_configuration_references', 'arguments': ARGS, 'evidence': RESULT}]}


def frame(client='codex', args=None, packet=PACKET, name='mcp__quill__find_configuration_references', index=0):
    args = ARGS if args is None else args
    content = json.dumps(packet)
    if client == 'codex':
        messages = [{'type': 'message', 'role': 'developer', 'content': [{'type': 'input_text', 'text': content}]}] if packet else []
        request = {'input': messages}
        response = {'id': f'resp{index}', 'output': [{'type': 'custom_tool_call', 'name': 'exec', 'call_id': f'call{index}',
                     'input': 'await tools.' + name + '(' + json.dumps(args) + ');'}],
                    'usage': {'input_tokens': 100, 'input_tokens_details': {'cached_tokens': 40}, 'output_tokens': 10}}
    else:
        messages = [{'role': 'user', 'content': [{'type': 'text', 'text': '<system-reminder>\nUserPromptSubmit hook additional context: ' + content + '\n</system-reminder>'}]}] if packet else []
        request = {'messages': messages}
        response = {'id': f'msg{index}', 'content': [{'type': 'tool_use', 'id': f'tool{index}', 'name': name, 'input': args}],
                    'usage': {'input_tokens': 20, 'cache_creation_input_tokens': 40, 'cache_read_input_tokens': 40, 'output_tokens': 10}}
    return {'client': client, 'request': request, 'response': response, 'source': f'capture{index}.json', 'time': index * 10 + 1, 'seconds': 1}


class ReuseAuditTest(unittest.TestCase):
    def test_json_numeric_roundtrip_is_not_a_delivery_failure(self):
        self.assertEqual(a.canonical({'confidence': 1.0}), a.canonical({'confidence': 1}))
        self.assertNotEqual(a.canonical({'value': False}), a.canonical({'value': 0}))

    def run_frames(self, frames, receipts=None, native=None):
        with tempfile.TemporaryDirectory() as directory, patch.object(a, 'cycles', return_value=frames), patch.object(a, 'native_receipts', return_value=(receipts or {}, native or [])):
            return a.audit(directory)

    def test_same_delivered_query_is_flagged_for_both_clients(self):
        for client in ['codex', 'claude']:
            with self.subTest(client=client):
                r = self.run_frames([frame(client)])
                self.assertEqual(r['counts'], {'red_flag': 1})
                self.assertEqual(r['findings'][0]['reason'], 'covered_query_repeated')
                self.assertIsNone(r['findings'][0]['attributed_waste_tokens'])
                self.assertEqual(r['full_capture_usage_including_prewarm']['all'], 110)

    def test_no_delivery_not_assumed_from_native_response(self):
        f = frame(packet=None)
        receipt = {'name': 'find_configuration_references', 'arguments': ARGS, 'result': RESULT,
                   'origin': 'native.jsonl', 'native_response_time': 0}
        r = self.run_frames([f], {a.canonical(RESULT): [receipt]})
        self.assertEqual(r['findings'][0]['reason'], 'native_answer_not_proven_delivered')
        self.assertTrue(r['review_required'])

    def test_future_native_response_cannot_prove_prior_delivery(self):
        receipt = {'name': 'find_configuration_references', 'arguments': ARGS, 'result': RESULT,
                   'origin': 'native.jsonl', 'native_response_time': 100}
        f = frame(packet=None); f['request']['input'] = [{'type': 'custom_tool_call_output', 'output': json.dumps(RESULT)}]
        self.assertEqual(a.deliveries(f, {a.canonical(RESULT): [receipt]}), [])

    def test_new_key_and_new_declared_values_are_justified(self):
        r = self.run_frames([frame(args={**ARGS, 'key': 'other.enabled'})])
        self.assertEqual(r['findings'][0]['classification'], 'justified_followup')
        packet = json.loads(json.dumps(PACKET)); packet['facts'][0]['arguments']['view'] = 'index'; packet['facts'][0]['evidence']['view'] = 'index'
        packet['facts'][0]['evidence']['references'][0].pop('declared_source')
        r = self.run_frames([frame(packet=packet)])
        self.assertEqual(r['findings'][0]['reason'], 'source_values_not_in_index_view')

    def test_projection_label_alone_is_not_proof_of_missing_facts(self):
        prior = {'name': 'find_configuration_references', 'arguments': {'key': KEY, 'view': 'index'}, 'result': {**RESULT, 'view': 'index'}}
        self.assertEqual(a.relation(prior, prior['name'], ARGS)[0], 'unresolved')

    def test_different_unknown_argument_is_not_automatically_justified(self):
        r = self.run_frames([frame(args={**ARGS, 'invented': True})])
        self.assertEqual(r['findings'][0]['classification'], 'unresolved')

    def test_short_symbol_alias_is_not_new_missing_information(self):
        prior = {'name': 'get_symbol_details', 'arguments': {'target': 'p.Bean'}, 'result': {'target': 'p.Bean', 'has_more': False}}
        self.assertEqual(a.relation(prior, prior['name'], {'target': 'Bean'})[0], 'unresolved')

    def test_runtime_requery_and_client_rejection_count(self):
        first = frame(args={**ARGS, 'view': 'effective', 'profile': 'prod'})
        second = frame(index=1, packet=None); second['response']['output'] = []
        second['request']['input'] = [{'type': 'custom_tool_call_output', 'call_id': 'call0',
            'output': json.dumps({'structuredContent': {'error_code': 'TOOL_INVOCATION_ERROR', 'message': 'Unknown argument: profile'}})}]
        r = self.run_frames([first, second])
        self.assertEqual(r['findings'][0]['reason'], 'unsupported_runtime_requery')
        self.assertEqual(r['findings'][0]['outcome'], 'client_schema_rejected_envelope')

    def test_stale_and_partial_pages(self):
        prior = {'name': 'find_configuration_references', 'arguments': ARGS, 'result': {**RESULT, '_meta': {'structure_stale': True}}}
        self.assertEqual(a.relation(prior, prior['name'], ARGS)[0], 'justified_followup')
        prior['result'] = {**RESULT, 'has_more': True}
        self.assertEqual(a.relation(prior, prior['name'], ARGS)[1], 'same_incomplete_page_repeated')
        self.assertEqual(a.relation(prior, prior['name'], {**ARGS, 'offset': 12})[0], 'justified_followup')

    def test_narrower_key_is_not_new_information_after_complete_glob(self):
        prior = {'name': 'find_configuration_references', 'arguments': {'key': 'warehouse.*', 'view': 'declared'}, 'result': RESULT}
        self.assertEqual(a.relation(prior, prior['name'], ARGS)[1], 'narrower_key_already_in_complete_query')
        prior['result'] = {**RESULT, 'references': [{'declared_source': {'status': 'packet_source_limit'}}]}
        self.assertEqual(a.relation(prior, prior['name'], ARGS)[0], 'justified_followup')

    def test_repeating_already_rejected_arguments_is_a_flag(self):
        args = {**ARGS, 'view': 'effective', 'profile': 'prod'}
        one = frame(args=args)
        two = frame(index=1, args=args, packet=None)
        two['request']['input'] = [{'type': 'custom_tool_call_output', 'call_id': 'call0', 'output': json.dumps({'structuredContent': {'error_code': 'TOOL_INVOCATION_ERROR', 'message': 'Unknown argument: profile'}})}]
        r = self.run_frames([one, two])
        self.assertEqual(r['findings'][1]['reason'], 'same_client_rejected_arguments_repeated')

    def test_explicit_human_verification_review_preserves_automatic_result(self):
        r = self.run_frames([frame()]); finding = r['findings'][0]
        with self.assertRaises(ValueError):a.apply_reviews([r], {finding['id']: {'classification': 'justified_followup'}})
        r = self.run_frames([frame()]); finding = r['findings'][0]
        a.apply_reviews([r], {finding['id']: {'classification': 'justified_followup', 'reason': 'explicit_user_verification', 'evidence': 'user request message 3'}})
        self.assertEqual(finding['automatic_classification'], 'red_flag'); self.assertFalse(r['review_required'])
        with self.assertRaises(ValueError):a.apply_reviews([r], {'unknown-id': {}})

    def test_cli_cannot_overwrite_saved_or_unrelated_reports(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); session = root/'session'; session.mkdir()
            unrelated = root/'unrelated'; unrelated.mkdir(); summary = unrelated/'summary.json'; summary.write_text('{"offline_only":true,"important":"preserve"}')
            before = summary.read_bytes()
            for output in [unrelated, session]:
                with patch('sys.argv', ['audit', str(session), '--output', str(output)]), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as caught:
                    a.main()
                self.assertEqual(caught.exception.code, 2)
            self.assertEqual(summary.read_bytes(), before)

    def test_legacy_function_exec_is_parsed_without_executing_code(self):
        calls = a.response_calls({'response': {'output': [{'type': 'function_call', 'name': 'exec', 'call_id': 'x', 'arguments': 'await tools.mcp__quill__foo({target:"X"});'}]}})
        self.assertEqual(calls[0]['arguments'], {'target': 'X'})

    def test_ws_payload_corruption_blocks_clearance_and_totals(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            d = Path(directory, 'wire/flow'); d.mkdir(parents=True)
            (d/'http.json').write_text(json.dumps({'method': 'GET', 'status': 101, 'completed': True, 'request_unchanged': True}))
            req = {'type': 'response.create', 'input': []}
            resp = {'type': 'response.completed', 'response': {'id': 'r', 'output': [], 'usage': {'input_tokens': 100, 'output_tokens': 1, 'input_tokens_details': {'cached_tokens': 50}}}}
            for i, value in enumerate([req, resp]):
                body = json.dumps(value).encode(); p = d/f'ws-{i:08}.body'; p.write_bytes(body)
                p.with_suffix('.json').write_text(json.dumps({'from_client': i == 0, 'timestamp': i, 'sha256': hashlib.sha256(body).hexdigest() if i else 'corrupted'}))
            r = a.audit(directory); self.assertFalse(r['coverage_complete']); self.assertIsNone(r['full_capture_usage_including_prewarm'])

    def test_citation_is_not_whole_file_coverage(self):
        prior = {'result': RESULT}
        self.assertEqual(a.source_read('Read', {'file_path': '/project/src/main/resources/application.properties', 'offset': 1, 'limit': 30}, [prior])[0], 'unresolved')
        prior['result'] = {'source_file': 'src/A.java', 'source_complete': True, 'source': 'a\nb\nc', 'source_line': 10, 'source_end_line': 12}
        self.assertEqual(a.source_read('Read', {'file_path': '/project/src/A.java', 'offset': 10, 'limit': 3}, [prior])[0], 'red_flag')
        self.assertEqual(a.source_read('Read', {'file_path': '/project/src/A.java', 'offset': 9, 'limit': 4}, [prior])[0], 'unresolved')

    def test_literal_parser_comments_strings_and_dynamic_dispatch(self):
        code = "// tools.mcp__quill__fake({})\ntext('tools.mcp__quill__fake({})'); await tools.mcp__quill__execute_tool({name:'find_configuration_references',arguments:{key:'warehouse.cache.enabled',view:'declared',limit:12}});"
        calls = a.js_calls(code); self.assertEqual(len(calls), 1)
        self.assertEqual(a.normalize(calls[0]['name'], calls[0]['arguments'])[0], 'find_configuration_references')
        self.assertEqual(a.js_calls('const f=tools.mcp__quill__foo; await f(args);')[0]['arguments'], None)
        self.assertEqual(a.js_calls('await tools[name](args)')[0]['name'], 'dynamic_dispatch')
        self.assertEqual(a.js_calls('await tools.mcp__quill__foo(__import__("os"));')[0]['arguments'], None)
        self.assertEqual(a.js_calls('text(ALL_TOOLS.filter(x=>/quill/.test(x.name)));')[0]['name'], 'tool_catalog_inspection')

    def test_reset_context_does_not_retain_old_prefetch(self):
        one = frame(); one['response']['output'] = []
        two = frame(index=1, packet=None)
        r = self.run_frames([one, two])
        self.assertEqual(r['findings'][0]['classification'], 'justified_followup')

    def test_parent_chain_and_missing_history(self):
        one = frame(); one['response']['output'] = []
        two = frame(index=1, packet=None); two['request']['previous_response_id'] = 'resp0'
        r = self.run_frames([one, two]); self.assertEqual(r['counts']['red_flag'], 1)
        two['request']['previous_response_id'] = 'absent'
        r = self.run_frames([two]); self.assertFalse(r['coverage_complete'])

    def test_input_mutation_prevents_false_repeat_flag(self):
        one = frame(name='apply_patch', args={'patch': 'change'})
        two = frame(index=1, packet=None); two['request']['previous_response_id'] = 'resp0'
        r = self.run_frames([one, two]); self.assertEqual(r['findings'][0]['classification'], 'unresolved')
        self.assertEqual(r['findings'][0]['reason'], 'inputs_may_have_changed_since_delivery')

    def test_usage_unknown_never_zero(self):
        f = frame(); del f['response']['usage']['input_tokens_details']
        r = self.run_frames([f]); self.assertIsNone(r['full_capture_usage_including_prewarm'])
        self.assertFalse(r['coverage_complete'])

    def test_no_wire_fallback_does_not_claim_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, 'rollout.jsonl').write_text(json.dumps({'payload': {'type': 'custom_tool_call', 'name': 'exec', 'input': 'await tools.mcp__quill__foo({target:"X"});'}}) + '\n')
            r = a.audit(directory); self.assertFalse(r['coverage_complete'])
            self.assertEqual(r['findings'][0]['reason'], 'model_delivery_not_proven')

    def test_claude_sse_split_json(self):
        events = [{'type': 'message_start', 'message': {'id': 'm', 'usage': {'input_tokens': 1}}},
                  {'type': 'content_block_start', 'index': 0, 'content_block': {'type': 'tool_use', 'id': 't', 'name': 'mcp__quill__foo', 'input': {}}},
                  {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'input_json_delta', 'partial_json': '{"target":'}},
                  {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'input_json_delta', 'partial_json': '"X"}'}},
                  {'type': 'message_stop'}]
        with tempfile.TemporaryDirectory() as d:
            p = Path(d, 'response.raw'); p.write_text('\n\n'.join('data: ' + json.dumps(x) for x in events))
            self.assertEqual(a.sse(p, 'claude')['content'][0]['input'], {'target': 'X'})
            p.write_text(p.read_text().rsplit('\n\n', 1)[0])
            with self.assertRaises(ValueError):a.sse(p, 'claude')

    def test_claude_empty_json_delta_preserves_zero_argument_input(self):
        events = [{'type': 'message_start', 'message': {'id': 'm', 'usage': {'input_tokens': 1}}},
                  {'type': 'content_block_start', 'index': 0, 'content_block': {'type': 'tool_use', 'id': 't', 'name': 'mcp__quill__get_overview', 'input': {}}},
                  {'type': 'content_block_delta', 'index': 0, 'delta': {'type': 'input_json_delta', 'partial_json': ''}},
                  {'type': 'message_stop'}]
        with tempfile.TemporaryDirectory() as d:
            p = Path(d, 'response.raw')
            for fragment, expected in [('', {}), ('  ', {}), ('{"bad":', None)]:
                events[2]['delta']['partial_json'] = fragment
                p.write_text('\n\n'.join('data: ' + json.dumps(x) for x in events))
                self.assertEqual(a.sse(p, 'claude')['content'][0]['input'], expected)

    def test_auxiliary_token_count_is_not_a_generation_with_unknown_usage(self):
        import hashlib
        with tempfile.TemporaryDirectory() as d:
            p = Path(d, 'transport', '001'); p.mkdir(parents=True)
            raw = b'{"model":"fixture","messages":[]}'
            (p/'request.json').write_bytes(raw)
            digest = hashlib.sha256(raw).hexdigest()
            meta = dict(path='/v1/models/count-tokens:rawPredict', status=400, completed=True,
                        incoming_body_sha256=digest, forwarded_body_sha256=digest)
            (p/'transport.json').write_text(json.dumps(meta))
            issues=[]; self.assertEqual(a.cycles(Path(d),issues),[]); self.assertEqual(issues,[])
            (p/'request.json').write_bytes(b'{}')
            issues=[]; a.cycles(Path(d),issues); self.assertTrue(issues)

    def test_claude_server_tool_search_is_discovery_not_a_quill_repeat(self):
        f = frame('claude'); f['response']['content'] = [{'type': 'server_tool_use', 'id': 's', 'name': 'tool_search_tool_regex', 'input': {'pattern': 'quill'}}]
        self.assertEqual(self.run_frames([f])['counts'], {'discovery': 1})

    def test_saved_codex_and_claude_logs(self):
        root = Path(__file__).resolve().parent.parent / 'benchmarks/results'
        # The saved session logs are local benchmark data, not part of the repository.
        if not (root / 'native-prefetch-reuse-wire-v2').is_dir():
            self.skipTest('no local session logs in benchmarks/results')
        old = a.audit(root / 'native-prefetch-reuse-wire-v2/sessions/cq-036-codex-old-wire-r1')
        self.assertEqual(old['counts']['red_flag'], 1)
        candidate = a.audit(root / 'native-prefetch-reuse-wire-v2/sessions/cq-036-codex-candidate-wire-r1')
        self.assertEqual(candidate['counts'].get('red_flag', 0), 0)
        self.assertEqual(candidate['full_capture_usage_including_prewarm']['all'], 86763)
        self.assertTrue(candidate['review_required'])  # Source overlap remains unresolved.
        rejected = a.audit(root / 'native-prefetch-reuse-wire-v1/sessions/cq-036-codex-candidate-wire-r1')
        self.assertTrue(any(f['outcome'] == 'client_schema_rejected_envelope' and f['tool'] == 'find_configuration_references' for f in rejected['findings']))
        claude = a.audit(root / 'native-configuration-packet-v1/sessions/independent-CQ036-claude-candidate-r1')
        self.assertTrue(claude['coverage_complete']); self.assertEqual(claude['clients'], ['claude'])
        self.assertTrue(claude['counts']['justified_followup'])


if __name__ == '__main__':
    unittest.main()
