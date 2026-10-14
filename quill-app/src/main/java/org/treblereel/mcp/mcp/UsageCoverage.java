package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.file.*;
import java.time.Instant;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.CompiledOutputInspector;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.MetaEnvelope;
import org.treblereel.mcp.core.SourceFileHashes;

/** Qualifies completeness inside the indexed snapshot; never proves global non-use. */
final class UsageCoverage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private UsageCoverage() {}
    static void append(ObjectNode response, Jdbi db, int targetId) {
        Map<String,String> metadata=IndexReader.getMetadata(db);
        var freshness=MetaEnvelope.from(db,0,0);
        var coverage=response.putObject("coverage").put("schema_version",1).put("scope","indexed_application_bytecode")
                .put("repository_usage_completeness_proven",false).put("external_consumers_covered",false)
                .put("reflection_covered",false).put("source_only_references_covered",false)
                .put("runtime_dispatch_exhaustive",false).put("bytecode_source_identity_proven",false);
        coverage.put("index_id",metadata.getOrDefault("index_id","unknown"));
        coverage.put("indexed_at",metadata.getOrDefault("indexed_at","unknown"));
        JsonNode sources=org.treblereel.mcp.db.QueryMetrics.measure("source_freshness", () -> sourceFreshness(metadata));
        coverage.set("source_snapshot",sources);
        String snapshot=freshness.structureStale() || sources.path("state").asText().equals("changed") ? "stale" : freshness.currentCommit()!=null
                && metadata.containsKey("index_id") && metadata.containsKey("indexed_at") ? "current_observed_worktree" : "unknown";
        coverage.put("snapshot_freshness",snapshot);
        var reasons=coverage.putArray("incomplete_reasons");
        if (!snapshot.equals("current_observed_worktree")) reasons.add(snapshot.equals("stale") ? "stale_index" : "freshness_evidence_unavailable");
        if (!sources.path("state").asText().equals("matching")) reasons.add("indexed_java_source_snapshot_unverified");
        JsonNode build=org.treblereel.mcp.db.QueryMetrics.measure("build_freshness", () -> buildFreshness(metadata));
        coverage.set("compiled_outputs",build);
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
        db.useHandle(h -> h.createQuery("""
                SELECT module, source_set, COUNT(*) AS classes FROM classes
                WHERE lifecycle='current' AND origin NOT IN ('dependency','orphan_output')
                GROUP BY module,source_set ORDER BY COALESCE(module,''),COALESCE(source_set,'')
                """).map((row,ctx) -> {
                    var scope=JSON.createObjectNode();
                    if (row.getString("module")==null) scope.putNull("module"); else scope.put("module",row.getString("module"));
                    if (row.getString("source_set")==null) scope.putNull("source_set"); else scope.put("source_set",row.getString("source_set"));
                    scope.put("class_count",row.getInt("classes")); return scope;
                }).list().forEach(scopes::add));
        if (scopes.valueStream().anyMatch(s -> !s.hasNonNull("module") || !s.hasNonNull("source_set"))) reasons.add("unlabeled_indexed_scopes");
        int scopeCount=scopes.size();
        while (scopes.size()>100) scopes.remove(scopes.size()-1);
        coverage.put("indexed_scope_count",scopeCount).put("scope_inventory_truncated",scopeCount>100);
        if (scopeCount>100) reasons.add("scope_inventory_truncated");
        boolean scanComplete=!response.path("normalization_truncated").asBoolean() && !response.path("traversal_truncated").asBoolean();
        boolean pageComplete=!response.path("truncated").asBoolean();
        coverage.put("scan_complete",scanComplete).put("page_complete",pageComplete);
        if (!scanComplete) reasons.add("scan_truncated");
        if (!pageComplete) reasons.add("page_window_incomplete");
        int handles=db.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM dependencies WHERE to_class_id=:id AND kind IN ('METHOD_HANDLE_REFERENCE','FIELD_HANDLE_REFERENCE')")
                .bind("id",targetId).mapTo(Integer.class).one());
        coverage.put("unclassified_handle_reference_groups",handles);
        if (handles>0) reasons.add("unclassified_member_handles");
        int legacyDynamic="2".equals(metadata.get("invokedynamic_evidence_protocol")) ? 0 : db.withHandle(h -> h.createQuery(
                "SELECT COUNT(*) FROM method_calls WHERE invocation_kind='dynamic' AND (to_class_id=:id OR from_class_id=:id)")
                .bind("id",targetId).mapTo(Integer.class).one());
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
    private static JsonNode buildFreshness(Map<String,String> metadata) {
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
    private static JsonNode sourceFreshness(Map<String,String> metadata) {
        var result=JSON.createObjectNode().put("state","unknown").put("basis","sha256").put("language_scope","java");
        try {
            JsonNode hashes=JSON.readTree(metadata.getOrDefault("java_source_hashes","null"));
            JsonNode roots=metadataArray(metadata,"java_source_roots");
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
            result.put("state",expected.equals(SourceFileHashes.captureJavaCached(root,paths)) ? "matching" : "changed");
            return result;
        } catch (Exception unavailable) { return result; }
    }
}
