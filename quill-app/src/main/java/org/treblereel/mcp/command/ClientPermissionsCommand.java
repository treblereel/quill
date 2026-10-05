package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import org.treblereel.mcp.mcp.ReadOnlyToolNames;
import org.treblereel.mcp.workspace.WorkspaceManifestStore;
import org.treblereel.mcp.workspace.WorkspaceRepositoryStateStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "permissions", mixinStandardHelpOptions = true,
        description = "Inspect or manage Quill's local Claude tool permissions",
        subcommands = {ClientPermissionsCommand.Status.class, ClientPermissionsCommand.Grant.class,
                ClientPermissionsCommand.Revoke.class})
public final class ClientPermissionsCommand implements Callable<Integer> {
    @Override public Integer call() {
        System.err.println("Use: quill client permissions status|grant|revoke");
        return CommandLine.ExitCode.USAGE;
    }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Read local permission evidence; no writes")
    public static final class Status extends Operation { public Status() { super("status"); } }
    @Command(name = "grant", mixinStandardHelpOptions = true, description = "Explicitly allow current read-only Quill tools")
    public static final class Grant extends Operation { public Grant() { super("grant"); } }
    @Command(name = "revoke", mixinStandardHelpOptions = true, description = "Remove only Quill-owned grants, preserving user rules")
    public static final class Revoke extends Operation { public Revoke() { super("revoke"); } }

    abstract static class Operation implements Callable<Integer> {
        @Option(names = "--project", description = "Configuration directory (default: current directory)") Path project;
        @Option(names = "--workspace", description = "Initialized workspace and its recorded configured repositories") Path workspace;
        @Option(names = "--json", description = "Machine-readable result") boolean json;
        @Spec CommandLine.Model.CommandSpec spec;
        private final String operation;
        Operation(String operation) { this.operation = operation; }

        @Override public Integer call() throws Exception {
            if (project != null && workspace != null) {
                spec.commandLine().getErr().println("[quill] Choose --project or --workspace, not both");
                return CommandLine.ExitCode.USAGE;
            }
            List<Path> targets = targets();
            // Validate all targets before any permission mutation.
            var before = targets.stream().map(ClaudePermissionStatus::inspect).toList();
            if (!operation.equals("status") && before.stream().anyMatch(report -> !report.valid()
                    || (operation.equals("grant") && !report.server_configured()))) {
                spec.commandLine().getErr().println("[quill] No permissions changed: invalid local settings "
                        + "or missing project-local Quill MCP server. Run status --json for details.");
                return CommandLine.ExitCode.SOFTWARE;
            }
            for (int i = 0; i < targets.size(); i++) {
                Path target = targets.get(i);
                if (operation.equals("grant") && !before.get(i).missing_tools().isEmpty()
                        && !ClaudeSettingsInstaller.allowTools(target, ReadOnlyToolNames.all())) {
                    throw new java.io.IOException("Could not grant permissions in " + target);
                }
                if (operation.equals("revoke") && Files.exists(target.resolve(".claude/quill-permissions.json"))
                        && !ClaudeSettingsInstaller.removeOwnedPermissions(target)) {
                    throw new java.io.IOException("Could not revoke owned permissions in " + target);
                }
            }
            var reports = operation.equals("status") ? before
                    : targets.stream().map(ClaudePermissionStatus::inspect).toList();
            var out = spec.commandLine().getOut();
            if (json) {
                var document = new ObjectMapper().createObjectNode();
                document.put("schema_version", 1);
                document.put("operation", operation);
                document.set("projects", new ObjectMapper().valueToTree(reports));
                out.println(document);
            } else {
                for (var report : reports) {
                    out.println(report.project_root() + ": configured=" + report.server_configured()
                            + ", allowed=" + report.allowed_tools().size() + "/" + report.tool_count()
                            + ", denied=" + report.denied_tools().size() + ", ask=" + report.ask_tools().size()
                            + ", owned=" + report.owned_rules().size() + ", invalid_files=" + report.invalid_files().size());
                }
                out.println(ClaudePermissionStatus.LIMITATION);
                if (operation.equals("revoke")) out.println("Only Quill-owned grants were removed; pre-existing allows remain.");
            }
            out.flush();
            return reports.stream().allMatch(ClaudePermissionStatus.Report::valid)
                    ? CommandLine.ExitCode.OK : CommandLine.ExitCode.SOFTWARE;
        }

        private List<Path> targets() throws Exception {
            Path root = (workspace != null ? workspace : project != null ? project : Path.of("."))
                    .toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("Configuration directory does not exist: " + root);
            if (workspace == null) return List.of(root);
            WorkspaceManifestStore.read(root);
            LinkedHashSet<Path> targets = new LinkedHashSet<>();
            targets.add(root);
            for (var repository : WorkspaceRepositoryStateStore.read(root)) {
                if (repository.relativePath() == null) throw new IllegalArgumentException("Invalid workspace repository path");
                Path target = root.resolve(repository.relativePath()).normalize();
                if (!target.startsWith(root) || !Files.isDirectory(target)
                        || !target.toRealPath().startsWith(root.toRealPath())) {
                    throw new IllegalArgumentException("Workspace repository is missing or outside the workspace: " + target);
                }
                if (ClaudeSettingsInstaller.isEnabled(target)
                        || Files.exists(target.resolve(".claude/quill-permissions.json"))) targets.add(target);
            }
            return List.copyOf(targets);
        }
    }
}
