package org.treblereel.mcp.mcp.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.index.ProjectIndexStore;
import org.treblereel.mcp.model.MetaEnvelope;

public final class ToolResponseSupport {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int SCOPE_PAGE = 50;

    private ToolResponseSupport() {}

    public static void appendMeta(ObjectNode root, ReadQueryContext reader, int naiveTokens) {
        appendMeta(root, reader, naiveTokens, null, null);
    }

    public static void appendMeta(
            ObjectNode root,
            ReadQueryContext reader,
            int naiveTokens,
            String targetSource,
            String targetModule) {
        MetaEnvelope meta = org.treblereel.mcp.index.BuildStatusInspector.freshness(reader, 0, 0);
        ObjectNode metaNode = root.putObject("_meta");
        metaNode.put("index_id", meta.indexId());
        if (fresh(meta)) {
            // Every response carries _meta; when nothing is stale the full freshness breakdown is
            // all false/zero/empty, so only the identifying fields and the verdict are kept. Edits
            // that change no structure (docs, benchmark notes) only add the changed-file count.
            metaNode.put("indexed_at", meta.indexedAt());
            metaNode.put("indexed_commit", meta.lastCommit());
            if (meta.worktreeDirty()) {
                metaNode.put("worktree_dirty", true);
                metaNode.put("worktree_changed_files", meta.worktreeChangedFiles());
            }
            metaNode.put("stale_warning", false);
            metaNode.put("stale_scope", "current");
            metaNode.put("answer_confidence", "high");
            appendRecovery(reader, metaNode);
            return;
        }
        if (reader.snapshot() != null) {
            metaNode.put(
                    "freshness_basis",
                    meta.staleReasons().contains("previous_generation")
                            ? "previous_prepared_generation"
                            : "prepared_input_observation");
            metaNode.set(
                    "input_observed_at",
                    reader.snapshot().worktree().path("observed_at").deepCopy());
        }
        metaNode.put("indexed_at", meta.indexedAt());
        metaNode.put("indexed_commit", meta.lastCommit());
        if (meta.currentCommit() == null) metaNode.putNull("current_commit");
        else metaNode.put("current_commit", meta.currentCommit());
        metaNode.put("commit_stale", meta.commitStale());
        metaNode.put("worktree_dirty", meta.worktreeDirty());
        metaNode.put("worktree_changed_files", meta.worktreeChangedFiles());
        metaNode.put("structural_changed_files", meta.structuralChangedFiles());
        metaNode.put("structure_stale", meta.structureStale());
        metaNode.set("stale_reasons", JSON.valueToTree(meta.staleReasons()));
        metaNode.put("stale_warning", meta.staleWarning());
        String projectRoot = reader.index.getMetadata().get("project_root");
        appendStaleScope(reader, metaNode, meta, projectRoot, targetSource, targetModule);
        appendRecovery(reader, metaNode);
    }

    private static boolean fresh(MetaEnvelope meta) {
        return !meta.staleWarning()
                && !meta.structureStale()
                && !meta.commitStale()
                && meta.structuralChangedFiles() == 0
                && meta.staleReasons().isEmpty()
                && meta.currentCommit() != null;
    }

    private static void appendRecovery(ReadQueryContext reader, ObjectNode metaNode) {
        String projectRoot = reader.index.getMetadata().get("project_root");
        if (projectRoot == null) return;
        ProjectIndexStore.readRecovery(Path.of(projectRoot))
                .ifPresent(
                        recovery -> {
                            ObjectNode recoveryNode = metaNode.putObject("index_recovery");
                            recoveryNode.put("reason", recovery.reason());
                            recoveryNode.put("selected_index_id", recovery.selectedIndexId());
                            recoveryNode.put("recovered_at", recovery.recoveredAt());
                        });
    }

