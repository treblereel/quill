package org.treblereel.mcp.index;

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
import org.treblereel.mcp.db.IndexRepository;
import org.treblereel.mcp.model.MetaEnvelope;

/** Produces a read-only, agent-oriented view of build and compiled-snapshot freshness. */
public final class BuildStatusInspector {

    private static final ObjectMapper JSON = new ObjectMapper();

    private BuildStatusInspector() {}

    public static MetaEnvelope freshness(org.treblereel.mcp.db.ReadQueryContext reader, int responseTokens, int naiveTokens) {
        return reader.snapshot() == null ? freshness(reader.index.getMetadata(), responseTokens, naiveTokens)
                : reader.snapshot().freshness();
    }

    public static String inspect(Path root, org.treblereel.mcp.db.ReadQueryContext reader, List<String> modules) {
        return reader.snapshot() == null ? inspect(root, reader.index.getMetadata(), modules)
                : reader.snapshot().buildStatus().apply(modules, reader.snapshot().freshness());
    }

    public static MetaEnvelope freshness(Jdbi jdbi, int responseTokens, int naiveTokens) {
        Map<String,String> metadata = jdbi.withHandle(repositoryHandle1 -> new IndexRepository(repositoryHandle1).getMetadata());
        return freshness(metadata, responseTokens, naiveTokens);
    }

    public static MetaEnvelope freshness(Map<String,String> metadata, int responseTokens, int naiveTokens) {
        String projectRoot = metadata.get("project_root");
        var worktree = projectRoot == null ? org.treblereel.mcp.core.WorktreeInspector.Snapshot.empty()
                : org.treblereel.mcp.core.WorktreeSnapshotCache.shared().get(Path.of(projectRoot));
        return MetaEnvelope.from(metadata, worktree.currentCommit(), worktree.structuralFingerprint(),
                worktree.structuralDirty(), worktree.dirty(), worktree.changes().size(),
                worktree.structuralChanges().size(), responseTokens, naiveTokens);
    }

    public static String inspect(Path projectRoot, Jdbi jdbi) {
        return inspect(projectRoot, jdbi, null);
    }

    public static String inspect(Path projectRoot, Jdbi jdbi, List<String> modules) {
        Map<String,String> metadata = jdbi.withHandle(repositoryHandle2 -> new IndexRepository(repositoryHandle2).getMetadata());
        return inspect(projectRoot, metadata, modules);
    }

    public static String inspect(Path projectRoot, Map<String,String> metadata) {
        return inspect(projectRoot, metadata, null);
    }

    public static String inspect(Path projectRoot, Map<String,String> metadata, List<String> modules) {
        return capture(projectRoot, metadata, freshness(metadata, 0, 0)).inspect(modules);
    }

