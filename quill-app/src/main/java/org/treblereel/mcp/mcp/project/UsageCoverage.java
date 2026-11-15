package org.treblereel.mcp.mcp.project;

import org.treblereel.mcp.index.BuildStatusInspector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.file.*;
import java.time.Instant;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.index.CompiledOutputInspector;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.core.Json;

/** Qualifies completeness inside the indexed snapshot; never proves global non-use. */
public final class UsageCoverage {
    private static final ObjectMapper JSON = Json.MAPPER;
    private UsageCoverage() {}
    private static final com.fasterxml.jackson.databind.node.ArrayNode NOT_COVERED =
            Json.MAPPER.createArrayNode()
                    .add("repository_usage_completeness").add("external_consumers").add("reflection")
                    .add("source_only_references").add("runtime_dispatch_exhaustiveness")
                    .add("bytecode_source_identity");
    public static void append(ObjectNode response, ReadQueryContext db, int targetId) {
        Map<String,String> metadata=db.index.getMetadata();
        var freshness=org.treblereel.mcp.index.BuildStatusInspector.freshness(db,0,0);
        // The same six guarantees are never proven; name them once instead of six false flags.
        // index_id and indexed_at are reported by _meta on the same response.
        var coverage=response.putObject("coverage").put("schema_version",2).put("scope","indexed_application_bytecode");
        coverage.set("not_covered", NOT_COVERED.deepCopy());
        JsonNode sources=db.sourceFreshness(
                () -> org.treblereel.mcp.diagnostics.QueryMetrics.measure("source_freshness", () -> sourceFreshness(metadata)));
        coverage.set("source_snapshot",sources.deepCopy());
        String snapshot=freshness.structureStale() || sources.path("state").asText().equals("changed") ? "stale" : freshness.currentCommit()!=null
                && metadata.containsKey("index_id") && metadata.containsKey("indexed_at") ? "current_observed_worktree" : "unknown";
        coverage.put("snapshot_freshness",snapshot);
        var reasons=coverage.putArray("incomplete_reasons");
        if (!snapshot.equals("current_observed_worktree")) reasons.add(snapshot.equals("stale") ? "stale_index" : "freshness_evidence_unavailable");
        if (!sources.path("state").asText().equals("matching")) reasons.add("indexed_java_source_snapshot_unverified");
        JsonNode build=db.buildFreshness(
                () -> org.treblereel.mcp.diagnostics.QueryMetrics.measure("build_freshness", () -> buildFreshness(metadata)));
        coverage.set("compiled_outputs",build.deepCopy());
        if (!build.path("state").asText().equals("fresh")) reasons.add("compiled_output_coverage_unverified");
        if (build.path("changed_after_index").asBoolean()) reasons.add("compiled_outputs_changed_after_index");
        String discovery=metadata.get("module_discovery_complete");
        coverage.put("module_discovery", "true".equals(discovery) ? "complete" : "false".equals(discovery) ? "partial" : "unknown");
        if (!"true".equals(discovery)) reasons.add(discovery==null ? "module_discovery_unknown" : "module_discovery_partial");
        var missing=metadataArray(metadata,"missing_test_output_modules");
        coverage.set("missing_test_output_modules",missing);
        if (missing==null) reasons.add("test_output_coverage_unknown");
        else if (!missing.isEmpty()) reasons.add("test_outputs_missing");
        var scopes=coverage.putArray("indexed_scopes");
        db.symbols.findIndexedScopes().forEach(scope -> {
            var node = scopes.addObject();
            if (scope.module() == null) node.putNull("module"); else node.put("module", scope.module());
            if (scope.sourceSet() == null) node.putNull("source_set"); else node.put("source_set", scope.sourceSet());
            node.put("class_count", scope.classes());
        });
        if (scopes.valueStream().anyMatch(s -> !s.hasNonNull("module") || !s.hasNonNull("source_set"))) reasons.add("unlabeled_indexed_scopes");
        int scopeCount=scopes.size();
        // Keep rows until MCP evidence paging; the conservative completeness flags stay unchanged.
        coverage.put("indexed_scope_count",scopeCount).put("scope_inventory_truncated",scopeCount>100);
        if (scopeCount>100) reasons.add("scope_inventory_truncated");
        boolean scanComplete=!response.path("normalization_truncated").asBoolean() && !response.path("traversal_truncated").asBoolean();
        boolean pageComplete=!response.path("truncated").asBoolean();
        coverage.put("scan_complete",scanComplete).put("page_complete",pageComplete);
        if (!scanComplete) reasons.add("scan_truncated");
        if (!pageComplete) reasons.add("page_window_incomplete");
        int handles=db.usages.countUnclassifiedHandles(targetId);
        coverage.put("unclassified_handle_reference_groups",handles);
        if (handles>0) reasons.add("unclassified_member_handles");
        int legacyDynamic="2".equals(metadata.get("invokedynamic_evidence_protocol")) ? 0 : db.usages.countLegacyDynamicReferences(targetId);
        coverage.put("legacy_dynamic_reference_groups",legacyDynamic);
        if (legacyDynamic>0) reasons.add("legacy_bootstrap_classification_unknown");
        boolean indexedComplete=reasons.isEmpty();
        coverage.put("indexed_scope_verified",indexedComplete);
        var dispatch=response.path("dispatch_context");
        boolean dispatchPageComplete=!dispatch.path("truncated").asBoolean() && !dispatch.path("scan_truncated").asBoolean();
        response.put("answer_complete",indexedComplete && dispatchPageComplete);
        response.put("unused_proven",false);
        if (response.path("showing").asInt()>0) return;
        var empty=response.putObject("empty_result").put("unused_proven",false)
                .put("indexed_total",response.path("total").asInt()).put("current_indexed_scope_verified",indexedComplete)
                .put("possible_dispatch_callers_present",dispatch.path("total").asInt()>0);
        empty.put("interpretation", response.path("total").asInt()>0 ? "empty_page_not_zero_usages"
                : indexedComplete ? "no_matching_references_in_indexed_scope" : "unknown_current_usage_coverage");
        var filters=empty.putObject("filters");
        for (String filter:List.of("module_filter","usage_kind","access","scope","direction","transitive","max_depth"))
            if (response.has(filter)) filters.set(filter,response.get(filter));
        empty.set("incomplete_reasons",reasons.deepCopy());
    }
    private static JsonNode metadataArray(Map<String,String> metadata,String key) {
        try {
            JsonNode node=JSON.readTree(metadata.getOrDefault(key,"null"));
            return node!=null && node.isArray() && node.valueStream().allMatch(JsonNode::isTextual) ? node : null;
        } catch (Exception invalid) { return null; }
    }
    static JsonNode buildFreshness(BuildStatusInspector.Observation observation) {
        var report = observation.compiledSnapshot().inspect(null);
        var result = JSON.createObjectNode().put("state", report.state().name().toLowerCase(Locale.ROOT))
                .put("basis", "prepared_build_observation").put("bytecode_identity_proven", false);
        result.set("stale_modules", JSON.valueToTree(report.staleModules()));
        Instant indexed;
        try { indexed = Instant.parse(observation.metadata().get("indexed_at")); }
        catch (RuntimeException unavailable) { return result.put("state", "unknown"); }
        boolean changed = observation.classTimes().values().stream().anyMatch(time -> time > indexed.toEpochMilli());
        result.put("changed_after_index", changed && !Boolean.FALSE.equals(observation.fingerprintChanged()));
        if (Boolean.FALSE.equals(observation.fingerprintChanged())) result.put("content_matches_index", true);
        return result;
    }

