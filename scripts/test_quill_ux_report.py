import json
import tempfile
import unittest
from pathlib import Path

from quill_ux_report import compare, load_events, summarize


class QuillUxReportTest(unittest.TestCase):

    def test_summarizes_content_free_events_by_tool(self):
        events = [
            {"tool": "get_overview", "session_id": "one", "duration_ms": 10,
             "response_bytes": 100, "status": "ok"},
            {"tool": "get_overview", "session_id": "one", "duration_ms": 30,
             "response_bytes": 300, "status": "error", "stale_warning": True},
            {"tool": "find_usages", "session_id": "two", "duration_ms": 20,
             "response_bytes": 200, "status": "ok", "has_more": True},
        ]

        report = summarize(events)

        self.assertEqual(3, report["calls"])
        self.assertEqual(2, report["sessions"])
        self.assertEqual(20, report["duration_ms"]["median"])
        self.assertEqual(1, report["statuses"]["error"])
        self.assertEqual(1, report["stale_responses"])
        self.assertEqual(1, report["truncated_responses"])
        self.assertEqual("get_overview", report["tools"][0]["tool"])

    def test_splits_calls_by_client_model_and_profile(self):
        events = [
            {"tool": "get_overview", "session_id": "a", "response_bytes": 100, "status": "ok",
             "client": "codex-mcp-client/0.162.1", "model": "gpt-6.1-sol", "tool_profile": "router"},
            {"tool": "execute_tool", "session_id": "a", "response_bytes": 50, "status": "error",
             "client": "codex-mcp-client/0.162.1", "model": "gpt-6.1-sol", "tool_profile": "router"},
            {"tool": "get_overview", "session_id": "b", "response_bytes": 400, "status": "ok",
             "client": "claude-code/2.1.311", "tool_profile": "full"},
            {"tool": "get_overview", "response_bytes": 10, "status": "ok"},
        ]

        clients = summarize(events)["clients"]

        self.assertEqual(("codex-mcp-client/0.162.1", "gpt-6.1-sol", "router", 2, 1, 150),
                         tuple(clients[0][key] for key in
                               ("client", "model", "tool_profile", "calls", "errors", "total_response_bytes")))
        claude = next(row for row in clients if row["client"].startswith("claude-code"))
        self.assertEqual("unknown", claude["model"])
        self.assertTrue(any(row["client"] == "unknown" for row in clients))

    def test_loads_json_lines_and_compares_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"
            path.write_text(json.dumps({"tool": "one", "duration_ms": 5,
                                        "response_bytes": 10, "status": "ok"}) + "\n")
            current = summarize(load_events(path))
        delta = compare(current, current)
        self.assertTrue(all(value == 0 for value in delta.values()))


if __name__ == "__main__":
    unittest.main()
