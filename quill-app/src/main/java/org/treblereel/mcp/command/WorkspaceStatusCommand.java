package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.workspace.WorkspaceManifest;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.mcp.WorkspaceProjectScope;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "status", mixinStandardHelpOptions = true,
        description = "Show the persisted workspace configuration")
public final class WorkspaceStatusCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Option(names = "--json", description = "Write machine-readable JSON to stdout")
    boolean json;

    @Override
    public Integer call() throws Exception {
        WorkspaceManifest manifest = WorkspaceManifestStore.read(workspaceRoot);
        var snapshot = new WorkspaceProjectScope(manifest.root()).snapshot();
        var discovery = WorkspaceDiscovery.discover(manifest);
        var repositoryStatuses = discovery.repositories().stream()
                .map(WorkspaceRepositoryStatusInspector::inspect)
                .toList();
        var coordinates = WorkspaceCoordinateCatalog.discover(manifest);
        if (json) {
            ObjectNode root = JSON.createObjectNode();
            root.put("workspace_root", manifest.root().toString());
            root.put("format_version", manifest.formatVersion());
            root.put("discovery_depth", manifest.discoveryDepth());
            root.set("excludes", JSON.valueToTree(manifest.excludes()));
            root.put("created_at", manifest.createdAt());
            root.put("manifest", WorkspaceManifestStore.manifest(manifest.root()).toString());
            root.put("repository_count", snapshot.projects().size());
            root.set("repositories", JSON.valueToTree(repositoryStatuses));
            root.set("diagnostics", JSON.valueToTree(snapshot.diagnostics()));
            root.put("module_count", coordinates.modules().size());
            root.put("coordinates_complete", coordinates.complete());
            root.set("coordinate_diagnostics", JSON.valueToTree(coordinates.diagnostics()));
            System.out.println(root);
        } else {
            System.out.println("Workspace: " + manifest.root());
            System.out.println("  Format:          " + manifest.formatVersion());
            System.out.println("  Discovery depth: " + manifest.discoveryDepth());
            System.out.println("  Manifest:        "
                    + WorkspaceManifestStore.manifest(manifest.root()));
            System.out.println("  Repositories:    " + snapshot.projects().size());
            long ready = repositoryStatuses.stream()
                    .filter(WorkspaceRepositoryStatusInspector.Status::queryReady).count();
            long unsupported = repositoryStatuses.stream()
                    .filter(status -> !status.supported()).count();
            System.out.println("  Query ready:     " + ready);
            System.out.println("  Unsupported:     " + unsupported);
            System.out.println("  Modules:         " + coordinates.modules().size());
            System.out.println("  Coordinates:     "
                    + (coordinates.complete() ? "complete" : "incomplete"));
            repositoryStatuses.forEach(status -> System.out.println("    " + status.name()
                    + ": " + summary(status)));
        }
        return CommandLine.ExitCode.OK;
    }

    private static String summary(WorkspaceRepositoryStatusInspector.Status status) {
        if (!status.supported()) return "unsupported";
        if (!status.compiled()) return "build required";
        if (!status.indexed()) return "index missing";
        if (!status.fresh()) return "indexed (" + status.indexHealth() + ")";
        return "ready";
    }
}
