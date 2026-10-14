import copy
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from quill_real_project_baseline import (complete_change_plan_tests, digest, input_state, prepare_case, run_project, score,
                                         select, source_path, typed_equal, validate_manifest)


class RealProjectBaselineTest(unittest.TestCase):
    def payload(self, **extra):
        return {"_meta": {"stale_warning": False}, **extra}

    def assertion(self, path=None, mode="equals", expected=True):
        return {"path": path or ["value"], "mode": mode, "expected": expected}

    def case(self, root):
        file = root / "Example.kt"
        file.write_text('fun call() = target(1)\n', encoding="utf-8")
        return {"id": "position", "tool": "get_symbol_at_position", "arguments": {},
                "evidence": [{"path": "Example.kt", "sha256": digest(file)}],
                "assertions": [self.assertion()],
                "position": {"path": "Example.kt", "anchor": "call() = target(1)", "token": "target"}}

    def test_typed_equality_rejects_boolean_integer_confusion_recursively(self):
        for actual, expected in [(True, 1), (1, 1.0), ([True], [1]), ({"v": True}, {"v": 1})]:
            with self.subTest(actual=actual):
                self.assertFalse(typed_equal(actual, expected))
        self.assertTrue(typed_equal({"x": [None, "ключ", 1]}, {"x": [None, "ключ", 1]}))

    def test_missing_projection_is_unknown_even_for_negative_assertion(self):
        self.assertEqual("unknown", score(self.payload(), [self.assertion(mode="excludes", expected=["bad"])])["status"])

    def test_empty_collection_is_valid_negative_evidence(self):
        self.assertEqual([], select({"usages": []}, ["usages", "*", "caller", "class"]))
        self.assertEqual("passed", score(self.payload(value=[]), [self.assertion(mode="excludes", expected=["bad"])])["status"])
        self.assertEqual("failed", score(self.payload(value=[]), [self.assertion(mode="contains", expected=["good"])])["status"])

    def test_wildcard_requires_array_and_every_item_field(self):
        for data in ({"a": {}}, {"a": [{"v": 1}, {}]}):
            with self.subTest(data=data), self.assertRaises(ValueError):
                select(data, ["a", "*", "v"])

    def test_missing_equals_field_does_not_match_null(self):
        self.assertEqual("unknown", score(self.payload(), [self.assertion(expected=None)])["status"])
        self.assertEqual("passed", score(self.payload(value=None), [self.assertion(expected=None)])["status"])

    def test_nested_projection_and_positive_and_negative_controls(self):
        payload = self.payload(usages=[{"caller": {"class": "A"}}, {"caller": {"class": "B"}}])
        assertions = [self.assertion(["usages", "*", "caller", "class"], "contains", ["A", "B"]),
                      self.assertion(["usages", "*", "caller", "class"], "excludes", ["C"])]
        self.assertEqual("passed", score(payload, assertions)["status"])
        assertions[1]["expected"] = ["B"]
        self.assertEqual("failed", score(payload, assertions)["status"])

    def test_stale_errors_and_partial_responses_never_pass(self):
        payloads = [self.payload(value=True, error="failed"), self.payload(value=True, error_code="FAILED"),
                    self.payload(value=True, has_more=True), self.payload(value=True, truncated=True),
                    self.payload(value=True, normalization_truncated=True), self.payload(value=True, answer_complete=False),
                    self.payload(value=True, test_index_coverage={"complete": False}), {"value": True}]
        payloads += [{"value": True, "_meta": {flag: True}} for flag in ("stale_warning", "structure_stale", "commit_stale")]
        for payload in payloads:
            with self.subTest(payload=payload):
                self.assertEqual("unknown", score(payload, [self.assertion()])["status"])

    def test_set_checks_use_typed_values_and_not_substrings(self):
        for actual, expected in [([True], [1]), (["foobar"], ["foo"])]:
            self.assertEqual("failed", score(self.payload(value=actual), [self.assertion(mode="contains", expected=expected)])["status"])

    def test_failed_fact_is_retained_when_another_fact_is_unavailable(self):
        result = score(self.payload(value=False), [self.assertion(), self.assertion(["missing"])])
        self.assertEqual("failed", result["status"])
        self.assertEqual(["failed", "unknown"], [a["status"] for a in result["assertions"]])

    def test_manifest_requires_pins_cases_and_assertions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = self.case(root)
            manifest = {"schema_version": 1, "projects": [{"id": "example", "revision": "a" * 40, "cases": [case]}]}
            validate_manifest(manifest)
            for mutate in (
                lambda m: m.update(schema_version=2),
                lambda m: m.update(projects=[]),
                lambda m: m["projects"].append(copy.deepcopy(m["projects"][0])),
                lambda m: m["projects"][0].update(revision="main"),
                lambda m: m["projects"][0].update(cases=[]),
                lambda m: m["projects"][0]["cases"].append(copy.deepcopy(case)),
                lambda m: m["projects"][0]["cases"][0].update(tool="execute_tool"),
                lambda m: m["projects"][0]["cases"][0].update(evidence=[]),
                lambda m: m["projects"][0]["cases"][0]["evidence"][0].update(sha256="short"),
                lambda m: m["projects"][0]["cases"][0].update(assertions=[]),
                lambda m: m["projects"][0]["cases"][0]["assertions"][0].update(path=[]),
                lambda m: m["projects"][0]["cases"][0]["assertions"][0].update(mode="contains", expected="foo"),
            ):
                invalid = copy.deepcopy(manifest)
                mutate(invalid)
                with self.subTest(manifest=invalid), self.assertRaises(ValueError):
                    validate_manifest(invalid)

    def test_positions_are_one_based_and_pinned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = self.case(root)
            self.assertEqual({"path": "Example.kt", "line": 1, "column": 14}, prepare_case(root, case))
            (root / "Example.kt").write_text("changed")
            with self.assertRaises(ValueError):
                prepare_case(root, case)

    def test_multiline_position_with_non_bmp_prefix_counts_utf16(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = self.case(root)
            file = root / "Example.kt"
            file.write_text('// first\nval emoji = "😀"; target(1)\n', encoding="utf-8")
            case["evidence"][0]["sha256"] = digest(file)
            case["position"].update(anchor='val emoji = "😀"; target(1)')
            self.assertEqual(2, prepare_case(root, case)["line"])
            self.assertEqual(19, prepare_case(root, case)["column"])

    def test_ambiguous_missing_and_unpinned_position_anchors_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = self.case(root)
            for position in ({"anchor": "absent"}, {"anchor": "t"}, {"token": "t"},
                             {"anchor": "", "token": ""}, {"path": "Other.kt"}):
                changed = copy.deepcopy(case)
                changed["position"].update(position)
                if position.get("path"):
                    (root / "Other.kt").write_text("target(1)")
                with self.subTest(position=position), self.assertRaises(ValueError):
                    prepare_case(root, changed)

    def test_traversal_absolute_paths_and_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "project"
            root.mkdir()
            outside = Path(directory) / "outside.kt"
            outside.write_text("secret")
            (root / "link.kt").symlink_to(outside)
            for path in ("../outside.kt", str(outside), "link.kt"):
                with self.subTest(path=path), self.assertRaises(ValueError):
                    source_path(root, path)

    def test_runner_keeps_first_request_separate_and_retains_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = self.case(root)
            project = {"id": "example", "revision": "a" * 40, "cases": [case]}
            state = {"git": {"git_head": "a" * 40}, "compiled_classes": 1}
            calls = []
            class Client:
                def __init__(self, *_): pass
                def __enter__(self): return self
                def __exit__(self, *_): pass
                def call(self, name, arguments):
                    calls.append(name)
                    if len(calls) == 3:
                        raise TimeoutError("private source must not be retained")
                    return {"_meta": {}, "value": True}, {"latency_ms": 5.0}
            with patch("quill_real_project_baseline.input_state", return_value=state):
                result = run_project(["quill"], root, project, 3, 10, Client)
            self.assertEqual("get_overview", calls[0])
            self.assertEqual("unknown", result["cases"][0]["status"])
            self.assertEqual(5.0, result["cases"][0]["first_request_ms"])
            self.assertEqual(2, result["cases"][0]["warm_latency"]["samples"])
            self.assertEqual(1, result["cases"][0]["warm_latency"]["errors"])
            self.assertFalse(result["all_passed"])
            self.assertNotIn("private source", str(result))

    def test_runner_detects_input_changes_and_revision_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = {"id": "example", "revision": "a" * 40, "cases": [self.case(root)]}
            state = {"git": {"git_head": "a" * 40}, "compiled_classes": 1}
            class Client:
                def __init__(self, *_): pass
                def __enter__(self): return self
                def __exit__(self, *_): pass
                def call(self, *_): return {"_meta": {}, "value": True}, {"latency_ms": 1}
            with patch("quill_real_project_baseline.input_state", side_effect=[state, {**state, "changed": True}]):
                result = run_project(["quill"], root, project, 1, 10, Client)
            self.assertFalse(result["all_passed"])
            self.assertEqual("inputs_changed_during_run", result["cases"][0]["reason"])
            with patch("quill_real_project_baseline.input_state", return_value={**state, "git": {"git_head": "b" * 40}}), self.assertRaises(ValueError):
                run_project(["quill"], root, project, 1, 10, Client)
            with patch("quill_real_project_baseline.input_state", return_value={**state, "compiled_classes": 0}), self.assertRaises(ValueError):
                run_project(["quill"], root, project, 1, 10, Client)

    def test_state_ignores_directories_ending_in_class_and_hashes_class_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = self.case(root)
            (root / "test.class").mkdir()
            output = root / "Example.class"
            output.write_bytes(b"old")
            git_result = type("Result", (), {"stdout": b""})()
            with patch("quill_real_project_baseline.subprocess.run", return_value=git_result), patch("quill_real_project_baseline.project_metadata", return_value={}):
                before = input_state(root, {"cases": [case]})
                output.write_bytes(b"new")
                after = input_state(root, {"cases": [case]})
            self.assertEqual(1, before["compiled_classes"])
            self.assertNotEqual(before["compiled_sha256"], after["compiled_sha256"])

    def test_nested_incomplete_test_plan_and_traversal_are_unknown(self):
        for extra in ({"traversal_truncated": True}, {"test_plan": {"answer_complete": False}},
                      {"test_plan": {"has_more": True}}, {"test_plan": {"test_index_coverage": {"complete": False}}}):
            result = score(self.payload(value=True, **extra), [self.assertion()])
            self.assertEqual("unknown", result["status"])
            self.assertEqual("passed", result["observed_fact_status"])

    def test_change_plan_follows_all_test_pages_before_scoring(self):
        payload = self.payload(indexed=True, test_plan={
            "tests": [{"class": "A", "file": "A.java"}], "total": 3, "showing": 1,
            "has_more": True, "answer_complete": True,
            "test_index_coverage": {"complete": True},
            "next_page_request": {"tool": "find_impacted_tests", "arguments": {
                "targets": ["Target"], "transitive": True, "max_depth": 3,
                "limit": 1, "offset": 1}}})
        payload["_meta"]["index_id"] = "index-1"
        calls = []
        class Client:
            def call(self, tool, args):
                calls.append((tool, args["offset"]))
                offset = args["offset"]
                return {"_meta": {"index_id": "index-1"}, "targets": ["Target"],
                        "offset": offset, "total": 3, "showing": 1,
                        "answer_complete": True, "test_index_coverage": {"complete": True},
                        "tests": [{"class": chr(ord("A") + offset),
                                   "file": chr(ord("A") + offset) + ".java"}],
                        "has_more": offset < 2, "next_offset": offset + 1}, {"latency_ms": 2.5}
        result, latency = complete_change_plan_tests(Client(), payload)
        self.assertEqual([("find_impacted_tests", 1), ("find_impacted_tests", 2)], calls)
        self.assertEqual(5.0, latency)
        self.assertEqual(3, result["test_plan"]["showing"])
        self.assertTrue(result["test_plan"]["pagination_complete"])
        self.assertEqual("passed", score(result, [self.assertion(["indexed"])])["status"])

    def test_change_plan_rejects_missing_stale_and_duplicate_pages(self):
        base = self.payload(test_plan={"tests": [{"class": "A", "file": "A.java"}],
            "total": 2, "has_more": True, "next_page_request": {
                "tool": "find_impacted_tests", "arguments": {
                    "targets": ["Target"], "limit": 1, "offset": 1}}})
        base["_meta"]["index_id"] = "index-1"
        class Client:
            def __init__(self, page): self.page = page
            def call(self, *_): return self.page, {"latency_ms": 1.0}
        for change in ({"_meta": {"index_id": "index-2"}},
                       {"tests": [{"class": "A", "file": "A.java"}]},
                       {"total": 3}, {"offset": 0}, {"answer_complete": False}):
            page = {"_meta": {"index_id": "index-1"}, "targets": ["Target"],
                    "offset": 1, "total": 2, "showing": 1, "answer_complete": True,
                    "test_index_coverage": {"complete": True},
                    "tests": [{"class": "B", "file": "B.java"}], "has_more": False}
            page.update(change)
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                complete_change_plan_tests(Client(page), copy.deepcopy(base))
        missing = copy.deepcopy(base)
        del missing["test_plan"]["next_page_request"]
        with self.assertRaises(RuntimeError):
            complete_change_plan_tests(Client({}), missing)


if __name__ == "__main__":
    unittest.main()
