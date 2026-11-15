package org.treblereel.mcp.mcp.tools;

import static org.treblereel.mcp.mcp.tools.WorkspaceResponses.*;

import org.treblereel.mcp.workspace.ProjectScope;
import org.treblereel.mcp.workspace.WorkspaceProjectScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.SymbolRepository;
import org.treblereel.mcp.db.IndexRepository;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.workspace.WorkspaceDependencyGraph;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.mcp.support.ToolResponseSupport;
import org.treblereel.mcp.core.Json;

/** Workspace inventory: compact overview, repositories, dependency edges and entity resolution. */
final class WorkspaceCatalogQueries {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final ProjectRegistry registry;

    WorkspaceCatalogQueries(ProjectRegistry registry) {
        this.registry = registry;
    }

    String getCompactOverview(int limit, int offset) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) {
            ObjectNode error = ToolResponseSupport.appendError(JSON.createObjectNode(),
                    "WORKSPACE_MODE_REQUIRED",
                    "Compact overview requires --workspace mode. This is a single-project server; retry get_overview with view=full and omit limit/offset. This does not mean the index is missing.");
            error.put("server_mode", "single_project");
            error.put("retryable", true);
            ObjectNode retry = error.putObject("retry_with");
            retry.put("tool", "get_overview");
            retry.putObject("arguments").put("view", "full");
            return error.toString();
        }
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
                node.setAll(ProjectAvailabilityResponses.summary(issue));
            }
            try {
                appendFreshness(node, resolution);
                if (!resolution.projects().isEmpty()) {
                    try (ReadQueryContext db = ReadQueryContext.open(resolution.projects().getFirst().jdbi())) {
                    Map<String, String> metadata = db.index.getMetadata();
                    node.put("framework", metadata.getOrDefault("framework", "unknown"));
                    node.put("indexed_classes", db.symbols.countClasses());
                    node.put("indexed_beans", db.beans.countBeans());
                }
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
                Map<String, String> metadata = entry.jdbi().withHandle(repositoryHandle1 -> new IndexRepository(repositoryHandle1).getMetadata());
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
                    project.jdbi().withHandle(repositoryHandle2 -> new SymbolRepository(repositoryHandle2).findClassesByName(target)));
            if (classes.isEmpty()) classes.addAll(project.jdbi().withHandle(repositoryHandle3 -> new SymbolRepository(repositoryHandle3).findClassesByShortName(target)));
            project.jdbi().withHandle(repositoryHandle4 -> new SymbolRepository(repositoryHandle4).findClassByPath(target)).ifPresent(classes::add);
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

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private record Candidate(String kind, String repository, String repositoryRoot,
            String className, String sourceFile, String origin) {}
}
