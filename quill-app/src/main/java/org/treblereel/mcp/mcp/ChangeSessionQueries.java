package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;

/** Builds a compact, stateless snapshot of the change workflow. */
final class ChangeSessionQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String EXTERNAL = "external_checkout";
    /** In V23 CQ-049 Codex edited a disposable clone 3 of 3 times and asked again 2-4 times, each answer still planned. */
    static final String OTHER_CHECKOUT = "Editing another checkout, such as a disposable clone? Quill cannot observe it;"
            + " after editing pass checkout=<its path> once for the final answer.";
    private static final Set<String> REVIEW_REASONS = Set.of(
            "UNRESOLVED_TARGETS", "INCOMPLETE_TEST_COVERAGE",
            "TARGET_INFERENCE_TRUNCATED", "WORKTREE_TRUNCATED",
            "MODULE_SELECTION_INCOMPLETE");
    private final ContextQueries contexts = new ContextQueries();
    private final ChangePlanQueries plans = new ChangePlanQueries();
    private final ChangeVerificationQueries verification = new ChangeVerificationQueries();
    private final WorktreeStatusQueries worktree = new WorktreeStatusQueries();

    String snapshot(ReadQueryContext reader, Path projectRoot, List<String> targets,
            String change, int limit) {
        return snapshot(reader, projectRoot, targets, change, limit, "full", "all");
    }

    String snapshot(ReadQueryContext reader, Path projectRoot, List<String> targets,
            String change, int limit, String detail) {
        return snapshot(reader, projectRoot, targets, change, limit, detail, "all");
    }

    String snapshot(ReadQueryContext reader, Path projectRoot, List<String> targets,
            String change, int limit, String detail, String view) {
        return snapshot(reader, projectRoot, targets, change, limit, detail, view, null);
    }

    String snapshot(ReadQueryContext reader, Path projectRoot, List<String> targets,
            String change, int limit, String detail, String view, String checkout) {
        String normalizedDetail = detail == null ? "summary" : detail.strip().toLowerCase();
        if (!normalizedDetail.equals("summary") && !normalizedDetail.equals("full")) {
            return errorResponse("Invalid detail: expected summary or full");
        }
        String normalizedView = view == null ? "auto" : view.strip().toLowerCase();
        if (!List.of("auto", "plan", "verification", "all").contains(normalizedView)) {
            return errorResponse("Invalid view: expected auto, plan, verification, or all");
        }
        if (change == null || change.isBlank()) {
            return plans.planChange(reader, projectRoot, targets, change, limit);
        }
        Path external;
        try {
            external = externalCheckout(projectRoot, checkout);
        } catch (IllegalArgumentException missing) {
            return errorResponse(missing.getMessage());
        }
        // Inspect every change; the context/test response budget must not truncate verification scope.
        ObjectNode changes = parse(worktree.getFreshWorktreeStatus(
                reader, projectRoot, null, Integer.MAX_VALUE, 0));
        boolean inferred = targets == null || targets.isEmpty();
        List<String> candidates = inferred
                ? ChangeVerificationQueries.inferredTargets(changes) : List.copyOf(targets);
        boolean inferenceTruncated = inferred && candidates.size() > 10;
        List<String> effectiveTargets = inferred ? candidates.stream().limit(10).toList() : candidates;
        ObjectNode context = parse(contexts.getContext(
                reader, effectiveTargets, true, limit));
        if (context.has("error_code")) return context.toString();
        ObjectNode plan = plans.planFromContext(reader, projectRoot, context, change);
        ArrayNode memberCallers = memberCallers(reader, canonicalTargets(context), change, limit);
        ObjectNode verify = parse(verification.verifyChangeWithContext(
                reader, projectRoot, effectiveTargets, limit, context, changes,
                inferred, inferenceTruncated));
        if (verify.has("error_code")) return verify.toString();
        List<String> canonicalTargets = canonicalTargets(context);

        ObjectNode result = JSON.createObjectNode();
        result.put("schema_version", 1);
        String stableSessionId = sessionId(projectRoot, canonicalTargets, change);
        result.put("session_id", stableSessionId);
        result.put("state_model", "stateless_snapshot");
        String currentPhase = external != null ? EXTERNAL : phase(verify);
        String effectiveView = normalizedView.equals("auto")
                ? (currentPhase.equals("planned") || external != null ? "plan" : "verification")
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
        // Stated in every view: in V25 CQ-049 Claude asked after editing and the plan view never showed.
        if (!memberCallers.isEmpty()) result.set("member_callers", memberCallers);

        boolean includePlan = effectiveView.equals("plan") || effectiveView.equals("all");
        boolean planned = currentPhase.equals("planned");
        // The indexed checkout's verification says nothing about edits made elsewhere.
        boolean includeVerification = external == null && (effectiveView.equals("verification")
                || effectiveView.equals("all"));
        ObjectNode checklist = reviewChecklist(plan, verify);
        result.set("phase_gate", phaseGate(currentPhase, checklist));
        if (includePlan) {
            result.set("plan", normalizedDetail.equals("full")
                    ? fullPlan(plan) : summaryPlan(plan));
        }
        if (includeVerification) {
            result.set("verification", normalizedDetail.equals("full")
                    ? fullVerification(verify) : summaryVerification(verify));
            // Before any edit only the compile command is actionable; test commands follow the edit.
            result.set("verification_plan", normalizedDetail.equals("full") && !planned
                    ? verify.path("verification_plan").deepCopy()
                    : summaryVerificationPlan(verify.path("verification_plan")));
            result.set("review_checklist", compactChecklist(checklist));
            result.set("verification_receipt",
                    verificationReceipt(stableSessionId, currentPhase, verify, checklist));
        }
        if (external != null) {
            result.set("external_checkout", externalSection(projectRoot, external, verify));
            result.set("verification_receipt", externalReceipt(stableSessionId, external));
        }
        ArrayNode actions = identifiedActions(stableSessionId, external != null
                ? externalActions(external)
                : currentPhase.equals("planned")
                ? plan.path("sequence").deepCopy()
                : phaseActions(currentPhase, verify.path("next_actions")));
        result.set("next_actions", actions);
        ObjectNode directive = external != null ? externalDirective(actions, external)
                : directive(currentPhase, actions, plan, verify);
        result.set("directive", directive);
        pointFirstActionAtDirective(actions, directive);
        if (directive.path("primary_action") instanceof ObjectNode primary) {
            trimSnapshotTests(primary);
            if (result.path("verification_receipt") instanceof ObjectNode receipt) dropRestatedRecommendation(receipt, primary);
        }
        AffectedTestRows.compactIn(result);
        if (verify.has("_meta")) result.set("_meta", compactMeta(verify.get("_meta"), includeVerification));
        var omitted = result.putArray("omitted_sections");
        if (!includePlan) omitted.add("plan");
        if (!includeVerification && external == null) {
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
                    .add("verification.test_evidence.tests");
        }
        if ((normalizedDetail.equals("summary") || planned) && includeVerification) {
            omitted.add("verification_plan.non_quick_compile_commands");
        }
        if (normalizedDetail.equals("summary")) {
            ToolResponseSupport.summarizeInventories(result).forEach(omitted::add);
            var request = result.putObject("full_evidence_request");
            request.put("tool", "change_session");
            var arguments = request.putObject("arguments");
            arguments.set("targets", JSON.valueToTree(effectiveTargets));
            arguments.put("change", change.strip()).put("limit", limit).put("detail", "full").put("view", "all");
            if (external != null) arguments.put("checkout", external.toString());
        }
        return result.toString();
    }

    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_$][\\w$]{2,}");
    private static final int MAX_NAMED_METHODS = 3;

    /**
     * Methods of a target class that the change names, with the methods calling them. A plan
     * lists dependent classes, but callers inside the class itself were never stated: in V24
     * CQ-049 two of three answers named the three call sites of parse by line only and missed
     * resolve.
     */
    static ArrayNode memberCallers(ReadQueryContext reader, List<String> classes, String change, int limit) {
        ArrayNode result = JSON.createArrayNode();
        Set<String> words = new java.util.HashSet<>();
        IDENTIFIER.matcher(change).results().forEach(match -> words.add(match.group()));
        SymbolUsageQueries usages = new SymbolUsageQueries();
        for (String type : classes) {
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, type);
            if (lookup.error() != null) continue;
            java.util.Map<String, List<String>> named = new java.util.LinkedHashMap<>();
            for (var member : reader.symbols.findClassMembers(lookup.cls().id())) {
                String name = member.name();
                if (member.descriptor() != null && member.descriptor().startsWith("(") && !name.startsWith("<")
                        && words.contains(name)) named.computeIfAbsent(name, key -> new ArrayList<>()).add(member.descriptor());
            }
            for (var entry : named.entrySet()) for (String descriptor : entry.getValue()) {
                if (result.size() == MAX_NAMED_METHODS) return result;
                String name = entry.getKey();
                boolean overloaded = entry.getValue().size() > 1;
                JsonNode found = parse(usages.findSymbolUsages(reader, lookup.cls().className(), name, "METHOD",
                        overloaded ? descriptor : null, null, limit, 0));
                if (found.has("error") || found.has("error_code")) continue;
                ObjectNode row = result.addObject().put("class", lookup.cls().className()).put("method", name);
                if (overloaded) row.put("descriptor", descriptor);
                ArrayNode callers = row.putArray("callers");
                for (JsonNode usage : found.path("usages")) {
                    JsonNode caller = usage.path("caller");
                    ObjectNode site = callers.addObject().put("class", caller.path("class").asText())
                            .put("method", caller.path("method").asText());
                    if (usage.has("evidence_lines")) site.set("lines", usage.get("evidence_lines").deepCopy());
                }
                copy(found, row, "total");
                copy(found, row, "has_more");
            }
        }
        return withContextMethodsFolded(result);
    }

    /**
     * A named method that calls another named method is where the change happens, not what changes:
     * in V28 CQ-049 the change said "call sites in resolve()" and the 15 test methods calling resolve
     * took 1.5 KB. Its callers become a count, and each calling method is stated once with all its lines.
     */
    private static ArrayNode withContextMethodsFolded(ArrayNode rows) {
        java.util.Map<String, java.util.List<String>> callerOf = new java.util.LinkedHashMap<>();
        for (JsonNode row : rows) {
            for (JsonNode caller : row.path("callers")) {
                callerOf.computeIfAbsent(caller.path("class").asText() + "#" + caller.path("method").asText(),
                        key -> new ArrayList<>()).add(row.path("method").asText());
            }
        }
        ArrayNode result = JSON.createArrayNode();
        for (JsonNode source : rows) {
            ObjectNode row = source.deepCopy();
            var targets = callerOf.get(row.path("class").asText() + "#" + row.path("method").asText());
            if (targets != null && !targets.contains(row.path("method").asText())) {
                ObjectNode context = JSON.createObjectNode().put("class", row.path("class").asText())
                        .put("method", row.path("method").asText())
                        .put("role", "calls " + String.join(", ", new java.util.LinkedHashSet<>(targets)) + "; its own callers are not part of the change")
                        .put("callers_total", row.path("callers").size());
                result.add(context);
                continue;
            }
            java.util.Map<String, ObjectNode> merged = new java.util.LinkedHashMap<>();
            for (JsonNode caller : row.path("callers")) {
                String key = caller.path("class").asText() + "#" + caller.path("method").asText();
                ObjectNode site = merged.get(key);
                if (site == null) {
                    merged.put(key, ((ObjectNode) caller).deepCopy());
                    continue;
                }
                java.util.TreeSet<Integer> lines = new java.util.TreeSet<>();
                site.path("lines").forEach(line -> lines.add(line.asInt()));
                caller.path("lines").forEach(line -> lines.add(line.asInt()));
                ArrayNode union = site.putArray("lines");
                lines.forEach(union::add);
            }
            ArrayNode callers = row.putArray("callers");
            merged.values().forEach(callers::add);
            result.add(row);
        }
        return result;
    }

    static final int SNAPSHOT_TESTS = 5;

    /** Agents ran -Dtest only for tests ranked 1-3 of the 20 a V24-V28 snapshot carried (39 runs). */
    static void trimSnapshotTests(ObjectNode primary) {
        if (!(primary.path("evidence_snapshot") instanceof ObjectNode snapshot)
                || !(snapshot.path("tests") instanceof ArrayNode tests) || tests.size() <= SNAPSHOT_TESTS) return;
        int total = tests.size();
        while (tests.size() > SNAPSHOT_TESTS) tests.remove(tests.size() - 1);
        snapshot.put("tests_total", total);
        snapshot.put("more_tests_from", "find_impacted_tests");
    }

    /**
     * Codex prints the checklist (V21-V23): required items keep their label, advisory items their
     * evidence, and clear items, which ask nothing, become a count. The phase gate reads the whole one.
     */
    static ObjectNode compactChecklist(ObjectNode checklist) {
        ObjectNode result = checklist.deepCopy();
        ArrayNode items = result.putArray("items");
        int clear = 0;
        for (JsonNode item : checklist.path("items")) {
            String status = item.path("status").asText();
            if (status.equals("clear")) { clear++; continue; }
            items.add(compact(item, status.equals("required")
                    ? List.of("id", "label", "status", "evidence", "evidence_count", "reason_code")
                    : List.of("id", "status", "evidence", "evidence_count")));
        }
        result.put("clear_count", clear);
        return result;
    }

    /** The receipt's recommendation restates the directive's own command when it carries one. */
    static void dropRestatedRecommendation(ObjectNode receipt, ObjectNode primary) {
        JsonNode argv = receipt.path("recommendation").path("argv");
        if (receipt.path("verified").asBoolean() || !argv.isArray() || argv.isEmpty()) return;
        for (String field : List.of("command", "preparation_command", "retry_command")) {
            if (primary.path(field).path("argv").equals(argv)) {
                receipt.remove("recommendation");
                return;
            }
        }
    }

    private static final Set<String> META_KEPT = Set.of("index_id", "structure_stale", "stale_warning", "stale_scope",
            "answer_confidence", "recommended_action", "stale_reasons", "commit_stale", "worktree_dirty",
            "limitations_stated_earlier", "partial", "warnings");

    /**
     * What judges the answer stays; commits, times and file counts go, and stale reasons go when
     * verification.build.freshness states them. V28 CQ-049 answers carried 700 bytes of _meta.
     */
    static ObjectNode compactMeta(JsonNode meta, boolean verificationStated) {
        ObjectNode result = JSON.createObjectNode();
        meta.propertyStream().filter(entry -> META_KEPT.contains(entry.getKey()))
                .filter(entry -> !(verificationStated && entry.getKey().equals("stale_reasons")))
                .filter(entry -> !entry.getKey().equals("commit_stale") || entry.getValue().asBoolean())
                .forEach(entry -> result.set(entry.getKey(), entry.getValue()));
        return result;
    }

    /**
     * The first action is stated once, in the directive: Codex prints only the directive, so a
     * pointer there (as the catalog's compaction made it) left it without its action.
     */
    static void pointFirstActionAtDirective(ArrayNode actions, ObjectNode directive) {
        if (actions.isEmpty() || !(directive.path("primary_action") instanceof ObjectNode primary)) return;
        JsonNode first = actions.get(0);
        boolean restated = first.propertyStream().allMatch(entry -> entry.getValue().equals(primary.get(entry.getKey())));
        if (restated) actions.set(0, JSON.createObjectNode().put("same_as", "/directive/primary_action"));
    }

    /** Null for the indexed checkout itself; a missing directory is an argument error. */
    static Path externalCheckout(Path projectRoot, String checkout) {
        if (checkout == null || checkout.isBlank()) return null;
        Path candidate = projectRoot.resolve(checkout.strip()).normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("checkout not found: " + candidate
                    + "; pass the directory that holds the edited sources");
        }
        try {
            Path real = candidate.toRealPath();
            return real.equals(projectRoot.toRealPath()) ? null : real;
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("checkout cannot be read: " + candidate);
        }
    }

    private static ObjectNode externalSection(Path projectRoot, Path external, ObjectNode verify) {
        ObjectNode section = JSON.createObjectNode();
        section.put("path", external.toString());
        section.put("covered_by_quill", false);
        section.put("reason", "Quill indexes and observes only " + projectRoot.toAbsolutePath().normalize()
                + "; edits, builds and tests in another checkout are invisible to it");
        var commands = section.putArray("commands");
        Set<String> roots = new java.util.LinkedHashSet<>();
        roots.add(projectRoot.toAbsolutePath().normalize().toString());
        try {
            roots.add(projectRoot.toRealPath().toString());
        } catch (IOException ignored) {
            // The normalized path is enough to rewrite the commands.
        }
        for (JsonNode source : verify.path("verification_plan").path("commands")) {
            String scope = source.path("scope").asText();
            if (!scope.equals("quick_compile") && !scope.equals("focused")) continue;
            ObjectNode command = JSON.createObjectNode();
            command.put("scope", scope);
            var argv = command.putArray("argv");
            for (JsonNode argument : source.path("argv")) argv.add(relocated(argument.asText(), roots, external));
            command.put("working_directory", external.toString());
            copy(source, command, "executes_tests");
            commands.add(command);
        }
        return section;
    }

    private static String relocated(String argument, Set<String> roots, Path external) {
        for (String root : roots) {
            if (argument.equals(root) || argument.startsWith(root + "/")) {
                return external + argument.substring(root.length());
            }
        }
        return argument;
    }

    private static ObjectNode externalReceipt(String sessionId, Path external) {
        ObjectNode receipt = JSON.createObjectNode();
        receipt.put("schema_version", 1);
        receipt.put("receipt_id", digest(sessionId + "\n" + external));
        receipt.put("session_id", sessionId);
        receipt.put("phase", EXTERNAL);
        receipt.put("verdict", "not_covered");
        receipt.put("verified", false);
        receipt.putArray("reason_codes").add("EXTERNAL_CHECKOUT");
        return receipt;
    }

    private static ArrayNode externalActions(Path external) {
        ArrayNode actions = JSON.createArrayNode();
        actions.addObject().put("order", 1).put("action", "verify_in_checkout")
                .put("description", "Build and test in " + external
                        + " with external_checkout.commands, then report that Quill evidence does not cover that checkout");
        return actions;
    }

    private static ObjectNode externalDirective(ArrayNode actions, Path external) {
        ObjectNode directive = JSON.createObjectNode();
        directive.put("schema_version", 1);
        directive.put("status", EXTERNAL);
        directive.put("headline", "Quill evidence does not cover edits in " + external);
        directive.put("action_count", actions.size());
        ObjectNode primary = actions.get(0).deepCopy();
        primary.put("evidence", "external_checkout.commands");
        primary.put("working_directory", external.toString());
        primary.put("executed_by_quill", false);
        directive.set("primary_action", primary);
        directive.put("final_answer", "This is the final change_session answer for that checkout;"
                + " asking again returns the same answer.");
        return directive;
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
                "total", "limit", "offset", "has_more", "next_offset",
                "next_page_request")));
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
                "verification_scope", "repository_status", "repository_stale_modules",
                "build_reason", "reason", "action", "action_required", "recommended_action")));
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
                "test_index_coverage", "reason", "action", "answer_complete", "limitations",
                "showing", "total", "has_more")));
        return result;
    }

    private static ObjectNode summaryVerificationPlan(JsonNode plan) {
        ObjectNode result = compact(plan, List.of("build_system", "working_directory",
                "runner", "requested_modules", "modules", "command_modules", "quick_compile_modules",
                "unresolved_modules", "module_selection_complete", "active_profiles", "profile_selection_complete", "profiles_by_source_set", "verification_scope",
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

        boolean previousGeneration = !verify.path("verdict").asText().equals("blocked")
                && metadata.path("stale_reasons").valueStream()
                .anyMatch(reason -> reason.asText().equals("previous_generation"));
        if (previousGeneration) quickCompile = null;
        ObjectNode recommendation = receipt.putObject("recommendation");
        recommendation.put("scope", previousGeneration ? "wait_for_refresh" : "quick_compile");
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
        // The receipt attests the outcome; verification.build in the same snapshot lists every output.
        if (observed.path("compiled_outputs") instanceof ObjectNode outputs) {
            outputs.remove(List.of("source_sets", "directories", "directories_truncated"));
        }
        copy(build, observed, "verification_scope");
        copy(build, observed, "repository_status");
        copy(build, observed, "repository_stale_modules");
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
                phase.equals("planned") || phase.equals("complete") || phase.equals(EXTERNAL));
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
            case EXTERNAL -> {
                gate.put("status", "not_applicable");
                gate.put("transition", "verify_in_checkout");
            }
            default -> throw new IllegalArgumentException("Unknown change phase: " + phase);
        }
        gate.put("required_evidence_count", evidence.size());
        return gate;
    }

    private static ArrayNode identifiedActions(String sessionId, JsonNode source) {
        ArrayNode identified = JSON.createArrayNode();
        for (JsonNode sourceAction : source) {
            ObjectNode action = sourceAction.deepCopy();
            action.put("action_id", digest(sessionId + "\n" + actionIdentity(action)));
            identified.add(action);
        }
        return identified;
    }

    private static String actionIdentity(JsonNode action) {
        ObjectNode identity = action.deepCopy();
        identity.remove(List.of("order", "action_id"));
        return identity.toString();
    }

    private static ObjectNode directive(String phase, ArrayNode actions,
            ObjectNode plan, ObjectNode verify) {
        ObjectNode directive = JSON.createObjectNode();
        directive.put("schema_version", 1);
        directive.put("status", phase);
        directive.put("headline", switch (phase) {
            case "planned" -> "Inspect the change plan before editing";
            case "review_required" -> "Resolve review evidence before verification";
            case "verification_required" -> "Capture fresh compile evidence";
            case "blocked" -> "Fix build diagnostics before retrying verification";
            case "complete" -> "Change verification is complete";
            default -> throw new IllegalArgumentException("Unknown change phase: " + phase);
        });
        directive.put("action_count", actions.size());
        if (actions.isEmpty()) directive.putNull("primary_action");
        else {
            ObjectNode primary = actions.get(0).deepCopy();
            if (primary.path("action").asText().equals("wait_for_refresh"))
                directive.put("headline", "Wait for the refreshed index generation before verification");
            String evidence = primary.path("evidence").asText();
            if (!evidence.isBlank()) {
                JsonNode value = evidence.startsWith("plan.")
                        ? plan.at("/" + evidence.substring(5).replace('.', '/'))
                        : verify.at("/" + evidence.replace('.', '/'));
                primary.set("evidence_snapshot", value.deepCopy());
            }
            if (phase.equals("planned")) {
                primary.set("evidence_snapshot", plan.path("primary_changes").deepCopy());
            }
            String scope = primary.path("command_scope").asText();
            boolean prepareTests = primary.path("action").asText().equals("inspect_test_evidence")
                    && (verify.path("verdict").asText().equals("needs_build")
                        || verify.path("_meta").path("structure_stale").asBoolean());
            for (JsonNode command : verify.path("verification_plan").path("commands")) {
                if (command.path("scope").asText().equals(scope)) {
                    primary.set("command", command.deepCopy());
                }
                if (prepareTests && command.path("scope").asText().equals("quick_compile")) {
                    primary.set("preparation_command", command.deepCopy());
                }
                if (phase.equals("blocked")
                        && command.path("scope").asText().equals("quick_compile")) {
                    primary.set("retry_command", command.deepCopy());
                }
            }
            if (primary.has("command") || primary.has("preparation_command")
                    || primary.has("retry_command")) {
                copy(verify.path("verification_plan"), primary, "working_directory");
                primary.put("executed_by_quill", false);
            }
            directive.set("primary_action", primary);
        }
        if (phase.equals("planned")) directive.put("other_checkout", OTHER_CHECKOUT);
        return directive;
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
        if (verification.path("_meta").path("stale_reasons").valueStream()
                .anyMatch(reason -> reason.asText().equals("previous_generation"))) return "verification_required";
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