    private static void appendStaleScope(
            ReadQueryContext reader,
            ObjectNode metaNode,
            MetaEnvelope meta,
            String projectRoot,
            String targetSource,
            String targetModule) {
        if (!meta.structureStale()) {
            metaNode.put("stale_scope", "current");
            metaNode.put("answer_confidence", "high");
            return;
        }
        if (meta.staleReasons().contains("previous_generation")) {
            metaNode.put("stale_scope", "repository");
            metaNode.put("answer_confidence", "low");
            metaNode.put(
                    "recommended_action",
                    "Using the previous prepared generation; retry after background refresh");
            return;
        }
        if (meta.commitStale()
                && meta.structuralChangedFiles() == 0
                && !meta.staleReasons().contains("indexed_java_sources_changed")
                && !meta.staleReasons().contains("indexed_jvm_sources_changed")
                && !meta.staleReasons().contains("compiled_snapshot_changed")) {
            metaNode.put("stale_scope", "repository");
            metaNode.put("answer_confidence", "low");
            metaNode.put(
                    "recommended_action",
                    "Refresh the Quill index at the current commit; rebuilding is unnecessary when inputs are unchanged");
            return;
        }
        if (projectRoot == null || targetSource == null || targetSource.isBlank()) {
            metaNode.put("stale_scope", "repository");
            metaNode.put("answer_confidence", "low");
            metaNode.put(
                    "recommended_action",
                    "Compile structural changes, then refresh the Quill index");
            return;
        }
        if (meta.commitStale()) {
            metaNode.put("stale_scope", "repository");
            metaNode.put("answer_confidence", "low");
            metaNode.put("target_source_changed", false);
            metaNode.put("target_module_changed", false);
            metaNode.put(
                    "recommended_action",
                    "Refresh the Quill index at the current commit before consequential decisions");
            return;
        }

        String source = normalize(targetSource);
        String module =
                targetModule == null || targetModule.isBlank() ? "." : normalize(targetModule);
        List<String> changed =
                reader.snapshot() == null
                        ? WorktreeSnapshotCache.shared()
                                .get(Path.of(projectRoot))
                                .structuralChanges()
                                .stream()
                                .map(WorktreeInspector.Change::projectPath)
                                .map(ToolResponseSupport::normalize)
                                .toList()
                        : reader.snapshot().structuralPaths();
        boolean targetChanged = changed.stream().anyMatch(path -> path.equals(source));
        boolean moduleChanged =
                !".".equals(module)
                        && changed.stream()
                                .anyMatch(
                                        path ->
                                                path.equals(module)
                                                        || path.startsWith(module + "/"));
        if (targetChanged) {
            metaNode.put("stale_scope", "target");
            metaNode.put("answer_confidence", "low");
        } else if (moduleChanged) {
            metaNode.put("stale_scope", "module");
            metaNode.put("answer_confidence", "medium");
        } else {
            metaNode.put("stale_scope", "outside_target_module");
            metaNode.put("answer_confidence", "medium");
        }
        metaNode.put("target_source_changed", targetChanged);
        metaNode.put("target_module_changed", moduleChanged);
        metaNode.put(
                "recommended_action",
                "Compile structural changes, then refresh the Quill index before consequential decisions");
    }

    private static String normalize(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        return normalized;
    }

    public static void appendPage(ObjectNode root, int showing, int total, int limit, int offset) {
        boolean hasMore = offset + showing < total;
        root.put("showing", showing);
        root.put("total", total);
        root.put("limit", limit);
        root.put("offset", offset);
        root.put("has_more", hasMore);
        root.put("truncated", offset > 0 || hasMore);
        if (hasMore) root.put("next_offset", offset + showing);
    }

    public static String errorResponse(String message) {
        return errorResponse("INVALID_ARGUMENT", message);
    }

    public static String errorResponse(String code, String message) {
        return errorNode(code, message).toString();
    }

    /** An INVALID_ARGUMENT error answer. */
    public static ObjectNode errorNode(String message) {
        return errorNode("INVALID_ARGUMENT", message);
    }

    public static ObjectNode errorNode(String code, String message) {
        return appendError(JSON.createObjectNode(), code, message);
    }

    public static ObjectNode appendError(ObjectNode root, String code, String message) {
        root.put("error_code", code);
        root.put("message", message);
        root.put("retryable", false);
        return root;
    }

    public static void appendRetryWith(ObjectNode root, String argument, String guidance) {
        root.put("retryable", true);
        root.putObject("retry_with").put(argument, guidance);
    }

    public static String classLookupError(
            ReadQueryContext reader, ClassTargetResolver.Lookup lookup, String target) {
        return classLookupErrorNode(reader, lookup, target).toString();
    }

    public static ObjectNode classLookupErrorNode(
            ReadQueryContext reader, ClassTargetResolver.Lookup lookup, String target) {
        ObjectNode root = ClassTargetResolver.errorResponse(JSON, lookup, target);
        appendDependencyHint(root, reader, lookup, target);
        appendMeta(root, reader, 0);
        return root;
    }

