package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

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
    }

    static ObjectNode details(ProjectRegistry.ProjectIssue issue) {
        ObjectNode root = JSON.createObjectNode();
        root.put("status", issue.code());
        root.put("project", issue.project());
        root.put("project_root", issue.projectRoot().toString());
        if (issue.buildSystem() == null) root.putNull("build_system");
        else root.put("build_system", issue.buildSystem());
        root.put("message", issue.message());
        if (issue.recommendedAction() == null) root.putNull("recommended_action");
        else root.put("recommended_action", issue.recommendedAction());
        root.put("build_was_started", issue.buildWasStarted());
        root.put("decision_owner", "mcp_client");
        root.put("retryable", !"unsupported_project".equals(issue.code()));
        return root;
    }
}
