package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.core.BuildRunnerResolver;
import org.treblereel.mcp.core.BuildSystem;

/** Produces safe, non-executing focused and fallback build command plans. */
final class VerificationPlanQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    ObjectNode plan(Path projectRoot, JsonNode contexts, JsonNode testEvidence) {
        BuildSystem buildSystem = BuildSystem.detect(projectRoot);
        BuildRunnerResolver.Runner runner = BuildRunnerResolver.resolve(projectRoot, buildSystem);
        Set<String> modules = modules(contexts);
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
        result.set("modules", JSON.valueToTree(modules));
        result.set("focused_tests", JSON.valueToTree(tests));
        ArrayNode commands = result.putArray("commands");
        if (runner.available()) {
            if (!tests.isEmpty()) append(commands, buildSystem, runner.argvPrefix(), modules,
                    tests, "focused", "Run the statically ranked affected tests");
            append(commands, buildSystem, runner.argvPrefix(), modules, List.of(),
                    "module_fallback", tests.isEmpty()
                            ? "No focused tests were ranked"
                            : "Fallback for partial or uncertain test coverage");
        }
        result.put("refresh_index_after_success", true);
        result.put("commands_executable", runner.available() && !commands.isEmpty());
        return result;
    }

    private static void append(ArrayNode commands, BuildSystem system, List<String> prefix,
            Set<String> modules, List<String> tests, String scope, String reason) {
        List<String> argv = new ArrayList<>(prefix);
        if (system == BuildSystem.MAVEN) {
            if (!modules.isEmpty() && !modules.equals(Set.of("."))) {
                argv.add("-pl");
                argv.add(String.join(",", modules));
                argv.add("-am");
            }
            if (!tests.isEmpty()) argv.add("-Dtest=" + String.join(",", tests));
            argv.add("test");
        } else {
            String task = modules.size() == 1 && !modules.contains(".")
                    ? ":" + modules.iterator().next().replace('/', ':') + ":test" : "test";
            argv.add(task);
            for (String test : tests) {
                argv.add("--tests");
                argv.add(test);
            }
        }
        ObjectNode command = commands.addObject();
        command.set("argv", JSON.valueToTree(argv));
        command.put("display", argv.stream().map(VerificationPlanQueries::displayArg)
                .collect(java.util.stream.Collectors.joining(" ")));
        command.put("scope", scope);
        command.put("reason", reason);
        command.put("safe_to_run", true);
    }

    private static Set<String> modules(JsonNode contexts) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode context : contexts) {
            String module = context.path("resolution").path("module").asText();
            if (!module.isBlank()) result.add(module);
        }
        return result;
    }

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
