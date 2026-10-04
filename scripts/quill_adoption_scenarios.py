"""Task-level native adoption scoring; model mentions and failed calls are not evidence."""

import hashlib
import json
import os
import re
from pathlib import Path


SCOPE = ("Work only in this fixture; do not access credentials, other projects, or unrelated "
         "services, and do not delegate. Do not modify files or run builds or tests. ")
SCENARIOS = {
    "navigation": {
        "prompt": "Locate the production declaration of greet(String) and its source file, "
                  "using a path relative to the fixture root. "
                  'Return only JSON with string fields "class" and "source_file".',
        "expected": {"class": "org.example.GreetingService",
                     "source_file": "src/main/java/org/example/GreetingService.java"},
        "tools": ("search_symbols", "search_classes", "resolve_entities", "get_file_symbols"),
    },
    "usages": {
        "prompt": "Which production and standard test classes call "
                  "org.example.GreetingService.greet? Include both source sets. "
                  'Return only JSON with a "callers" array of fully qualified class names.',
        "expected": {"callers": ["org.example.GreetingController",
                                 "org.example.GreetingServiceCompileProbe"]},
        "tools": ("find_symbol_usages", "find_usages", "get_call_hierarchy"),
    },
    "dependencies": {
        "prompt": "Which internal production class does org.example.GreetingController "
                  "depend on directly? Exclude JDK types. "
                  'Return only JSON with a "dependencies" array of fully qualified class names.',
        "expected": {"dependencies": ["org.example.GreetingService"]},
        "tools": ("get_dependencies",),
    },
    "history": {
        "prompt": "What is the latest Git commit subject that changed "
                  "src/main/java/org/example/GreetingService.java? "
                  'Return only JSON with the string field "latest_subject".',
        "expected": {"latest_subject": "Use a welcoming greeting"},
        "tools": ("get_file_history", "get_recent_changes"),
    },
    "change_plan": {
        "prompt": "What should I inspect before adding an enthusiastic greeting to "
                  "org.example.GreetingService without changing its public signature? "
                  "Report the current change workflow phase, its immediate primary action, "
                  "and whether verification is complete. Return only JSON with string fields "
                  '"phase", "primary_action", and boolean "verified".',
        "expected": None,  # Filled from an independent live wire snapshot before inference.
        "tools": ("change_session",),
    },
}
DEFAULT_SCENARIOS = tuple(SCENARIOS)  # Preserve existing opt-in workload and profile contracts.
SCENARIOS.update({
    "call_chain": {
        "prompt": "Trace the internal production call chain from "
                  "org.example.GreetingEndpoint.render to the method that constructs the greeting. "
                  'Return only JSON with an "edges" array of "fully.qualified.Class.method -> fully.qualified.Class.method" strings.',
        "expected": {"edges": ["org.example.GreetingEndpoint.render -> org.example.GreetingController.hello",
                               "org.example.GreetingController.hello -> org.example.GreetingService.greet"]},
        "tools": ("get_call_hierarchy",),
    },
    "known_file": {
        "prompt": "Read src/main/java/org/example/GreetingService.java and report "
                  'the greeting prefix string, including its trailing space. Return only JSON with "prefix".',
        "expected": {"prefix": "Welcome, "}, "tools": (), "route": "source",
    },
    "literal": {
        "prompt": 'Locate the exact literal "Welcome, " in production Java source. '
                  'Return only JSON with "source_file", a path relative to the fixture root.',
        "expected": {"source_file": "src/main/java/org/example/GreetingService.java"},
        "tools": (), "route": "source",
    },
    "impact_modules": {
        "prompt": "If the behavior of org.example.GreetingService.greet changes without changing its signature, "
                  "which Maven modules contain that class or transitive production callers that should be reviewed? "
                  'Exclude the aggregator and independent modules. Return only JSON with a "modules" array of module directory names.',
        "expected": {"modules": ["core", "api"]},
        "tools": ("change_session", "plan_change", "get_dependencies", "get_call_hierarchy", "find_symbol_usages"),
        "evidence_contract": "module_impact:v2",
    },
    "impacted_tests": {
        "prompt": "Which JUnit test classes should be reviewed if the behavior of "
                  "org.example.GreetingService.greet changes without changing its signature? Include transitive "
                  "production callers; exclude compile-only probes and tests of independent services. "
                  'Return only JSON with a "tests" array of fully qualified test class names.',
        "expected": {"tests": ["org.example.GreetingServiceTest", "org.example.GreetingEndpointTest"]},
        "tools": ("find_impacted_tests", "change_session", "plan_change"),
    },
})


