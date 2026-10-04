package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** Builds a compact, stateless snapshot of the change workflow. */
final class ChangeSessionQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContextQueries contexts = new ContextQueries();
    private final ChangePlanQueries plans = new ChangePlanQueries();
    private final ChangeVerificationQueries verification = new ChangeVerificationQueries();
    private final WorktreeStatusQueries worktree = new WorktreeStatusQueries();

    String snapshot(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        return snapshot(jdbi, projectRoot, targets, change, limit, "full", "all");
    }

    String snapshot(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit, String detail) {
        return snapshot(jdbi, projectRoot, targets, change, limit, detail, "all");
    }

    String snapshot(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit, String detail, String view) {
        String normalizedDetail = detail == null ? "summary" : detail.strip().toLowerCase();
        if (!normalizedDetail.equals("summary") && !normalizedDetail.equals("full")) {
            return errorResponse("Invalid detail: expected summary or full");
        }
        String normalizedView = view == null ? "auto" : view.strip().toLowerCase();
        if (!List.of("auto", "plan", "verification", "all").contains(normalizedView)) {
            return errorResponse("Invalid view: expected auto, plan, verification, or all");
        }
        if (change == null || change.isBlank()) {
            return plans.planChange(jdbi, projectRoot, targets, change, limit);
        }
        ObjectNode changes = parse(worktree.getFreshWorktreeStatus(
                jdbi, projectRoot, null, limit, 0));
        boolean inferred = targets == null || targets.isEmpty();
        List<String> candidates = inferred
                ? ChangeVerificationQueries.inferredTargets(changes) : List.copyOf(targets);
        boolean inferenceTruncated = inferred && candidates.size() > 10;
        List<String> effectiveTargets = candidates.stream().limit(10).toList();
        ObjectNode context = parse(contexts.getContext(
                jdbi, effectiveTargets, true, limit));
        if (context.has("error_code")) return context.toString();
        ObjectNode plan = plans.planFromContext(projectRoot, context, change);
        ObjectNode verify = parse(verification.verifyChangeWithContext(
                jdbi, projectRoot, effectiveTargets, limit, context, changes,
                inferred, inferenceTruncated));
        if (verify.has("error_code")) return verify.toString();
        List<String> canonicalTargets = canonicalTargets(context);

        ObjectNode result = JSON.createObjectNode();
        result.put("schema_version", 1);
        result.put("session_id", sessionId(projectRoot, canonicalTargets, change));
        result.put("state_model", "stateless_snapshot");
        String currentPhase = phase(verify);
        String effectiveView = normalizedView.equals("auto")
                ? (currentPhase.equals("planned") ? "plan" : "verification")
                : normalizedView;
        result.put("detail", normalizedDetail);
        result.put("view_requested", normalizedView);
        result.put("view", effectiveView);
        result.put("change", change.strip());
        result.put("target_source", inferred ? "dirty_worktree" : "explicit");
        result.put("target_inference_truncated", inferenceTruncated);
        result.set("requested_targets", JSON.valueToTree(effectiveTargets));
        result.set("targets", JSON.valueToTree(canonicalTargets));
        result.put("phase", currentPhase);

        boolean includePlan = effectiveView.equals("plan") || effectiveView.equals("all");
        boolean includeVerification = effectiveView.equals("verification")
                || effectiveView.equals("all");
        if (includePlan) {
            result.set("plan", normalizedDetail.equals("full")
                    ? fullPlan(plan) : summaryPlan(plan));
        }
        if (includeVerification) {
            result.set("verification", normalizedDetail.equals("full")
                    ? fullVerification(verify) : summaryVerification(verify));
            result.set("verification_plan", normalizedDetail.equals("full")
                    ? verify.path("verification_plan").deepCopy()
                    : summaryVerificationPlan(verify.path("verification_plan")));
        }
        result.set("next_actions", (effectiveView.equals("plan")
                ? plan.path("sequence") : verify.path("next_actions")).deepCopy());
        copy(verify, result, "_meta");
        var omitted = result.putArray("omitted_sections");
        if (!includePlan) omitted.add("plan");
        if (!includeVerification) {
            omitted.add("verification");
            omitted.add("verification_plan");
        }
        if (normalizedDetail.equals("summary") && includePlan) {
            omitted.add("plan.member_contracts").add("plan.coupling")
                    .add("plan.test_plan.tests");
        }
        if (normalizedDetail.equals("summary") && includeVerification) {
            omitted.add("verification.worktree.changes")
                    .add("verification.diagnostics.problems_after_first_5")
                    .add("verification.test_evidence.tests")
                    .add("verification_plan.non_quick_compile_commands");
        }
        return result.toString();
    }

    private static ObjectNode fullPlan(ObjectNode plan) {
        return compact(plan, List.of("plan_status", "answer_complete", "primary_changes",
                "dependency_review", "test_plan", "warnings", "sequence"));
    }

    private static ObjectNode summaryPlan(ObjectNode plan) {
        ObjectNode result = compact(plan, List.of(
                "plan_status", "answer_complete", "warnings", "sequence"));
        var primary = result.putArray("primary_changes");
        for (JsonNode item : plan.path("primary_changes")) {
            primary.add(compact(item, List.of("class", "file", "module", "source_set",
                    "kind", "bean", "risk_score", "risk_level", "recommendation")));
        }
        var dependencies = result.putArray("dependency_review");
        for (JsonNode item : plan.path("dependency_review")) {
            dependencies.add(compact(item, List.of(
                    "class", "file", "module", "usage_kind", "action", "reason")));
        }
        result.set("test_plan", compact(plan.path("test_plan"), List.of(
                "test_index_coverage", "answer_complete", "limitations", "showing",
                "total", "has_more")));
        return result;
    }

    private static ObjectNode fullVerification(ObjectNode verify) {
        return compact(verify, List.of("verdict", "verified", "target_source", "worktree",
                "build", "diagnostics", "test_evidence", "blockers"));
    }

    private static ObjectNode summaryVerification(ObjectNode verify) {
        ObjectNode result = compact(verify, List.of(
                "verdict", "verified", "target_source", "blockers"));
        result.set("worktree", compact(verify.path("worktree"), List.of(
                "branch", "commit_stale", "dirty", "structural_dirty",
                "counts_by_status", "showing", "total", "has_more")));
        result.set("build", compact(verify.path("build"), List.of(
                "build_system", "compiled_outputs", "freshness", "status",
                "build_reason", "action_required", "recommended_action")));
        ObjectNode diagnostics = compact(verify.path("diagnostics"), List.of(
                "build_status", "build_tool", "finished_at", "limitations",
                "showing", "total", "has_more", "recommended_action"));
        var problems = diagnostics.putArray("problems");
        int count = 0;
        for (JsonNode problem : verify.path("diagnostics").path("problems")) {
            if (count++ == 5) break;
            problems.add(problem.deepCopy());
        }
        result.set("diagnostics", diagnostics);
        result.set("test_evidence", compact(verify.path("test_evidence"), List.of(
                "test_index_coverage", "answer_complete", "limitations",
                "showing", "total", "has_more")));
        return result;
    }

    private static ObjectNode summaryVerificationPlan(JsonNode plan) {
        ObjectNode result = compact(plan, List.of("build_system", "working_directory",
                "runner", "modules", "refresh_index_after_success", "commands_executable"));
        var commands = result.putArray("commands");
        var otherScopes = result.putArray("other_command_scopes");
        for (JsonNode command : plan.path("commands")) {
            if (command.path("scope").asText().equals("quick_compile")) {
                commands.add(command.deepCopy());
            } else {
                otherScopes.add(command.path("scope").asText());
            }
        }
        return result;
    }

    private static ObjectNode compact(JsonNode source, List<String> fields) {
        ObjectNode result = JSON.createObjectNode();
        fields.forEach(field -> copy(source, result, field));
        return result;
    }

    private static String phase(ObjectNode verification) {
        String verdict = verification.path("verdict").asText();
        if (verdict.equals("ready")) return "complete";
        if (verdict.equals("blocked")) return "blocked";
        if (!verification.path("worktree").path("structural_dirty").asBoolean()) return "planned";
        return "verification_required";
    }

    private static String sessionId(Path root, List<String> targets, String change) {
        String identity = root.toAbsolutePath().normalize() + "\n"
                + String.join("\n", targets) + "\n" + change.strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<String> canonicalTargets(ObjectNode context) {
        List<String> result = new ArrayList<>();
        for (JsonNode item : context.path("contexts")) {
            String className = item.path("resolution").path("class").asText();
            if (!className.isBlank() && !result.contains(className)) result.add(className);
        }
        for (JsonNode item : context.path("unresolved_targets")) {
            String target = item.path("target").asText().strip();
            if (!target.isBlank() && !result.contains(target)) result.add(target);
        }
        return List.copyOf(result);
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
            throw new IllegalStateException("Cannot compose change session", error);
        }
    }
}
