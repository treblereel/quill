package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/** Turns context evidence into an ordered, explicitly scoped change plan. */
final class ChangePlanQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContextQueries contextQueries = new ContextQueries();

    String planChange(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        if (change == null || change.isBlank()) {
            return errorResponse("A non-empty change description is required");
        }
        ObjectNode context = parse(contextQueries.getContext(jdbi, targets, true, limit));
        if (context.has("error_code")) return context.toString();

        ObjectNode root = JSON.createObjectNode();
        root.put("change", change.strip());
        copy(context, root, "requested_targets");
        copy(context, root, "resolved_target_count");
        copy(context, root, "unresolved_target_count");
        copy(context, root, "unresolved_targets");

        ArrayNode primary = root.putArray("primary_changes");
        ArrayNode dependencies = root.putArray("dependency_review");
        Set<String> dependencyKeys = new HashSet<>();
        boolean highRisk = false;
        boolean veryHighRisk = false;
        for (JsonNode item : context.path("contexts")) {
            ObjectNode target = primary.addObject();
            JsonNode resolution = item.path("resolution");
            JsonNode symbol = item.path("symbol");
            JsonNode coupling = item.path("coupling");
            JsonNode risk = item.path("risk");
            target.put("class", resolution.path("class").asText());
            target.put("file", sourceFile(resolution.path("source").asText()));
            copy(resolution, target, "module");
            copy(resolution, target, "source_set");
            copy(symbol, target, "kind");
            copy(symbol, target, "bean");
            appendMemberSummary(target, symbol.path("members"));
            target.set("coupling", coupling.deepCopy());
            copy(risk, target, "risk_score");
            copy(risk, target, "risk_level");
            copy(risk, target, "recommendation");
            String riskLevel = risk.path("risk_level").asText("").toUpperCase();
            highRisk |= riskLevel.equals("HIGH") || riskLevel.equals("VERY_HIGH");
            veryHighRisk |= riskLevel.equals("VERY_HIGH");

            for (JsonNode usage : item.path("usages").path("usages")) {
                String key = usage.path("class").asText() + "\u0000"
                        + usage.path("usage_kind").asText();
                if (!dependencyKeys.add(key)) continue;
                ObjectNode candidate = dependencies.addObject();
                copy(usage, candidate, "class");
                candidate.put("file", sourceFile(usage.path("source").asText()));
                copy(usage, candidate, "module");
                copy(usage, candidate, "usage_kind");
                copy(usage, candidate, "indexed_kind");
                copy(usage, candidate, "occurrences");
                candidate.put("action", "review");
                candidate.put("reason", "Uses a requested target; static evidence does not prove an edit is required");
            }
        }

        ObjectNode impact = context.path("impacted_tests") instanceof ObjectNode object
                ? object : JSON.createObjectNode();
        ObjectNode testPlan = root.putObject("test_plan");
        copy(impact, testPlan, "test_index_coverage");
        copy(impact, testPlan, "answer_complete");
        copy(impact, testPlan, "limitations");
        copy(impact, testPlan, "tests");
        copy(impact, testPlan, "showing");
        copy(impact, testPlan, "total");
        copy(impact, testPlan, "has_more");
        root.set("verification_plan", new VerificationPlanQueries().plan(
                projectRoot, context.path("contexts"), impact));

        ArrayNode warnings = root.putArray("warnings");
        if (context.path("unresolved_target_count").asInt() > 0) {
            warning(warnings, "UNRESOLVED_TARGETS",
                    "Some requested targets could not be resolved; their impact is absent from this plan");
        }
        if (!impact.path("answer_complete").asBoolean(true)) {
            warning(warnings, "INCOMPLETE_TEST_COVERAGE",
                    "The affected-test list is incomplete; use module or build-system test selection as a fallback");
        }
        if (highRisk) {
            warning(warnings, "HIGH_CHANGE_RISK",
                    veryHighRisk ? "At least one target has very high change risk"
                            : "At least one target has high change risk");
        }
        JsonNode meta = context.path("_meta");
        if (meta.path("structure_stale").asBoolean()) {
            warning(warnings, "STALE_INDEX",
                    "Structural changes are newer than the index; refresh it before relying on this plan");
        }

        ArrayNode sequence = root.putArray("sequence");
        step(sequence, 1, "inspect_primary", "Inspect the resolved declarations and member contracts before editing");
        step(sequence, 2, "edit_primary", "Implement the requested change in the primary files");
        step(sequence, 3, "review_dependencies", "Review usage candidates for contract or behavior fallout");
        step(sequence, 4, "update_tests", "Update or add focused tests, starting with the ranked affected tests");
        step(sequence, 5, "verify", "Run focused tests, then the owning modules' build and refresh the index");

        boolean complete = context.path("answer_complete").asBoolean()
                && impact.path("answer_complete").asBoolean(true)
                && !meta.path("structure_stale").asBoolean();
        root.put("plan_status", complete ? "ready" : "partial");
        root.put("answer_complete", complete);
        root.set("_meta", meta.deepCopy());
        return root.toString();
    }

    private static void step(ArrayNode steps, int order, String action, String description) {
        ObjectNode step = steps.addObject();
        step.put("order", order);
        step.put("action", action);
        step.put("description", description);
    }

    private static void appendMemberSummary(ObjectNode target, JsonNode members) {
        if (!members.isArray()) return;
        ArrayNode summary = target.putArray("member_contracts");
        for (JsonNode member : members) {
            ObjectNode item = summary.addObject();
            copy(member, item, "kind");
            copy(member, item, "name");
            copy(member, item, "signature");
        }
    }

    private static void warning(ArrayNode warnings, String code, String message) {
        ObjectNode warning = warnings.addObject();
        warning.put("code", code);
        warning.put("message", message);
    }

    private static String sourceFile(String source) {
        return source.replaceFirst(":\\d+$", "");
    }

    private static void copy(JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value != null) target.set(field, value.deepCopy());
    }

    private static ObjectNode parse(String json) {
        try {
            JsonNode parsed = JSON.readTree(json);
            if (parsed instanceof ObjectNode object) return object;
            throw new IllegalStateException("Expected a JSON object");
        } catch (Exception error) {
            throw new IllegalStateException("Cannot compose change plan", error);
        }
    }
}
