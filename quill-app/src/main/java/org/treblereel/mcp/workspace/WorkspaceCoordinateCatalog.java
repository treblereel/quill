package org.treblereel.mcp.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectCoordinatesDiscovery;

/** Workspace-wide mapping from build coordinates to local repository modules. */
public final class WorkspaceCoordinateCatalog {

    private record CacheEntry(String fingerprint, Result result) {}

    private static final ConcurrentHashMap<java.nio.file.Path, CacheEntry> CACHE =
            new ConcurrentHashMap<>();

    public record Module(
            String repository,
            String repositoryRelativePath,
            String module,
            String group,
            String artifact,
            String version,
            String buildSystem,
            String evidence) {
        public String ga() {
            return group == null || artifact == null ? null : group + ":" + artifact;
        }
    }

    public record Result(
            List<Module> modules,
            Map<String, List<Module>> modulesByGa,
            boolean complete,
            List<String> diagnostics) {
        public Result {
            modules = List.copyOf(modules);
            Map<String, List<Module>> copy = new LinkedHashMap<>();
            modulesByGa.forEach((coordinate, values) ->
                    copy.put(coordinate, List.copyOf(values)));
            modulesByGa = Map.copyOf(copy);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private WorkspaceCoordinateCatalog() {}

    public static Result discover(WorkspaceManifest manifest) {
        WorkspaceDiscovery.Result repositories = WorkspaceDiscovery.discover(manifest);
        java.nio.file.Path root = manifest.root().toAbsolutePath().normalize();
        CacheEntry cached = CACHE.get(root);
        if (cached != null) {
            String fingerprint = WorkspaceMetadataFingerprint.compute(
                    repositories, cached.result().modules(), false);
            if (cached.fingerprint().equals(fingerprint)) return cached.result();
        }
        return CACHE.compute(root, (ignored, current) -> {
            if (current != null) {
                String fingerprint = WorkspaceMetadataFingerprint.compute(
                        repositories, current.result().modules(), false);
                if (current.fingerprint().equals(fingerprint)) return current;
            }
            Result result = discoverUncached(repositories);
            String fingerprint = WorkspaceMetadataFingerprint.compute(
                    repositories, result.modules(), false);
            return new CacheEntry(fingerprint, result);
        }).result();
    }

    static void invalidate(java.nio.file.Path workspaceRoot) {
        CACHE.remove(workspaceRoot.toAbsolutePath().normalize());
    }

    private static Result discoverUncached(WorkspaceDiscovery.Result repositories) {
        List<Module> modules = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(repositories.diagnostics());
        boolean complete = repositories.diagnostics().isEmpty();
        for (WorkspaceDiscovery.Repository repository : repositories.repositories()) {
            try {
                BuildSystem.detect(repository.root());
            } catch (IllegalArgumentException unsupported) {
                // A workspace may intentionally contain documentation, web, and other non-Java
                // repositories. They are outside this catalog rather than incomplete Java data.
                continue;
            }
            ProjectCoordinatesDiscovery.Result coordinates =
                    ProjectCoordinatesDiscovery.discover(repository.root());
            complete &= coordinates.complete();
            coordinates.diagnostics().forEach(diagnostic -> diagnostics.add(
                    "Repository '" + repository.name() + "': " + diagnostic));
            for (ProjectCoordinatesDiscovery.Module module : coordinates.modules()) {
                modules.add(new Module(repository.name(), repository.relativePath(),
                        module.module(), module.group(), module.artifact(), module.version(),
                        module.buildSystem().name().toLowerCase(java.util.Locale.ROOT),
                        module.evidence()));
            }
        }
        modules.sort(Comparator.comparing(Module::repository).thenComparing(Module::module));
        Map<String, List<Module>> byGa = new LinkedHashMap<>();
        for (Module module : modules) {
            if (module.ga() != null) {
                byGa.computeIfAbsent(module.ga(), ignored -> new ArrayList<>()).add(module);
            }
        }
        byGa.forEach((ga, candidates) -> {
            if (candidates.size() > 1) {
                diagnostics.add("Coordinate '" + ga + "' is provided by "
                        + candidates.size() + " workspace modules: "
                        + candidates.stream().map(WorkspaceCoordinateCatalog::candidate)
                                .collect(Collectors.joining(", ")));
            }
        });
        return new Result(modules, byGa, complete, diagnostics);
    }

    private static String candidate(Module module) {
        return module.repository() + ":" + module.module()
                + (module.version() == null ? "" : "@" + module.version());
    }
}
