package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.MetaEnvelope;

/** Per-response compiler evidence, kept separate from bytecode line evidence. */
final class JavaSourceLocations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Jdbi db;
    private final Path root;
    private final JsonNode hashes;
    private final boolean fresh;
    private final Map<String, JsonNode> files = new HashMap<>();
    JavaSourceLocations(Jdbi db) {
        this.db = db;
        var metadata = IndexReader.getMetadata(db);
        root = metadata.containsKey("project_root") ? Path.of(metadata.get("project_root")).toAbsolutePath().normalize() : null;
        JsonNode evidence;
        try { evidence = JSON.readTree(metadata.getOrDefault("java_source_hashes", "{}")); }
        catch (Exception unavailable) { evidence = JSON.createObjectNode(); }
        hashes = evidence == null || !evidence.isObject() ? JSON.createObjectNode() : evidence;
        fresh = !MetaEnvelope.from(db, 0, 0).structureStale();
    }
    private JsonNode references(String source) {
        try {
            if (files.size() >= 10) return status("source_evidence_file_limit");
            if (root == null || !hashes.path(source).isTextual()) return status("indexed_source_hash_unavailable");
            if (!fresh) return status("index_stale");
            Path file = root.resolve(source).normalize();
            if (!file.startsWith(root) || !file.toRealPath().startsWith(root.toRealPath())) return status("source_outside_root");
            if (!hashes.path(source).asText().equals(SourceFileHashes.hash(file))) return status("source_changed_after_index");
            JsonNode result = JavaSemanticQueries.resolve(db, root, file, -2);
            if (!"resolved".equals(result.path("status").asText())) return result;
            if (!hashes.path(source).asText().equals(result.path("source_hash").asText())
                    || !hashes.path(source).asText().equals(SourceFileHashes.hash(file))) return status("source_changed_after_index");
            return result;
        } catch (Exception unavailable) { return status("source_unavailable"); }
    }
    void append(ObjectNode node, String source, String callerOwner, String callerName, String callerDescriptor,
            String owner, String name, String descriptor, String access, String symbolId, List<Integer> lines) {
        if (source == null || !source.endsWith(".java")) return;
        JsonNode result = files.computeIfAbsent(source, this::references);
        var evidence = node.putObject("source_evidence").put("basis", "compiler")
                .put("status", result.path("status").asText()).put("scope", "indexed_source")
                .put("bytecode_source_identity_proven", false).put("reference_coverage_complete", false);
        if (!"resolved".equals(result.path("status").asText())) return;
        evidence.put("generation", result.path("generation").asLong());
        evidence.put("truncated", result.path("truncated").asBoolean());
        var locations = evidence.putArray("locations");
        for (JsonNode reference : result.path("references")) {
            if (!reference.path("owner").asText().equals(owner) || !reference.path("name").asText().equals(name)
                    || !reference.path("descriptor").asText().equals(descriptor)
                    || !reference.path("caller_owner").asText().equals(callerOwner)
                    || !reference.path("caller_name").asText().equals(callerName)
                    || !reference.path("caller_descriptor").asText().equals(callerDescriptor)
                    || !lines.contains(reference.path("line").asInt())) continue;
            String mode = reference.path("access").asText();
            if (!access.equals("call") && !mode.equals(access) && !mode.equals("read_write")) continue;
            var location = locations.addObject().put("file", source).put("symbol_id", symbolId).put("usage_kind", mode)
                    .put("range_kind", "reference_expression");
            for (String coordinate : List.of("line", "column", "end_line", "end_column")) location.set(coordinate, reference.get(coordinate));
        }
        evidence.put("status", locations.isEmpty() ? "no_matching_source_reference" : "corroborated");
    }
    private static JsonNode status(String value) { return JSON.createObjectNode().put("status", value); }
}
