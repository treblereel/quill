package org.treblereel.mcp.workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.DeclaredDependencyDiscovery;
import org.treblereel.mcp.core.DependencyIndexer;

/** Resolves declared module dependencies onto providers in the same workspace. */
public final class WorkspaceDependencyGraph {

    public record Edge(
            String consumerRepository,
            String consumerModule,
            String providerRepository,
            String providerModule,
            String coordinate,
            Set<String> scopes,
            String checkoutVersion,
            String resolvedBinaryVersion,
            String status,
            boolean crossRepository,
            boolean ambiguousProvider) {
        public Edge {
            scopes = Set.copyOf(scopes);
        }
    }

    public record Result(List<Edge> edges, boolean complete, List<String> diagnostics) {
        public Result {
            edges = List.copyOf(edges);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private WorkspaceDependencyGraph() {}

    public static Result discover(WorkspaceManifest manifest) {
        WorkspaceCoordinateCatalog.Result catalog = WorkspaceCoordinateCatalog.discover(manifest);
        WorkspaceDiscovery.Result repositories = WorkspaceDiscovery.discover(manifest);
        Map<String, Path> roots = new LinkedHashMap<>();
        repositories.repositories().forEach(repository ->
                roots.put(repository.name(), repository.root()));
        Map<String, List<WorkspaceCoordinateCatalog.Module>> modulesByRepository =
                new LinkedHashMap<>();
        catalog.modules().forEach(module -> modulesByRepository
                .computeIfAbsent(module.repository(), ignored -> new ArrayList<>()).add(module));

        List<Edge> edges = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>(catalog.diagnostics());
        boolean complete = catalog.complete();
        for (var repository : modulesByRepository.entrySet()) {
            Path root = roots.get(repository.getKey());
            if (root == null || repository.getValue().isEmpty()) continue;
            BuildSystem buildSystem = BuildSystem.valueOf(
                    repository.getValue().getFirst().buildSystem()
                            .toUpperCase(java.util.Locale.ROOT));
            Set<String> moduleNames = new LinkedHashSet<>();
            repository.getValue().forEach(module -> moduleNames.add(module.module()));
            DeclaredDependencyDiscovery.Result declarations =
                    DeclaredDependencyDiscovery.discover(root, buildSystem, moduleNames);
            complete &= declarations.complete();
            if (!declarations.complete()) {
                diagnostics.add("Repository '" + repository.getKey()
                        + "': direct dependency metadata is incomplete");
            }
            for (var module : declarations.dependenciesByModule().entrySet()) {
                Path moduleRoot = module.getKey().equals(".")
                        ? root : root.resolve(module.getKey());
                List<Path> classpath = DependencyIndexer.parseClasspathFile(
                        buildSystem.classpathFile(moduleRoot));
                for (String dependency : module.getValue()) {
                    List<WorkspaceCoordinateCatalog.Module> providers =
                            catalog.modulesByGa().getOrDefault(dependency, List.of());
                    if (providers.isEmpty()) continue;
                    String binaryVersion = resolvedVersion(classpath, dependency);
                    Set<String> scopes = declarations.scopesByModule()
                            .getOrDefault(module.getKey(), Map.of())
                            .getOrDefault(dependency, Set.of());
                    boolean ambiguous = providers.size() > 1;
                    for (WorkspaceCoordinateCatalog.Module provider : providers) {
                        edges.add(new Edge(repository.getKey(), module.getKey(),
                                provider.repository(), provider.module(), dependency, scopes,
                                provider.version(), binaryVersion,
                                status(provider.version(), binaryVersion, ambiguous),
                                !repository.getKey().equals(provider.repository()), ambiguous));
                    }
                }
            }
        }
        edges.sort(Comparator.comparing(Edge::consumerRepository)
                .thenComparing(Edge::consumerModule)
                .thenComparing(Edge::coordinate)
                .thenComparing(Edge::providerRepository));
        return new Result(edges, complete, diagnostics);
    }

    static String resolvedVersion(List<Path> jars, String ga) {
        int separator = ga.indexOf(':');
        if (separator <= 0 || separator == ga.length() - 1) return null;
        String group = ga.substring(0, separator);
        String artifact = ga.substring(separator + 1);
        for (Path jar : jars) {
            Path absolute = jar.toAbsolutePath().normalize();
            Path version = absolute.getParent();
            if (version == null) continue;
            Path artifactDirectory = version.getParent();
            if (artifactDirectory != null
                    && artifactDirectory.getFileName().toString().equals(artifact)
                    && groupMatchesMavenPath(artifactDirectory.getParent(), group)) {
                return version.getFileName().toString();
            }
            // Gradle cache: group/artifact/version/hash/artifact-version.jar
            Path hash = version;
            Path gradleVersion = hash.getParent();
            Path gradleArtifact = gradleVersion == null ? null : gradleVersion.getParent();
            Path gradleGroup = gradleArtifact == null ? null : gradleArtifact.getParent();
            if (gradleArtifact != null && gradleGroup != null
                    && gradleArtifact.getFileName().toString().equals(artifact)
                    && gradleGroup.getFileName().toString().equals(group)) {
                return gradleVersion.getFileName().toString();
            }
        }
        return null;
    }

    private static boolean groupMatchesMavenPath(Path directory, String group) {
        if (directory == null) return false;
        String[] segments = group.split("\\.");
        Path current = directory;
        for (int i = segments.length - 1; i >= 0; i--) {
            if (current == null || !current.getFileName().toString().equals(segments[i])) {
                return false;
            }
            current = current.getParent();
        }
        return true;
    }

    private static String status(String checkoutVersion, String binaryVersion,
            boolean ambiguous) {
        if (ambiguous) return "ambiguous_provider";
        if (binaryVersion == null) return "local_provider_binary_unresolved";
        if (checkoutVersion == null) return "checkout_version_unknown";
        return checkoutVersion.equals(binaryVersion)
                ? "version_match" : "binary_behind_checkout";
    }
}