    /**
     * Framework and library classes (VertxProducer, jakarta Event, ...) are not in the application
     * index; point at the dependency index instead of a dead end. A miss inside a package the
     * application itself declares is more likely a misspelled application class.
     */
    private static void appendDependencyHint(
            ObjectNode root,
            ReadQueryContext reader,
            ClassTargetResolver.Lookup lookup,
            String target) {
        if (!"CLASS_NOT_FOUND".equals(lookup.errorCode())
                || !root.path("candidates").isEmpty()
                || target == null
                || target.contains("/")) return;
        int lastDot = target.lastIndexOf('.');
        if (lastDot <= 0) return;
        String packageName = target.substring(0, lastDot);
        if (!reader.symbols.searchClasses(packageName + ".%", 1).isEmpty()) {
            root.put(
                    "reason",
                    "No such class in application package "
                            + packageName
                            + "; check the name with search_classes");
            return;
        }
        root.put(
                "reason",
                "Not in indexed application bytecode; it may be a dependency, "
                        + "build-time (deployment) or runtime-generated class");
        root.putObject("next_step")
                .put("tool", "get_external_symbol_details")
                .putObject("arguments")
                .put("class_name", target);
        appendApplicationReferences(root, reader, target, null);
        if (root.has("application_references"))
            root.put(
                    "reason",
                    "Dependency class, not in the application index; application_references lists the application "
                            + "classes and members using it");
    }

    private static final int REFERENCE_LIMIT = 20;

    /**
     * What a usage, bean or hierarchy question about a dependency class can still be answered with:
     * the application classes referencing it and the members it annotates.
     */
    public static void appendApplicationReferences(
            ObjectNode root, ReadQueryContext reader, String target, java.util.Set<String> kinds) {
        appendApplicationReferences(root, reader, target, kinds, REFERENCE_LIMIT);
    }

    public static void appendApplicationReferences(
            ObjectNode root,
            ReadQueryContext reader,
            String target,
            java.util.Set<String> kinds,
            int limit) {
        java.util.Map<String, java.util.Set<String>> references = new java.util.TreeMap<>();
        reader.usages.findCurrentExternalTypeUses(target).stream()
                .filter(row -> kinds == null || kinds.contains(String.valueOf(row.usageKind())))
                .forEach(
                        row ->
                                references
                                        .computeIfAbsent(
                                                String.valueOf(row.className()),
                                                ignored -> new java.util.TreeSet<>())
                                        .add(String.valueOf(row.usageKind())));
        if (kinds == null || kinds.contains("ANNOTATION"))
            reader.symbols
                    .findMembersAnnotatedWith(target)
                    .forEach(
                            member ->
                                    references
                                            .computeIfAbsent(
                                                    member, ignored -> new java.util.TreeSet<>())
                                            .add("ANNOTATION"));
        if (references.isEmpty()) return;
        var lines = root.putArray("application_references");
        references.entrySet().stream()
                .limit(limit)
                .forEach(entry -> lines.add(entry.getKey() + " " + entry.getValue()));
        if (references.size() > limit)
            root.putObject("application_references_truncated")
                    .put("shown", limit)
                    .put("total", references.size());
    }

    public static String queryEvidence(
            String json,
            String tool,
            java.util.Map<String, Object> arguments,
            boolean full,
            int offset) {
        try {
            var root = JSON.readTree(json);
            presentQueryEvidence(root, tool, JSON.valueToTree(arguments), full, offset);
            return root.toString();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return json;
        }
    }

    /**
     * {@link #queryEvidence(String, String, java.util.Map, boolean, int)} applied to a parsed
     * answer in place.
     */
    public static void queryEvidence(
            ObjectNode root,
            String tool,
            java.util.Map<String, Object> arguments,
            boolean full,
            int offset) {
        presentQueryEvidence(root, tool, JSON.valueToTree(arguments), full, offset);
    }

    private static ObjectNode evidenceRequest(String tool, ObjectNode arguments, int offset) {
        var request = JSON.createObjectNode().put("tool", tool);
        var args =
                arguments.deepCopy().put("evidence_detail", "full").put("evidence_offset", offset);
        request.set("arguments", args);
        return request;
    }

