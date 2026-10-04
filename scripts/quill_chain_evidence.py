"""Bounded, opt-in fixture diagnostics; never retain raw answers or tool results."""
import json

from quill_adoption_scenarios import native_trace, _correct, _observed


def chain_evidence(client, stream, expected):
    _, final, _ = native_trace(client, stream)
    text = final if isinstance(final, str) else ""
    embedded = False
    surrounding = None
    # Diagnostic only: do not loosen the scoring parser or accept prose-wrapped answers.
    for index, char in enumerate(text[:8192]):
        if char == "{":
            try:
                value, length = json.JSONDecoder().raw_decode(text[index:index + 8192])
                if _correct(value, expected):
                    embedded = True
                    surrounding = {"prefix_chars": index, "suffix_chars": len(text) - index - length}
                    break
            except ValueError:
                pass
    calls, by_id = [], {}
    for event in stream:
        message = event.get("message")
        items = message.get("content", []) if client == "claude" and isinstance(message, dict) \
            else [] if client == "claude" else [event.get("item", {})]
        if not isinstance(items, list):
            continue
        for item in items:
            if not isinstance(item, dict):
                continue
            name = item.get("name", item.get("tool", ""))
            identifier = item.get("id")
            is_hierarchy = isinstance(name, str) and (name == "mcp__quill__get_call_hierarchy" if client == "claude"
                else name == "get_call_hierarchy" and item.get("server") == "quill")
            if is_hierarchy and len(calls) < 16 and not (isinstance(identifier, str) and identifier in by_id):
                args = item.get("input", item.get("arguments", {}))
                if isinstance(args, str):
                    try:
                        args = json.loads(args[:2048])
                    except ValueError:
                        args = {}
                if not isinstance(args, dict):
                    args = {}
                safe = {}
                for key in ("target", "method", "direction", "transitive", "max_depth", "scope"):
                    value = args.get(key)
                    if key == "target" and value in ("org.example.GreetingEndpoint", "org.example.GreetingController",
                                                     "org.example.GreetingService"):
                        safe[key] = value
                    elif key == "method" and isinstance(value, str) and value in {"render", "hello", "greet", "<init>"}:
                        safe[key] = value
                    elif key == "direction" and isinstance(value, str) and value in {"inbound", "outbound", "both"}:
                        safe[key] = value
                    elif key == "scope" and isinstance(value, str) and value in {"all", "cross_class", "cross_package"}:
                        safe[key] = value
                    elif key in {"transitive", "max_depth"} and type(value) in {bool, int}:
                        safe[key] = value
                call = {"arguments": safe}
                calls.append(call)
                if isinstance(identifier, str):
                    by_id[identifier] = call
            result_id = item.get("tool_use_id")
            if item.get("type") == "tool_result" and isinstance(result_id, str) and result_id in by_id:
                content = item.get("content")
                if isinstance(content, list):
                    content = next((part.get("text") for part in content if isinstance(part, dict)
                                    and part.get("type") == "text"), None)
                if isinstance(content, str) and len(content) > 65536:
                    by_id[result_id]["response_shape"] = {"omitted": "size_limit"}
                    continue
                try:
                    data = json.loads(content) if isinstance(content, str) else content
                except ValueError:
                    data = None
                if isinstance(data, dict):
                    data = data.get("structuredContent", data)
                if isinstance(data, dict):
                    by_id[result_id]["response_shape"] = {
                        "calls_count": len(data["calls"]) if isinstance(data.get("calls"), list) else None,
                        "has_error": bool(data.get("error_code") or data.get("error")),
                        "known_keys": sorted(set(data) & {"target", "method", "calls", "total", "has_more", "_meta"})}
    return {"final_chars": len(text), "strict_json_object": _observed(final) is not None,
            "embedded_expected_json": embedded, "surrounding_expected_json": surrounding,
            "hierarchy_calls": calls,
            "scope": "fixture-only whitelisted arguments and result shape; raw content discarded"}
