package org.treblereel.mcp.mcp.query;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.IndexRepository.ComparisonSnapshot;
import org.treblereel.mcp.db.IndexRepository;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.core.Json;

/** Compares the active immutable index with an earlier local generation. */
public final class IndexComparisonQueries {

    private static final ObjectMapper JSON = Json.MAPPER;

    public String compareIndex(ReadQueryContext current, Path projectRoot, String requestedBaseline, int detailLimit) {
        Map<String, String> currentMeta = current.index.getMetadata();
        List<Generation> generations = generations(projectRoot,
                currentMeta.getOrDefault("index_id", ""));
        Generation baseline = selectBaseline(generations, requestedBaseline);
        if (baseline == null) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", requestedBaseline == null || requestedBaseline.isBlank()
                    ? "No previous compatible index generation is available"
                    : "Baseline index not found: " + requestedBaseline);
            error.set("available_baselines", JSON.valueToTree(generations.stream()
                    .map(Generation::indexId).toList()));
            appendMeta(error, current, 0);
            return error.toString();
        }

        try {
            try (ReadQueryContext previous = ReadQueryContext.openWithGraph( QuillDatabase.open(baseline.database()))) {
            ComparisonSnapshot before = previous.index.comparisonSnapshot();
            ComparisonSnapshot after = current.index.comparisonSnapshot();
            ObjectNode root = JSON.createObjectNode();
            appendGeneration(root.putObject("baseline"), baseline.metadata());
            appendGeneration(root.putObject("current"), currentMeta);
            root.put("comparison_scope",
                    "current_project_classes_static_dependencies_beans_and_injection_resolution");
            appendClassChanges(root.putObject("classes"), before.classes(), after.classes(), detailLimit);
            appendMapChanges(root.putObject("dependencies"), before.dependencies(),
                    after.dependencies(), detailLimit, "edges");
            appendSetChanges(root.putObject("beans"), before.beans(), after.beans(),
                    detailLimit, "identities");
            appendInjectionChanges(root.putObject("injections"), before.injections(),
                    after.injections(), detailLimit);
            root.set("api_compatibility", ApiCompatibilityQueries.compare(previous, current, detailLimit));
            root.putArray("limitations")
                    .add("Comparison uses retained local immutable generations; pruned generations are unavailable")
                    .add("Dependency deltas describe indexed static edges, not runtime call counts")
                    .add("Git-history tables are intentionally excluded from structural index comparison");
            appendMeta(root, current, 0);
            return root.toString();
        }
} catch (RuntimeException error) {
            return errorResponse("Baseline index is incompatible or unreadable: "
                    + safeMessage(error));
        }
    }





    private static void appendClassChanges(ObjectNode node, Map<String, String> before,
            Map<String, String> after, int limit) {
        Set<String> added = difference(after.keySet(), before.keySet());
        Set<String> removed = difference(before.keySet(), after.keySet());
        Set<String> modified = new TreeSet<>();
        before.keySet().stream().filter(after::containsKey)
                .filter(name -> !before.get(name).equals(after.get(name)))
                .forEach(modified::add);
        node.put("baseline_count", before.size());
        node.put("current_count", after.size());
        appendValues(node, "added", added, limit);
        appendValues(node, "removed", removed, limit);
        appendValues(node, "modified", modified, limit);
    }

    private static <T> void appendMapChanges(ObjectNode node, Map<String, T> before,
            Map<String, T> after, int limit, String label) {
        Set<String> added = difference(after.keySet(), before.keySet());
        Set<String> removed = difference(before.keySet(), after.keySet());
        Set<String> changed = new TreeSet<>();
        before.keySet().stream().filter(after::containsKey)
                .filter(key -> !before.get(key).equals(after.get(key))).forEach(changed::add);
        node.put("baseline_" + label, before.size());
        node.put("current_" + label, after.size());
        appendValues(node, "added", added, limit);
        appendValues(node, "removed", removed, limit);
        appendValues(node, "occurrence_count_changed", changed, limit);
    }

    private static void appendSetChanges(ObjectNode node, Set<String> before,
            Set<String> after, int limit, String label) {
        node.put("baseline_" + label, before.size());
        node.put("current_" + label, after.size());
        appendValues(node, "added", difference(after, before), limit);
        appendValues(node, "removed", difference(before, after), limit);
    }

    private static void appendInjectionChanges(ObjectNode node, Map<String, String> before,
            Map<String, String> after, int limit) {
        node.put("baseline_count", before.size());
        node.put("current_count", after.size());
        appendValues(node, "added", difference(after.keySet(), before.keySet()), limit);
        appendValues(node, "removed", difference(before.keySet(), after.keySet()), limit);
        List<String> changed = before.keySet().stream().filter(after::containsKey)
                .filter(key -> !before.get(key).equals(after.get(key))).sorted().toList();
        node.put("resolution_changed_count", changed.size());
        ArrayNode values = node.putArray("resolution_changed");
        changed.stream().limit(limit).forEach(key -> {
            ObjectNode value = values.addObject();
            value.put("injection", key);
            value.put("before", before.get(key));
            value.put("after", after.get(key));
        });
        node.put("resolution_changed_truncated", changed.size() > limit);
    }

    private static void appendValues(
            ObjectNode node, String name, Set<String> values, int limit) {
        node.put(name + "_count", values.size());
        node.set(name, JSON.valueToTree(values.stream().limit(limit).toList()));
        node.put(name + "_truncated", values.size() > limit);
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static List<Generation> generations(Path root, String currentIndexId) {
        Path directory = root.resolve(".quill");
        if (!Files.isDirectory(directory)) return List.of();
        List<Path> paths;
        try (Stream<Path> files = Files.list(directory)) {
            paths = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .toList();
        } catch (IOException error) {
            return List.of();
        }
        List<Generation> result = new ArrayList<>();
        for (Path path : paths) {
            try {
                try (ReadQueryContext reader = ReadQueryContext.open( QuillDatabase.open(path))) {
                Map<String, String> metadata = reader.index.getMetadata();
                String indexId = metadata.get("index_id");
                String fileName = path.getFileName().toString();
                String fileIndexId = fileName.substring(0, fileName.length() - 3);
                if (indexId == null || !indexId.equals(fileIndexId)
                        || indexId.equals(currentIndexId)) continue;
                result.add(new Generation(path, indexId, metadata));
            }
} catch (RuntimeException ignored) {
                // Old schema versions and corrupt generations are not valid comparison baselines.
            }
        }
        result.sort(Comparator.comparing((Generation value) ->
                value.metadata().getOrDefault("indexed_at", "")).reversed());
        return result;
    }

    private static Generation selectBaseline(List<Generation> generations, String requested) {
        if (requested == null || requested.isBlank()) {
            return generations.isEmpty() ? null : generations.getFirst();
        }
        String value = requested.strip();
        return generations.stream().filter(generation -> generation.indexId().equals(value)
                        || generation.metadata().getOrDefault("last_commit", "").equals(value))
                .findFirst().orElse(null);
    }

    private static void appendGeneration(ObjectNode node, Map<String, String> metadata) {
        node.put("index_id", metadata.getOrDefault("index_id", "unknown"));
        node.put("indexed_at", metadata.getOrDefault("indexed_at", "unknown"));
        node.put("commit", metadata.getOrDefault("last_commit", "unknown"));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName()
                : current.getMessage();
    }


    private record Generation(Path database, String indexId, Map<String, String> metadata) {}

}