def prompt_for(scenario):
    return SCOPE + SCENARIOS[scenario]["prompt"]


def fixture_snapshot(root):
    """Detect edits outside derived Git/index state, including build-output changes."""
    ignored = {".git", ".quill", ".quill-workspace"}
    result = {}
    for path in Path(root).rglob("*"):
        relative = path.relative_to(root)
        if any(part in ignored for part in relative.parts):
            continue
        if path.is_symlink():
            result[relative.as_posix()] = ("symlink:" + os.readlink(path), path.lstat().st_mtime_ns)
            continue
        if not path.is_file():
            continue
        # Hash inputs and outputs; do not put file contents in the diagnostic report.
        result[relative.as_posix()] = (hashlib.sha256(path.read_bytes()).hexdigest(),
                                      path.stat().st_mtime_ns)
    return result


def _arguments(value):
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except ValueError:
            return {}
    return value if isinstance(value, dict) else {}


def _response_status(result, failed=False):
    if failed:
        return "error"
    if result is None or result == {} or result == [] or result == "":
        return "unknown"
    values = [result]
    if isinstance(result, dict):
        if result.get("isError") is True or result.get("is_error") is True:
            return "error"
        structured = result.get("structuredContent", result.get("structured_content"))
        if ("content" in result or "structuredContent" in result or "structured_content" in result
                or result.get("type") == "tool_result") and not result.get("content") and not structured:
            return "unknown"
        if isinstance(structured, dict):
            values.append(structured)
        content = result.get("content", [])
        if isinstance(content, (str, dict)):
            values.append(content)
        elif isinstance(content, list):
            values.extend(item.get("text") for item in content if isinstance(item, dict))
    for value in values:
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except ValueError:
                continue
        if isinstance(value, dict):
            if value.get("isError") is True or value.get("error") or value.get("error_code"):
                return "error"
            projects = value.get("projects", [])
            if isinstance(projects, list) and projects and all(
                    isinstance(item, dict) and (item.get("error") or
                    (isinstance(item.get("data"), dict) and
                     (item["data"].get("error") or item["data"].get("error_code"))))
                    for item in projects):
                return "error"
    return "ok"


