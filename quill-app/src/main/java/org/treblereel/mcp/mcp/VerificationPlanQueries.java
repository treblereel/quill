package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.core.BuildRunnerResolver;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.GradleProjectDiscovery;
import org.treblereel.mcp.core.MavenProjectDiscovery;

/** Produces safe, non-executing focused and fallback build command plans. */
final class VerificationPlanQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    ObjectNode plan(Path projectRoot, JsonNode contexts, JsonNode testEvidence) {
        BuildSystem buildSystem = BuildSystem.detect(projectRoot);
        BuildRunnerResolver.Runner runner = BuildRunnerResolver.resolve(projectRoot, buildSystem);
        Set<String> requestedModules = modules(contexts);
        ModuleSelection selection = selectModules(projectRoot, buildSystem, requestedModules);
        List<String> tests = tests(testEvidence);

        ObjectNode result = JSON.createObjectNode();
        result.put("build_system", buildSystem.name().toLowerCase());
        result.put("working_directory", projectRoot.toAbsolutePath().normalize().toString());
        ObjectNode runnerNode = result.putObject("runner");
        runnerNode.put("kind", runner.kind());
        runnerNode.put("source", runner.source());
        runnerNode.put("available", runner.available());
        runnerNode.set("argv_prefix", JSON.valueToTree(runner.argvPrefix()));
        if (runner.unavailableReason() == null) runnerNode.putNull("unavailable_reason");
        else runnerNode.put("unavailable_reason", runner.unavailableReason());
        result.set("requested_modules", JSON.valueToTree(requestedModules));
        result.set("modules", JSON.valueToTree(selection.modules()));
        result.set("command_modules", JSON.valueToTree(selection.commandModules()));
        result.set("unresolved_modules", JSON.valueToTree(selection.unresolvedModules()));
        result.put("module_selection_complete", selection.complete());
        result.set("focused_tests", JSON.valueToTree(tests));
        ArrayNode commands = result.putArray("commands");
        if (runner.available()) {
            appendCompile(commands, buildSystem, runner.argvPrefix(), selection);
            if (!tests.isEmpty()) append(commands, buildSystem, runner.argvPrefix(), selection,
                    tests, "focused", "Run the statically ranked affected tests");
            append(commands, buildSystem, runner.argvPrefix(), selection, List.of(),
                    "module_fallback", tests.isEmpty()
                            ? "No focused tests were ranked"
                            : "Fallback for partial or uncertain test coverage");
        }
        result.put("refresh_index_after_success", true);
        result.put("commands_executable", runner.available() && !commands.isEmpty());
        return result;
    }

    private static void appendCompile(ArrayNode commands, BuildSystem system,
            List<String> prefix, ModuleSelection selection) {
        List<String> argv = new ArrayList<>(prefix);
        if (system == BuildSystem.MAVEN) {
            appendMavenModules(argv, selection.commandModules());
            argv.add("test-compile");
        } else {
            appendGradleTasks(argv, selection.commandModules(), "testClasses");
        }
        appendCommand(commands, argv, "quick_compile",
                "Compile production and standard test sources without running tests", false, true);
    }

    private static void append(ArrayNode commands, BuildSystem system, List<String> prefix,
            ModuleSelection selection, List<String> tests, String scope, String reason) {
        List<String> argv = new ArrayList<>(prefix);
        if (system == BuildSystem.MAVEN) {
            appendMavenModules(argv, selection.commandModules());
            if (!tests.isEmpty()) argv.add("-Dtest=" + String.join(",", tests));
            argv.add("test");
        } else {
            appendGradleTasks(argv, selection.commandModules(), "test");
            for (String test : tests) {
                argv.add("--tests");
                argv.add(test);
            }
        }
        appendCommand(commands, argv, scope, reason, true, true);
    }

    private static void appendMavenModules(List<String> argv, Set<String> modules) {
        if (!modules.isEmpty() && !modules.equals(Set.of("."))) {
            argv.add("-pl");
            argv.add(String.join(",", modules));
            argv.add("-am");
        }
    }

    private static void appendGradleTasks(
            List<String> argv, Set<String> projectPaths, String task) {
        if (projectPaths.isEmpty() || projectPaths.contains(":")) {
            argv.add(task);
            return;
        }
        projectPaths.forEach(path -> argv.add(path + ":" + task));
    }

    private static void appendCommand(ArrayNode commands, List<String> argv,
            String scope, String reason, boolean executesTests, boolean safeToRun) {
        ObjectNode command = commands.addObject();
        command.set("argv", JSON.valueToTree(argv));
        command.put("display", argv.stream().map(VerificationPlanQueries::displayArg)
                .collect(java.util.stream.Collectors.joining(" ")));
        command.put("scope", scope);
        command.put("reason", reason);
        command.put("executes_tests", executesTests);
        command.put("compiles_test_sources", true);
        command.put("safe_to_run", safeToRun);
    }

    private static Set<String> modules(JsonNode contexts) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode context : contexts) {
            String module = context.path("resolution").path("module").asText();
            if (!module.isBlank()) result.add(module);
        }
        return result;
    }

    private static ModuleSelection selectModules(
            Path root, BuildSystem system, Set<String> requested) {
        if (requested.isEmpty()) return new ModuleSelection(Set.of(), Set.of(), Set.of(), true);
        if (system == BuildSystem.MAVEN) return selectMavenModules(root, requested);
        return selectGradleModules(root, requested);
    }

    private static ModuleSelection selectMavenModules(Path root, Set<String> requested) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Set<String> available = new LinkedHashSet<>();
        MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(normalizedRoot);
        for (Path module : discovery.moduleDirectories()) {
            available.add(module.equals(normalizedRoot)
                    ? "." : normalizedRoot.relativize(module).toString().replace('\\', '/'));
        }
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        requested.forEach(module -> (available.contains(module) ? resolved : unresolved).add(module));
        Set<String> commandModules = resolved.contains(".") || resolved.isEmpty()
                ? Set.of(".") : orderedSet(resolved);
        return new ModuleSelection(orderedSet(resolved), commandModules,
                orderedSet(unresolved), discovery.complete() && unresolved.isEmpty());
    }

    private static ModuleSelection selectGradleModules(Path root, Set<String> requested) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        GradleProjectDiscovery.Discovery discovery = GradleProjectDiscovery.discover(normalizedRoot);
        Map<String, String> available = new LinkedHashMap<>();
        discovery.projectPaths().forEach((directory, projectPath) -> {
            if (directory.startsWith(normalizedRoot)) {
                String module = directory.equals(normalizedRoot) ? "."
                        : normalizedRoot.relativize(directory).toString().replace('\\', '/');
                available.put(module, projectPath);
            }
        });
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> commandModules = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (String module : requested) {
            String projectPath = available.get(module);
            if (projectPath == null) unresolved.add(module);
            else {
                resolved.add(module);
                commandModules.add(projectPath);
            }
        }
        if (!discovery.complete()) {
            resolved.clear();
            commandModules.clear();
            commandModules.add(":");
            unresolved.clear();
            unresolved.addAll(requested);
        } else if (commandModules.isEmpty()) {
            commandModules.add(":");
        }
        return new ModuleSelection(orderedSet(resolved), orderedSet(commandModules),
                orderedSet(unresolved), discovery.complete() && unresolved.isEmpty());
    }

    private static Set<String> orderedSet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private record ModuleSelection(Set<String> modules, Set<String> commandModules,
            Set<String> unresolvedModules, boolean complete) {}

    private static List<String> tests(JsonNode evidence) {
        List<String> result = new ArrayList<>();
        for (JsonNode test : evidence.path("tests")) {
            String className = test.path("class").asText();
            if (!className.isBlank() && !result.contains(className)) result.add(className);
        }
        return List.copyOf(result);
    }

    private static String displayArg(String value) {
        if (value.matches("[A-Za-z0-9_./:=,@+-]+")) return value;
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
