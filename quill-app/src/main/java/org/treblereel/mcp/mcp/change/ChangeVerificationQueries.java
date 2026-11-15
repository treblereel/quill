package org.treblereel.mcp.mcp.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.index.BuildProblemInspector;
import org.treblereel.mcp.index.BuildStatusInspector;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.mcp.support.ToolResponseSupport;
import org.treblereel.mcp.mcp.project.VerificationPlanQueries;
import org.treblereel.mcp.mcp.project.WorktreeStatusQueries;

/** Composes non-mutating evidence about whether a requested change is verified. */
public final class ChangeVerificationQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ContextQueries contexts = new ContextQueries();
    private final WorktreeStatusQueries worktree = new WorktreeStatusQueries();

    public String verifyChange(ReadQueryContext reader, Path projectRoot, List<String> targets, int limit) {
        // Inspect every change; the context/test response budget must not truncate verification scope.
        ObjectNode changes = parse(worktree.getFreshWorktreeStatus(
                reader, projectRoot, null, Integer.MAX_VALUE, 0));
        boolean inferred = targets == null || targets.isEmpty();
        List<String> candidates = inferred ? inferredTargets(changes) : List.copyOf(targets);
        boolean inferenceTruncated = inferred && candidates.size() > 10;
        List<String> effectiveTargets = inferred ? candidates.stream().limit(10).toList() : candidates;
        ObjectNode context = effectiveTargets.isEmpty()
                ? emptyContext(reader) : parse(contexts.getContext(
                        reader, effectiveTargets, false, limit));
        if (context.has("error_code")) return context.toString();
        return compactAnswer(compose(reader, projectRoot, effectiveTargets, limit, context, changes,
                inferred, inferenceTruncated));
    }

    /**
     * Affected tests are stated by what ranks them, and the focused command names them once. In
     * V24 CQ-049 two verify_change answers were 28 and 21 KB; 10 KB of each were the full rows of
     * the same 20 tests and 1 KB more their names again in focused_tests.
     */
    static String compactAnswer(String answer) {
        ObjectNode result = parse(answer);
        if (result.has("error_code") || result.has("error")) return answer;
        if (result.at("/test_evidence/tests") instanceof com.fasterxml.jackson.databind.node.ArrayNode tests) {
            for (int i = 0; i < tests.size(); i++) tests.set(i, AffectedTestRows.row(tests.get(i)));
        }
        // Settled facts are counted, not listed: in V26 CQ-049 a supported build protocol, matching
        // source sets and untracked harness config files took 3 KB of each answer.
        if (result.at("/worktree/changes") instanceof com.fasterxml.jackson.databind.node.ArrayNode changes
                && result.path("worktree") instanceof ObjectNode tree) {
            var structural = JSON.createArrayNode();
            int other = 0;
            for (JsonNode change : changes) {
                if (change.path("structural").asBoolean(true)) structural.add(change);
                else other++;
            }
            if (other > 0) {
                tree.set("changes", structural);
                tree.put("non_structural_changes", other);
            }
        }
        if (result.at("/build/integration") instanceof ObjectNode integration
                && integration.path("protocol").path("status").asText().equals("supported")) {
            integration.remove("protocol");
        }
        if (result.at("/build/compiled_outputs") instanceof ObjectNode outputs
                && outputs.path("source_sets").isArray() && !outputs.path("source_sets").isEmpty()
                && outputs.path("source_sets").valueStream().allMatch(set -> set.path("status").asText().equals("matching"))) {
            outputs.put("source_sets_matching", outputs.path("source_sets").size());
            outputs.remove("source_sets");
        }
        if (result.path("verification_plan") instanceof ObjectNode plan && plan.path("focused_tests").isArray()
                && plan.path("commands").valueStream().anyMatch(command -> command.path("scope").asText().equals("focused"))) {
            plan.put("focused_test_count", plan.path("focused_tests").size());
            plan.remove("focused_tests");
        }
        return result.toString();
    }

    public String verifyChangeWithContext(ReadQueryContext reader, Path projectRoot, List<String> targets,
            int limit, ObjectNode context) {
        ObjectNode changes = parse(worktree.getFreshWorktreeStatus(
                reader, projectRoot, null, Integer.MAX_VALUE, 0));
        return verifyChangeWithContext(reader, projectRoot, targets, limit, context,
                changes, false, false);
    }

    public String verifyChangeWithContext(ReadQueryContext reader, Path projectRoot, List<String> targets,
            int limit, ObjectNode context, ObjectNode changes, boolean inferred,
            boolean inferenceTruncated) {
        return compose(reader, projectRoot, List.copyOf(targets), limit, context, changes,
                inferred, inferenceTruncated);
    }

    private String compose(ReadQueryContext reader, Path projectRoot, List<String> effectiveTargets,
            int limit, ObjectNode context, ObjectNode changes, boolean inferred,
            boolean inferenceTruncated) {
        JsonNode impact = context.path("impacted_tests");
        ObjectNode verificationPlan = new VerificationPlanQueries().plan(
                reader, projectRoot, context.path("contexts"), impact);
        JsonNode scope = verificationPlan.path("verification_scope");
        List<String> modules = scope.path("kind").asText().equals("modules_with_prerequisites")
                ? scope.path("modules").valueStream().map(JsonNode::asText).toList() : null;
        ObjectNode build = parse(BuildStatusInspector.inspect(projectRoot, reader, modules));
        build.set("verification_scope", scope.deepCopy());
        var staleSets = build.path("compiled_outputs").path("source_sets").valueStream()
                .filter(evidence -> evidence.path("status").asText().equals("changed"))
                .filter(evidence -> context.path("contexts").valueStream().anyMatch(target ->
                        target.path("resolution").path("module").asText().equals(evidence.path("module").asText())
                        && VerificationPlanQueries.capturedSourceSet(org.treblereel.mcp.core.BuildSystem.detect(projectRoot),
                                target.path("resolution").path("source_set").asText("main")).equals(evidence.path("source_set").asText())))
                .toList();
        if (!staleSets.isEmpty()) {
            build.put("status", "build_required").put("build_reason", "source_set_inputs_changed")
                    .put("recommended_action", "Compile the requested source sets with the captured profiles using verification_plan");
            ((ObjectNode) build.path("compiled_outputs")).set("stale_source_sets", JSON.valueToTree(staleSets));
        }
        ObjectNode diagnostics = parse(BuildProblemInspector.inspect(
                projectRoot, "all", null, limit, 0));

        ProjectAvailabilityResponses.appendBuildGuidance(build, diagnostics);
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
                "status", "build_reason", "reason", "action", "action_required", "recommended_action",
                "verification_scope", "repository_status", "repository_stale_modules",
                "build_was_started")));
        root.set("diagnostics", compact(diagnostics, List.of(
                "build_status", "build_tool", "finished_at", "capture_scope", "limitations",
                "problems", "showing", "total", "has_more", "recommended_action",
                "build_was_started")));
        root.set("test_evidence", compact(impact, List.of(
                "test_index_coverage", "answer_complete", "limitations", "tests", "showing",
                "total", "has_more")));
        var testCoverage = impact.path("test_index_coverage");
        if (!testCoverage.path("missing_modules").isEmpty() || !testCoverage.path("missing_classpath_modules").isEmpty()) {
            var reason = ProjectRegistry.ReadinessReason.TEST_EVIDENCE_MISSING;
            ((ObjectNode) root.path("test_evidence")).put("reason", reason.code()).put("action", reason.action);
        }
        root.set("verification_plan", verificationPlan);

        String verdict = verdict(context, build, diagnostics, changes, verificationPlan,
                effectiveTargets.isEmpty(), inferenceTruncated);
        ChangedTestExecution.Result changedTests = ChangedTestExecution.inspect(projectRoot, changes);
        if (changedTests.evidence().path("changed_test_classes").asInt() > 0) root.set("changed_tests", changedTests.evidence());
        // A successful compile does not verify tests that were changed and never ran (Apollo AQ-049).
        if (verdict.equals("ready") && changedTests.blocking()) verdict = "partial";
        root.put("verdict", verdict);
        root.put("verified", verdict.equals("ready"));
        ArrayNode blockers = root.putArray("blockers");
        ArrayNode nextActions = root.putArray("next_actions");
        appendGuidance(verdict, context, diagnostics, changes, verificationPlan,
                blockers, nextActions, effectiveTargets.isEmpty(), inferenceTruncated);
        appendChangedTestGuidance(changedTests, verificationPlan, blockers, nextActions);
        root.set("_meta", context.path("_meta").deepCopy());
        return root.toString();
    }

    private static String verdict(ObjectNode context, ObjectNode build,
            ObjectNode diagnostics, ObjectNode changes, ObjectNode verificationPlan,
            boolean noTargets,
            boolean inferenceTruncated) {
        if ("failed".equals(diagnostics.path("build_status").asText())
                || diagnostics.path("total").asInt() > 0) return "blocked";
        String buildStatus = build.path("status").asText();
        String evidenceStatus = diagnostics.path("build_status").asText("unknown");
        if (context.path("_meta").path("stale_reasons").valueStream()
                .anyMatch(reason -> reason.asText().equals("previous_generation"))) return "partial";
        if (buildStatus.equals("build_required") || buildStatus.equals("refresh_pending")
                || buildStatus.equals("index_refresh_required")
                || evidenceStatus.equals("unknown") || evidenceStatus.equals("unavailable")) {
            return "needs_build";
        }
        if (noTargets || inferenceTruncated
                || !verificationPlan.path("module_selection_complete").asBoolean(true)
                || !verificationPlan.path("profile_selection_complete").asBoolean(true)
                || !context.path("answer_complete").asBoolean()
                || !context.path("impacted_tests").path("answer_complete").asBoolean(true)
                || context.path("_meta").path("structure_stale").asBoolean()
                || changes.path("has_more").asBoolean()) return "partial";
        return "ready";
    }

    private static void appendGuidance(String verdict, ObjectNode context,
            ObjectNode diagnostics, ObjectNode changes, ObjectNode verificationPlan,
            ArrayNode blockers, ArrayNode actions, boolean noTargets,
            boolean inferenceTruncated) {
        if (!verdict.equals("blocked") && context.path("_meta").path("stale_reasons").valueStream()
                .anyMatch(reason -> reason.asText().equals("previous_generation"))) {
            blockers.addObject().put("code", "INDEX_REFRESH_PENDING")
                    .put("message", "The answer uses the previous prepared generation; freshness is not verified");
            action(actions, "wait_for_refresh", "INDEX_REFRESH_PENDING")
                    .put("description", "Retry the same query after background refresh; inspect project warnings if refresh failed");
            return;
        }
        boolean runnerAvailable = verificationPlan.path("runner").path("available").asBoolean();
        if (!verificationPlan.path("profile_selection_complete").asBoolean(true)) {
            blockers.addObject().put("code", "PROFILE_SELECTION_UNRESOLVED").put("message", "Requested source sets have incompatible or unavailable profile evidence");
            action(actions, "inspect_build_profiles", "PROFILE_SELECTION_UNRESOLVED").put("evidence", "verification_plan.profiles_by_source_set");
        }
        if (verdict.equals("blocked") && onlyTestFailures(diagnostics)) {
            blockers.addObject().put("code", "TESTS_FAILED")
                    .put("message", "The last captured build ran tests and they failed or could not be discovered");
            action(actions, "fix_failing_tests", "TESTS_FAILED").put("evidence", "diagnostics.problems")
                    .put("description", "The test phase ran, so the sources compiled; read the test reports the problem"
                            + " names and fix the failing tests or test discovery, not compiler diagnostics");
            String testScope = testCommandScope(verificationPlan);
            if (runnerAvailable && testScope != null) {
                externalCommand(actions, testScope, "TESTS_FAILED");
            } else {
                verificationCommand(actions, runnerAvailable, "TESTS_FAILED");
            }
        } else if (verdict.equals("blocked") && onlyCategory(diagnostics, "environment")) {
            blockers.addObject().put("code", "BUILD_ENVIRONMENT")
                    .put("message", "The last captured build failed on dependencies, network or file access before compiling");
            action(actions, "fix_build_environment", "BUILD_ENVIRONMENT").put("evidence", "diagnostics.problems")
                    .put("description", "The build environment failed, not the sources: make the named dependencies,"
                            + " network or files available, or say the build could not run here, then retry");
            verificationCommand(actions, runnerAvailable, "BUILD_ENVIRONMENT");
        } else if (verdict.equals("blocked")) {
            blockers.addObject().put("code", "BUILD_FAILED")
                    .put("message", "The last captured build failed or reported diagnostics");
            action(actions, "fix_diagnostics", "BUILD_FAILED")
                    .put("evidence", "diagnostics.problems");
            verificationCommand(actions, runnerAvailable, "BUILD_FAILED");
        }
        if (verdict.equals("needs_build")) {
            blockers.addObject().put("code", "BUILD_EVIDENCE_REQUIRED")
                    .put("message", "Fresh successful build evidence is not available");
            verificationCommand(actions, runnerAvailable, "BUILD_EVIDENCE_REQUIRED");
        }
        if (context.path("unresolved_target_count").asInt() > 0) {
            blockers.addObject().put("code", "UNRESOLVED_TARGETS")
                    .put("message", "Some requested targets are absent from the evidence");
            action(actions, "resolve_targets", "UNRESOLVED_TARGETS")
                    .put("evidence", "unresolved_targets");
        }
        if (noTargets) {
            blockers.addObject().put("code", "NO_CHANGE_TARGETS")
                    .put("message", "No explicit targets or dirty JVM source files were available");
            action(actions, "supply_targets", "NO_CHANGE_TARGETS")
                    .put("tool", "verify_change");
        }
        if (inferenceTruncated) {
            blockers.addObject().put("code", "TARGET_INFERENCE_TRUNCATED")
                    .put("message", "More than 10 dirty JVM files require verification");
            action(actions, "verify_remaining_targets", "TARGET_INFERENCE_TRUNCATED")
                    .put("tool", "verify_change");
        }
        if (!context.path("impacted_tests").path("answer_complete").asBoolean(true)) {
            blockers.addObject().put("code", "INCOMPLETE_TEST_COVERAGE")
                    .put("message", "The ranked affected-test set is incomplete");
            ObjectNode fallback = action(actions, "inspect_test_evidence",
                    "INCOMPLETE_TEST_COVERAGE");
            fallback.put("evidence", "test_evidence");
            fallback.put("description", "Inspect affected-test limitations; compile test sources "
                    + "and refresh stale evidence before deciding whether test execution is needed");
        }
        if (context.path("_meta").path("structure_stale").asBoolean()) {
            blockers.addObject().put("code", "STALE_INDEX")
                    .put("message", "The index predates structural worktree changes");
            action(actions, "refresh_index", "STALE_INDEX")
                    .put("after", "successful_external_build");
        }
        if (changes.path("has_more").asBoolean()) {
            blockers.addObject().put("code", "WORKTREE_TRUNCATED")
                    .put("message", "The worktree change list exceeds the requested limit");
            action(actions, "inspect_remaining_worktree", "WORKTREE_TRUNCATED")
                    .put("tool", "get_worktree_status");
        }
        if (!verificationPlan.path("module_selection_complete").asBoolean(true)) {
            blockers.addObject().put("code", "MODULE_SELECTION_INCOMPLETE")
                    .put("message", "Some indexed modules do not map to build-system modules");
            action(actions, "inspect_modules", "MODULE_SELECTION_INCOMPLETE")
                    .put("evidence", "verification_plan.unresolved_modules");
        }
        if (verdict.equals("blocked") || verdict.equals("needs_build")) {
            toolCall(actions, "verify_change", runnerAvailable
                    ? "command_success" : "runner_available_and_command_success");
        }
        if (verdict.equals("ready")) action(actions, "complete", "VERIFIED");
        if (diagnostics.path("build_status").asText().equals("success")
                && !verdict.equals("ready") && actions.isEmpty()) {
            action(actions, "inspect_partial_evidence", "PARTIAL_EVIDENCE");
        }
    }

    /** V23 CQ-049: archunit failed test discovery and the answer said to fix compiler diagnostics with test-compile. */
    private static boolean onlyTestFailures(ObjectNode diagnostics) {
        return onlyCategory(diagnostics, "test_failure");
    }

    private static boolean onlyCategory(ObjectNode diagnostics, String category) {
        JsonNode problems = diagnostics.path("problems");
        return !problems.isEmpty() && !diagnostics.path("has_more").asBoolean()
                && problems.valueStream().allMatch(problem -> problem.path("category").asText().equals(category));
    }

    /** Recompiling cannot show that failed tests pass; the retry runs tests, the focused ones when ranked. */
    private static void appendChangedTestGuidance(ChangedTestExecution.Result changedTests,
            ObjectNode verificationPlan, ArrayNode blockers, ArrayNode actions) {
        if (!changedTests.failed().isEmpty()) {
            blockers.addObject().put("code", "CHANGED_TESTS_FAILED")
                    .put("message", "Changed test classes failed in their latest run: " + String.join(", ", changedTests.failed()));
        }
        if (!changedTests.notExecuted().isEmpty()) {
            blockers.addObject().put("code", "CHANGED_TESTS_NOT_EXECUTED")
                    .put("message", "Changed test classes have not run since they changed: " + String.join(", ", changedTests.notExecuted())
                            + ". A compiling build does not show they pass; run them, or report them as not run");
        }
        if (!changedTests.blocking()) return;
        String code = changedTests.failed().isEmpty() ? "CHANGED_TESTS_NOT_EXECUTED" : "CHANGED_TESTS_FAILED";
        String testScope = testCommandScope(verificationPlan);
        if (testScope != null && verificationPlan.path("runner").path("available").asBoolean()) externalCommand(actions, testScope, code);
        action(actions, "run_changed_tests", code).put("evidence", "changed_tests.classes")
                .put("description", "Run the listed test classes; if they cannot run here, say so in the answer instead of reporting the change as verified");
    }

    private static String testCommandScope(ObjectNode verificationPlan) {
        List<String> scopes = verificationPlan.path("commands").valueStream()
                .filter(command -> command.path("executes_tests").asBoolean())
                .map(command -> command.path("scope").asText()).toList();
        return scopes.contains("focused") ? "focused" : scopes.stream().findFirst().orElse(null);
    }

    private static ObjectNode action(ArrayNode actions, String action, String reason) {
        ObjectNode result = actions.addObject();
        result.put("order", actions.size());
        result.put("action", action);
        result.put("reason", reason);
        return result;
    }

    private static void externalCommand(ArrayNode actions, String scope, String reason) {
        ObjectNode result = action(actions, "run_external_command", reason);
        result.put("command_scope", scope);
        result.put("command_source", "verification_plan.commands");
        result.put("working_directory_source", "verification_plan.working_directory");
        result.put("executed_by_quill", false);
    }

    private static void verificationCommand(
            ArrayNode actions, boolean runnerAvailable, String reason) {
        if (runnerAvailable) {
            externalCommand(actions, "quick_compile", reason);
            return;
        }
        ObjectNode result = action(actions, "make_build_runner_available", reason);
        result.put("evidence", "verification_plan.runner.unavailable_reason");
        result.put("executed_by_quill", false);
    }

    private static void toolCall(ArrayNode actions, String tool, String after) {
        ObjectNode result = action(actions, "call_tool", "RECHECK_EVIDENCE");
        result.put("tool", tool);
        result.put("after", after);
    }

    static List<String> inferredTargets(ObjectNode changes) {
        List<String> result = new ArrayList<>();
        for (JsonNode change : changes.path("changes")) {
            String path = change.path("path").asText();
            if ((path.endsWith(".java") || path.endsWith(".kt") || path.endsWith(".groovy"))
                    && !result.contains(path)) result.add(path);
        }
        return List.copyOf(result);
    }

    private static ObjectNode emptyContext(ReadQueryContext reader) {
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
        ToolResponseSupport.appendMeta(context, reader, 0);
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
