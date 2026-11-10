package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

/** Stable structured MCP responses for projects that cannot currently be queried. */
final class ProjectAvailabilityResponses {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProjectAvailabilityResponses() {}

    static String error(ProjectRegistry.ProjectIssue issue) {
        ObjectNode root = details(issue);
        root.put("error", issue.code());
        return root.toString();
    }

    static String error(List<ProjectRegistry.ProjectIssue> issues) {
        if (issues.size() == 1) return error(issues.getFirst());
        ObjectNode root = JSON.createObjectNode();
        root.put("error", "projects_unavailable");
        root.put("status", "action_required");
        root.put("build_was_started", false);
        append(root, issues);
        return root.toString();
    }

    static void append(ObjectNode root, List<ProjectRegistry.ProjectIssue> issues) {
        append(root, "unavailable_projects", issues);
    }

    static void append(ObjectNode root, String field,
            List<ProjectRegistry.ProjectIssue> issues) {
        if (issues.isEmpty()) return;
        ArrayNode unavailable = root.putArray(field);
        issues.forEach(issue -> unavailable.add(details(issue)));
        if (field.equals("project_warnings")) annotateScopedBuildWarnings(root);
        // An answer that states its own plan and diagnostics is not repeated by its warnings:
        // in V26 CQ-049 a verify_change warning restated both, 1.6 KB.
        boolean stated = root.has("verification_plan") && root.has("diagnostics");
        if (root.path("detail").asText().equals("summary") || stated) {
            for (int i = 0; i < unavailable.size(); i++) unavailable.set(i, summary(unavailable.get(i)));
        }
        // A warning the answer's own build section states is a pointer: in V28 CQ-049 change_session
        // restated status, reason and action of verification.build in 800-1000 bytes.
        String pointer = root.has("build") ? "/build" : root.path("verification").has("build") ? "/verification/build" : null;
        if (field.equals("project_warnings") && pointer != null) {
            JsonNode build = root.at(pointer);
            for (int i = 0; i < unavailable.size(); i++) {
                JsonNode warning = unavailable.get(i);
                boolean same = List.of("status", "reason", "action").stream()
                        .allMatch(key -> warning.path(key).asText().equals(build.path(key).asText()));
                if (!same) continue;
                ObjectNode compact = JSON.createObjectNode();
                for (String key : List.of("project", "status", "reason")) compact.set(key, warning.path(key).deepCopy());
                compact.put("same_as", pointer);
                unavailable.set(i, compact);
            }
        }
    }

    static void annotateScopedBuildWarnings(ObjectNode root) {
        JsonNode build = root.has("build") ? root.path("build") : root.path("verification").path("build");
        JsonNode scope = build.path("verification_scope");
        if (!scope.path("kind").asText().equals("modules_with_prerequisites")
                || scope.path("modules_has_more").asBoolean()) return;
        Set<String> modules = scope.path("modules").valueStream().map(JsonNode::asText)
                .collect(java.util.stream.Collectors.toSet());
        if (modules.isEmpty()) return;
        for (JsonNode warning : root.path("project_warnings")) {
            if (!(warning instanceof ObjectNode object)
                    || !warning.path("build_reason").asText().equals("classes_stale")
                    || warning.path("stale_modules").isEmpty()
                    || warning.path("stale_modules_has_more").asBoolean()) continue;
            boolean outside = warning.path("stale_modules").valueStream()
                    .map(JsonNode::asText).noneMatch(modules::contains);
            if (outside) {
                object.put("scope", "repository_outside_change");
                object.put("blocking_for_change", false);
                object.put("recommended_action", "Advisory outside this verification scope; "
                        + "follow the change directive. Rebuild these modules only for repository-wide verification.");
            }
        }
    }

