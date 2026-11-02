import json
from pathlib import Path
import tempfile
import unittest

import quill_turn_budget as t


def write_lines(path, events):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(event) for event in events) + "\nnot json\n")


def token_count(input_tokens, cached, output):
    return {"type": "event_msg", "payload": {"type": "token_count", "info": {"last_token_usage": {
        "input_tokens": input_tokens, "cached_input_tokens": cached, "output_tokens": output}}}}


def script(text):
    return {"type": "response_item", "payload": {"type": "custom_tool_call", "input": text}}


def assistant(message_id, usage, *blocks):
    return {"type": "assistant", "message": {"id": message_id, "usage": usage, "content": list(blocks)}}


class TurnBudgetTest(unittest.TestCase):
    def setUp(self):
        self.sessions = Path(tempfile.mkdtemp())

    def test_codex_turns_are_charged_to_what_the_script_did(self):
        write_lines(self.sessions / "a-codex-with_quill" / "rollout.jsonl", [
            {"type": "event_msg", "payload": {"type": "token_count", "info": None}},
            script('text(await tools.mcp__quill__search_tools({query:"x"}))'),
            token_count(100, 80, 5),
            script('await tools.mcp__quill__list_beans({}); '
                   'await tools.exec_command({cmd:"rg -n Foo src"})'),
            token_count(200, 150, 7),
            script('await tools.exec_command({cmd:"sed -n \'1,20p\' A.java"})'),
            token_count(300, 250, 9),
            script('await tools.exec_command({cmd:"./mvnw test-compile"})'),
            token_count(400, 350, 11),
            token_count(500, 450, 13),
        ])
        report = t.summarize(self.sessions)["codex"]
        self.assertEqual(5, report["requests"])
        self.assertEqual(["catalog_search", "quill_query", "source_read", "build", "final_answer"],
                         report["sessions_detail"][0]["turns"])
        self.assertEqual(207, report["by_category"]["quill_query"]["all"])
        self.assertEqual(1545, report["all"])

    def test_claude_streamed_snapshots_count_once_with_the_final_usage(self):
        partial = {"input_tokens": 3, "cache_read_input_tokens": 100,
                   "cache_creation_input_tokens": 20, "output_tokens": 1}
        final = dict(partial, output_tokens=40)
        write_lines(self.sessions / "b-claude-with_quill" / "events.jsonl", [
            assistant("m1", partial, {"type": "thinking"}),
            assistant("m1", final, {"type": "tool_use", "name": "mcp__quill__get_context"}),
            assistant("m1", final, {"type": "tool_use", "name": "Read", "input": {}}),
            assistant("m2", {"input_tokens": 1, "cache_read_input_tokens": 200, "output_tokens": 30},
                      {"type": "tool_use", "name": "Grep", "input": {}}),
            assistant("m3", {"input_tokens": 1, "cache_read_input_tokens": 300, "output_tokens": 50},
                      {"type": "text", "text": "answer"}),
        ])
        report = t.summarize(self.sessions)["claude"]
        self.assertEqual(3, report["requests"])
        self.assertEqual(["quill_query", "source_search", "final_answer"],
                         report["sessions_detail"][0]["turns"])
        self.assertEqual(123 + 40, report["by_category"]["quill_query"]["all"])

    def test_shell_commands_are_classified_by_their_first_matching_kind(self):
        self.assertEqual("build", t.classify_command("JAVA_HOME=/x ./mvnw -q test"))
        self.assertEqual("source_search", t.classify_command("rg -n 'parse' src"))
        self.assertEqual("source_read", t.classify_command("nl -ba A.java | sed -n '1,9p'"))
        self.assertEqual("other_tool", t.classify_command("git status --short"))


if __name__ == "__main__":
    unittest.main()
