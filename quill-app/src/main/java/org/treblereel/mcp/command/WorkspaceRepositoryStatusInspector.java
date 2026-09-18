package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;

/** Produces a compact readiness snapshot for one workspace repository. */
final class WorkspaceRepositoryStatusInspector {

    record Status(
            String name,
            Path root,
            boolean supported,
            String buildSystem,
            boolean compiled,
            int compiledDirectories,
            boolean indexed,
            String indexHealth,
            Integer indexSchema,
            int currentSchema,
            boolean fresh,
            List<String> staleReasons,
            String buildIntegration,
            boolean queryReady,
            String diagnostic) {
        Status {
            staleReasons = List.copyOf(staleReasons);
        }
    }

    private WorkspaceRepositoryStatusInspector() {}

    static Status inspect(WorkspaceDiscovery.Repository repository) {
        Path root = repository.root();
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(root);
        } catch (IllegalArgumentException unsupported) {
            return new Status(repository.name(), root, false, null, false, 0,
                    false, "unsupported", null, QuillDatabase.currentSchemaVersion(),
                    false, List.of(), "not_applicable", false,
                    "Unsupported Java project");
        }

        List<Path> compiledDirectories = ProjectInitializer.findClassesDirs(root);
        ProjectDiagnostics.Report index = ProjectDiagnostics.inspect(root);
        BuildIntegrationInstaller.Inspection integration =
                BuildIntegrationInstaller.inspect(root);
        Integer schema = index.database() == null
                ? null : QuillDatabase.inspectSchemaVersion(index.database());
        List<String> staleReasons = index.staleReasons();
        boolean fresh = index.indexed() && staleReasons.isEmpty();
        String diagnostic = index.indexed() ? null : index.errorMessage();
        return new Status(repository.name(), root, true,
                buildSystem.name().toLowerCase(Locale.ROOT),
                !compiledDirectories.isEmpty(), compiledDirectories.size(),
                index.indexed(), index.health(), schema, QuillDatabase.currentSchemaVersion(),
                fresh, staleReasons, integration.state().name().toLowerCase(Locale.ROOT),
                index.indexed(), diagnostic);
    }
}