    public static Observation capture(Path projectRoot, Map<String,String> metadata, MetaEnvelope freshness) {
        Path root = projectRoot.toAbsolutePath().normalize();
        BuildSystem system = BuildSystem.detect(root);
        var discovery = ProjectLayout.discoverClassesDirs(root, false);
        var compiled = CompiledOutputInspector.capture(root, discovery);
        var integration = BuildIntegrationInstaller.inspect(root);
        Map<Path, Long> times = new java.util.HashMap<>();
        for (Path output : discovery.classesDirectories()) {
            Long modified = latestClassModified(output);
            if (modified != null) times.put(output, modified);
        }
        Boolean fingerprintChanged = metadata.get("index_id") == null ? null
                : ProjectUpdater.hasCompiledOutputChanges(root, metadata, discovery.classesDirectories());
        ArrayNode settings = JSON.createArrayNode();
        discovery.moduleDirectories().stream().sorted().forEach(module ->
                settings.addAll(JavaCompilerSettings.inspect(List.of(module), system)));
        return new Observation(root, java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata)), system, freshness, discovery, compiled,
                integration, pendingEvents(root), Map.copyOf(times), fingerprintChanged,
                BuildIntegrationCapabilities.inspect(root, system), settings);
    }

    public record Observation(Path root, Map<String,String> metadata, BuildSystem buildSystem,
            MetaEnvelope freshness, ProjectLayout.ClassesDiscovery discovery,
            CompiledOutputInspector.Snapshot compiledSnapshot, BuildIntegrationInstaller.Inspection integration,
            long pendingEvents, Map<Path,Long> classTimes, Boolean fingerprintChanged,
            com.fasterxml.jackson.databind.JsonNode protocol, ArrayNode compilerSettings) {
        public String inspect(List<String> modules) { return inspect(modules, freshness); }

        public String inspect(List<String> modules, MetaEnvelope freshness) {
            var outputs = discovery.outputs().stream()
                    .filter(output -> modules == null || modules.contains(relative(root, output.moduleDirectory())))
                    .toList();
            var repositoryCompiled = compiledSnapshot.inspect(null);
            var compiledStatus = compiledSnapshot.inspect(modules);
            Long latest = outputs.stream().map(output -> classTimes.get(output.directory()))
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            Long repositoryLatest = classTimes.values().stream().max(Comparator.naturalOrder()).orElse(null);
            Instant indexedAt = parseInstant(metadata.get("indexed_at"));
            boolean outputsChanged = latest != null && indexedAt != null && latest > indexedAt.toEpochMilli();
            ObjectNode result = JSON.createObjectNode();
            result.put("build_system", buildSystem.name().toLowerCase());
            var integrationNode = result.putObject("integration");
            integrationNode.put("state", integration.state().name().toLowerCase());
            integrationNode.put("path", relative(root, integration.path()));
            integrationNode.put("detail", integration.detail());
            integrationNode.set("protocol", protocol.deepCopy());
            var selectedModules = discovery.moduleDirectories().stream()
                    .filter(module -> modules == null || modules.contains(relative(root, module)))
                    .sorted().map(Path::toString).collect(java.util.stream.Collectors.toSet());
            ArrayNode settings = result.putArray("java_compiler_settings");
            compilerSettings.forEach(node -> { if (selectedModules.contains(node.path("module").asText())) settings.add(node.deepCopy()); });
            var compiled = result.putObject("compiled_outputs");
            compiled.put("count", outputs.size());
            compiled.put("main_count", outputs.stream().filter(output -> output.sourceSet().equals("main")).count());
            compiled.put("test_count", outputs.stream().filter(output -> output.sourceSet().equals("test")).count());
            var directories = compiled.putArray("directories");
            outputs.stream().map(output -> relative(root, output.directory())).sorted().forEach(directories::add);
            compiled.put("directories_truncated", false);
            compiled.put("status", compiledStatus.state().name().toLowerCase());
            compiled.set("stale_modules", JSON.valueToTree(compiledStatus.staleModules()));
            var sourceSets = compiled.putArray("source_sets");
            for (var evidence : compiledSnapshot.sourceSets()) {
                if (modules != null && !modules.contains(evidence.path("module").asText())) continue;
                var item = (ObjectNode) evidence.deepCopy();
                Long sourceSetLatest = outputs.stream().filter(output -> relative(root, output.moduleDirectory()).equals(evidence.path("module").asText())
                        && output.sourceSet().equals(evidence.path("source_set").asText()))
                        .map(output -> classTimes.get(output.directory())).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
                if (sourceSetLatest != null) item.put("latest_class_modified_at", Instant.ofEpochMilli(sourceSetLatest).toString());
                sourceSets.add(item);
            }
            if (latest == null) compiled.putNull("latest_class_modified_at");
            else compiled.put("latest_class_modified_at", Instant.ofEpochMilli(latest).toString());
            compiled.put("changed_after_index", outputsChanged);
            result.putObject("build_events").put("pending", pendingEvents)
                    .put("consumed_before_tool_execution", true).put("protocol", 3);
            var index = result.putObject("index");
            index.put("index_id", metadata.getOrDefault("index_id", "unknown"));
            index.put("indexed_at", metadata.getOrDefault("indexed_at", "unknown"));
            index.put("compiled_before_index", Boolean.parseBoolean(metadata.getOrDefault("compiled_before_index", "false")));
            index.put("dependency_status", metadata.getOrDefault("dependency_index", "unknown"));
            if (fingerprintChanged == null) index.putNull("project_fingerprint_changed");
            else index.put("project_fingerprint_changed", fingerprintChanged);
            var state = result.putObject("freshness");
            state.put("commit_stale", freshness.commitStale()).put("worktree_dirty", freshness.worktreeDirty())
                    .put("structural_changed_files", freshness.structuralChangedFiles()).put("structure_stale", freshness.structureStale());
            state.set("stale_reasons", JSON.valueToTree(freshness.staleReasons()));
            Recommendation recommendation = recommendation(buildSystem, compiledStatus, integration,
                    pendingEvents, freshness, outputsChanged, fingerprintChanged);
            result.put("status", recommendation.status());
            result.put("build_reason", recommendation.buildReason());
            result.put("action_required", recommendation.action() != null);
            result.put("recommended_action", recommendation.action());
            result.put("build_was_started", false);
            boolean repositoryChanged = repositoryLatest != null && indexedAt != null && repositoryLatest > indexedAt.toEpochMilli();
            result.put("repository_status", recommendation(buildSystem, repositoryCompiled, integration,
                    pendingEvents, freshness, repositoryChanged, fingerprintChanged).status());
            result.set("repository_stale_modules", JSON.valueToTree(repositoryCompiled.staleModules()));
            return result.toString();
        }
    }

    private static Recommendation recommendation(BuildSystem buildSystem,
            CompiledOutputInspector.Report compiled,
            BuildIntegrationInstaller.Inspection integration, long pendingEvents,
            MetaEnvelope freshness, boolean outputsChangedAfterIndex, Boolean fingerprintChanged) {
        if (compiled.state() == CompiledOutputInspector.State.MISSING) {
            return new Recommendation("build_required", "classes_missing",
                    buildSystem == BuildSystem.MAVEN
                    ? "Run the project's Maven compile/package command"
                    : "Run the project's Gradle classes/build command");
        }
        if (compiled.state() == CompiledOutputInspector.State.STALE) {
            return new Recommendation("build_required", "classes_stale",
                    buildSystem == BuildSystem.MAVEN
                            ? "Rebuild the stale Maven modules; Quill will refresh after success"
                            : "Rebuild the stale Gradle modules; Quill will refresh after success");
        }
        if ((freshness.staleReasons().contains("dirty_worktree_not_compiled")
                || freshness.staleReasons().contains("indexed_java_sources_changed")
                || freshness.staleReasons().contains("indexed_jvm_sources_changed")
                || freshness.staleReasons().contains("indexed_inputs_changed")
                    && (!freshness.commitStale() || freshness.structuralChangedFiles() > 0))) {
            return new Recommendation("build_required", "classes_stale",
                    buildSystem == BuildSystem.MAVEN
                    ? "Compile the Maven project; Quill will refresh after a successful build"
                    : "Compile the Gradle project; Quill will refresh after a successful build");
        }
        if (integration.state() == BuildIntegrationInstaller.State.INVALID) {
            return new Recommendation("integration_error", null,
                    "Repair " + integration.path() + " or reinstall Quill build integration");
        }
        if (integration.state() == BuildIntegrationInstaller.State.MISSING
                || integration.state() == BuildIntegrationInstaller.State.OUTDATED) {
            return new Recommendation("integration_required", null,
                    "Run quill init to install the build-result integration");
        }
        if (pendingEvents > 0) {
            return new Recommendation("refresh_pending", null,
                    "Make another MCP request to consume the build-result event");
        }
        if (outputsChangedAfterIndex || Boolean.TRUE.equals(fingerprintChanged)
                || freshness.commitStale()) {
            return new Recommendation("index_refresh_required", null,
                    "Run quill update or make a new MCP request after a successful build event");
        }
        return new Recommendation("ready", null, null);
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

    private static Long latestClassModified(Path output) {
        Long[] latest = {null};
        try {
            Files.walkFileTree(output, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path path,
                        java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                    if (path.getFileName().toString().endsWith(".class")
                            && (attributes.isRegularFile() || Files.isRegularFile(path))) {
                        long modified = (attributes.isSymbolicLink() ? Files.getLastModifiedTime(path)
                                : attributes.lastModifiedTime()).toMillis();
                        latest[0] = latest[0] == null ? modified : Math.max(latest[0], modified);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return latest[0];
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

    private record Recommendation(String status, String buildReason, String action) {}
}
