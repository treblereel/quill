import json
import unittest
import subprocess
import sys
import time
import tempfile
from unittest.mock import patch
from pathlib import Path
from quill_cold_start_probe import client_errors, command, failure_category, overview_arguments, summarize, stream_process, timing_summary, observation_receipt, observed_command


class ColdStartProbeTest(unittest.TestCase):
    def test_observer_wraps_effective_launcher_without_changing_model_or_environment(self):
        config = dict(enabled=True, transport=dict(type='stdio', command='/private/native',
                      args=['--mcp', '--project', '/private/project'], env={'SECRET': 'PRIVATE'}))
        with patch('quill_cold_start_probe.subprocess.check_output', return_value=json.dumps(config)) as lookup:
            argv = observed_command(Path('/private/project'), 'plain task', True, Path('/tmp/trace'))
        self.assertIn('mcp_servers.quill.command=' + json.dumps(sys.executable), argv)
        self.assertTrue(any('/private/native' in arg and '--mcp' in arg for arg in argv))
        self.assertEqual('plain task', argv[-1])
        self.assertNotIn('PRIVATE', json.dumps(argv))
        self.assertFalse(any('mcp_servers.quill.env' in arg or 'model=' in arg for arg in argv))
        self.assertEqual(['mcp', 'get', 'quill', '--json'], lookup.call_args.args[0][-4:])

    def test_streamed_timings_without_commands_or_contents(self):
        events = [dict(type='item.started', item=dict(id='1', type='command_execution', command='PRIVATE')),
                  dict(type='item.completed', item=dict(id='1', type='command_execution', aggregated_output='PRIVATE'))]
        code = 'import sys,time; print(' + repr(json.dumps(events[0])) + ',flush=True); time.sleep(.05); print(' + repr(json.dumps(events[1])) + ',flush=True)'
        with subprocess.Popen([sys.executable, '-c', code], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              text=True, start_new_session=True) as process:
            _, _, timeout, timeline = stream_process(process, 5, time.monotonic())
        self.assertFalse(timeout)
        self.assertEqual('shell', timeline[0]['tool'])
        self.assertGreater(timeline[0]['event_span_ms'], 20)
        self.assertNotIn('PRIVATE', json.dumps(timeline))

    def test_stream_timeout_reaps_process(self):
        with subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(10)'],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                              start_new_session=True) as process:
            _, _, timed_out, _ = stream_process(process, .1, time.monotonic())
        self.assertTrue(timed_out)
        self.assertIsNotNone(process.returncode)

    def test_closed_streams_do_not_disable_process_deadline(self):
        with subprocess.Popen([sys.executable, '-c',
                              'import os,time; os.close(1); os.close(2); time.sleep(10)'],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                              start_new_session=True) as process:
            _, _, timed_out, _ = stream_process(process, .1, time.monotonic())
        self.assertTrue(timed_out)
        self.assertIsNotNone(process.returncode)

    def test_parallel_timings_are_union_not_double_counted(self):
        timeline = [dict(tool='quill:get_overview', started_ms=10, completed_ms=30),
                    dict(tool='shell', started_ms=20, completed_ms=40)]
        summary = timing_summary(timeline, 100)
        self.assertEqual(30, summary['observed_tool_span_union_ms'])
        self.assertEqual(70, summary['outside_observed_tool_spans_ms'])

    def test_no_trace_is_unknown_not_proof_of_unavailable_model_tools(self):
        with tempfile.TemporaryDirectory() as temporary:
            evidence = observation_receipt(Path(temporary) / 'absent.jsonl', 0)
        self.assertFalse(evidence['catalog_delivered_to_client'])
        self.assertEqual('unknown', evidence['model_visibility'])

    def test_semantic_call_before_overview_is_not_overview_first(self):
        stream = [dict(type='item.completed', item=dict(type='mcp_tool_call', server='quill',
                  tool=tool, status='completed', result={'content': []}))
                  for tool in ('search_classes', 'get_overview')]
        result = summarize('codex', '\n'.join(map(json.dumps, stream)))
        self.assertTrue(result['overview_successful'])
        self.assertFalse(result['overview_first_quill_call'])
        self.assertTrue(summarize('codex', json.dumps(stream[1]))['overview_first_quill_call'])

    def test_client_errors_are_sanitized_and_source_text_is_not_an_error(self):
        stream = [dict(type='error', message='stream disconnected Bearer private-token /Users/example/file'),
                  dict(type='item.completed', item={'text': 'authentication source code'})]
        errors = client_errors('\n'.join(map(json.dumps, stream)))
        self.assertEqual(['stream disconnected <redacted> <path>'], errors)
        self.assertEqual('stream_disconnected', failure_category(1, False, errors[0]))

    def test_usage_failure_is_distinct_from_quill_adoption_failure(self):
        self.assertEqual('rate_or_usage_limit', failure_category(1, False, 'You have hit your usage limit'))
        self.assertEqual('model_capacity', failure_category(1, False,
                         'Selected model is at capacity; stream disconnected'))
        self.assertIsNone(failure_category(0, False, 'noise'))
        self.assertEqual('timeout', failure_category(-15, True, ''))

    def test_overview_arguments_do_not_save_paths_or_unknown_input(self):
        self.assertEqual({'view': 'full', 'project': '<selector>'}, overview_arguments(
            {'view': 'full', 'project': '/private/user/project', 'unknown': 'sensitive'}))
        self.assertEqual({'project': 'repository'}, overview_arguments({'project': 'repository'}))

    def test_completion_event_without_answer_is_not_a_final_answer(self):
        self.assertFalse(summarize('codex', json.dumps({'type': 'turn.completed'}))['final_answer'])
        self.assertFalse(summarize('claude', json.dumps({'type': 'result'}))['final_answer'])

    def test_commands_do_not_inject_instructions_servers_models_or_permissions(self):
        for client in ("codex", "claude"):
            argv = command(client, Path('/tmp/project.with spaces'), 'plain task', True)
            for flag in ('--allowedTools', '--permission-mode', '--mcp-config', '--bare',
                         '--append-system-prompt', '--system-prompt', '--model', '--yolo'):
                self.assertNotIn(flag, argv)
            if client == 'codex':
                self.assertIn('projects={"/tmp/project.with spaces"={trust_level="trusted"}}', argv)

    def test_denied_claude_call_does_not_count_as_success(self):
        stream = [dict(type='assistant', message={'content': [dict(type='tool_use', id='1',
                  name='mcp__quill__get_overview', input={})]}),
                  dict(type='user', message={'content': [dict(type='tool_result', tool_use_id='1')]}),
                  dict(type='result', permission_denials=[{'tool_use_id': '1'}])]
        result = summarize('claude', '\n'.join(map(json.dumps, stream)))
        self.assertTrue(result['overview_attempted'])
        self.assertFalse(result['overview_successful'])
        self.assertEqual(1, result['permission_denials'])
        self.assertEqual(1, result['quill_permission_denials'])

    def test_non_quill_permissions_are_reported_separately(self):
        stream = [dict(type='assistant', message={'content': [dict(type='tool_use', id='1', name='Bash')]}),
                  dict(type='result', permission_denials=[{'tool_use_id': '1'}])]
        result = summarize('claude', '\n'.join(map(json.dumps, stream)))
        self.assertEqual(1, result['permission_denials'])
        self.assertEqual(0, result['quill_permission_denials'])

    def test_success_requires_result_not_catalog_or_mention(self):
        stream = [dict(type='system', tools=['mcp__quill__get_overview']),
                  dict(type='assistant', message={'content': [dict(type='text', text='Quill works')]}),
                  dict(type='result')]
        self.assertFalse(summarize('claude', '\n'.join(map(json.dumps, stream)))['overview_attempted'])
        stream.append(dict(type='assistant', message={'content': [dict(type='tool_use', id='1',
                      name='mcp__quill__get_overview', input={})]}))
        self.assertFalse(summarize('claude', '\n'.join(map(json.dumps, stream)))['overview_successful'])
        stream.append(dict(type='user', message={'content': [dict(type='tool_result', tool_use_id='1')]}))
        self.assertTrue(summarize('claude', '\n'.join(map(json.dumps, stream)))['overview_successful'])

    def test_codex_tool_errors_and_source_fallback_are_visible(self):
        stream = [dict(type='item.completed', item=dict(type='command_execution', exit_code=0)),
                  dict(type='item.completed', item=dict(type='mcp_tool_call', server='quill',
                       tool='get_overview', status='completed', arguments={}, result={'isError': True})),
                  dict(type='turn.completed')]
        result = summarize('codex', '\n'.join(map(json.dumps, stream)))
        self.assertFalse(result['overview_successful'])
        self.assertTrue(result['source_reads_before_overview'])
        stream[1]['item']['result'] = {'content': [], 'structuredContent': {'project': {}}}
        self.assertTrue(summarize('codex', '\n'.join(map(json.dumps, stream)))['overview_successful'])

    def test_parser_ignores_noise_without_saving_source_or_final_answer(self):
        result = summarize('codex', 'secret noise\n[]\n' + json.dumps(dict(type='item.completed',
                 item=dict(type='agent_message', text='private contents'))) )
        self.assertNotIn('private', json.dumps(result))
        self.assertNotIn('secret', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
