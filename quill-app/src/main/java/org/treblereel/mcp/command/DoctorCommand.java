package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Static project checks by default; explicit MCP probing may refresh derived index state. */
@Command(name = "doctor", mixinStandardHelpOptions = true,
        description = "Diagnose project integration, compiled outputs, and index freshness")
public class DoctorCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--json", description = "Write machine-readable JSON to stdout")
    boolean json;

    @Option(names = "--probe-mcp", description = "Execute the project .mcp.json launcher and check "
            + "MCP connectivity (30s timeout; no AI requests; may refresh index state)")
    boolean probeMcp;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        Path root = ProjectRootFinder.find(projectPath);
        Report report = inspect(root);
        if (probeMcp) {
            List<Check> checks = new ArrayList<>(report.checks());
            checks.add(McpConnectivityProbe.inspect(root));
            report = new Report(report.projectRoot(), List.copyOf(checks));
        }
        PrintWriter output = spec != null ? spec.commandLine().getOut() : new PrintWriter(System.out);
        output.println(json ? toJson(report) : toText(report));
        output.flush();
        return report.hasErrors()
                ? picocli.CommandLine.ExitCode.SOFTWARE : picocli.CommandLine.ExitCode.OK;
    }

    static Report inspect(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        List<Check> checks = new ArrayList<>();
        BuildSystem buildSystem = BuildSystem.detect(normalized);
        checks.add(Check.pass("project", "Detected "
                + buildSystem.name().toLowerCase(Locale.ROOT) + " project at " + normalized));

        List<Path> classesDirectories = ProjectInitializer.findMainClassesDirs(normalized);
        if (classesDirectories.isEmpty()) {
            checks.add(Check.error("compiled_outputs", "No compiled main classes were found",
                    buildSystem == BuildSystem.MAVEN
                            ? "Run the project's Maven compile/package command"
                            : "Run the project's Gradle classes/build command"));
        } else {
            checks.add(Check.pass("compiled_outputs", "Found " + classesDirectories.size()
                    + " compiled main class director"
                    + (classesDirectories.size() == 1 ? "y" : "ies")));
        }

        ProjectDiagnostics.Report diagnostics = ProjectDiagnostics.inspect(normalized);
        if (!diagnostics.indexed()) {
            String action = switch (diagnostics.errorCode()) {
                case "INDEX_REFRESH_PENDING" -> "Start Quill through MCP or make any MCP tool request";
                case "INDEX_INCOMPATIBLE" -> "Run `quill init --project " + normalized
                        + "` with the current Quill binary";
                default -> "Run `quill init --project " + normalized
                        + "` after compiling the project";
            };
            checks.add(Check.error("index", diagnostics.errorMessage(), action));
        } else {
            switch (diagnostics.health()) {
                case "healthy" -> checks.add(Check.pass("index", "Index is healthy at generation "
                        + diagnostics.freshness().indexId()));
                case "recovered" -> checks.add(Check.warning("index",
                        "Index is usable after automatic recovery", "Run `quill update --force`"
                                + " to publish a clean generation"));
                default -> checks.add(Check.warning("index", "Index is " + diagnostics.health()
                                + ": " + String.join(", ", diagnostics.staleReasons()),
                        "Compile structural changes, then run an MCP request or `quill update`"));
            }
            if (UpdateCommand.hasProjectChanges(normalized, diagnostics.database())) {
                checks.add(Check.warning("project_fingerprint",
                        "Compiled outputs or project inputs differ from the active index",
                        "After a successful build, run an MCP request or `quill update`"));
            } else {
                checks.add(Check.pass("project_fingerprint",
                        "Compiled outputs and project inputs match the active index"));
            }
            String dependencyStatus = diagnostics.metadata()
                    .getOrDefault("dependency_index", "unknown");
            if ("complete".equals(dependencyStatus)) {
                checks.add(Check.pass("dependency_index", diagnostics.metadata()
                        .getOrDefault("dependency_index_detail", "Dependency index is complete")));
            } else {
                checks.add(Check.warning("dependency_index", "Dependency index is "
                                + dependencyStatus + ": " + diagnostics.metadata()
                                .getOrDefault("dependency_index_detail", "no detail"),
                        "Check build-tool dependency resolution and run `quill update --force`"));
            }
            addTestIndexCheck(checks, diagnostics.metadata(), buildSystem);
        }

        BuildIntegrationInstaller.Inspection integration =
                BuildIntegrationInstaller.inspect(normalized);
        switch (integration.state()) {
            case INSTALLED -> checks.add(Check.pass("build_integration", integration.detail()));
            case MISSING -> checks.add(Check.warning("build_integration", integration.detail(),
                    "Run `quill init --project " + normalized + "` without --index-only"));
            case OUTDATED -> checks.add(Check.warning("build_integration", integration.detail(),
                    "Run `quill init --project " + normalized + "` to update it"));
            case INVALID -> checks.add(Check.error("build_integration", integration.detail(),
                    "Repair " + integration.path() + " or run `quill clean` followed by `quill init`"));
        }

        long pendingEvents = countPendingEvents(normalized);
        if (pendingEvents == 0) {
            checks.add(Check.pass("build_events", "No pending build events"));
        } else {
            checks.add(Check.warning("build_events", pendingEvents + " build event"
                            + (pendingEvents == 1 ? " is" : "s are") + " waiting to be consumed",
                    "Start Quill through MCP or make any MCP tool request"));
        }

        if (gitignoreContainsQuill(normalized)) {
            checks.add(Check.pass("gitignore", ".quill/ is ignored by the project"));
        } else {
            checks.add(Check.warning("gitignore", ".quill/ is not listed in .gitignore",
                    "Add `.quill/` to " + normalized.resolve(".gitignore")));
        }

        List<String> clients = configuredClients(normalized);
        if (clients.isEmpty()) {
            checks.add(Check.info("mcp_configuration",
                    "No project-local Claude Code or Codex configuration was detected",
                    null));
        } else {
            checks.add(Check.pass("mcp_configuration",
                    "Project-local MCP configuration detected for " + String.join(" and ", clients)));
        }
        List<String> brokenLaunchers = brokenClientLaunchers(normalized);
        if (clients.contains("Codex")) {
            checks.add(Check.info("codex_activation",
                    "Project configuration is present; live Codex tool activation was not checked",
                    "Trust this project in Codex and start a fresh session, then confirm get_overview "
                            + "is callable. Project-local config requires project trust; empty MCP "
                            + "resources do not prove missing tools. For headless automation, pass "
                            + "trust with a projects inline-table override (not a quoted dotted key), "
                            + "or use a trusted user profile or explicit mcp_servers.quill transport overrides."));
        }
        if (!brokenLaunchers.isEmpty()) {
            checks.add(Check.error("mcp_launcher",
                    "Configured Quill launcher does not exist or is not executable: "
                            + String.join(", ", brokenLaunchers),
                    "Repair the project-local MCP configuration or run `quill init` with a working native launcher"));
        } else if (!clients.isEmpty()) {
            checks.add(Check.pass("mcp_launcher", "Configured Quill launcher paths are usable"));
        }
        switch (ProjectConfiguration.inspectClaudeMd(normalized)) {
            case CURRENT -> checks.add(Check.pass("claude_instructions",
                    "CLAUDE.md contains the current managed Quill guidance"));
            case MISSING -> checks.add(Check.warning("claude_instructions",
                    "CLAUDE.md does not contain managed Quill guidance",
                    "Run `quill init --project " + normalized + "`"));
            case INVALID -> checks.add(Check.warning("claude_instructions",
                    "CLAUDE.md contains an incomplete managed Quill block",
                    "Repair Quill marker boundaries in CLAUDE.md, then run `quill init`"));
            case OUTDATED -> checks.add(Check.warning("claude_instructions",
                    "CLAUDE.md contains outdated or modified managed Quill guidance",
                    "Run `quill init --project " + normalized + "` to refresh it"));
        }
        switch (ProjectConfiguration.inspectAgentsMd(normalized)) {
            case CURRENT -> checks.add(Check.pass("codex_instructions",
                    "AGENTS.md contains the current managed Quill guidance"));
            case MISSING -> checks.add(Check.warning("codex_instructions",
                    "AGENTS.md does not contain managed Quill guidance",
                    "Run `quill init --project " + normalized + "`"));
            case INVALID -> checks.add(Check.warning("codex_instructions",
                    "AGENTS.md contains an incomplete managed Quill block",
                    "Repair Quill marker boundaries in AGENTS.md, then run `quill init`"));
            case OUTDATED -> checks.add(Check.warning("codex_instructions",
                    "AGENTS.md contains outdated or modified managed Quill guidance",
                    "Run `quill init --project " + normalized + "` to refresh it"));
        }
        if (ClaudeSettingsInstaller.isEnabled(normalized)) {
            checks.add(Check.pass("claude_approval",
                    "Claude Code project MCP configuration explicitly enables Quill"));
        } else {
            checks.add(Check.warning("claude_approval",
                    "Claude Code may require interactive approval before starting Quill",
                    "Run `quill init --project " + normalized + "`"));
        }
        if (claudeAlwaysLoads(normalized)) {
            checks.add(Check.pass("claude_eager_loading",
                    "Claude Code loads Quill tools eagerly"));
        } else {
            checks.add(Check.warning("claude_eager_loading",
                    "Claude Code may defer Quill tools behind ToolSearch",
                    "Run `quill init --project " + normalized + "`"));
        }
        String toolProfile = claudeToolProfile(normalized);
        if (toolProfile != null) {
            checks.add(Check.pass("claude_tool_profile", "Claude Code MCP uses the `"
                    + toolProfile + "` Quill tool profile"));
        }
        ClaudePermissionStatus.Report permissions = ClaudePermissionStatus.inspect(normalized);
        String permissionStatusCommand = "quill client permissions status --project \"" + normalized + "\"";
        if (!permissions.valid()) {
            checks.add(Check.warning("claude_tool_permissions", "Invalid local permission evidence in "
                    + String.join(", ", permissions.invalid_files()), permissionStatusCommand + " --json"));
        } else if (!permissions.denied_tools().isEmpty() || !permissions.ask_tools().isEmpty()) {
            checks.add(Check.warning("claude_tool_permissions", "Local Quill permission policies: denied="
                    + permissions.denied_tools().size() + ", ask=" + permissions.ask_tools().size()
                    + "; allow rules do not override them", permissionStatusCommand
                    + " --json; review existing policies in Claude /permissions"));
        } else if (!permissions.missing_tools().isEmpty()) {
            checks.add(Check.info("claude_tool_permissions", "Optional no-prompt Quill grants are missing for "
                    + permissions.missing_tools().size() + " current tools",
                    permissions.server_configured()
                            ? "quill client permissions grant --project \"" + normalized + "\""
                            : "quill init --project \"" + normalized + "\""));
        } else {
            checks.add(Check.pass("claude_tool_permissions", "All current read-only Quill tools have local allow rules"));
        }
        checks.add(Check.info("claude_permission_scope", ClaudePermissionStatus.LIMITATION, null));
        return new Report(normalized, List.copyOf(checks));
    }

    private static void addTestIndexCheck(List<Check> checks, Map<String, String> metadata,
            BuildSystem buildSystem) {
        List<String> indexed = metadataList(metadata, "compiled_test_modules");
        List<String> missing = metadataList(metadata, "missing_test_output_modules");
        List<String> missingClasspath = metadataList(metadata, "missing_test_classpath_modules");
        List<String> stale = metadataList(metadata, "stale_test_output_modules");
        if (missing.isEmpty() && missingClasspath.isEmpty() && stale.isEmpty()) {
            checks.add(Check.pass("test_index", indexed.isEmpty()
                    ? "No compiled test outputs were discovered"
                    : "Indexed compiled tests from " + indexed.size() + " module"
                            + (indexed.size() == 1 ? "" : "s")));
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!missing.isEmpty()) problems.add("missing outputs: " + String.join(", ", missing));
        if (!missingClasspath.isEmpty()) {
            problems.add("missing runtime classpaths: " + String.join(", ", missingClasspath));
        }
        if (!stale.isEmpty()) problems.add("stale outputs: " + String.join(", ", stale));
        checks.add(Check.warning("test_index", "Test index coverage is partial ("
                        + String.join("; ", problems) + ")",
                buildSystem == BuildSystem.MAVEN
                        ? "Run the project's Maven test-compile command, then `quill update`"
                        : "Run the project's Gradle testClasses task, then `quill update`"));
    }

    private static List<String> metadataList(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) return List.of();
        try {
            JsonNode parsed = JSON.readTree(value);
            if (!parsed.isArray()) return List.of();
            List<String> result = new ArrayList<>();
            parsed.forEach(item -> {
                if (item.isTextual()) result.add(item.asText());
            });
            return List.copyOf(result);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static long countPendingEvents(Path root) {
        Path directory = root.resolve(".quill/build-events");
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static boolean gitignoreContainsQuill(Path root) {
        Path gitignore = root.resolve(".gitignore");
        if (!Files.isRegularFile(gitignore)) return false;
        try {
            return Files.readAllLines(gitignore).stream()
                    .map(String::strip)
                    .anyMatch(line -> line.equals(".quill/") || line.equals("/.quill/")
                            || line.equals(".quill") || line.equals("/.quill"));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static List<String> configuredClients(Path root) {
        List<String> clients = new ArrayList<>();
        Path claude = root.resolve(".mcp.json");
        if (Files.isRegularFile(claude)) {
            try {
                if (JSON.readTree(claude.toFile()).path("mcpServers").has("quill")) {
                    clients.add("Claude Code");
                }
            } catch (IOException ignored) {
                // A malformed optional client configuration is not an index health failure.
            }
        }
        Path codex = root.resolve(".codex/config.toml");
        if (Files.isRegularFile(codex)) {
            try {
                if (CodexConfigInstaller.definesQuillServer(Files.readString(codex))) {
                    clients.add("Codex");
                }
            } catch (IOException ignored) {
                // A malformed optional client configuration is not an index health failure.
            }
        }
        return List.copyOf(clients);
    }

    private static List<String> brokenClientLaunchers(Path root) {
        List<String> broken = new ArrayList<>();
        Path claude = root.resolve(".mcp.json");
        if (Files.isRegularFile(claude)) {
            try {
                JsonNode quill = JSON.readTree(claude.toFile()).path("mcpServers").path("quill");
                if (!quill.isMissingNode()) {
                    addBrokenLauncher(broken, "Claude Code", quill.path("command").asText(null));
                }
            } catch (IOException ignored) {
                // Malformed optional configuration is handled as absent by configuredClients.
            }
        }
        Path codex = root.resolve(".codex/config.toml");
        if (Files.isRegularFile(codex)) {
            try {
                String content = Files.readString(codex);
                if (CodexConfigInstaller.definesQuillServer(content)) {
                    addBrokenLauncher(broken, "Codex",
                            CodexConfigInstaller.quillCommand(content).orElse(null));
                }
            } catch (IOException ignored) {
                // Malformed optional configuration is handled as absent by configuredClients.
            }
        }
        return List.copyOf(broken);
    }

    private static void addBrokenLauncher(List<String> broken, String client, String command) {
        if (command == null || command.isBlank()) {
            broken.add(client + " (missing command)");
            return;
        }
        Path candidate;
        try {
            candidate = Path.of(command);
        } catch (RuntimeException ignored) {
            broken.add(client + " (invalid path: " + command + ")");
            return;
        }
        if (!candidate.isAbsolute()) return;
        if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) {
            broken.add(client + " (" + command + ")");
        }
    }

    private static String claudeToolProfile(Path root) {
        Path config = root.resolve(".mcp.json");
        if (!Files.isRegularFile(config)) return null;
        try {
            JsonNode args = JSON.readTree(config.toFile())
                    .path("mcpServers").path("quill").path("args");
            if (!args.isArray()) return null;
            for (int i = 0; i + 1 < args.size(); i++) {
                if ("--tools".equals(args.get(i).asText())) return args.get(i + 1).asText();
            }
            return "full";
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean claudeAlwaysLoads(Path root) {
        Path config = root.resolve(".mcp.json");
        if (!Files.isRegularFile(config)) return false;
        try {
            return JSON.readTree(config.toFile()).path("mcpServers").path("quill")
                    .path("alwaysLoad").asBoolean(false);
        } catch (IOException error) {
            return false;
        }
    }

    static String toJson(Report report) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schema_version", 1);
        root.put("project_root", report.projectRoot().toString());
        root.put("overall", report.overall());
        ObjectNode summary = root.putObject("summary");
        summary.put("passed", report.count(Status.PASS));
        summary.put("warnings", report.count(Status.WARNING));
        summary.put("errors", report.count(Status.ERROR));
        summary.put("info", report.count(Status.INFO));
        ArrayNode checks = root.putArray("checks");
        for (Check check : report.checks()) {
            ObjectNode node = checks.addObject();
            node.put("id", check.id());
            node.put("status", check.status().jsonName());
            node.put("message", check.message());
            if (check.action() != null) node.put("action", check.action());
        }
        return root.toString();
    }

    static String toText(Report report) {
        StringBuilder result = new StringBuilder("Quill doctor: ")
                .append(report.projectRoot()).append('\n');
        for (Check check : report.checks()) {
            result.append('[').append(check.status().label()).append("] ")
                    .append(check.id()).append(": ").append(check.message()).append('\n');
            if (check.action() != null) {
                result.append("       Action: ").append(check.action()).append('\n');
            }
        }
        return result.append("Overall: ").append(report.overall().toUpperCase(Locale.ROOT))
                .toString();
    }

    enum Status {
        PASS("pass", "OK"), WARNING("warning", "WARN"), ERROR("error", "ERROR"),
        INFO("info", "INFO");

        private final String jsonName;
        private final String label;

        Status(String jsonName, String label) {
            this.jsonName = jsonName;
            this.label = label;
        }

        String jsonName() {
            return jsonName;
        }

        String label() {
            return label;
        }
    }

    record Check(String id, Status status, String message, String action) {
        static Check pass(String id, String message) {
            return new Check(id, Status.PASS, message, null);
        }

        static Check warning(String id, String message, String action) {
            return new Check(id, Status.WARNING, message, action);
        }

        static Check error(String id, String message, String action) {
            return new Check(id, Status.ERROR, message, action);
        }

        static Check info(String id, String message, String action) {
            return new Check(id, Status.INFO, message, action);
        }
    }

    record Report(Path projectRoot, List<Check> checks) {
        boolean hasErrors() {
            return checks.stream().anyMatch(check -> check.status() == Status.ERROR);
        }

        long count(Status status) {
            return checks.stream().filter(check -> check.status() == status).count();
        }

        String overall() {
            if (hasErrors()) return "error";
            if (checks.stream().anyMatch(check -> check.status() == Status.WARNING)) {
                return "warning";
            }
            return "healthy";
        }
    }
}
