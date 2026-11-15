package org.treblereel.mcp.mcp.project;

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
import org.treblereel.mcp.index.MavenCompileScope;
import org.treblereel.mcp.core.Json;

/** Produces safe, non-executing focused and fallback build command plans. */
public final class VerificationPlanQueries {

    private static final ObjectMapper JSON = Json.MAPPER;

    public ObjectNode plan(org.treblereel.mcp.db.ReadQueryContext reader, Path root, JsonNode contexts, JsonNode tests) {
        return reader.snapshot() == null ? plan(root, contexts, tests)
                : reader.snapshot().verificationPlan().apply(contexts, tests);
    }

    public ObjectNode plan(Path projectRoot, JsonNode contexts, JsonNode testEvidence) {
        return prepare(projectRoot).apply(contexts, testEvidence);
    }

    static java.util.function.BiFunction<JsonNode, JsonNode, ObjectNode> prepare(Path projectRoot) {
        BuildSystem system = BuildSystem.detect(projectRoot);
        BuildRunnerResolver.Runner runner = BuildRunnerResolver.resolve(projectRoot, system);
        java.util.function.Function<Set<String>, ModuleSelection> select;
        java.util.function.Function<Set<String>, MavenCompileScope.Scope> compile;
        if (system == BuildSystem.MAVEN) {
            Path root = projectRoot.toAbsolutePath().normalize();
            var discovery = MavenProjectDiscovery.discover(root);
            Set<String> available = discovery.moduleDirectories().stream()
                    .map(module -> module.equals(root) ? "." : root.relativize(module).toString().replace('\\', '/'))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            select = requested -> selectMavenModules(available, discovery.complete(), requested);
            compile = MavenCompileScope.prepare(root);
        } else {
            Path root = projectRoot.toAbsolutePath().normalize();
            var discovery = GradleProjectDiscovery.discover(root);
            Map<String,String> available = new LinkedHashMap<>();
            discovery.projectPaths().forEach((directory, path) -> {
                if (directory.startsWith(root)) available.put(directory.equals(root) ? "."
                        : root.relativize(directory).toString().replace('\\', '/'), path);
            });
            select = requested -> selectGradleModules(available, discovery.complete(), requested);
            compile = requested -> new MavenCompileScope.Scope(List.of(), true, "unknown_or_non_maven_scope");
        }
        var selector = select;
        var compiler = compile;
        var profiles = new LinkedHashMap<String, List<String>>();
        var unavailableProfiles = new LinkedHashSet<String>();
        if (system == BuildSystem.MAVEN) for (Path module : MavenProjectDiscovery.discover(projectRoot).moduleDirectories()) {
            String name = module.equals(projectRoot) ? "." : projectRoot.relativize(module).toString().replace('\\', '/');
            for (String set : List.of("main", "test")) {
                Path file = module.resolve("target/quill-java-" + set + ".properties");
                Path kotlin = module.resolve("target/quill-kotlin-" + set + ".properties");
                try {
                    if (java.nio.file.Files.isRegularFile(kotlin) && (!java.nio.file.Files.isRegularFile(file)
                            || java.nio.file.Files.getLastModifiedTime(kotlin).compareTo(java.nio.file.Files.getLastModifiedTime(file)) > 0)) file = kotlin;
                    if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > 4 * 1024 * 1024) continue;
                    var values = new java.util.Properties(); try (var input = java.nio.file.Files.newBufferedReader(file)) { values.load(input); }
                    int count = Integer.parseInt(values.getProperty("activeProfile.count", "0"));
                    if (count < 0 || count > 256) continue;
                    var ids = new ArrayList<String>();
                    for (int i=0;i<count;i++) {
                        String id = values.getProperty("activeProfile."+i, "");
                        if (!id.matches("[A-Za-z0-9_.+-]+")) throw new IllegalArgumentException("Invalid profile id");
                        ids.add(id);
                    }
                    profiles.put(name+":"+set, List.copyOf(ids));
                } catch (java.io.IOException | RuntimeException unavailable) { unavailableProfiles.add(name+":"+set); }
            }
        }
        return (contexts, evidence) -> {
            var result = compose(projectRoot, system, runner, contexts, evidence, selector, compiler);
            var selections = new LinkedHashSet<List<String>>();
            var profileEvidence = result.putObject("profiles_by_source_set");
            boolean availableProfiles = true;
            for (var context : contexts) {
                var resolution = context.path("resolution");
                String key = resolution.path("module").asText()+":"+capturedSourceSet(system, resolution.path("source_set").asText("main"));
                if (unavailableProfiles.contains(key)) availableProfiles = false;
                var selected = profiles.get(key);
                profileEvidence.set(key, JSON.valueToTree(selected));
                if (selected != null && !selected.isEmpty()) selections.add(selected);
            }
            var selected = selections.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
            boolean compatible = availableProfiles && selections.stream().allMatch(selected::containsAll);
            result.put("profile_selection_complete", compatible);
            result.set("active_profiles", JSON.valueToTree(selected));
            if (!compatible) { result.put("commands_executable", false); result.withArray("commands").removeAll(); }
            else if (!selected.isEmpty()) for (var command : result.withArray("commands")) {
                var argv = (ArrayNode) command.path("argv"); argv.add("-P"+String.join(",", selected));
                ((ObjectNode) command).put("display", argv.valueStream().map(JsonNode::asText).map(VerificationPlanQueries::displayArg)
                        .collect(java.util.stream.Collectors.joining(" ")));
            }
            return result;
        };
    }

    public static String capturedSourceSet(BuildSystem system, String sourceSet) {
        // Maven's testCompile also owns profile-added integration-test roots.
        return system == BuildSystem.MAVEN && sourceSet.equals("integration-test") ? "test" : sourceSet;
    }

    private static ObjectNode compose(Path projectRoot, BuildSystem buildSystem, BuildRunnerResolver.Runner runner,
            JsonNode contexts, JsonNode testEvidence,
            java.util.function.Function<Set<String>, ModuleSelection> select,
            java.util.function.Function<Set<String>, MavenCompileScope.Scope> compile) {
        Set<String> requestedModules = modules(contexts);
        ModuleSelection selection = select.apply(requestedModules);
        var scope = buildSystem == BuildSystem.MAVEN && selection.complete()
                ? compile.apply(selection.commandModules())
                : new MavenCompileScope.Scope(List.of(), true, "unknown_or_non_maven_scope");
        ModuleSelection compileSelection = scope.repository()
                ? new ModuleSelection(selection.modules(), Set.of(buildSystem == BuildSystem.MAVEN ? "." : ":"),
                        selection.unresolvedModules(), selection.complete()) : selection;
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
        result.set("quick_compile_modules", JSON.valueToTree(compileSelection.commandModules()));
        result.set("unresolved_modules", JSON.valueToTree(selection.unresolvedModules()));
        result.put("module_selection_complete", selection.complete());
        ObjectNode scopeNode = result.putObject("verification_scope");
        scopeNode.put("kind", scope.repository() ? "repository" : "modules_with_prerequisites");
        scopeNode.set("modules", JSON.valueToTree(scope.modules()));
        scopeNode.put("reason", scope.reason());
        result.set("focused_tests", JSON.valueToTree(tests));
        ArrayNode commands = result.putArray("commands");
        if (runner.available()) {
            appendCompile(commands, buildSystem, runner.argvPrefix(), compileSelection);
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

    private static ModuleSelection selectMavenModules(Set<String> available, boolean complete, Set<String> requested) {
        if (requested.isEmpty()) return new ModuleSelection(Set.of(), Set.of(), Set.of(), true);
        Set<String> resolved = new LinkedHashSet<>();
        Set<String> unresolved = new LinkedHashSet<>();
        requested.forEach(module -> (available.contains(module) ? resolved : unresolved).add(module));
        Set<String> commandModules = resolved.contains(".") || resolved.isEmpty()
                ? Set.of(".") : orderedSet(resolved);
        return new ModuleSelection(orderedSet(resolved), commandModules,
                orderedSet(unresolved), complete && unresolved.isEmpty());
    }

    private static ModuleSelection selectGradleModules(Map<String,String> available, boolean complete, Set<String> requested) {
        if (requested.isEmpty()) return new ModuleSelection(Set.of(), Set.of(), Set.of(), true);
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
        if (!complete) {
            resolved.clear();
            commandModules.clear();
            commandModules.add(":");
            unresolved.clear();
            unresolved.addAll(requested);
        } else if (commandModules.isEmpty()) {
            commandModules.add(":");
        }
        return new ModuleSelection(orderedSet(resolved), orderedSet(commandModules),
                orderedSet(unresolved), complete && unresolved.isEmpty());
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
