package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.JvmSourceFiles;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.diagnostics.DebugTrace;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.MetaEnvelope;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.workspace.WorkspaceDependencyGraph;
import org.treblereel.mcp.workspace.query.WorkspaceHop;
import org.treblereel.mcp.workspace.query.WorkspaceQueryRouter;
import org.treblereel.mcp.workspace.query.WorkspaceRoute;

/** Structured workspace catalog, dependency, and entity-resolution responses. */
final class WorkspaceToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectRegistry registry;

    WorkspaceToolQueries(ProjectRegistry registry) {
        this.registry = registry;
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
            List<ProviderClass> providers = findProviderClasses(resolvedTarget, null);
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
            String providerResponse = new QuillToolQueries().getDependencies(
                    providerResolution.projects().getFirst().jdbi(), provider.className(),
                    direction, depth, includeNodes, limit, offset, cursor);
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

    String getCompactOverview(int limit, int offset) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        ProjectScope.Snapshot snapshot = scope.snapshot();
        int total = snapshot.projects().size();
        int from = Math.min(offset, total);
        int to = from + Math.min(limit, total - from);
        ObjectNode root = JSON.createObjectNode();
        root.put("view", "compact");
        root.put("workspace_root", scope.root().toString());
        root.put("revision", snapshot.revision());
        root.put("scope", "Workspace inventory; counts and freshness are from indexes on this page, not a complete architecture or endpoint analysis.");
        ArrayNode projects = root.putArray("projects");
        // Resolve only this page; do not construct full overview/hub/DI responses.
        for (ProjectScope.Project project : snapshot.projects().subList(from, to)) {
            ObjectNode node = projects.addObject();
            node.put("name", project.name());
            node.put("relative_path", normalize(scope.root().relativize(project.root()).toString()));
            ProjectRegistry.Resolution resolution = registry.resolve(project.name());
            ProjectRegistry.ProjectIssue issue = resolution.issues().stream()
                    .filter(value -> value.project().equals(project.name())).findFirst().orElse(null);
            node.put("indexed", !resolution.projects().isEmpty());
            node.put("status", issue != null ? issue.code()
                    : resolution.projects().isEmpty() ? "unavailable" : "ready");
            if (issue != null) {
                node.put("message", issue.message());
                node.put("recommended_action", issue.recommendedAction());
            }
            try {
                appendFreshness(node, resolution);
                if (!resolution.projects().isEmpty()) {
                    Jdbi db = resolution.projects().getFirst().jdbi();
                    Map<String, String> metadata = IndexReader.getMetadata(db);
                    node.put("framework", metadata.getOrDefault("framework", "unknown"));
                    node.put("indexed_classes", IndexReader.countClasses(db));
                    node.put("indexed_beans", IndexReader.countBeans(db));
                }
            } catch (Exception failure) {
                node.put("status", "query_failed");
                node.put("message", ProjectRegistry.safeMessage(failure));
            }
            ObjectNode next = node.putObject("details_request");
            next.put("tool", "get_overview");
            next.putObject("arguments").put("project", project.name()).put("view", "full");
        }
        ToolResponseSupport.appendPage(root, to - from, total, limit, offset);
        if (to < total) {
            ObjectNode next = root.putObject("next_page_request");
            next.put("tool", "get_overview");
            next.putObject("arguments").put("view", "compact").put("limit", limit).put("offset", to);
        }
        appendDiagnostics(root, snapshot.diagnostics());
        return root.toString();
    }

    String listRepositories(boolean includeModules, int limit, int offset) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        ProjectScope.Snapshot snapshot = scope.snapshot();
        ProjectRegistry.Resolution initialized = registry.resolve();
        Map<String, ProjectRegistry.ProjectEntry> entries = new LinkedHashMap<>();
        initialized.projects().forEach(entry -> entries.put(entry.name(), entry));
        Map<String, ProjectRegistry.ProjectIssue> issues = new LinkedHashMap<>();
        initialized.issues().forEach(issue -> issues.put(issue.project(), issue));
        WorkspaceCoordinateCatalog.Result coordinates =
                WorkspaceCoordinateCatalog.discover(scope.manifest());
        Map<String, List<WorkspaceCoordinateCatalog.Module>> modules = new LinkedHashMap<>();
        coordinates.modules().forEach(module -> modules
                .computeIfAbsent(module.repository(), ignored -> new ArrayList<>()).add(module));

        int total = snapshot.projects().size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        ObjectNode root = JSON.createObjectNode();
        root.put("workspace_root", scope.root().toString());
        root.put("revision", snapshot.revision());
        ArrayNode repositories = root.putArray("repositories");
        for (ProjectScope.Project project : snapshot.projects().subList(from, to)) {
            ObjectNode node = repositories.addObject();
            node.put("name", project.name());
            node.put("root", project.root().toString());
            node.put("relative_path", normalize(scope.root().relativize(project.root()).toString()));
            ProjectRegistry.ProjectEntry entry = entries.get(project.name());
            node.put("indexed", entry != null);
            ProjectRegistry.ProjectIssue issue = issues.get(project.name());
            node.put("status", issue != null ? issue.code()
                    : entry != null ? "ready" : "unavailable");
            if (issue != null) node.set("availability",
                    ProjectAvailabilityResponses.details(issue));
            node.put("branch", nullToUnknown(GitAnalyzer.resolveBranch(project.root())));
            node.put("current_commit", nullToUnknown(GitAnalyzer.resolveHead(project.root())));
            var worktree = WorktreeSnapshotCache.shared().get(project.root());
            node.put("worktree_dirty", worktree.dirty());
            node.put("worktree_changed_files", worktree.changes().size());
            List<WorkspaceCoordinateCatalog.Module> repositoryModules =
                    modules.getOrDefault(project.name(), List.of());
            node.put("module_count", repositoryModules.size());
            if (entry != null) {
                Map<String, String> metadata = IndexReader.getMetadata(entry.jdbi());
                node.put("index_id", metadata.getOrDefault("index_id", "unknown"));
                node.put("indexed_at", metadata.getOrDefault("indexed_at", "unknown"));
                node.put("indexed_commit", metadata.getOrDefault("last_commit", "unknown"));
            }
            if (includeModules) {
                ArrayNode moduleArray = node.putArray("modules");
                repositoryModules.forEach(module -> {
                    ObjectNode value = moduleArray.addObject();
                    value.put("module", module.module());
                    value.put("coordinate", module.ga());
                    value.put("version", module.version());
                    value.put("build_system", module.buildSystem());
                });
            }
        }
        ToolResponseSupport.appendPage(root, to - from, total, limit, offset);
        root.put("coordinates_complete", coordinates.complete());
        appendDiagnostics(root, mergeDiagnostics(
                initialized.errors(), snapshot.diagnostics(), coordinates.diagnostics()));
        return root.toString();
    }

    String getDependencies(String repository, String direction, boolean crossRepositoryOnly,
            int limit, int offset) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        String normalizedDirection = direction == null ? "both"
                : direction.strip().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("consumers", "providers", "both").contains(normalizedDirection)) {
            return error("direction must be consumers, providers, or both");
        }
        WorkspaceDependencyGraph.Result graph =
                WorkspaceDependencyGraph.discover(scope.manifest());
        ProjectRegistry.Resolution availability = repository == null || repository.isBlank()
                ? null : registry.resolve(repository);
        List<WorkspaceDependencyGraph.Edge> selected = graph.edges().stream()
                .filter(edge -> !crossRepositoryOnly || edge.crossRepository())
                .filter(edge -> repository == null || repository.isBlank()
                        || matchesDirection(edge, repository.strip(), normalizedDirection))
                .toList();
        int total = selected.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        ObjectNode root = JSON.createObjectNode();
        root.put("workspace_root", scope.root().toString());
        root.put("direction", normalizedDirection);
        root.put("cross_repository_only", crossRepositoryOnly);
        ArrayNode edges = root.putArray("dependencies");
        for (WorkspaceDependencyGraph.Edge edge : selected.subList(from, to)) {
            edges.add(edgeJson(edge));
        }
        ToolResponseSupport.appendPage(root, to - from, total, limit, offset);
        root.put("complete", graph.complete());
        appendDiagnostics(root, graph.diagnostics());
        if (availability != null) {
            ProjectAvailabilityResponses.append(root, availability.issues());
        }
        return root.toString();
    }

    String resolveEntity(String target) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        ProjectScope.Snapshot snapshot = scope.snapshot();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (ProjectScope.Project project : scope.select(snapshot, target)) {
            candidates.put("repository:" + project.name(), new Candidate(
                    "repository", project.name(), project.root().toString(), null, null, null));
        }

        ProjectRegistry.Resolution availability = registry.resolve();
        for (ProjectRegistry.ProjectEntry project : availability.projects()) {
            List<ClassRecord> classes = new ArrayList<>(
                    IndexReader.findClassesByName(project.jdbi(), target));
            if (classes.isEmpty()) classes.addAll(IndexReader.findClassesByShortName(
                    project.jdbi(), target));
            IndexReader.findClassByPath(project.jdbi(), target).ifPresent(classes::add);
            for (ClassRecord cls : classes) {
                if ("dependency".equals(cls.origin()) || "orphan_output".equals(cls.origin())) {
                    continue;
                }
                String key = project.name() + ":" + cls.className() + ":" + cls.sourceFile();
                candidates.put(key, new Candidate("class", project.name(), project.root().toString(),
                        cls.className(), cls.sourceFile(), cls.origin()));
            }
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("target", target);
        root.put("resolution", candidates.isEmpty() && !availability.issues().isEmpty() ? "unknown"
                : candidates.isEmpty() ? "not_found"
                : candidates.size() == 1 ? "resolved" : "ambiguous");
        ArrayNode values = root.putArray("candidates");
        candidates.values().forEach(candidate -> values.add(candidateJson(candidate)));
        if (candidates.isEmpty()) {
            ProjectAvailabilityResponses.append(root, availability.issues());
        }
        return root.toString();
    }

    String findUsages(String target, String providerRepository, String usageKind,
            int limit, int consumerLimit, int offset) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        ProjectRegistry.Resolution providerAvailability = null;
        if (providerRepository != null && !providerRepository.isBlank()) {
            providerAvailability = registry.resolve(providerRepository);
        }
        List<ProviderClass> providers = findProviderClasses(target, providerRepository);
        if (providers.isEmpty()) {
            if (providerAvailability != null && providerAvailability.projects().isEmpty()
                    && !providerAvailability.issues().isEmpty()) {
                return ProjectAvailabilityResponses.error(providerAvailability.issues());
            }
            return error("Workspace class not found: " + target);
        }
        if (providers.size() > 1) {
            ObjectNode ambiguous = JSON.createObjectNode();
            ambiguous.put("error", "ambiguous_workspace_class");
            ambiguous.put("target", target);
            ArrayNode values = ambiguous.putArray("candidates");
            providers.forEach(provider -> values.add(providerJson(provider)));
            return ambiguous.toString();
        }

        ProviderClass provider = providers.getFirst();
        WorkspaceCoordinateCatalog.Result catalog =
                WorkspaceCoordinateCatalog.discover(scope.manifest());
        WorkspaceCoordinateCatalog.Module providerModule = catalog.modules().stream()
                .filter(module -> module.repository().equals(provider.repository())
                        && module.module().equals(provider.module()))
                .findFirst().orElse(null);
        if (providerModule == null || providerModule.ga() == null) {
            return error("No build coordinate for provider module " + provider.repository()
                    + ":" + provider.module());
        }

        WorkspaceDependencyGraph.Result graph =
                WorkspaceDependencyGraph.discover(scope.manifest());
        List<WorkspaceDependencyGraph.Edge> consumers = graph.edges().stream()
                .filter(WorkspaceDependencyGraph.Edge::crossRepository)
                .filter(edge -> edge.providerRepository().equals(provider.repository())
                        && edge.providerModule().equals(provider.module()))
                .toList();
        Map<String, WorkspaceDependencyGraph.Edge> consumerEdges = new LinkedHashMap<>();
        consumers.forEach(edge -> consumerEdges.putIfAbsent(edge.consumerRepository(), edge));
        List<ConsumerUsage> usages = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        int unavailableConsumers = 0;
        UsageToolQueries usageQueries = new UsageToolQueries();
        List<ProjectRegistry.ProjectEntry> consumerProjects = new ArrayList<>();
        for (var consumer : consumerEdges.entrySet()) {
            ProjectRegistry.Resolution resolved = registry.resolve(consumer.getKey());
            if (resolved.projects().isEmpty()) {
                unavailableConsumers++;
                diagnostics.addAll(resolved.errors());
                continue;
            }
            consumerProjects.add(resolved.projects().getFirst());
        }
        List<ProjectQueryExecutor.QueryResult> queryResults =
                new ProjectQueryExecutor(registry).executeProjects(consumerProjects,
                        consumer -> consumerUsages(usageQueries, consumer.jdbi(),
                                provider.className(), usageKind, consumerLimit,
                                consumerEdges.get(consumer.name())));
        int failedConsumers = 0;
        int truncatedConsumers = 0;
        int resolvedConsumers = 0;
        List<ConsumerResolutionFailure> unresolvedConsumers = new ArrayList<>();
        for (ProjectQueryExecutor.QueryResult result : queryResults) {
            if (!result.successful()) {
                failedConsumers++;
                diagnostics.add("Repository '" + result.project()
                        + "' query failed: " + result.error());
                continue;
            }
            try {
                var data = JSON.readTree(result.json());
                if (hasToolError(data)) {
                    unresolvedConsumers.add(new ConsumerResolutionFailure(
                            result.project(), data));
                    continue;
                }
                resolvedConsumers++;
                if (data.path("usage_group_count").asInt() > 0) {
                    if (data.path("truncated").asBoolean()) truncatedConsumers++;
                    usages.add(new ConsumerUsage(result.project(),
                            consumerEdges.get(result.project()), data));
                }
            } catch (Exception invalid) {
                failedConsumers++;
                diagnostics.add("Repository '" + result.project()
                        + "' returned invalid usage data: " + invalid.getMessage());
            }
        }
        diagnostics.addAll(graph.diagnostics());

        usages.sort(java.util.Comparator.comparing(ConsumerUsage::repository));
        int total = usages.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        ObjectNode root = JSON.createObjectNode();
        root.put("target", provider.className());
        ObjectNode providerNode = root.putObject("provider");
        providerNode.put("repository", provider.repository());
        providerNode.put("module", provider.module());
        providerNode.put("coordinate", providerModule.ga());
        providerNode.put("version", providerModule.version());
        ArrayNode values = root.putArray("consumers");
        for (ConsumerUsage usage : usages.subList(from, to)) {
            ObjectNode node = values.addObject();
            node.put("repository", usage.repository());
            node.put("resolved_binary_version", usage.edge().resolvedBinaryVersion());
            node.put("version_status", usage.edge().status());
            node.set("usage", usage.data());
        }
        ToolResponseSupport.appendPage(root, to - from, total, limit, offset);
        root.put("candidate_consumer_count", consumerEdges.size());
        root.put("queried_consumer_count", consumerProjects.size());
        root.put("resolved_consumer_count", resolvedConsumers);
        root.put("unresolved_consumer_count", unresolvedConsumers.size());
        root.put("unavailable_consumer_count", unavailableConsumers);
        root.put("failed_consumer_count", failedConsumers);
        root.put("truncated_consumer_count", truncatedConsumers);
        root.put("consumer_usage_limit", consumerLimit);
        root.put("complete", graph.complete() && unavailableConsumers == 0
                && failedConsumers == 0 && unresolvedConsumers.isEmpty()
                && truncatedConsumers == 0);
        ArrayNode unresolved = root.putArray("unresolved_consumers");
        unresolvedConsumers.stream().limit(20).forEach(value -> {
            ObjectNode node = unresolved.addObject();
            node.put("repository", value.repository());
            node.put("status", errorCode(value.data(), "UNKNOWN_ERROR"));
            node.put("message", errorMessage(value.data(), "unknown error"));
            copyIfPresent(value.data(), node, "target_resolution");
            copyIfPresent(value.data(), node, "resolution_strategies_checked");
            copyIfPresent(value.data(), node, "dependency_evidence");
            copyIfPresent(value.data(), node, "limitations");
        });
        root.put("unresolved_consumers_truncated", unresolvedConsumers.size() > 20);
        appendDiagnostics(root, diagnostics);
        if (providerAvailability != null && !providerAvailability.issues().isEmpty()) {
            ProjectAvailabilityResponses.append(root, "provider_warnings",
                    providerAvailability.issues());
        }
        root.putArray("limitations").add(
                "Only repositories declaring the provider artifact are queried");
        return root.toString();
    }

    private static String consumerUsages(UsageToolQueries usageQueries, Jdbi jdbi,
            String target, String usageKind, int limit, WorkspaceDependencyGraph.Edge edge) {
        String response = usageQueries.findUsages(
                jdbi, target, usageKind, null, limit, 0, false);
        try {
            JsonNode parsed = JSON.readTree(response);
            if (!isClassNotFound(parsed)) {
                return response;
            }
            List<org.treblereel.mcp.model.ExternalBeanRecord> beans =
                    IndexReader.findExternalBeans(jdbi, Map.of("class_name", target));
            boolean includeDiscovery = usageKind == null || usageKind.isBlank()
                    || "all".equalsIgnoreCase(usageKind)
                    || "bean_discovery".equalsIgnoreCase(usageKind);
            List<org.treblereel.mcp.model.ExternalBeanRecord> matches = includeDiscovery
                    ? beans : List.of();
            List<org.treblereel.mcp.model.ExternalDepRecord> allReferences =
                    IndexReader.findExternalTypeUsages(jdbi, target);
            List<org.treblereel.mcp.model.ExternalDepRecord> references = allReferences.stream()
                            .filter(reference -> matchesExternalUsageKind(
                                    usageKind, reference.usageKind()))
                            .toList();
            if (beans.isEmpty() && allReferences.isEmpty()) {
                return unresolvedArtifactDependency(target, edge).toString();
            }
            Map<Integer, ClassRecord> callers = IndexReader.findClassesByIds(jdbi,
                    references.stream().map(org.treblereel.mcp.model.ExternalDepRecord::classId)
                            .distinct().toList());
            ObjectNode root = JSON.createObjectNode();
            root.put("target", target);
            root.put("granularity", beans.isEmpty() ? "dependency_type"
                    : allReferences.isEmpty() ? "dependency_bean" : "dependency_evidence");
            root.put("target_resolution", beans.isEmpty() ? "external_type_index"
                    : allReferences.isEmpty() ? "external_bean_index"
                    : "external_type_and_bean_index");
            appendResolutionStrategies(root);
            if (usageKind == null || usageKind.isBlank()) root.putNull("usage_kind");
            else root.put("usage_kind", usageKind);
            int total = matches.size() + references.size();
            root.put("usage_group_count", total);
            root.put("usage_occurrence_count", total);
            ArrayNode usages = root.putArray("usages");
            int[] remaining = {limit};
            matches.stream().limit(remaining[0]).forEach(bean -> {
                ObjectNode node = usages.addObject();
                node.put("class", bean.className());
                node.put("usage_kind", "bean_discovery");
                node.put("indexed_kind", "EXTERNAL_BEAN");
                node.put("occurrences", 1);
                node.put("origin", "dependency");
                node.put("framework", bean.framework());
                node.put("bean_kind", bean.kind());
                if (bean.scope() == null) node.putNull("scope");
                else node.put("scope", bean.scope());
                if (bean.artifact() == null) node.putNull("artifact");
                else node.put("artifact", bean.artifact());
                node.put("reason", "Framework bean discovery exposes this dependency bean "
                        + "without a direct application class reference");
                ArrayNode configuration = node.putArray("required_configuration");
                bean.injectionPoints().stream()
                        .filter(injection -> injection.configurationKey() != null
                                && injection.configurationRequired())
                        .forEach(injection -> configuration.addObject()
                                .put("key", injection.configurationKey())
                                .put("member", injection.member()));
                remaining[0]--;
            });
            references.stream().limit(remaining[0]).forEach(reference -> {
                ClassRecord caller = callers.get(reference.classId());
                if (caller == null) return;
                ObjectNode node = usages.addObject();
                node.put("class", caller.className());
                node.put("usage_kind", externalUsageKind(reference.usageKind()));
                node.put("indexed_kind", "EXTERNAL_" + reference.usageKind());
                node.put("occurrences", 1);
                node.put("origin", caller.origin());
                if (caller.sourceFile() == null) node.putNull("source");
                else node.put("source", caller.sourceFile() + ":" + caller.sourceLine());
                if (caller.module() == null) node.putNull("module");
                else node.put("module", caller.module());
                if (caller.sourceSet() == null) node.putNull("source_set");
                else node.put("source_set", caller.sourceSet());
                node.put("reason", "The consumer bytecode signature references the workspace "
                        + "provider type");
            });
            ToolResponseSupport.appendPage(root, usages.size(), total, limit, 0);
            Map<String, String> metadata = IndexReader.getMetadata(jdbi);
            ObjectNode snapshot = root.putObject("index_snapshot");
            snapshot.put("index_id", metadata.getOrDefault("index_id", "unknown"));
            snapshot.put("indexed_at", metadata.getOrDefault("indexed_at", "unknown"));
            snapshot.put("indexed_commit", metadata.getOrDefault("last_commit", "unknown"));
            snapshot.put("live_freshness_evaluated", false);
            return root.toString();
        } catch (Exception invalid) {
            return response;
        }
    }

    private static ObjectNode unresolvedArtifactDependency(
            String target, WorkspaceDependencyGraph.Edge edge) {
        ObjectNode root = JSON.createObjectNode();
        root.put("error", "artifact_dependency_without_class_evidence");
        root.put("message", "The repository declares the provider artifact, but its index has "
                + "no application class, external bean, or bytecode signature evidence for "
                + target + ". Artifact dependency alone does not prove use of this class.");
        root.put("target", target);
        root.put("target_resolution", "artifact_dependency_only");
        appendResolutionStrategies(root);
        ObjectNode evidence = root.putObject("dependency_evidence");
        if (edge == null) {
            evidence.put("available", false);
        } else {
            evidence.put("available", true);
            evidence.put("coordinate", edge.coordinate());
            evidence.put("consumer_module", edge.consumerModule());
            evidence.set("scopes", JSON.valueToTree(edge.scopes()));
            evidence.put("source_set", edge.sourceSet());
            evidence.put("evidence", edge.evidence());
            if (edge.resolvedBinaryVersion() == null) {
                evidence.putNull("resolved_binary_version");
            } else {
                evidence.put("resolved_binary_version", edge.resolvedBinaryVersion());
            }
            evidence.put("version_status", edge.status());
        }
        root.putArray("limitations")
                .add("Declared artifact dependency identifies candidate repositories, not "
                        + "concrete class usage")
                .add("Reflective, configuration-only, generated-after-index, and dynamically "
                        + "loaded references may not leave class-level bytecode evidence");
        return root;
    }

    private static void appendResolutionStrategies(ObjectNode root) {
        root.putArray("resolution_strategies_checked")
                .add("local_class_index")
                .add("external_bean_index")
                .add("external_type_index");
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String field) {
        if (source.has(field)) target.set(field, source.get(field));
    }

    private static boolean matchesExternalUsageKind(String requested, String indexed) {
        return requested == null || requested.isBlank() || "all".equalsIgnoreCase(requested)
                || requested.equalsIgnoreCase(externalUsageKind(indexed));
    }

    private static String externalUsageKind(String indexed) {
        return switch (indexed) {
            case "ANNOTATION" -> "annotation";
            case "EXTENDS", "IMPLEMENTS" -> "inheritance";
            default -> "type_reference";
        };
    }

    String routeMissingUsages(String response, String consumerRepository, String target,
            String usageKind, int limit, int offset) {
        if (registry.workspaceScope() == null) return response;
        try {
            JsonNode parsed = JSON.readTree(response);
            if (!isClassNotFound(parsed)) {
                return response;
            }
            List<ProviderClass> providers = findProviderClasses(target, null);
            if (providers.size() != 1) return response;
            ProviderClass provider = providers.getFirst();
            JsonNode workspaceResult = JSON.readTree(findUsages(target,
                    provider.repository(), usageKind, limit, Math.min(limit, 20), offset));
            ObjectNode root = JSON.createObjectNode();
            root.put("target", target);
            root.put("origin", "workspace_provider");
            root.putObject("local_resolution")
                    .put("status", "not_found")
                    .put("repository", consumerRepository)
                    .put("message", errorMessage(parsed));
            root.set("workspace_usage", workspaceResult);
            root.put("answer_complete", workspaceResult.path("complete").asBoolean(false));
            return root.toString();
        } catch (Exception ignored) {
            return response;
        }
    }

    String routeMissingClassQuery(String response, String consumerRepository, String target,
            String operation, Function<Jdbi, String> providerQuery) {
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
            List<ProviderClass> providers = findProviderClasses(target, null);
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

            JsonNode providerData = JSON.readTree(providerQuery.apply(
                    availability.projects().getFirst().jdbi()));
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

    String assessRisk(String target, String providerRepository, int maxDepth) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        if (providerRepository != null && !providerRepository.isBlank()) {
            ProjectRegistry.Resolution availability = registry.resolve(providerRepository);
            if (availability.projects().isEmpty() && !availability.issues().isEmpty()) {
                return ProjectAvailabilityResponses.error(availability.issues());
            }
        }
        List<ProviderClass> providers = findProviderClasses(target, providerRepository);
        if (providers.isEmpty()) return error("Workspace class not found: " + target);
        if (providers.size() > 1) {
            ObjectNode root = JSON.createObjectNode();
            root.put("error", "ambiguous_workspace_class");
            ArrayNode values = root.putArray("candidates");
            providers.forEach(provider -> values.add(providerJson(provider)));
            return root.toString();
        }
        ProviderClass provider = providers.getFirst();
        ProjectRegistry.Resolution providerResolution = registry.resolve(provider.repository());
        if (providerResolution.projects().isEmpty()) {
            return error("Provider repository is not indexed: " + provider.repository());
        }
        com.fasterxml.jackson.databind.JsonNode localRisk;
        try {
            localRisk = JSON.readTree(new ChangeRiskQueries().getRisk(
                    providerResolution.projects().getFirst().jdbi(), provider.className()));
        } catch (Exception invalid) {
            return error("Could not calculate provider risk: " + invalid.getMessage());
        }

        WorkspaceDependencyGraph.Result graph =
                WorkspaceDependencyGraph.discover(scope.manifest());
        Map<String, Integer> depths = downstreamDepths(
                graph.edges(), provider.repository(), maxDepth);
        List<DownstreamRisk> downstream = new ArrayList<>();
        int drifted = 0;
        for (var entry : depths.entrySet()) {
            WorkspaceDependencyGraph.Edge evidence = graph.edges().stream()
                    .filter(edge -> edge.consumerRepository().equals(entry.getKey()))
                    .filter(WorkspaceDependencyGraph.Edge::crossRepository)
                    .findFirst().orElse(null);
            if (evidence == null) continue;
            if ("binary_behind_checkout".equals(evidence.status())) drifted++;
            int usageGroups = 0;
            int impactedTests = 0;
            if (entry.getValue() == 1) {
                ProjectRegistry.Resolution consumer = registry.resolve(entry.getKey());
                if (!consumer.projects().isEmpty()) {
                    var jdbi = consumer.projects().getFirst().jdbi();
                    try {
                        var usage = JSON.readTree(new UsageToolQueries().findUsages(
                                jdbi, provider.className(), null, null, 1, 0));
                        usageGroups = usage.path("usage_group_count").asInt();
                        var tests = JSON.readTree(new TestImpactQueries().findImpactedTests(
                                jdbi, List.of(provider.className()), true, 3, 1, 0));
                        impactedTests = tests.path("total").asInt();
                    } catch (Exception ignored) {
                        // The dependency edge remains valid even when detailed evidence is absent.
                    }
                }
            }
            downstream.add(new DownstreamRisk(entry.getKey(), entry.getValue(),
                    evidence.coordinate(), evidence.status(), usageGroups, impactedTests,
                    entry.getValue() == 1 ? "high" : "medium"));
        }
        downstream.sort(java.util.Comparator.comparingInt(DownstreamRisk::depth)
                .thenComparing(DownstreamRisk::repository));

        double localScore = localRisk.path("risk_score").asDouble();
        long direct = depths.values().stream().filter(depth -> depth == 1).count();
        long transitive = depths.size() - direct;
        double workspaceScore = Math.min(10.0, Math.round((localScore + direct * 0.5
                + transitive * 0.2 + drifted * 0.5) * 10.0) / 10.0);
        ObjectNode root = JSON.createObjectNode();
        root.put("target", provider.className());
        root.put("provider_repository", provider.repository());
        root.put("workspace_risk_score", workspaceScore);
        root.put("workspace_risk_level", riskLevel(workspaceScore));
        root.set("provider_risk", localRisk);
        root.put("direct_consumer_count", direct);
        root.put("transitive_consumer_count", transitive);
        root.put("version_drift_count", drifted);
        ArrayNode downstreamValues = root.putArray("downstream");
        downstream.forEach(value -> downstreamValues.add(downstreamJson(value)));
        root.put("complete", graph.complete());
        appendDiagnostics(root, graph.diagnostics());
        root.putArray("limitations")
                .add("Transitive impact is repository-level dependency reachability")
                .add("Usage and test evidence is calculated only for direct consumers");
        ObjectNode scoring = root.putObject("scoring");
        scoring.put("strategy", "provider_risk_plus_downstream_reachability");
        scoring.put("direct_consumer_weight", 0.5);
        scoring.put("transitive_consumer_weight", 0.2);
        scoring.put("version_drift_weight", 0.5);
        return root.toString();
    }

    private static Map<String, Integer> downstreamDepths(
            List<WorkspaceDependencyGraph.Edge> edges, String provider, int maxDepth) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(provider);
        depths.put(provider, 0);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int depth = depths.get(current);
            if (depth >= maxDepth) continue;
            for (WorkspaceDependencyGraph.Edge edge : edges) {
                if (!edge.crossRepository()
                        || !edge.providerRepository().equals(current)) continue;
                int candidateDepth = depth + 1;
                Integer previous = depths.get(edge.consumerRepository());
                if (previous == null || candidateDepth < previous) {
                    depths.put(edge.consumerRepository(), candidateDepth);
                    queue.addLast(edge.consumerRepository());
                }
            }
        }
        depths.remove(provider);
        return depths;
    }

    private static String riskLevel(double score) {
        if (score >= 8) return "CRITICAL";
        if (score >= 6) return "HIGH";
        if (score >= 3) return "MEDIUM";
        return "LOW";
    }

    private List<ProviderClass> findProviderClasses(String target, String repository) {
        ProjectRegistry.Resolution projects = repository == null || repository.isBlank()
                ? registry.resolve() : registry.resolve(repository);
        Map<String, ProviderClass> candidates = new LinkedHashMap<>();
        for (ProjectRegistry.ProjectEntry project : projects.projects()) {
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(project.jdbi(), target);
            if (!lookup.found()) continue;
            ClassRecord cls = lookup.cls();
            if ("dependency".equals(cls.origin()) || "orphan_output".equals(cls.origin())) {
                continue;
            }
            String module = cls.module() == null || cls.module().isBlank() ? "." : cls.module();
            candidates.put(project.name() + ":" + cls.className() + ":" + module,
                    new ProviderClass(project.name(), module, cls.className(), cls.sourceFile()));
        }
        if (candidates.isEmpty()) findProviderSources(target, repository, candidates);
        return List.copyOf(candidates.values());
    }

    private void findProviderSources(
            String target, String repository, Map<String, ProviderClass> candidates) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null || target == null || !target.contains(".")
                || target.contains("/") || target.contains("\\")) return;
        WorkspaceCoordinateCatalog.Result catalog =
                WorkspaceCoordinateCatalog.discover(scope.manifest());
        for (WorkspaceCoordinateCatalog.Module module : catalog.modules()) {
            if (repository != null && !repository.isBlank()
                    && !module.repository().equalsIgnoreCase(repository.strip())) continue;
            java.nio.file.Path repositoryRoot = scope.root().resolve(
                    module.repositoryRelativePath()).normalize();
            java.nio.file.Path moduleRoot = ".".equals(module.module())
                    ? repositoryRoot : repositoryRoot.resolve(module.module());
            for (String sourceSet : List.of("main", "test")) {
                java.nio.file.Path source = JvmSourceFiles.findConventionalSource(
                        moduleRoot, sourceSet, target);
                if (source == null) continue;
                String sourceFile = repositoryRoot.relativize(source).toString()
                        .replace('\\', '/');
                String key = module.repository() + ':' + target + ':' + module.module();
                candidates.putIfAbsent(key, new ProviderClass(module.repository(),
                        module.module(), target, sourceFile));
            }
        }
    }

    private static boolean matchesDirection(WorkspaceDependencyGraph.Edge edge,
            String repository, String direction) {
        boolean provider = edge.providerRepository().equalsIgnoreCase(repository);
        boolean consumer = edge.consumerRepository().equalsIgnoreCase(repository);
        return switch (direction) {
            case "consumers" -> provider;
            case "providers" -> consumer;
            default -> provider || consumer;
        };
    }

    private static List<String> mergeDiagnostics(List<String>... groups) {
        Set<String> merged = new LinkedHashSet<>();
        for (List<String> group : groups) merged.addAll(group);
        return List.copyOf(merged);
    }

    private static void appendDiagnostics(ObjectNode root, List<String> diagnostics) {
        int limit = Math.min(10, diagnostics.size());
        root.put("diagnostic_count", diagnostics.size());
        root.set("diagnostics", JSON.valueToTree(diagnostics.subList(0, limit)));
        root.put("diagnostics_truncated", limit < diagnostics.size());
    }

    private static ObjectNode edgeJson(WorkspaceDependencyGraph.Edge edge) {
        ObjectNode node = JSON.createObjectNode();
        node.put("consumerRepository", edge.consumerRepository());
        node.put("consumerModule", edge.consumerModule());
        node.put("providerRepository", edge.providerRepository());
        node.put("providerModule", edge.providerModule());
        node.put("coordinate", edge.coordinate());
        ArrayNode scopes = node.putArray("scopes");
        edge.scopes().forEach(scopes::add);
        node.put("sourceSet", edge.sourceSet());
        node.put("evidence", edge.evidence());
        node.put("checkoutVersion", edge.checkoutVersion());
        node.put("resolvedBinaryVersion", edge.resolvedBinaryVersion());
        node.put("status", edge.status());
        node.put("crossRepository", edge.crossRepository());
        node.put("ambiguousProvider", edge.ambiguousProvider());
        return node;
    }

    private static ObjectNode candidateJson(Candidate candidate) {
        ObjectNode node = JSON.createObjectNode();
        node.put("kind", candidate.kind());
        node.put("repository", candidate.repository());
        node.put("repositoryRoot", candidate.repositoryRoot());
        node.put("className", candidate.className());
        node.put("sourceFile", candidate.sourceFile());
        node.put("origin", candidate.origin());
        return node;
    }

    private static ObjectNode providerJson(ProviderClass provider) {
        ObjectNode node = JSON.createObjectNode();
        node.put("repository", provider.repository());
        node.put("module", provider.module());
        node.put("className", provider.className());
        node.put("sourceFile", provider.sourceFile());
        return node;
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

    private static boolean appendFreshness(ObjectNode parent,
            ProjectRegistry.Resolution resolution) {
        ObjectNode freshness = parent.putObject("index_freshness");
        if (resolution.projects().isEmpty()) {
            freshness.put("status", "unavailable");
            freshness.put("complete", false);
            return false;
        }
        MetaEnvelope meta = MetaEnvelope.from(
                resolution.projects().getFirst().jdbi(), 0, 0);
        freshness.put("status", meta.structureStale() ? "stale" : "current");
        freshness.put("complete", !meta.structureStale());
        freshness.put("indexed_at", meta.indexedAt());
        freshness.put("indexed_commit", meta.lastCommit());
        if (meta.currentCommit() == null) freshness.putNull("current_commit");
        else freshness.put("current_commit", meta.currentCommit());
        freshness.put("worktree_dirty", meta.worktreeDirty());
        freshness.put("structure_stale", meta.structureStale());
        freshness.set("stale_reasons", JSON.valueToTree(meta.staleReasons()));
        return !meta.structureStale();
    }

    private static ObjectNode downstreamJson(DownstreamRisk risk) {
        ObjectNode node = JSON.createObjectNode();
        node.put("repository", risk.repository());
        node.put("depth", risk.depth());
        node.put("coordinate", risk.coordinate());
        node.put("versionStatus", risk.versionStatus());
        node.put("usageGroups", risk.usageGroups());
        node.put("impactedTests", risk.impactedTests());
        node.put("confidence", risk.confidence());
        return node;
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static boolean isClassNotFound(JsonNode node) {
        return "CLASS_NOT_FOUND".equals(node.path("error_code").asText())
                || errorMessage(node).startsWith("Class not found");
    }

    private static boolean hasToolError(JsonNode node) {
        return node.has("error_code") || node.has("error");
    }

    private static String errorCode(JsonNode node, String fallback) {
        String code = node.path("error_code").asText();
        if (!code.isBlank()) return code;
        String legacy = node.path("error").asText();
        return legacy.isBlank() ? fallback : legacy;
    }

    private static String errorMessage(JsonNode node) {
        return errorMessage(node, "");
    }

    private static String errorMessage(JsonNode node, String fallback) {
        String message = node.path("message").asText();
        if (!message.isBlank()) return message;
        return node.path("error").asText(fallback);
    }

    private static String workspaceRequired() {
        return JSON.createObjectNode().put("error", "workspace_mode_required")
                .put("message", "Start Quill with --workspace <path>").toString();
    }

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private record Candidate(String kind, String repository, String repositoryRoot,
            String className, String sourceFile, String origin) {}

    private record ProviderClass(String repository, String module, String className,
            String sourceFile) {}

    private record ConsumerUsage(String repository, WorkspaceDependencyGraph.Edge edge,
            com.fasterxml.jackson.databind.JsonNode data) {}

    private record ConsumerResolutionFailure(String repository, JsonNode data) {}

    private record DownstreamRisk(String repository, int depth, String coordinate,
            String versionStatus, int usageGroups, int impactedTests, String confidence) {}
}
