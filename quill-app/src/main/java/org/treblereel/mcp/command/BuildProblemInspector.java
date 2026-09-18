package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads and normalizes diagnostics captured by the Maven or Gradle build integration. */
public final class BuildProblemInspector {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MAVEN_LOCATION = Pattern.compile(
            "^(?:\\[ERROR]\\s*)?(.+?\\.(?:java|kt|groovy)):\\[(\\d+),(\\d+)]\\s*(.*)$");
    private static final Pattern COMPILER_LOCATION = Pattern.compile(
            "^(?:\\[ERROR]\\s*|[ew]:\\s*)?(.+?\\.(?:java|kt|groovy)):(\\d+)(?::(\\d+))?:\\s*(.*)$");
    private static final Pattern EXCEPTION_PREFIX = Pattern.compile(
            "^([a-zA-Z_$][a-zA-Z0-9_$.]*(?:Exception|Error|Failure))(?::\\s*(.*))?$",
            Pattern.DOTALL);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;]*m");

    private BuildProblemInspector() {}

    public static String inspect(Path projectRoot, String severity, String module,
            int limit, int offset) {
        Path root = projectRoot.toAbsolutePath().normalize();
        String normalizedSeverity = severity == null
                ? "all" : severity.strip().toLowerCase(Locale.ROOT);
        if (!normalizedSeverity.equals("all") && !normalizedSeverity.equals("error")) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "Invalid severity: expected all or error");
            return error.toString();
        }

        Path stateFile = root.resolve(".quill/build-state.json");
        if (!Files.isRegularFile(stateFile)) {
            ObjectNode result = base("unknown", null, null);
            result.putArray("problems");
            page(result, 0, 0, limit, offset);
            result.put("recommended_action",
                    "Run the project build to capture diagnostics; Quill will not start it");
            return result.toString();
        }

        try {
            JsonNode state = JSON.readTree(stateFile.toFile());
            String buildTool = state.path("buildTool").asText("unknown");
            long finishedAt = state.path("finishedAt").asLong(0);
            boolean successful = state.path("successful").asBoolean(false);
            ObjectNode result = base(successful ? "success" : "failed",
                    buildTool, finishedAt > 0 ? Instant.ofEpochMilli(finishedAt) : null);
            List<Problem> problems = successful ? List.of()
                    : parseProblems(root, state.path("failureMessages"));
            if (module != null && !module.isBlank()) {
                problems = problems.stream()
                        .filter(problem -> module.equals(problem.module())).toList();
            }
            int from = Math.min(offset, problems.size());
            int to = Math.min(from + limit, problems.size());
            ArrayNode values = result.putArray("problems");
            for (Problem problem : problems.subList(from, to)) {
                ObjectNode node = values.addObject();
                node.put("severity", "error");
                node.put("message", problem.message());
                if (problem.source() != null) node.put("source", problem.source());
                if (problem.line() != null) node.put("line", problem.line());
                if (problem.column() != null) node.put("column", problem.column());
                if (problem.module() != null) node.put("module", problem.module());
                if (problem.exception() != null) node.put("exception", problem.exception());
            }
            page(result, to - from, problems.size(), limit, offset);
            result.put("located_problem_count", problems.stream()
                    .filter(problem -> problem.source() != null).count());
            result.put("recommended_action", successful ? "none"
                    : "Fix the reported problems and run the project build again");
            return result.toString();
        } catch (IOException | RuntimeException error) {
            ObjectNode result = base("unavailable", null, null);
            result.put("error", "Could not read captured build diagnostics: "
                    + safeMessage(error));
            result.putArray("problems");
            page(result, 0, 0, limit, offset);
            return result.toString();
        }
    }

    private static ObjectNode base(String status, String buildTool, Instant finishedAt) {
        ObjectNode result = JSON.createObjectNode();
        result.put("build_status", status);
        if (buildTool == null) result.putNull("build_tool");
        else result.put("build_tool", buildTool);
        if (finishedAt == null) result.putNull("finished_at");
        else result.put("finished_at", finishedAt.toString());
        result.put("build_was_started", false);
        result.put("located_problem_count", 0);
        result.put("capture_scope", "build_failure_exception_chain");
        result.putArray("limitations").add(
                "Compiler diagnostics written only to console may not appear in the captured exception chain");
        return result;
    }

    private static List<Problem> parseProblems(Path root, JsonNode messages) {
        Map<String, Problem> unique = new LinkedHashMap<>();
        if (messages.isArray()) {
            for (JsonNode value : messages) {
                if (!value.isTextual() || value.textValue().isBlank()) continue;
                parseMessage(root, value.textValue(), unique);
            }
        }
        if (unique.isEmpty()) {
            Problem fallback = new Problem("Build failed without diagnostic messages",
                    null, null, null, null, null);
            unique.put(fallback.key(), fallback);
        }
        return new ArrayList<>(unique.values());
    }

    private static void parseMessage(Path root, String raw, Map<String, Problem> target) {
        raw = ANSI.matcher(raw).replaceAll("");
        String exception = exceptionName(raw);
        boolean located = false;
        for (String line : raw.lines().toList()) {
            Matcher matcher = MAVEN_LOCATION.matcher(line.strip());
            if (!matcher.matches()) matcher = COMPILER_LOCATION.matcher(line.strip());
            if (!matcher.matches()) continue;
            String source = normalizeSource(root, matcher.group(1));
            Integer sourceLine = integer(matcher.group(2));
            Integer column = integer(matcher.group(3));
            String message = cleanMessage(matcher.group(4));
            Problem problem = new Problem(message.isBlank() ? "Compilation failed" : message,
                    source, sourceLine, column, module(source), exception);
            target.putIfAbsent(problem.key(), problem);
            located = true;
        }
        if (located) return;
        Matcher prefix = EXCEPTION_PREFIX.matcher(raw.strip());
        String message = prefix.matches() && prefix.group(2) != null
                ? prefix.group(2).strip() : raw.strip();
        if (message.length() > 4_000) message = message.substring(0, 4_000);
        Problem problem = new Problem(message, null, null, null, null, exception);
        target.putIfAbsent(problem.key(), problem);
    }

    private static String normalizeSource(Path root, String value) {
        String cleaned = value.strip().replace('\\', '/');
        try {
            Path provided = Path.of(cleaned);
            Path path = (provided.isAbsolute() ? provided : root.resolve(provided)).normalize();
            if (path.startsWith(root)) return root.relativize(path).toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            // Keep compiler-provided text when it is not a valid local path.
        }
        return cleaned;
    }

    private static String module(String source) {
        if (source == null) return null;
        if (source.startsWith("src/")) return ".";
        int marker = source.indexOf("/src/");
        if (marker < 0) return null;
        String prefix = source.substring(0, marker);
        return prefix.isBlank() ? "." : prefix;
    }

    private static String exceptionName(String raw) {
        String first = raw.lines().findFirst().orElse("").strip();
        Matcher matcher = EXCEPTION_PREFIX.matcher(first);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String cleanMessage(String value) {
        return value == null ? "" : value.replaceFirst("^(?:error:|warning:)\\s*", "").strip();
    }

    private static Integer integer(String value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void page(ObjectNode result, int showing, int total, int limit, int offset) {
        result.put("showing", showing);
        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("has_more", (long) offset + showing < total);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private record Problem(
            String message, String source, Integer line, Integer column,
            String module, String exception) {
        String key() {
            return source + ":" + line + ":" + column + ":" + message;
        }
    }
}