    static void appendBuildGuidance(ObjectNode build, com.fasterxml.jackson.databind.JsonNode diagnostics) {
        if (!diagnostics.path("build_status").asText().equals("failed")
                && build.path("freshness").path("stale_reasons").valueStream()
                .anyMatch(reason -> reason.asText().equals("previous_generation"))) return;
        var reason = diagnostics.path("build_status").asText().equals("failed")
                ? ProjectRegistry.ReadinessReason.BUILD_FAILED
                : switch (build.path("status").asText()) {
                    case "ready" -> ProjectRegistry.ReadinessReason.READY;
                    case "build_required" -> ProjectRegistry.ReadinessReason.forCode("build_required", build.path("build_reason").asText());
                    case "refresh_pending" -> ProjectRegistry.ReadinessReason.BUILD_EVENTS_QUEUED;
                    case "index_refresh_required" -> ProjectRegistry.ReadinessReason.INDEX_OUTDATED;
                    case "integration_required", "integration_error" -> ProjectRegistry.ReadinessReason.BUILD_INTEGRATION_REQUIRED;
                    default -> ProjectRegistry.ReadinessReason.INDEX_UNAVAILABLE;
                };
        if (build.path("freshness").path("stale_reasons").valueStream()
                .anyMatch(value -> value.asText().equals("input_watch_unavailable")))
            reason = ProjectRegistry.ReadinessReason.INPUT_OBSERVATION_RECOVERING;
        build.put("reason", reason.code());
        build.put("action", reason.action);
        if (reason == ProjectRegistry.ReadinessReason.BUILD_FAILED) {
            build.put("build_reason", "last_build_failed");
            build.put("action_required", true);
            build.put("recommended_action", "Fix the captured build errors, then run the quick_compile command in verification_plan");
        } else if (reason == ProjectRegistry.ReadinessReason.INPUT_OBSERVATION_RECOVERING) {
            build.put("recommended_action", "Input observation is recovering; retry the same query without rebuilding");
        }
    }

    static ObjectNode summary(ProjectRegistry.ProjectIssue issue) {
        return summary(details(issue));
    }

    static ObjectNode summary(com.fasterxml.jackson.databind.JsonNode issue) {
        ObjectNode root = issue.deepCopy();
        if (root.path("diagnostics").isObject()) {
            var diagnostic = root.putObject("diagnostics_summary");
            for (String field : List.of("build_status", "build_tool", "finished_at", "total",
                    "located_problem_count", "unlocated_problem_count"))
                if (root.path("diagnostics").has(field)) diagnostic.set(field, root.path("diagnostics").path(field).deepCopy());
        }
        root.put("verification_plan_available", root.hasNonNull("verification_plan"));
        root.remove(List.of("verification_plan", "diagnostics"));
        ToolResponseSupport.summarizeInventories(root);
        root.remove("stale_modules");
        root.putArray("omitted_sections").add("verification_plan").add("diagnostics").add("stale_modules");
        var request = root.putObject("details_request");
        request.put("tool", "get_build_status");
        request.putObject("arguments").put("detail", "full").set("project", issue.path("project").deepCopy());
        return root;
    }

    static ObjectNode details(ProjectRegistry.ProjectIssue issue) {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", issue.code());
        root.put("reason", issue.reason().code());
        root.put("action", issue.reason().action);
        if (issue.stage() != null) root.put("stage", issue.stage());
        if (issue.elapsedMs() != null) root.put("elapsed_ms", issue.elapsedMs());
        if (issue.retryAfterMs() != null) root.put("retry_after_ms", issue.retryAfterMs());
        root.put("automatic_retry", issue.retryAfterMs() != null);
        root.put("previous_generation_available", issue.previousGenerationAvailable());
        if (issue.verificationPlan() != null) root.set("verification_plan", issue.verificationPlan());
        if (issue.diagnostics() != null) root.set("diagnostics", issue.diagnostics());
        root.put("project", issue.project());
        root.put("project_root", issue.projectRoot().toString());
        if (issue.buildSystem() == null) root.putNull("build_system");
        else root.put("build_system", issue.buildSystem());
        root.put("message", issue.message());
        if (issue.recommendedAction() == null) root.putNull("recommended_action");
        else root.put("recommended_action", issue.recommendedAction());
        if (issue.buildReason() == null) root.putNull("build_reason");
        else root.put("build_reason", issue.buildReason());
        root.set("stale_modules", JSON.valueToTree(issue.staleModules()));
        root.put("build_was_started", issue.buildWasStarted());
        root.put("decision_owner", "mcp_client");
        root.put("retryable", !"unsupported_project".equals(issue.code())
                && !"metadata_only".equals(issue.code()));
        return root;
    }
}
