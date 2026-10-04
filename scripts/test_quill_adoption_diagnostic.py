import unittest

from quill_adoption_diagnostic import PROMPT, metadata_contract


class AdoptionDiagnosticTest(unittest.TestCase):
    def test_prompt_does_not_remind_agent_to_use_quill_or_tool_names(self):
        for name in ("quill", "get_overview", "change_session", "installed instructions"):
            self.assertNotIn(name, PROMPT.lower())

    def test_native_wire_contract_rejects_missing_and_empty_annotations(self):
        initialized = {"instructions": "Begin with get_overview, then change_session"}
        for annotations in (None, {}, {"readOnlyHint": "true"}):
            contract = metadata_contract(initialized, {"tools": [{"annotations": annotations}]})
            self.assertTrue(contract["server_instructions"])
            self.assertFalse(contract["query_safety_hints"])
        hints = {"readOnlyHint": True, "destructiveHint": False, "openWorldHint": False}
        self.assertTrue(all(metadata_contract(initialized, {"tools": [{"annotations": hints}]}).values()))
        self.assertFalse(metadata_contract({}, {"tools": []})["server_instructions"])
        self.assertFalse(metadata_contract(initialized, {"tools": []})["query_safety_hints"])


if __name__ == "__main__":
    unittest.main()
