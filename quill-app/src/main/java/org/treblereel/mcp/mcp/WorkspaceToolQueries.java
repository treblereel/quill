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
        root.set("diagnostics", JSON.valueToTree(mergeDiagnostics(
                initialized.errors(), snapshot.diagnostics(), coordinates.diagnostics())));
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
            edges.add(JSON.valueToTree(edge));
        }
        ToolResponseSupport.appendPage(root, to - from, total, limit, offset);
        root.put("complete", graph.complete());
        root.set("diagnostics", JSON.valueToTree(graph.diagnostics()));
        return root.toString();
    }

    String resolveEntity(String target) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        ProjectScope.Snapshot snapshot = scope.snapshot();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (ProjectScope.Project project : scope.select(snapshot, target)) {
            candidates.put("repository:" + project.name(), new Candidate(
                    "repository", project.name(), project.root().toString(), null, null));
        }

        for (ProjectRegistry.ProjectEntry project : registry.resolve().projects()) {
            List<ClassRecord> classes = new ArrayList<>(
                    IndexReader.findClassesByName(project.jdbi(), target));
            if (classes.isEmpty()) classes.addAll(IndexReader.findClassesByShortName(
                    project.jdbi(), target));
            IndexReader.findClassByPath(project.jdbi(), target).ifPresent(classes::add);
            for (ClassRecord cls : classes) {
                String key = project.name() + ":" + cls.className() + ":" + cls.sourceFile();
                candidates.put(key, new Candidate("class", project.name(), project.root().toString(),
                        cls.className(), cls.sourceFile()));
            }
        }

        ObjectNode root = JSON.createObjectNode();
        root.put("target", target);
        root.put("resolution", candidates.isEmpty() ? "not_found"
                : candidates.size() == 1 ? "resolved" : "ambiguous");
        ArrayNode values = root.putArray("candidates");
        candidates.values().forEach(candidate -> values.add(JSON.valueToTree(candidate)));
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
            String className, String sourceFile) {}
}
