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
