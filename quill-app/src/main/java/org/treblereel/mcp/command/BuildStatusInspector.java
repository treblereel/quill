package org.treblereel.mcp.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.MetaEnvelope;

/** Produces a read-only, agent-oriented view of build and compiled-snapshot freshness. */
public final class BuildStatusInspector {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BuildStatusInspector() {}

    public static String inspect(Path projectRoot, Jdbi jdbi) {
        Path root = projectRoot.toAbsolutePath().normalize();
        BuildSystem buildSystem = BuildSystem.detect(root);
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        MetaEnvelope freshness = MetaEnvelope.from(jdbi, 0, 0);
        List<Path> outputs = ProjectInitializer.findClassesDirs(root);
        BuildIntegrationInstaller.Inspection integration =
                BuildIntegrationInstaller.inspect(root);
        long pendingEvents = pendingEvents(root);
        Long latestClassModified = latestClassModified(outputs);
        Instant indexedAt = parseInstant(metadata.get("indexed_at"));
        boolean outputsChangedAfterIndex = latestClassModified != null && indexedAt != null
                && latestClassModified > indexedAt.toEpochMilli();
        Path database = ProjectIndexStore.findBestAvailableDb(root);
        Boolean fingerprintChanged = database == null
                ? null : UpdateCommand.hasProjectChanges(root, database);

        ObjectNode result = JSON.createObjectNode();
        result.put("build_system", buildSystem.name().toLowerCase());
        ObjectNode integrationNode = result.putObject("integration");
        integrationNode.put("state", integration.state().name().toLowerCase());
        integrationNode.put("path", relative(root, integration.path()));
        integrationNode.put("detail", integration.detail());

        ObjectNode compiled = result.putObject("compiled_outputs");
        compiled.put("count", outputs.size());
        ArrayNode directories = compiled.putArray("directories");
        outputs.stream().map(path -> relative(root, path)).sorted()
                .limit(100).forEach(directories::add);
        compiled.put("directories_truncated", outputs.size() > 100);
        if (latestClassModified == null) compiled.putNull("latest_class_modified_at");
        else compiled.put("latest_class_modified_at",
                Instant.ofEpochMilli(latestClassModified).toString());
        compiled.put("changed_after_index", outputsChangedAfterIndex);

        ObjectNode events = result.putObject("build_events");
        events.put("pending", pendingEvents);
        events.put("consumed_before_tool_execution", true);
        events.put("protocol", 2);

        ObjectNode index = result.putObject("index");
        index.put("index_id", metadata.getOrDefault("index_id", "unknown"));
        index.put("indexed_at", metadata.getOrDefault("indexed_at", "unknown"));
        index.put("compiled_before_index", Boolean.parseBoolean(
                metadata.getOrDefault("compiled_before_index", "false")));
        index.put("dependency_status", metadata.getOrDefault("dependency_index", "unknown"));
        if (fingerprintChanged == null) index.putNull("project_fingerprint_changed");
        else index.put("project_fingerprint_changed", fingerprintChanged);

        ObjectNode freshnessNode = result.putObject("freshness");
        freshnessNode.put("commit_stale", freshness.commitStale());
        freshnessNode.put("worktree_dirty", freshness.worktreeDirty());
        freshnessNode.put("structural_changed_files", freshness.structuralChangedFiles());
        freshnessNode.put("structure_stale", freshness.structureStale());
        freshnessNode.set("stale_reasons", JSON.valueToTree(freshness.staleReasons()));

        Recommendation recommendation = recommendation(buildSystem, outputs, integration,
                pendingEvents, freshness, outputsChangedAfterIndex, fingerprintChanged);
        result.put("status", recommendation.status());
        result.put("action_required", recommendation.action() != null);
        if (recommendation.action() == null) result.putNull("recommended_action");
        else result.put("recommended_action", recommendation.action());
        result.put("build_was_started", false);
        return result.toString();
    }

    private static Recommendation recommendation(BuildSystem buildSystem, List<Path> outputs,
            BuildIntegrationInstaller.Inspection integration, long pendingEvents,
            MetaEnvelope freshness, boolean outputsChangedAfterIndex, Boolean fingerprintChanged) {
        if (outputs.isEmpty()) {
            return new Recommendation("build_required", buildSystem == BuildSystem.MAVEN
                    ? "Run the project's Maven compile/package command"
                    : "Run the project's Gradle classes/build command");
        }
        if (integration.state() == BuildIntegrationInstaller.State.INVALID) {
            return new Recommendation("integration_error",
                    "Repair " + integration.path() + " or reinstall Quill build integration");
        }
        if (integration.state() == BuildIntegrationInstaller.State.MISSING
                || integration.state() == BuildIntegrationInstaller.State.OUTDATED) {
            return new Recommendation("integration_required",
                    "Run quill init to install the build-result integration");
        }
        if (pendingEvents > 0) {
            return new Recommendation("refresh_pending",
                    "Make another MCP request to consume the build-result event");
        }
        if (freshness.staleReasons().contains("dirty_worktree_not_compiled")) {
            return new Recommendation("build_required", buildSystem == BuildSystem.MAVEN
                    ? "Compile the Maven project; Quill will refresh after a successful build"
                    : "Compile the Gradle project; Quill will refresh after a successful build");
        }
        if (outputsChangedAfterIndex || Boolean.TRUE.equals(fingerprintChanged)
                || freshness.commitStale()) {
            return new Recommendation("index_refresh_required",
                    "Run quill update or make a new MCP request after a successful build event");
        }
        return new Recommendation("ready", null);
    }

    private static long pendingEvents(Path root) {
        Path directory = root.resolve(".quill/build-events");
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static Long latestClassModified(List<Path> outputs) {
        return outputs.stream().map(BuildStatusInspector::latestClassModified)
                .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    private static Long latestClassModified(Path output) {
        try (Stream<Path> files = Files.walk(output)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (IOException ignored) {
                            return null;
                        }
                    }).filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String relative(Path root, Path path) {
        if (path == null) return "unknown";
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString();
    }

    private record Recommendation(String status, String action) {}
}