def native_trace(client, stream):
    """Keep call order and correlate Claude tool_use IDs with actual tool_result messages."""
    trace, calls = [], {}
    final = ""
    client_failed = False
    for event in stream:
        if client == "codex":
            item = event.get("item", {})
            if not isinstance(item, dict):
                continue
            kind = item.get("type")
            if event.get("type") in {"turn.failed", "error"}:
                client_failed = True
            if event.get("type") == "item.completed" and kind == "agent_message":
                final = item.get("text", "")
            if event.get("type") not in {"item.started", "item.completed"}:
                continue
            if kind not in {"mcp_tool_call", "command_execution", "file_change"}:
                continue
            identifier = item.get("id")
            call = calls.get(identifier) if identifier else None
            if call is None:
                provider = "quill" if kind == "mcp_tool_call" and item.get("server") == "quill" else "source"
                name = item.get("tool", kind)
                arguments = _arguments(item.get("arguments"))
                if provider == "quill" and name == "execute_tool":
                    name = arguments.get("name", name)
                call = {"provider": provider, "tool": name, "status": "unknown"}
                if kind == "command_execution":
                    call["command"] = item.get("command", "")
                trace.append(call)
                if identifier:
                    calls[identifier] = call
            if event.get("type") == "item.completed":
                failed = bool(item.get("error")) or item.get("status") in {"failed", "error"} \
                    or item.get("exit_code") not in {None, 0}
                call["status"] = _response_status(item.get("result"), failed) if kind == "mcp_tool_call" \
                    else "error" if failed else "observed"
        else:
            if event.get("type") == "result":
                final = event.get("result", final)
                client_failed |= event.get("is_error") is True
            message = event.get("message", {})
            if not isinstance(message, dict) or not isinstance(message.get("content"), list):
                continue
            for item in message["content"]:
                if not isinstance(item, dict):
                    continue
                kind = item.get("type")
                if kind == "tool_use" and event.get("type") == "assistant":
                    identifier = item.get("id")
                    if identifier and identifier in calls:
                        continue
                    name = item.get("name", "")
                    provider = "quill" if name.startswith("mcp__quill__") else "source"
                    name = name.removeprefix("mcp__quill__")
                    arguments = _arguments(item.get("input"))
                    if provider == "quill" and name == "execute_tool":
                        name = arguments.get("name", name)
                    call = {"provider": provider, "tool": name, "status": "unknown"}
                    if name == "Bash":
                        call["command"] = arguments.get("command", "")
                    trace.append(call)
                    if identifier:
                        calls[identifier] = call
                elif kind == "tool_result" and event.get("type") == "user":
                    call = calls.get(item.get("tool_use_id"))
                    if call is not None:
                        call["status"] = _response_status(item, item.get("is_error") is True)
                elif kind == "text" and event.get("type") == "assistant":
                    final = item.get("text", final)
    return trace, final, client_failed


def _observed(text):
    if not isinstance(text, str):
        return None
    text = text.strip()
    if text.startswith("```") and text.endswith("```"):
        text = "\n".join(text.splitlines()[1:-1])
    try:
        value = json.loads(text)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def _correct(observed, expected):
    if not isinstance(observed, dict) or not isinstance(expected, dict) or observed.keys() != expected.keys():
        return False
    for key, value in expected.items():
        actual = observed[key]
        if type(actual) is not type(value):
            return False
        if isinstance(value, list):
            if not all(isinstance(item, str) for item in actual) or sorted(actual) != sorted(value):
                return False
        elif actual != value:
            return False
    return True


