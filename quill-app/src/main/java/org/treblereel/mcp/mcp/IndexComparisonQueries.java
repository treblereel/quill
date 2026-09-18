package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

/** Compares the active immutable index with an earlier local generation. */
final class IndexComparisonQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String compareIndex(Jdbi current, Path projectRoot, String requestedBaseline, int detailLimit) {
        Map<String, String> currentMeta = IndexReader.getMetadata(current);
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
            Jdbi previous = QuillDatabase.open(baseline.database());
            Snapshot before = snapshot(previous);
            Snapshot after = snapshot(current);
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
            root.putArray("limitations")
                    .add("Comparison uses retained local immutable generations; pruned generations are unavailable")
                    .add("Dependency deltas describe indexed static edges, not runtime call counts")
                    .add("Git-history tables are intentionally excluded from structural index comparison");
            appendMeta(root, current, 0);
            return root.toString();
        } catch (RuntimeException error) {
            return errorResponse("Baseline index is incompatible or unreadable: "
                    + safeMessage(error));
        }
    }

    private static Snapshot snapshot(Jdbi jdbi) {
        Map<String, StringBuilder> classValues = jdbi.withHandle(handle -> {
            Map<String, StringBuilder> result = new TreeMap<>();
            handle.createQuery("""
                    SELECT id, class_name, kind, superclass, interfaces, source_file,
                           is_bean, origin, module, source_set
                    FROM classes
                    WHERE lifecycle = 'current' AND origin != 'orphan_output'
                    ORDER BY class_name, id
                    """).map((rs, ctx) -> Map.entry(rs.getInt("id"), new ClassValue(
                            rs.getString("class_name"), String.join("|",
                                    value(rs.getString("kind")),
                                    value(rs.getString("superclass")),
                                    value(rs.getString("interfaces")),
                                    value(rs.getString("source_file")),
                                    Integer.toString(rs.getInt("is_bean")),
                                    value(rs.getString("origin")),
                                    value(rs.getString("module")),
                                    value(rs.getString("source_set"))))))
                    .forEach(entry -> result.put(entry.getValue().name(),
                            new StringBuilder(entry.getValue().fingerprint())));
            return result;
        });
        appendClassDetails(jdbi, classValues, """
                SELECT c.class_name, 'A|' || a.annotation_name || '|' || a.direct || '|'
                       || COALESCE(a.via_annotation, '') AS detail
                FROM class_annotations a JOIN classes c ON c.id = a.class_id
                WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                ORDER BY c.class_name, detail
                """);
        appendClassDetails(jdbi, classValues, """
                SELECT c.class_name, 'M|' || m.kind || '|' || m.signature || '|'
                       || m.type_name || '|' || m.modifiers || '|' || m.annotations AS detail
                FROM class_members m JOIN classes c ON c.id = m.class_id
                WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                ORDER BY c.class_name, detail
                """);
        Map<String, String> classes = new TreeMap<>();
        classValues.forEach((name, fingerprint) -> classes.put(name, fingerprint.toString()));

        Map<String, Integer> dependencies = jdbi.withHandle(handle -> {
            Map<String, Integer> result = new TreeMap<>();
            handle.createQuery("""
                    SELECT source.class_name AS source_name, target.class_name AS target_name,
                           d.kind, d.occurrence_count
                    FROM dependencies d
                    JOIN classes source ON source.id = d.from_class_id
                    JOIN classes target ON target.id = d.to_class_id
                    WHERE source.lifecycle = 'current' AND source.origin != 'orphan_output'
                      AND target.lifecycle = 'current' AND target.origin != 'orphan_output'
                    ORDER BY source_name, target_name, d.kind
                    """).map((rs, ctx) -> Map.entry(
                            rs.getString("source_name") + " -> "
                                    + rs.getString("target_name") + " ["
                                    + rs.getString("kind") + "]",
                            rs.getInt("occurrence_count")))
                    .forEach(entry -> result.merge(entry.getKey(), entry.getValue(), Integer::sum));
            return result;
        });
        Set<String> beans = jdbi.withHandle(handle -> new TreeSet<>(handle.createQuery("""
                SELECT c.class_name || ' [' || b.kind || '] ' || COALESCE(b.member_name, '')
                FROM beans b JOIN classes c ON c.id = b.class_id
                WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                ORDER BY 1
                """).mapTo(String.class).list()));
        Map<String, String> injections = jdbi.withHandle(handle -> {
            Map<String, String> result = new TreeMap<>();
            handle.createQuery("""
                    SELECT c.class_name, ip.kind, ip.target_type, ip.field_name,
                           ip.qualifiers, ip.resolution_status, ip.resolution_reason
                    FROM injection_points ip
                    JOIN beans b ON b.id = ip.bean_id
                    JOIN classes c ON c.id = b.class_id
                    WHERE c.lifecycle = 'current' AND c.origin != 'orphan_output'
                    ORDER BY c.class_name, ip.kind, ip.target_type, ip.field_name
                    """).map((rs, ctx) -> Map.entry(
                            rs.getString("class_name") + "#"
                                    + value(rs.getString("field_name")) + " ["
                                    + rs.getString("kind") + ":"
                                    + rs.getString("target_type") + ":"
                                    + value(rs.getString("qualifiers")) + "]",
                            rs.getString("resolution_status") + ":"
                                    + value(rs.getString("resolution_reason"))))
                    .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
            return result;
        });
        return new Snapshot(classes, dependencies, beans, injections);
    }

    private static void appendClassDetails(
            Jdbi jdbi, Map<String, StringBuilder> classes, String sql) {
        jdbi.useHandle(handle -> handle.createQuery(sql)
                .map((rs, ctx) -> Map.entry(
                        rs.getString("class_name"), rs.getString("detail")))
                .forEach(entry -> {
                    StringBuilder fingerprint = classes.get(entry.getKey());
                    if (fingerprint != null) fingerprint.append('\n').append(entry.getValue());
                }));
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
                Jdbi jdbi = QuillDatabase.open(path);
                Map<String, String> metadata = IndexReader.getMetadata(jdbi);
                String indexId = metadata.get("index_id");
                String fileName = path.getFileName().toString();
                String fileIndexId = fileName.substring(0, fileName.length() - 3);
                if (indexId == null || !indexId.equals(fileIndexId)
                        || indexId.equals(currentIndexId)) continue;
                result.add(new Generation(path, indexId, metadata));
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

    private record ClassValue(String name, String fingerprint) {}
    private record Generation(Path database, String indexId, Map<String, String> metadata) {}
    private record Snapshot(Map<String, String> classes, Map<String, Integer> dependencies,
                            Set<String> beans, Map<String, String> injections) {}
}
