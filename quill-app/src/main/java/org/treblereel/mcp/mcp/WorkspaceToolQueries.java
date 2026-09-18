package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.workspace.WorkspaceDependencyGraph;

/** Structured workspace catalog, dependency, and entity-resolution responses. */
final class WorkspaceToolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProjectRegistry registry;

    WorkspaceToolQueries(ProjectRegistry registry) {
        this.registry = registry;
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
            int limit, int offset) {
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
        int queriedConsumers = 0;
        int unavailableConsumers = 0;
        UsageToolQueries usageQueries = new UsageToolQueries();
        for (var consumer : consumerEdges.entrySet()) {
            ProjectRegistry.Resolution resolved = registry.resolve(consumer.getKey());
            if (resolved.projects().isEmpty()) {
                unavailableConsumers++;
                diagnostics.addAll(resolved.errors());
                continue;
            }
            queriedConsumers++;
            String json = usageQueries.findUsages(resolved.projects().getFirst().jdbi(),
                    provider.className(), usageKind, null, 200, 0);
            try {
                var data = JSON.readTree(json);
                if (!data.has("error") && data.path("usage_group_count").asInt() > 0) {
                    usages.add(new ConsumerUsage(consumer.getKey(), consumer.getValue(), data));
                }
            } catch (Exception invalid) {
                diagnostics.add("Repository '" + consumer.getKey()
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
        root.put("queried_consumer_count", queriedConsumers);
        root.put("unavailable_consumer_count", unavailableConsumers);
        root.put("complete", graph.complete());
        appendDiagnostics(root, diagnostics);
        root.putArray("limitations").add(
                "Only repositories declaring the provider artifact are queried");
        return root.toString();
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
            String module = cls.module() == null || cls.module().isBlank() ? "." : cls.module();
            candidates.put(project.name() + ":" + cls.className() + ":" + module,
                    new ProviderClass(project.name(), module, cls.className(), cls.sourceFile()));
        }
        return List.copyOf(candidates.values());
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

    private record DownstreamRisk(String repository, int depth, String coordinate,
            String versionStatus, int usageGroups, int impactedTests, String confidence) {}
}
