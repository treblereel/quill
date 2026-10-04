"""Versioned factual extraction, separate from legacy whole-answer acceptance."""
import json


class DuplicateKeysError(ValueError):
    pass


def no_duplicate_keys(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise DuplicateKeysError("Duplicate JSON key")
        value[key] = item
    return value


def score_answer(text, expected, variants, matcher, format_valid):
    result = {"contract": "facts_and_format:v1", "format_valid": format_valid,
              "fact_status": "missing", "facts_correct": None, "candidate_count": 0}
    if not isinstance(text, str) or not isinstance(expected, dict):
        result["fact_status"] = "unavailable"
        return result
    if len(text) > 8192:
        result["fact_status"] = "limit"
        return result
    candidates, index, attempts = [], 0, 0
    decoder = json.JSONDecoder(object_pairs_hook=no_duplicate_keys)
    while index < len(text):
        if text[index] not in "{[":
            index += 1
            continue
        attempts += 1
        if attempts > 32:
            result["fact_status"] = "limit"
            return result
        try:
            value, length = decoder.raw_decode(text[index:])
        except DuplicateKeysError:
            result["fact_status"] = "malformed"
            return result
        except ValueError:
            index += 1
            continue
        index += length  # Do not cherry-pick a nested object from another response schema.
        if isinstance(value, dict) and value.keys() == expected.keys():
            candidates.append(value)
    result["candidate_count"] = len(candidates)
    if len(candidates) > 1:
        result["fact_status"] = "ambiguous"
    elif len(candidates) == 1:
        correct = matcher(candidates[0], expected) or any(matcher(candidates[0], value) for value in variants)
        result.update({"facts_correct": correct, "fact_status": "correct" if correct else "incorrect"})
    return result


def dimension_counts(runs):
    dimensions = [run.get("evaluation", {}).get("answer_dimensions") for run in runs if not run.get("unavailable")]
    dimensions = [item for item in dimensions if isinstance(item, dict)]
    return {"contract": "facts_and_format:v1", "captured": len(dimensions),
            "facts_evaluated": sum(type(item.get("facts_correct")) is bool for item in dimensions),
            "facts_correct": sum(item.get("facts_correct") is True for item in dimensions),
            "format_valid": sum(item.get("format_valid") is True for item in dimensions),
            "factual_workflow_passed": sum(item.get("factual_workflow_passed") is True for item in dimensions)}
