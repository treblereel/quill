package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Whether test classes changed in the worktree have run since they changed. A compiling build says nothing about
 * them: in Apollo AQ-049 claude added two tests, never ran them (Mockito could not start), and reported
 * "verified true" because verification only saw a successful test-compile. Surefire/failsafe and Gradle write one
 * {@code TEST-<class>.xml} per run; a report older than the source is from before the change.
 */
final class ChangedTestExecution {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TEST_SOURCE = Pattern.compile("^(?:(.*)/)?src/test/(?:java|kotlin|groovy)/(.+)\\.(?:java|kt|groovy)$");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\b(tests|failures|errors|skipped)=\"(\\d+)\"");

    private ChangedTestExecution() {}

    record Result(ObjectNode evidence, List<String> notExecuted, List<String> failed) {
        boolean blocking() {
            return !notExecuted.isEmpty() || !failed.isEmpty();
        }
    }

    static Result inspect(Path projectRoot, JsonNode changes) {
        ObjectNode evidence = JSON.createObjectNode()
                .put("scope", "test classes changed in the worktree; a TEST-<class>.xml report newer than the source counts as a run after the change");
        ArrayNode classes = evidence.putArray("classes");
        List<String> notExecuted = new ArrayList<>(), failed = new ArrayList<>();
        for (JsonNode change : changes.path("changes")) {
            if (change.path("status").asText().equals("deleted")) continue;
            Matcher source = TEST_SOURCE.matcher(change.path("path").asText());
            if (!source.matches()) continue;
            String className = source.group(2).replace('/', '.');
            Path module = source.group(1) == null ? projectRoot : projectRoot.resolve(source.group(1));
            Path file = projectRoot.resolve(change.path("path").asText());
            ObjectNode row = classes.addObject().put("class", className).put("source", change.path("path").asText());
            Path report = report(module, className);
            try {
                if (report == null || Files.getLastModifiedTime(report).compareTo(Files.getLastModifiedTime(file)) < 0) {
                    row.put("status", report == null ? "not_executed" : "report_predates_change");
                    notExecuted.add(className);
                    continue;
                }
                String text = Files.readString(report);
                int end = text.indexOf('>', text.indexOf("<testsuite"));
                Matcher counts = ATTRIBUTE.matcher(end < 0 ? "" : text.substring(text.indexOf("<testsuite"), end));
                int tests = 0, problems = 0, skipped = 0;
                while (counts.find()) {
                    int value = Integer.parseInt(counts.group(2));
                    switch (counts.group(1)) {
                        case "tests" -> tests = value;
                        case "skipped" -> skipped = value;
                        default -> problems += value;
                    }
                }
                row.put("status", problems > 0 ? "failed" : tests - skipped > 0 ? "passed" : "no_test_ran")
                        .put("tests", tests).put("failures_and_errors", problems).put("skipped", skipped)
                        .put("report", projectRoot.relativize(report).toString());
                if (problems > 0) failed.add(className);
                else if (tests - skipped <= 0) notExecuted.add(className);
            } catch (Exception unreadable) {
                row.put("status", "report_unreadable");
                notExecuted.add(className);
            }
        }
        evidence.put("changed_test_classes", classes.size());
        return new Result(evidence, List.copyOf(notExecuted), List.copyOf(failed));
    }

    private static Path report(Path module, String className) {
        String name = "TEST-" + className + ".xml";
        for (Path candidate : List.of(module.resolve("target/surefire-reports").resolve(name),
                module.resolve("target/failsafe-reports").resolve(name))) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        Path gradle = module.resolve("build/test-results");
        if (!Files.isDirectory(gradle)) return null;
        try (Stream<Path> tasks = Files.list(gradle)) {
            return tasks.map(task -> task.resolve(name)).filter(Files::isRegularFile).findFirst().orElse(null);
        } catch (Exception unreadable) {
            return null;
        }
    }
}