    static JsonNode buildFreshness(Map<String,String> metadata) {
        var result=JSON.createObjectNode().put("state","unknown").put("basis","observed_files_and_timestamps")
                .put("bytecode_identity_proven",false);
        try {
            if (!metadata.containsKey("project_root")) return result;
            Path root=Path.of(metadata.get("project_root"));
            if (!Files.isDirectory(root)) return result;
            Instant indexed=Instant.parse(metadata.get("indexed_at"));
            var observation=CompiledOutputInspector.observe(root,indexed);
            var report=observation.report();
            result.put("state",report.state().name().toLowerCase(Locale.ROOT));
            result.set("stale_modules",JSON.valueToTree(report.staleModules()));
            boolean changed=observation.changedAfterIndex();
            result.put("changed_after_index",changed); return result;
        } catch (Exception unavailable) { return result.put("state","unknown"); }
    }
    static JsonNode sourceFreshness(Map<String,String> metadata) {
        boolean inputs = metadata.containsKey("source_input_hashes");
        boolean jvm = inputs || metadata.containsKey("jvm_source_hashes");
        var result=JSON.createObjectNode().put("state","unknown").put("basis","sha256").put("language_scope", jvm ? "jvm" : "java");
        try {
            JsonNode hashes=JSON.readTree(metadata.getOrDefault(inputs ? "source_input_hashes" : jvm ? "jvm_source_hashes" : "java_source_hashes","null"));
            JsonNode roots=metadataArray(metadata,inputs ? "source_input_roots" : "java_source_roots");
            if (hashes==null || !hashes.isObject() || roots==null || !metadata.containsKey("project_root")) return result;
            Path root=Path.of(metadata.get("project_root")).toAbsolutePath().normalize();
            var paths=new ArrayList<Path>();
            for (JsonNode value:roots) {
                Path path=root.resolve(value.asText()).normalize();
                if (!path.startsWith(root)) return result;
                paths.add(path);
            }
            Map<String,String> expected=new TreeMap<>();
            var entries=hashes.fields();
            while (entries.hasNext()) {
                var entry=entries.next();
                if (!entry.getValue().isTextual() || !entry.getValue().asText().matches("[a-f0-9]{64}")) return result;
                expected.put(entry.getKey(),entry.getValue().asText());
            }
            result.put("state",expected.equals(inputs ? SourceFileHashes.captureInputsCached(root,paths)
                    : jvm ? SourceFileHashes.captureJvmCached(root,paths) : SourceFileHashes.captureJavaCached(root,paths)) ? "matching" : "changed");
            return result;
        } catch (Exception unavailable) { return result; }
    }
}
