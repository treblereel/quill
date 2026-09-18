package org.treblereel.mcp.workspace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.ProjectCoordinatesDiscovery;

/** Workspace-wide mapping from build coordinates to local repository modules. */
public final class WorkspaceCoordinateCatalog {

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
        List<Module> modules = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(repositories.diagnostics());
        boolean complete = repositories.diagnostics().isEmpty();
        for (WorkspaceDiscovery.Repository repository : repositories.repositories()) {
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
                        + candidates.size() + " workspace modules");
            }
        });
        return new Result(modules, byGa, complete, diagnostics);
    }
}