def evaluate_capture(client, stream, capture, scenario, expected, fixture_unchanged, expected_variants=(), rubric="legacy"):
    if rubric not in {"legacy", "routing"}:
        raise ValueError("Unknown task rubric")
    trace, final, client_failed = native_trace(client, stream)
    observed = _observed(final)
    quill = [call for call in trace if call["provider"] == "quill"]
    successful = [call["tool"] for call in quill if call["status"] == "ok"]
    criteria = {
        "client_completed": capture.get("returncode") == 0 and not capture.get("timeout") and not client_failed,
        "overview_first": bool(quill) and quill[0]["tool"] == "get_overview" and quill[0]["status"] == "ok",
        "task_tool_succeeded": any(tool in successful for tool in SCENARIOS[scenario]["tools"]) or any(
            set(group).issubset(successful) for group in SCENARIOS[scenario].get("tool_sets", ())),
        "answer_correct": _correct(observed, expected) or any(_correct(observed, value) for value in expected_variants),
        "fixture_unchanged": fixture_unchanged,
        "no_edit_tool_calls": not any(call["provider"] == "source" and call["tool"] in
            {"file_change", "Edit", "Write", "MultiEdit", "apply_patch"} for call in trace),
        # Recognize normal shell build invocations, not arbitrary code executing a subprocess.
        "no_build_commands": not any(re.search(
            r"(?:^|[;&|]\s*|\b(?:bash|sh)\s+-[lc]+\s+['\"])(?:[^\s]+/)?"
            r"(?:mvnw?|gradlew?)\s+(?![-]*version\b|--version\b|[-]*help\b)(?=\S)",
            call.get("command", "")) for call in trace),
        "user_config_unchanged": client != "codex" or capture.get("user_config_unchanged") is True,
    }
    first_quill = next((index for index, call in enumerate(trace) if call["provider"] == "quill"), len(trace))
    meaningful = [call for call in trace if call["tool"] not in {"ToolSearch", "get_overview", "search_tools"}]
    route = SCENARIOS[scenario].get("route", "quill")
    source_tools = {"Read", "Grep", "Glob", "command_execution"}
    first = meaningful[0] if meaningful else None
    routing = bool(first) and first["provider"] == route and first["status"] in {"ok", "observed"}
    if route == "source":
        routing &= bool(first) and first["tool"] in source_tools
    required = set(criteria) - {"overview_first", "task_tool_succeeded"}
    routing_success = any(call["provider"] == "source" and call["tool"] in source_tools
                          and call["status"] in {"ok", "observed"} for call in meaningful) if route == "source" \
        else criteria["task_tool_succeeded"]
    routing_passed = all(criteria[key] for key in required) and routing and routing_success
    if rubric == "routing":
        criteria["first_route_appropriate"] = routing
        criteria["route_evidence_succeeded"] = routing_success
    passed = all(criteria.values()) if rubric == "legacy" else routing_passed
    from quill_answer_dimensions import score_answer
    dimensions = score_answer(final, expected, expected_variants, _correct, observed is not None)
    fact_gate = all(criteria[key] for key in required - {"answer_correct"}) and routing and routing_success
    dimensions["factual_workflow_passed"] = fact_gate and dimensions["facts_correct"] is True
    return {"passed": passed, "rubric": rubric, "routing_passed": routing_passed,
            "evidence_contract": SCENARIOS[scenario].get("evidence_contract", "task_tools:v1"),
            "answer_dimensions": dimensions,
            "required_criteria": sorted(criteria if rubric == "legacy" else
                                        required | {"first_route_appropriate", "route_evidence_succeeded"}),
            "answer_format_valid": observed is not None,
            "call_count": len(trace), "failed_call_count": sum(call["status"] == "error" for call in trace),
            "first_meaningful_call": first, "expected_route": route,
            "non_overview_quill_call_count": sum(call["tool"] != "get_overview" for call in quill),
            "criteria": criteria, "expected": expected,
            "expected_variants": list(expected_variants),
            "observed": observed, "trace": trace, "source_before_quill": first_quill,
            "quill_call_count": len(quill), "successful_quill_call_count": len(successful)}


def summarize(runs, expected_total=None):
    total = len(runs) if expected_total is None else expected_total
    result = {"total": total, "completed": len(runs), "remaining": max(0, total - len(runs)),
              "passed": 0, "failed": 0, "unavailable": 0, "quill_used": 0,
              "criteria_passed": {}, "by_scenario": {}}
    for run in runs:
        key = run["scenario"]
        group = result["by_scenario"].setdefault(key, {"total": 0, "passed": 0, "failed": 0, "unavailable": 0})
        status = "unavailable" if run.get("unavailable") else "passed" if run.get("evaluation", {}).get("passed") else "failed"
        result[status] += 1
        group["total"] += 1
        group[status] += 1
        evaluation = run.get("evaluation", {})
        if evaluation.get("successful_quill_call_count", 0) > 0:
            result["quill_used"] += 1
        for criterion, passed in evaluation.get("criteria", {}).items():
            result["criteria_passed"][criterion] = result["criteria_passed"].get(criterion, 0) + int(passed is True)
    result["all_passed"] = bool(runs) and len(runs) == total and result["passed"] == total
    if any(run.get("evaluation", {}).get("answer_dimensions") for run in runs):
        from quill_answer_dimensions import dimension_counts
        result["answer_dimensions"] = dimension_counts(runs)
    return result
