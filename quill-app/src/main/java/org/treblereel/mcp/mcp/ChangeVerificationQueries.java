package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.BuildProblemInspector;
import org.treblereel.mcp.command.BuildStatusInspector;

/** Composes non-mutating evidence about whether a requested change is verified. */
final class ChangeVerificationQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContextQueries contexts = new ContextQueries();
    private final WorktreeStatusQueries worktree = new WorktreeStatusQueries();

    String verifyChange(Jdbi jdbi, Path projectRoot, List<String> targets, int limit) {
        ObjectNode changes = parse(worktree.getWorktreeStatus(
                jdbi, projectRoot, null, limit, 0));
        boolean inferred = targets == null || targets.isEmpty();
        List<String> candidates = inferred ? inferredTargets(changes) : List.copyOf(targets);
        boolean inferenceTruncated = inferred && candidates.size() > 10;
        List<String> effectiveTargets = candidates.stream().limit(10).toList();
        ObjectNode context = effectiveTargets.isEmpty()
                ? emptyContext(jdbi) : parse(contexts.getContext(
                        jdbi, effectiveTargets, false, limit));
        if (context.has("error_code")) return context.toString();
        ObjectNode build = parse(BuildStatusInspector.inspect(projectRoot, jdbi));
        ObjectNode diagnostics = parse(BuildProblemInspector.inspect(
                projectRoot, "all", null, limit, 0));

        ObjectNode root = JSON.createObjectNode();
        root.put("target_source", inferred ? "dirty_worktree" : "explicit");
        root.put("inferred_target_count", inferred ? effectiveTargets.size() : 0);
        root.put("target_inference_truncated", inferenceTruncated);
        copy(context, root, "requested_targets");
        copy(context, root, "resolved_target_count");
        copy(context, root, "unresolved_target_count");
        copy(context, root, "unresolved_targets");
        root.set("worktree", compact(changes, List.of(
                "branch", "indexed_commit", "current_commit", "commit_stale", "dirty",
                "structural_dirty", "counts_by_status", "changes", "showing", "total",
                "has_more")));
        root.set("build", compact(build, List.of(
                "build_system", "integration", "compiled_outputs", "index", "freshness",
                "status", "build_reason", "action_required", "recommended_action",
                "build_was_started")));
        root.set("diagnostics", compact(diagnostics, List.of(
                "build_status", "build_tool", "finished_at", "capture_scope", "limitations",
                "problems", "showing", "total", "has_more", "recommended_action",
                "build_was_started")));
        JsonNode impact = context.path("impacted_tests");
        root.set("test_evidence", compact(impact, List.of(
                "test_index_coverage", "answer_complete", "limitations", "tests", "showing",
                "total", "has_more")));
        root.set("verification_plan", new VerificationPlanQueries().plan(
                projectRoot, context.path("contexts"), impact));

        String verdict = verdict(context, build, diagnostics, changes,
                effectiveTargets.isEmpty(), inferenceTruncated);
        root.put("verdict", verdict);
        root.put("verified", verdict.equals("ready"));
        ArrayNode blockers = root.putArray("blockers");
        ArrayNode nextActions = root.putArray("next_actions");
        appendGuidance(verdict, context, build, diagnostics, changes, blockers, nextActions,
                effectiveTargets.isEmpty(), inferenceTruncated);
        root.set("_meta", context.path("_meta").deepCopy());
        return root.toString();
    }

    private static String verdict(ObjectNode context, ObjectNode build,
            ObjectNode diagnostics, ObjectNode changes, boolean noTargets,
            boolean inferenceTruncated) {
        if ("failed".equals(diagnostics.path("build_status").asText())
                || diagnostics.path("total").asInt() > 0) return "blocked";
        String buildStatus = build.path("status").asText();
        String evidenceStatus = diagnostics.path("build_status").asText("unknown");
        if (buildStatus.equals("build_required") || buildStatus.equals("refresh_pending")
                || buildStatus.equals("index_refresh_required")
                || evidenceStatus.equals("unknown") || evidenceStatus.equals("unavailable")) {
            return "needs_build";
        }
        if (noTargets || inferenceTruncated || !context.path("answer_complete").asBoolean()
                || !context.path("impacted_tests").path("answer_complete").asBoolean(true)
                || context.path("_meta").path("structure_stale").asBoolean()
                || changes.path("has_more").asBoolean()) return "partial";
        return "ready";
    }

    private static void appendGuidance(String verdict, ObjectNode context, ObjectNode build,
            ObjectNode diagnostics, ObjectNode changes, ArrayNode blockers, ArrayNode actions,
            boolean noTargets, boolean inferenceTruncated) {
        if (verdict.equals("blocked")) {
            blockers.addObject().put("code", "BUILD_FAILED")
                    .put("message", "The last captured build failed or reported diagnostics");
            actions.add("Fix captured diagnostics and run the focused build again");
        }
        if (verdict.equals("needs_build")) {
            blockers.addObject().put("code", "BUILD_EVIDENCE_REQUIRED")
                    .put("message", "Fresh successful build evidence is not available");
            String recommendation = build.path("recommended_action").asText();
            actions.add(recommendation.isBlank()
                    ? "Run the owning modules' build and retry verification" : recommendation);
        }
        if (context.path("unresolved_target_count").asInt() > 0) {
            blockers.addObject().put("code", "UNRESOLVED_TARGETS")
                    .put("message", "Some requested targets are absent from the evidence");
            actions.add("Resolve or correct every requested target");
        }
        if (noTargets) {
            blockers.addObject().put("code", "NO_CHANGE_TARGETS")
                    .put("message", "No explicit targets or dirty JVM source files were available");
            actions.add("Pass changed classes or source paths explicitly");
        }
        if (inferenceTruncated) {
            blockers.addObject().put("code", "TARGET_INFERENCE_TRUNCATED")
                    .put("message", "More than 10 dirty JVM files require verification");
            actions.add("Verify additional targets in a separate request");
        }
        if (!context.path("impacted_tests").path("answer_complete").asBoolean(true)) {
            blockers.addObject().put("code", "INCOMPLETE_TEST_COVERAGE")
                    .put("message", "The ranked affected-test set is incomplete");
            actions.add("Use owning-module tests as a fallback for missing test-index coverage");
        }
        if (context.path("_meta").path("structure_stale").asBoolean()) {
            blockers.addObject().put("code", "STALE_INDEX")
                    .put("message", "The index predates structural worktree changes");
            actions.add("Compile the structural changes and refresh the Quill index");
        }
        if (changes.path("has_more").asBoolean()) {
            blockers.addObject().put("code", "WORKTREE_TRUNCATED")
                    .put("message", "The worktree change list exceeds the requested limit");
            actions.add("Request the remaining worktree changes before final verification");
        }
        if (verdict.equals("ready")) actions.add("No blocking evidence found");
        if (diagnostics.path("build_status").asText().equals("success")
                && !verdict.equals("ready") && actions.isEmpty()) {
            actions.add("Address partial evidence before declaring the change verified");
        }
    }

    private static List<String> inferredTargets(ObjectNode changes) {
        List<String> result = new ArrayList<>();
        for (JsonNode change : changes.path("changes")) {
            String path = change.path("path").asText();
            if ((path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy"))
                    && !result.contains(path)) result.add(path);
        }
        return List.copyOf(result);
    }

    private static ObjectNode emptyContext(Jdbi jdbi) {
        ObjectNode context = JSON.createObjectNode();
        context.putArray("requested_targets");
        context.put("resolved_target_count", 0);
        context.put("unresolved_target_count", 0);
        context.putArray("unresolved_targets");
        context.put("answer_complete", false);
        ObjectNode impact = context.putObject("impacted_tests");
        impact.put("answer_complete", false);
        impact.putArray("limitations").add("No change targets were available for test-impact analysis");
        impact.putArray("tests");
        ToolResponseSupport.appendMeta(context, jdbi, 0);
        return context;
    }

    private static ObjectNode compact(JsonNode source, List<String> fields) {
        ObjectNode result = JSON.createObjectNode();
        fields.forEach(field -> copy(source, result, field));
        return result;
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
            throw new IllegalStateException("Cannot compose change verification", error);
        }
    }
}
