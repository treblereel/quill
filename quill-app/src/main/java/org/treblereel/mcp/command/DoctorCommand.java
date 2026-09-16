package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectRootFinder;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Performs project setup and index health checks without building or re-indexing. */
@Command(name = "doctor", mixinStandardHelpOptions = true,
        description = "Diagnose project integration, compiled outputs, and index freshness")
public class DoctorCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--json", description = "Write machine-readable JSON to stdout")
    boolean json;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        Path root = ProjectRootFinder.find(projectPath);
        Report report = inspect(root);
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

        List<Path> classesDirectories = ProjectInitializer.findClassesDirs(normalized);
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
            checks.add(Check.error("index", diagnostics.errorMessage(),
                    "Run `quill init --project " + normalized + "` after compiling the project"));
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
        return new Report(normalized, List.copyOf(checks));
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
