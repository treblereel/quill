package org.treblereel.mcp.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Prepared evidence for one generation and observed input revision; callbacks perform no I/O. */
public record ProjectReadSnapshot(
        Map<String, String> metadata, List<ClassRecord> classes,
        JsonNode sourceFreshness, JsonNode buildFreshness, JsonNode worktree,
        MetaEnvelope freshness, List<String> structuralPaths,
        BiFunction<List<String>, MetaEnvelope, String> buildStatus,
        BiFunction<JsonNode, JsonNode, ObjectNode> verificationPlan) {
    public ProjectReadSnapshot {
        metadata = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(metadata));
        classes = List.copyOf(classes);
        structuralPaths = List.copyOf(structuralPaths);
        sourceFreshness = sourceFreshness.deepCopy();
        buildFreshness = buildFreshness.deepCopy();
        worktree = worktree.deepCopy();
    }
}
