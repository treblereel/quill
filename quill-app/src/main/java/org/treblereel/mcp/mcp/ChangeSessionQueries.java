package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;

/** Builds a compact, stateless snapshot of the change workflow. */
final class ChangeSessionQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REVIEW_REASONS = Set.of(
            "UNRESOLVED_TARGETS", "INCOMPLETE_TEST_COVERAGE",
            "TARGET_INFERENCE_TRUNCATED", "WORKTREE_TRUNCATED",
            "MODULE_SELECTION_INCOMPLETE");
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
        String stableSessionId = sessionId(projectRoot, canonicalTargets, change);
        result.put("session_id", stableSessionId);
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
        ObjectNode checklist = reviewChecklist(plan, verify);
        result.set("phase_gate", phaseGate(currentPhase, checklist));
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
            result.set("review_checklist", checklist);
            result.set("verification_receipt",
                    verificationReceipt(stableSessionId, currentPhase, verify, checklist));
        }
        result.set("next_actions", effectiveView.equals("plan")
                ? plan.path("sequence").deepCopy()
                : phaseActions(currentPhase, verify.path("next_actions")));
        copy(verify, result, "_meta");
        var omitted = result.putArray("omitted_sections");
        if (!includePlan) omitted.add("plan");
        if (!includeVerification) {
            omitted.add("verification");
            omitted.add("verification_plan");
            omitted.add("review_checklist");
            omitted.add("verification_receipt");
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
                "runner", "requested_modules", "modules", "command_modules",
                "unresolved_modules", "module_selection_complete",
                "refresh_index_after_success", "commands_executable"));
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

    private static ObjectNode verificationReceipt(
            String sessionId, String currentPhase, ObjectNode verify, ObjectNode checklist) {
        JsonNode diagnostics = verify.path("diagnostics");
        JsonNode build = verify.path("build");
        JsonNode metadata = verify.path("_meta");
        JsonNode quickCompile = null;
        for (JsonNode command : verify.path("verification_plan").path("commands")) {
            if (command.path("scope").asText().equals("quick_compile")) {
                quickCompile = command;
                break;
            }
        }

        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schema_version", 1);
        receipt.put("receipt_id", digest(sessionId + "\n"
                + diagnostics.path("finished_at").asText() + "\n"
                + verify.path("verdict").asText()));
        receipt.put("session_id", sessionId);
        receipt.put("phase", currentPhase);
        receipt.put("verdict", verify.path("verdict").asText());
        receipt.put("verified", verify.path("verified").asBoolean());
        receipt.put("review_status", checklist.path("status").asText());
        receipt.put("review_required_count", checklist.path("required_count").asInt());

        ObjectNode recommendation = receipt.putObject("recommendation");
        recommendation.put("scope", "quick_compile");
        recommendation.set("argv", quickCompile == null
                ? JSON.createArrayNode() : quickCompile.path("argv").deepCopy());
        copy(verify.path("verification_plan"), recommendation, "working_directory");
        if (quickCompile != null) {
            copy(quickCompile, recommendation, "executes_tests");
            copy(quickCompile, recommendation, "compiles_test_sources");
        }
        recommendation.put("executed_by_quill", false);
        recommendation.put("command_attestation", "not_captured");

        ObjectNode observed = receipt.putObject("observed_evidence");
        String buildStatus = diagnostics.path("build_status").asText("unknown");
        observed.put("build_event_observed",
                !buildStatus.equals("unknown") && !buildStatus.equals("unavailable"));
        observed.put("build_status", buildStatus);
        copy(diagnostics, observed, "build_tool");
        copy(diagnostics, observed, "finished_at");
        copy(diagnostics, observed, "capture_scope");
        copy(build, observed, "compiled_outputs");
        observed.put("index_current", !metadata.path("structure_stale").asBoolean());
        observed.put("structural_worktree_dirty",
                verify.path("worktree").path("structural_dirty").asBoolean());

        var reasons = receipt.putArray("reason_codes");
        if (buildStatus.equals("success")) reasons.add("SUCCESSFUL_BUILD_EVENT");
        if (buildStatus.equals("failed")) reasons.add("FAILED_BUILD_EVENT");
        if (!metadata.path("structure_stale").asBoolean()) reasons.add("INDEX_CURRENT");
        if (verify.path("unresolved_target_count").asInt() == 0) {
            reasons.add("TARGETS_RESOLVED");
        }
        if (verify.path("test_evidence").path("answer_complete").asBoolean()) {
            reasons.add("TEST_EVIDENCE_COMPLETE");
        }
        for (JsonNode blocker : verify.path("blockers")) {
            reasons.add("BLOCKER_" + blocker.path("code").asText());
        }
        var limitations = receipt.putArray("limitations");
        if (observed.path("build_event_observed").asBoolean()) {
            limitations.add("The build event attests the result, not the exact command argv");
        }
        return receipt;
    }

    private static ObjectNode reviewChecklist(ObjectNode plan, ObjectNode verify) {
        Set<String> blockers = new java.util.LinkedHashSet<>();
        for (JsonNode blocker : verify.path("blockers")) {
            blockers.add(blocker.path("code").asText());
        }
        ObjectNode checklist = JSON.createObjectNode();
        checklist.put("schema_version", 1);
        checklist.put("acknowledgement_model", "evidence_only");
        var items = checklist.putArray("items");
        checklistItem(items, "primary_contracts", "Review changed declarations and contracts",
                plan.path("primary_changes").isEmpty() ? "clear" : "advisory",
                "plan.primary_changes", plan.path("primary_changes").size(), null);
        checklistItem(items, "dependency_usages", "Review callers and dependency fallout",
                plan.path("dependency_review").isEmpty() ? "clear" : "advisory",
                "plan.dependency_review", plan.path("dependency_review").size(), null);
        checklistItem(items, "target_resolution", "Resolve every requested target",
                blockers.contains("UNRESOLVED_TARGETS") ? "required" : "clear",
                "verification.blockers", verify.path("unresolved_target_count").asInt(),
                blockers.contains("UNRESOLVED_TARGETS") ? "UNRESOLVED_TARGETS" : null);
        checklistItem(items, "test_coverage", "Review incomplete affected-test evidence",
                blockers.contains("INCOMPLETE_TEST_COVERAGE") ? "required" : "clear",
                "verification.test_evidence", verify.path("test_evidence")
                        .path("limitations").size(), blockers.contains("INCOMPLETE_TEST_COVERAGE")
                                ? "INCOMPLETE_TEST_COVERAGE" : null);
        checklistItem(items, "module_selection", "Resolve build-system module mappings",
                blockers.contains("MODULE_SELECTION_INCOMPLETE") ? "required" : "clear",
                "verification_plan.unresolved_modules", verify.path("verification_plan")
                        .path("unresolved_modules").size(),
                blockers.contains("MODULE_SELECTION_INCOMPLETE")
                        ? "MODULE_SELECTION_INCOMPLETE" : null);
        boolean worktreeIncomplete = blockers.contains("TARGET_INFERENCE_TRUNCATED")
                || blockers.contains("WORKTREE_TRUNCATED");
        checklistItem(items, "worktree_scope", "Inspect all structurally changed files",
                worktreeIncomplete ? "required" : "clear", "verification.worktree",
                verify.path("worktree").path("total").asInt(), worktreeIncomplete
                        ? (blockers.contains("TARGET_INFERENCE_TRUNCATED")
                                ? "TARGET_INFERENCE_TRUNCATED" : "WORKTREE_TRUNCATED")
                        : null);

        int required = 0;
        int advisory = 0;
        for (JsonNode item : items) {
            if (item.path("status").asText().equals("required")) required++;
            if (item.path("status").asText().equals("advisory")) advisory++;
        }
        checklist.put("required_count", required);
        checklist.put("advisory_count", advisory);
        checklist.put("status", required > 0 ? "required" : advisory > 0 ? "advisory" : "clear");
        return checklist;
    }

    static ObjectNode phaseGate(String phase, ObjectNode checklist) {
        ObjectNode gate = JSON.createObjectNode();
        gate.put("schema_version", 1);
        gate.put("phase", phase);
        gate.put("can_proceed_without_resolution",
                phase.equals("planned") || phase.equals("complete"));
        ArrayNode evidence = gate.putArray("required_evidence");
        switch (phase) {
            case "planned" -> {
                gate.put("status", "ready");
                gate.put("transition", "apply_structural_change");
            }
            case "review_required" -> {
                gate.put("status", "action_required");
                gate.put("transition", "resolve_review_evidence");
                for (JsonNode item : checklist.path("items")) {
                    if (item.path("status").asText().equals("required")) {
                        evidence.add(compact(item, List.of(
                                "id", "evidence", "evidence_count", "reason_code")));
                    }
                }
            }
            case "verification_required" -> {
                gate.put("status", "action_required");
                gate.put("transition", "capture_successful_build");
                gateEvidence(evidence, "successful_build_event",
                        "verification.diagnostics", "BUILD_EVIDENCE_REQUIRED");
            }
            case "blocked" -> {
                gate.put("status", "action_required");
                gate.put("transition", "fix_diagnostics_and_rebuild");
                gateEvidence(evidence, "successful_build_event",
                        "verification.diagnostics", "BUILD_FAILED");
            }
            case "complete" -> {
                gate.put("status", "satisfied");
                gate.put("transition", "none");
            }
            default -> throw new IllegalArgumentException("Unknown change phase: " + phase);
        }
        gate.put("required_evidence_count", evidence.size());
        return gate;
    }

    private static void gateEvidence(ArrayNode evidence, String id, String source, String reason) {
        ObjectNode item = evidence.addObject();
        item.put("id", id);
        item.put("evidence", source);
        item.put("reason_code", reason);
    }

    private static void checklistItem(ArrayNode items,
            String id, String label, String status, String evidence, int count, String reason) {
        ObjectNode item = items.addObject();
        item.put("id", id);
        item.put("label", label);
        item.put("status", status);
        item.put("evidence", evidence);
        item.put("evidence_count", count);
        if (reason == null) item.putNull("reason_code");
        else item.put("reason_code", reason);
    }

    private static ObjectNode compact(JsonNode source, List<String> fields) {
        ObjectNode result = JSON.createObjectNode();
        fields.forEach(field -> copy(source, result, field));
        return result;
    }

    private static JsonNode phaseActions(String phase, JsonNode source) {
        if (!phase.equals("review_required") || !source.isArray()) return source.deepCopy();
        var ordered = JSON.createArrayNode();
        for (boolean review : List.of(true, false)) {
            for (JsonNode action : source) {
                boolean reviewAction = REVIEW_REASONS.contains(action.path("reason").asText());
                if (reviewAction == review) ordered.add(action.deepCopy());
            }
        }
        for (int index = 0; index < ordered.size(); index++) {
            ((ObjectNode) ordered.get(index)).put("order", index + 1);
        }
        return ordered;
    }

    static String phase(ObjectNode verification) {
        String verdict = verification.path("verdict").asText();
        if (verdict.equals("ready")) return "complete";
        if (verdict.equals("blocked")) return "blocked";
        if (!verification.path("worktree").path("structural_dirty").asBoolean()) return "planned";
        for (JsonNode blocker : verification.path("blockers")) {
            if (REVIEW_REASONS.contains(blocker.path("code").asText())) {
                return "review_required";
            }
        }
        return "verification_required";
    }

    private static String sessionId(Path root, List<String> targets, String change) {
        String identity = root.toAbsolutePath().normalize() + "\n"
                + String.join("\n", targets) + "\n" + change.strip();
        return digest(identity);
    }

    private static String digest(String identity) {
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