    private static void presentQueryEvidence(
            com.fasterxml.jackson.databind.JsonNode value,
            String tool,
            ObjectNode arguments,
            boolean full,
            int offset) {
        if (value instanceof ObjectNode object) {
            if (!full
                    && object.path("decision_owner").asText().equals("mcp_client")
                    && (object.has("verification_plan") || object.has("diagnostics"))) {
                var summary = ProjectAvailabilityResponses.summary(object);
                object.removeAll();
                object.setAll(summary);
            }
            if (!tool.equals("find_workspace_usages") && object.path("project").isTextual()) {
                arguments = arguments.deepCopy();
                arguments.set("project", object.path("project").deepCopy());
            }
            for (var entry : List.copyOf(object.properties())) {
                String field = entry.getKey();
                if (field.equals("coverage") && entry.getValue() instanceof ObjectNode coverage) {
                    coverage.put("evidence_detail", full ? "full" : "summary");
                    var scopes = coverage.path("indexed_scopes");
                    if (full && scopes.isArray()) {
                        // 50 scopes per evidence page keep a full page inside the response budget
                        // even for long module paths.
                        int total = scopes.size(),
                                from = Math.min(offset, total),
                                to = from + Math.min(SCOPE_PAGE, total - from);
                        var page = JSON.createArrayNode();
                        scopes.valueStream().skip(from).limit(to - from).forEach(page::add);
                        coverage.set("indexed_scopes", page);
                        var paging = coverage.putObject("indexed_scopes_page");
                        appendPage(paging, to - from, total, SCOPE_PAGE, offset);
                        var indexId =
                                coverage.has("index_id")
                                        ? coverage.path("index_id")
                                        : object.path("_meta").path("index_id");
                        if (!indexId.isMissingNode()) paging.set("index_id", indexId.deepCopy());
                        if (to < total)
                            paging.set("next_page_request", evidenceRequest(tool, arguments, to));
                    } else if (!full) {
                        countOnly(coverage, "indexed_scopes", "indexed_scope_count");
                        countOnly(
                                coverage,
                                "missing_test_output_modules",
                                "missing_test_output_module_count");
                        if (coverage.path("compiled_outputs") instanceof ObjectNode outputs)
                            countOnly(outputs, "stale_modules", "stale_module_count");
                        coverage.set("details_request", evidenceRequest(tool, arguments, 0));
                    }
                } else if (!full
                        && Set.of("project_warnings", "unavailable_projects").contains(field)
                        && entry.getValue().isArray()) {
                    var warnings = JSON.createArrayNode();
                    entry.getValue()
                            .forEach(
                                    warning ->
                                            warnings.add(
                                                    ProjectAvailabilityResponses.summary(warning)));
                    object.set(field, warnings);
                } else {
                    presentQueryEvidence(entry.getValue(), tool, arguments, full, offset);
                }
            }
        } else if (value.isArray()) {
            for (var item : value) presentQueryEvidence(item, tool, arguments, full, offset);
        }
    }

    private static void countOnly(ObjectNode object, String field, String count) {
        var values = object.remove(field);
        if (values == null) return;
        if (values.isArray()) {
            if (!object.has(count)) object.put(count, values.size());
        } else if (values.isNull()) object.putNull(count);
    }

    private static final java.util.Set<String> SUMMARY_INVENTORIES =
            java.util.Set.of(
                    "source_sets",
                    "stale_source_sets",
                    "directories",
                    "indexed_modules",
                    "modules",
                    "requested_modules",
                    "command_modules",
                    "quick_compile_modules",
                    "unresolved_modules",
                    "missing_modules",
                    "missing_classpath_modules",
                    "stale_modules",
                    "repository_stale_modules",
                    "changed_inputs");

    /** Presentation only: call after decisions that require complete inventories. */
    public static List<String> summarizeInventories(ObjectNode root) {
        var omitted = new java.util.ArrayList<String>();
        summarizeInventories(root, "", omitted);
        return List.copyOf(omitted);
    }

    private static void summarizeInventories(
            com.fasterxml.jackson.databind.JsonNode value, String path, List<String> omitted) {
        if (value instanceof ObjectNode object) {
            for (var entry : List.copyOf(object.properties())) {
                String field = entry.getKey();
                var items = object.path(field);
                String child = path.isEmpty() ? field : path + "." + field;
                if (items.isArray()
                        && SUMMARY_INVENTORIES.contains(field)
                        && !object.has(field + "_count")) {
                    object.put(field + "_count", items.size());
                    object.put(field + "_has_more", items.size() > 5);
                    if (field.equals("source_sets")) {
                        var counts = object.putObject("source_sets_status_counts");
                        for (var item : items) {
                            String status = item.path("status").asText("unknown");
                            counts.put(status, counts.path(status).asInt() + 1);
                        }
                    }
                    if (items.size() > 5) {
                        var sample = JSON.createArrayNode();
                        items.valueStream().limit(5).forEach(sample::add);
                        object.set(field, sample);
                        items = sample;
                        omitted.add(child + "_after_first_5");
                    }
                }
                summarizeInventories(items, child, omitted);
            }
        } else if (value.isArray()) {
            for (var item : value) summarizeInventories(item, path, omitted);
        }
    }

    public static boolean isProducer(String kind) {
        return "PRODUCER_METHOD".equals(kind) || "PRODUCER_FIELD".equals(kind);
    }
}
