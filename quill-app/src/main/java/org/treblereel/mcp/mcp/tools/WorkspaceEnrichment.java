package org.treblereel.mcp.mcp.tools;

import static org.treblereel.mcp.mcp.tools.WorkspaceResponses.*;

import org.treblereel.mcp.workspace.WorkspaceProjectScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.diagnostics.DebugTrace;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.workspace.query.WorkspaceHop;
import org.treblereel.mcp.workspace.query.WorkspaceQueryRouter;
import org.treblereel.mcp.workspace.query.WorkspaceRoute;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.diagnostics.QuillLog;

/** Adds workspace provider routes and readiness to single-project dependency and bean answers. */
final class WorkspaceEnrichment {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final ProjectRegistry registry;
    private final WorkspaceProviders providerLookup;

    WorkspaceEnrichment(ProjectRegistry registry, WorkspaceProviders providers) {
        this.registry = registry;
        this.providerLookup = providers;
    }

    String enrichProjectDependencies(String response, String consumerRepository) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return response;
        try {
            JsonNode parsed = JSON.readTree(response);
            if (!(parsed instanceof ObjectNode root) || !root.path("dependencies").isArray()) {
                return response;
            }
            WorkspaceQueryRouter router = new WorkspaceQueryRouter(scope);
            Map<String, ProjectRegistry.Resolution> availability = new LinkedHashMap<>();
            int mapped = 0;
            int ambiguous = 0;
            int unresolved = 0;
            for (JsonNode value : root.path("dependencies")) {
                if (!(value instanceof ObjectNode dependency)) continue;
                String coordinate = dependency.path("id").asText(null);
                if (coordinate == null) continue;
                Map<String, WorkspaceHop> candidates = new java.util.TreeMap<>();
                List<String> statuses = new ArrayList<>();
                boolean complete = true;
                JsonNode modules = dependency.path("used_by_modules");
                if (!modules.isArray() || modules.isEmpty()) continue;
                for (JsonNode module : modules) {
                    WorkspaceRoute route = router.resolveDependency(consumerRepository,
                            module.asText("."), coordinate);
                    statuses.add(route.status());
                    complete &= route.complete();
                    for (WorkspaceHop candidate : route.candidates()) {
                        candidates.putIfAbsent(candidate.toRepository() + ':'
                                + candidate.toModule(), candidate);
                    }
                }
                String status = candidates.isEmpty() ? "not_found"
                        : candidates.size() == 1 && statuses.stream().noneMatch(
                                "ambiguous"::equals) ? "resolved" : "ambiguous";
                if (status.equals("resolved")) mapped++;
                else if (status.equals("ambiguous")) ambiguous++;
                else unresolved++;
                ObjectNode resolution = dependency.putObject("workspace_resolution");
                resolution.put("status", status);
                resolution.put("complete", complete && !status.equals("ambiguous"));
                ArrayNode values = resolution.putArray("candidates");
                for (WorkspaceHop candidate : candidates.values()) {
                    ObjectNode provider = values.addObject();
                    provider.put("repository", candidate.toRepository());
                    provider.put("module", candidate.toModule());
                    provider.put("coordinate", candidate.coordinate());
                    provider.put("source_set", candidate.sourceSet());
                    provider.put("evidence", candidate.evidence());
                    provider.put("confidence", candidate.confidence());
                    provider.put("version_status", candidate.versionStatus());
                    ProjectRegistry.Resolution available = availability.computeIfAbsent(
                            candidate.toRepository(), registry::resolve);
                    ProjectRegistry.ProjectIssue issue = available.issues().stream()
                            .filter(item -> item.project().equals(candidate.toRepository()))
                            .findFirst().orElse(null);
                    String providerStatus = issue == null && !available.projects().isEmpty()
                            ? "ready" : issue == null ? "unavailable" : issue.code();
                    provider.put("index_status", providerStatus);
                    if (issue != null) provider.set("availability",
                            ProjectAvailabilityResponses.details(issue));
                }
                if (candidates.size() == 1) {
                    JsonNode provider = values.get(0);
                    resolution.put("provider_repository", provider.path("repository").asText());
                    resolution.put("provider_module", provider.path("module").asText());
                    resolution.put("provider_index_status",
                            provider.path("index_status").asText());
                }
            }
            ObjectNode summary = root.putObject("workspace_provider_resolution");
            summary.put("mapped_dependency_count", mapped);
            summary.put("ambiguous_dependency_count", ambiguous);
            summary.put("unresolved_dependency_count", unresolved);
            summary.put("complete", ambiguous == 0 && unresolved == 0);
            return root.toString();
        } catch (Exception ignored) {
            QuillLog.swallowed(ignored);
            return response;
        }
    }

    String enrichClassDependencies(String response, String consumerRepository, String target,
            String direction, int depth, boolean includeNodes, int limit, int offset,
            String cursor) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return response;
        DebugTrace.Trace trace = DebugTrace.start("workspace_dependency_route");
        try {
            JsonNode parsed = JSON.readTree(response);
            if (!(parsed instanceof ObjectNode root)) {
                trace.event("route_skipped", Map.of("reason", "non_object_response"));
                return response;
            }
            boolean localMissing = isClassNotFound(root);
            boolean dependencyClass = "dependency".equals(root.path("origin").asText());
            if (!localMissing && !dependencyClass) {
                trace.event("route_skipped", Map.of("reason", "local_source_class"));
                return response;
            }
            String resolvedTarget = root.path("target").asText(target);
            List<ProviderClass> providers = providerLookup.findProviderClasses(resolvedTarget, null);
            trace.event("provider_candidates_resolved", Map.of(
                    "operation", "get_dependencies",
                    "consumer_repository", consumerRepository,
                    "target", resolvedTarget,
                    "candidate_count", providers.size()));
            if (localMissing && !providers.isEmpty()) {
                ObjectNode localResolution = root.putObject("local_resolution");
                localResolution.put("status", "not_found");
                localResolution.put("repository", consumerRepository);
                localResolution.put("message", errorMessage(root));
                root.remove(List.of("error", "error_code", "message", "retryable"));
                root.put("target", resolvedTarget);
                root.put("origin", "workspace_provider");
            }
            ObjectNode traversal = root.putObject("workspace_traversal");
            traversal.put("enabled", true);
            traversal.put("start_repository", consumerRepository);
            traversal.put("target", resolvedTarget);
            traversal.put("max_depth", depth);
            if (providers.isEmpty()) {
                traversal.put("status", "provider_not_found");
                traversal.put("complete", false);
                traversal.putArray("repositories_queried").add(consumerRepository);
                trace.event("route_rejected", Map.of("operation", "get_dependencies",
                        "reason", "provider_not_found"));
                return root.toString();
            }
            if (providers.size() > 1) {
                traversal.put("status", "ambiguous_provider");
                traversal.put("complete", false);
                ArrayNode candidates = traversal.putArray("provider_candidates");
                providers.forEach(provider -> candidates.add(providerJson(provider)));
                trace.event("route_rejected", Map.of("operation", "get_dependencies",
                        "reason", "ambiguous_provider",
                        "candidate_count", providers.size()));
                return root.toString();
            }
            ProviderClass provider = providers.getFirst();
            WorkspaceCoordinateCatalog.Result catalog =
                    WorkspaceCoordinateCatalog.discover(scope.manifest());
            WorkspaceCoordinateCatalog.Module providerModule = catalog.modules().stream()
                    .filter(module -> module.repository().equals(provider.repository()))
                    .filter(module -> module.module().equals(provider.module()))
                    .findFirst().orElse(null);
            WorkspaceRoute route = providerModule == null || providerModule.ga() == null
                    ? new WorkspaceRoute("not_found", List.of(), false,
                            List.of("Provider module has no build coordinate"))
                    : new WorkspaceQueryRouter(scope).resolveDependency(consumerRepository,
                            root.path("module").asText("."), providerModule.gav());
            traversal.put("status", route.status());
            traversal.put("complete", route.resolved());
            traversal.putArray("repositories_queried")
                    .add(consumerRepository).add(provider.repository());
            ArrayNode routes = traversal.putArray("routes");
            route.candidates().stream()
                    .filter(candidate -> candidate.toRepository().equals(provider.repository())
                            && candidate.toModule().equals(provider.module()))
                    .forEach(candidate -> routes.add(hopJson(candidate)));

            ObjectNode providerNode = traversal.putObject("provider");
            providerNode.put("repository", provider.repository());
            providerNode.put("module", provider.module());
            providerNode.put("class_name", provider.className());
            if (provider.sourceFile() != null) {
                providerNode.put("source_file", provider.sourceFile());
            }
            if (providerModule != null) providerNode.put("coordinate", providerModule.ga());

            ProjectRegistry.Resolution providerResolution = registry.resolve(
                    provider.repository());
            boolean providerFresh = appendFreshness(providerNode, providerResolution);
            if (providerResolution.projects().isEmpty()) {
                traversal.put("status", "provider_unavailable");
                traversal.put("complete", false);
                ProjectAvailabilityResponses.append(traversal, providerResolution.issues());
                trace.event("route_rejected", Map.of("operation", "get_dependencies",
                        "reason", "provider_unavailable",
                        "provider_repository", provider.repository()));
                return root.toString();
            }
            String providerResponse = providerResolution.projects().getFirst().read(context -> new QuillToolQueries().getDependencies(
                    context, provider.className(), direction, depth, includeNodes, limit, offset, cursor));
            JsonNode providerData = JSON.readTree(providerResponse);
            providerNode.set("data", providerData);
            if (!providerResolution.issues().isEmpty()) {
                ProjectAvailabilityResponses.append(traversal, "project_warnings",
                        providerResolution.issues());
            }
            if (hasToolError(providerData)) {
                traversal.put("status", "provider_index_incomplete");
                traversal.put("complete", false);
            } else {
                traversal.put("status", route.status());
                traversal.put("complete", route.resolved() && providerFresh
                        && providerResolution.issues().isEmpty());
            }
            trace.event("route_selected", Map.of(
                    "operation", "get_dependencies",
                    "consumer_repository", consumerRepository,
                    "provider_repository", provider.repository(),
                    "provider_module", provider.module(),
                    "status", traversal.path("status").asText(),
                    "complete", traversal.path("complete").asBoolean()));
            return root.toString();
        } catch (Exception failure) {
            trace.event("route_failed", Map.of("operation", "get_dependencies",
                    "error_type", failure.getClass().getSimpleName(),
                    "message", ProjectRegistry.safeMessage(failure)));
            return response;
        } finally {
            trace.close();
        }
    }

    String enrichDependencyBeans(String response) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return response;
        try {
            JsonNode parsed = JSON.readTree(response);
            if (!(parsed instanceof ObjectNode root) || !root.path("beans").isArray()) {
                return response;
            }
            WorkspaceCoordinateCatalog.Result catalog =
                    WorkspaceCoordinateCatalog.discover(scope.manifest());
            Map<String, ProjectRegistry.Resolution> availability = new LinkedHashMap<>();
            Map<String, ProviderSummary> providerSummaries = new java.util.TreeMap<>();
            int dependencyBeans = 0;
            int mappedBeans = 0;
            for (JsonNode value : root.path("beans")) {
                if (!(value instanceof ObjectNode bean)
                        || !"dependency".equals(bean.path("origin").asText())) continue;
                dependencyBeans++;
                String ga = ga(bean.path("artifact").asText(null));
                if (ga == null) continue;
                List<WorkspaceCoordinateCatalog.Module> providers =
                        catalog.modulesByGa().getOrDefault(ga, List.of());
                if (providers.isEmpty()) continue;
                mappedBeans++;
                ArrayNode candidates = bean.putArray("workspace_providers");
                for (WorkspaceCoordinateCatalog.Module provider : providers) {
                    ObjectNode candidate = candidates.addObject();
                    candidate.put("repository", provider.repository());
                    candidate.put("module", provider.module());
                    candidate.put("coordinate", provider.ga());
                    candidate.put("version", provider.version());
                    ProjectRegistry.Resolution resolved = availability.computeIfAbsent(
                            provider.repository(), registry::resolve);
                    ProjectRegistry.ProjectIssue issue = resolved.issues().stream()
                            .filter(item -> item.project().equals(provider.repository()))
                            .findFirst().orElse(null);
                    candidate.put("status", issue == null && !resolved.projects().isEmpty()
                            ? "ready" : issue == null ? "unavailable" : issue.code());
                    String status = candidate.path("status").asText("unavailable");
                    String summaryKey = provider.repository() + '|' + provider.module()
                            + '|' + status;
                    ProviderSummary existing = providerSummaries.get(summaryKey);
                    providerSummaries.put(summaryKey, existing == null
                            ? new ProviderSummary(provider.repository(), provider.module(),
                                    status, 1, new java.util.TreeSet<>(Set.of(ga)))
                            : existing.add(ga));
                    if (issue != null) {
                        candidate.set("availability", ProjectAvailabilityResponses.details(issue));
                    }
                }
                if (providers.size() == 1) {
                    WorkspaceCoordinateCatalog.Module provider = providers.getFirst();
                    bean.put("provider_repository", provider.repository());
                    bean.put("provider_module", provider.module());
                    bean.put("provider_status",
                            candidates.get(0).path("status").asText("unavailable"));
                }
            }
            ObjectNode resolution = root.putObject("workspace_provider_resolution");
            resolution.put("dependency_bean_count", dependencyBeans);
            resolution.put("mapped_bean_count", mappedBeans);
            resolution.put("complete", dependencyBeans == mappedBeans);
            resolution.put("additional_workspace_lookup_required",
                    dependencyBeans != mappedBeans);
            ArrayNode summaries = resolution.putArray("providers");
            providerSummaries.values().forEach(provider -> {
                ObjectNode value = summaries.addObject();
                value.put("repository", provider.repository());
                value.put("module", provider.module());
                value.put("status", provider.status());
                value.put("bean_count", provider.beanCount());
                value.set("coordinates", JSON.valueToTree(provider.coordinates()));
            });
            if (root.path("answer_coverage").isObject()) {
                ((ObjectNode) root.path("answer_coverage"))
                        .put("workspace_provider_status", dependencyBeans == mappedBeans);
            }
            return root.toString();
        } catch (Exception ignored) {
            QuillLog.swallowed(ignored);
            return response;
        }
    }

    private static String ga(String coordinate) {
        if (coordinate == null || coordinate.isBlank()) return null;
        String[] parts = coordinate.split(":");
        return parts.length >= 2 ? parts[0] + ':' + parts[1] : null;
    }

    private record ProviderSummary(String repository, String module, String status,
            int beanCount, java.util.SortedSet<String> coordinates) {
        ProviderSummary add(String coordinate) {
            java.util.SortedSet<String> updated = new java.util.TreeSet<>(coordinates);
            updated.add(coordinate);
            return new ProviderSummary(repository, module, status, beanCount + 1, updated);
        }
    }


    private ObjectNode hopJson(WorkspaceHop hop) {
        ObjectNode node = JSON.createObjectNode();
        node.put("from_repository", hop.fromRepository());
        node.put("from_module", hop.fromModule());
        node.put("to_repository", hop.toRepository());
        node.put("to_module", hop.toModule());
        node.put("coordinate", hop.coordinate());
        node.set("scopes", JSON.valueToTree(hop.scopes()));
        node.put("source_set", hop.sourceSet());
        node.put("evidence", hop.evidence());
        node.put("resolution", hop.resolution());
        node.put("confidence", hop.confidence());
        node.put("version_status", hop.versionStatus());
        appendFreshness(node, registry.resolve(hop.toRepository()));
        return node;
    }
}
