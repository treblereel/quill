package org.treblereel.mcp.mcp.tools;

import static org.treblereel.mcp.mcp.tools.WorkspaceResponses.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.diagnostics.DebugTrace;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.project.ProjectRegistry;

/** Answers a local class miss from the one workspace repository that declares the class. */
final class WorkspaceRouting {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectRegistry registry;
    private final WorkspaceProviders providerLookup;

    WorkspaceRouting(ProjectRegistry registry, WorkspaceProviders providers) {
        this.registry = registry;
        this.providerLookup = providers;
    }

    String routeMissingClassQuery(String response, String consumerRepository, String target,
            String operation, Function<ReadQueryContext, String> providerQuery, boolean withGraph) {
        if (registry.workspaceScope() == null) return response;
        DebugTrace.Trace trace = DebugTrace.start("workspace_route");
        try {
            JsonNode local = JSON.readTree(response);
            String localError = errorMessage(local);
            if (!isRoutableClassMiss(localError)) {
                trace.event("route_skipped", Map.of("operation", operation,
                        "reason", "local_result_available"));
                return response;
            }
            List<ProviderClass> providers = providerLookup.findProviderClasses(target, null);
            trace.event("provider_candidates_resolved", Map.of(
                    "operation", operation,
                    "consumer_repository", consumerRepository,
                    "target", target,
                    "candidate_count", providers.size()));
            if (providers.isEmpty()) {
                trace.event("route_skipped", Map.of("operation", operation,
                        "reason", "provider_not_found"));
                return response;
            }

            ObjectNode root = JSON.createObjectNode();
            root.put("target", target);
            root.put("origin", "workspace_provider");
            root.putObject("local_resolution")
                    .put("status", localError.startsWith("Class not found")
                            ? "not_found" : "not_resolved_locally")
                    .put("repository", consumerRepository)
                    .put("message", localError);
            ObjectNode workspaceResult = root.putObject("workspace_result");
            workspaceResult.put("operation", operation);
            if (providers.size() > 1) {
                workspaceResult.put("status", "ambiguous_workspace_class");
                workspaceResult.put("complete", false);
                ArrayNode candidates = workspaceResult.putArray("candidates");
                providers.forEach(provider -> candidates.add(providerJson(provider)));
                root.put("answer_complete", false);
                trace.event("route_rejected", Map.of("operation", operation,
                        "reason", "ambiguous_provider",
                        "candidate_count", providers.size()));
                return root.toString();
            }

            ProviderClass provider = providers.getFirst();
            ObjectNode providerNode = providerJson(provider);
            workspaceResult.set("provider", providerNode);
            ProjectRegistry.Resolution availability = registry.resolve(provider.repository());
            boolean providerFresh = appendFreshness(providerNode, availability);
            if (availability.projects().isEmpty()) {
                workspaceResult.put("status", "provider_unavailable");
                workspaceResult.put("complete", false);
                ProjectAvailabilityResponses.append(workspaceResult, "project_warnings",
                        availability.issues());
                root.put("answer_complete", false);
                trace.event("route_rejected", Map.of("operation", operation,
                        "reason", "provider_unavailable",
                        "provider_repository", provider.repository()));
                return root.toString();
            }

            JsonNode providerData = JSON.readTree((withGraph ? availability.projects().getFirst().readWithGraph(providerQuery)
                    : availability.projects().getFirst().read(providerQuery)));
            boolean absentFromIndex = isClassNotFound(providerData);
            workspaceResult.put("status", absentFromIndex
                    ? "provider_index_incomplete" : "resolved");
            boolean complete = !absentFromIndex && providerFresh
                    && availability.issues().isEmpty();
            workspaceResult.put("complete", complete);
            workspaceResult.set("data", providerData);
            ProjectAvailabilityResponses.append(workspaceResult, "project_warnings",
                    availability.issues());
            root.put("answer_complete", complete);
            trace.event("route_selected", Map.of(
                    "operation", operation,
                    "consumer_repository", consumerRepository,
                    "provider_repository", provider.repository(),
                    "provider_module", provider.module(),
                    "status", workspaceResult.path("status").asText(),
                    "complete", complete));
            return root.toString();
        } catch (Exception failure) {
            trace.event("route_failed", Map.of("operation", operation,
                    "error_type", failure.getClass().getSimpleName(),
                    "message", ProjectRegistry.safeMessage(failure)));
            return response;
        } finally {
            trace.close();
        }
    }

    private static boolean isRoutableClassMiss(String error) {
        return error.startsWith("Class not found") || error.startsWith("Not a bean:");
    }
}
