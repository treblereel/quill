import unittest

from quill_guidance_adoption import evaluate_capture


class GuidanceAdoptionTest(unittest.TestCase):
    def test_prefixed_tool_trace_attests_adoption_but_not_output_types(self):
        result = {"observed": {"verified": "false"}, "tool_trace": [
            {"tool": "quill_get_overview"}, {"tool": "quill_change_session"},
        ]}
        evaluate_capture(result, {"verified": False})
        self.assertTrue(result["overview_first"])
        self.assertTrue(result["change_session_used"])
        self.assertFalse(result["correct"])

    def test_source_first_and_integer_boolean_are_not_counted_as_success(self):
        result = {"observed": {"verified": 0}, "tool_trace": [
            {"tool": "read_file"}, {"tool": "quill_change_session"},
        ]}
        evaluate_capture(result, {"verified": False})
        self.assertFalse(result["overview_first"])
        self.assertFalse(result["correct"])


if __name__ == "__main__":
    unittest.main()
