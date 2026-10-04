package org.treblereel.mcp.command;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.treblereel.mcp.workspace.WorkspaceManifest;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceLock;
import org.treblereel.mcp.workspace.WorkspaceDiscovery;
import org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", mixinStandardHelpOptions = true,
        description = "Initialize a Quill workspace and index suitable repositories without "
                + "running their builds")
public final class WorkspaceInitCommand implements Callable<Integer> {

    @Option(names = "--project", description = "Path to the workspace root")
    Path workspaceRoot;

    @Option(names = "--depth", description = "Maximum repository discovery depth (default: 1)")
    int discoveryDepth = 1;

    @Option(names = "--index-only", description = "Skip build integration and repository "
            + "configuration while creating indexes")
    boolean indexOnly;

    @Option(names = "--jobs", defaultValue = "4",
            showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
            description = "Maximum repositories to process concurrently")
    int jobs = WorkspaceRepositoryInitializer.DEFAULT_JOBS;

    @Option(names = "--probe-mcp", description = "After initialization, execute the workspace "
            + ".mcp.json launcher and check MCP connectivity (30s timeout; no AI requests)")
    boolean probeMcp;

    @Option(names = "--allow-quill-tools", negatable = true,
            description = "Allow current read-only Quill tools in local Claude permissions; "
                    + "--no-allow-quill-tools skips the interactive consent question")
    Boolean allowQuillTools;

    @Override
    public Integer call() throws Exception {
        if (jobs < 1) throw new IllegalArgumentException("--jobs must be at least 1");
        if (indexOnly && (probeMcp || Boolean.TRUE.equals(allowQuillTools))) {
            System.err.println("[quill] --probe-mcp/--allow-quill-tools cannot be combined with --index-only");
            return CommandLine.ExitCode.USAGE;
        }
        WorkspaceManifest manifest = WorkspaceManifestStore.initialize(
                workspaceRoot, discoveryDepth);
        System.out.println("Initialized Quill workspace at " + manifest.root());
        System.out.println("Manifest: " + WorkspaceManifestStore.manifest(manifest.root()));
        WorkspaceLock lock = WorkspaceLock.tryAcquire(manifest.root());
        if (lock == null) {
            System.err.println("[quill] Workspace is in use: " + manifest.root());
            return CommandLine.ExitCode.SOFTWARE;
        }
        WorkspaceRepositoryInitializer.Result result;
        try (lock) {
            WorkspaceDiscovery.Result discovery = WorkspaceDiscovery.discover(manifest);
            result = WorkspaceRepositoryInitializer.initializeAll(
                    discovery, indexOnly, jobs, System.out::println);
            WorkspaceClientConfiguration.install(
                    manifest.root(), discovery.repositories(), indexOnly);
            if (!indexOnly && result.successful()) {
                ClaudePermissionConsent.configure(allowQuillTools,
                        WorkspaceClientConfiguration.supportedTargets(
                                manifest.root(), discovery.repositories()));
            }
            WorkspaceRepositoryStateStore.write(manifest.root(), discovery.repositories());
        }
        System.out.println("Workspace initialization complete: indexed=" + result.indexed()
                + ", pending_build=" + result.pendingBuild()
                + ", metadata_only=" + result.metadataOnly()
                + ", skipped=" + result.skipped() + ", failed=" + result.failed()
                + ", unchanged=" + result.unchanged());
        if (!result.successful()) return CommandLine.ExitCode.SOFTWARE;
        McpConnectivityProbe.guidance(indexOnly);
        if (probeMcp) {
            DoctorCommand.Check check = McpConnectivityProbe.inspect(manifest.root());
            System.out.println("[" + check.status().label() + "] " + check.message());
            if (check.action() != null) System.out.println("Action: " + check.action());
            if (check.status() == DoctorCommand.Status.ERROR) return CommandLine.ExitCode.SOFTWARE;
        }
        return CommandLine.ExitCode.OK;
    }
}
