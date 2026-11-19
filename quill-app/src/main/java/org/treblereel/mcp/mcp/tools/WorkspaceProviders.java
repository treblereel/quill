package org.treblereel.mcp.mcp.tools;

import static org.treblereel.mcp.mcp.tools.WorkspaceResponses.*;

import org.treblereel.mcp.workspace.WorkspaceProjectScope;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.core.JvmSourceFiles;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.mcp.support.ClassTargetResolver;
import org.treblereel.mcp.mcp.project.ProjectRegistry;

/** Finds the workspace repository and module that declare a class. */
final class WorkspaceProviders {

    private final ProjectRegistry registry;

    WorkspaceProviders(ProjectRegistry registry) {
        this.registry = registry;
    }

    List<ProviderClass> findProviderClasses(String target, String repository) {
        ProjectRegistry.Resolution projects = repository == null || repository.isBlank()
                ? registry.resolve() : registry.resolve(repository);
        Map<String, ProviderClass> candidates = new LinkedHashMap<>();
        for (ProjectRegistry.ProjectEntry project : projects.projects()) {
            ClassTargetResolver.Lookup lookup = project.read(context -> ClassTargetResolver.resolve(context, target));
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
}
