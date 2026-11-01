package org.treblereel.mcp.mcp;

import org.treblereel.mcp.index.BuildStatusInspector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.core.SourceFileHashes;

/** Per-response compiler evidence, kept separate from bytecode line evidence. */
final class JavaSourceLocations {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ReadQueryContext db;
    private final Path root;
    private final JsonNode hashes;
    private final boolean fresh;
    private final Map<String, JsonNode> files = new HashMap<>();
    /**
     * Source lines of hash-verified files. Returning the referencing line saves the agent a
     * read_file round just to see the code a location points at.
     */
    private static final int MAX_EXCERPTS = 50, MAX_EXCERPT_LENGTH = 160;
    private final Map<String, List<String>> lines = new HashMap<>();
    private int excerpts;
    private boolean used;
    JavaSourceLocations(ReadQueryContext db) {
        this.db = db;
        var metadata = db.index.getMetadata();
        root = metadata.containsKey("project_root") ? Path.of(metadata.get("project_root")).toAbsolutePath().normalize() : null;
        JsonNode evidence;
        try { evidence = JSON.readTree(metadata.getOrDefault("java_source_hashes", "{}")); }
        catch (Exception unavailable) { evidence = JSON.createObjectNode(); }
        hashes = evidence == null || !evidence.isObject() ? JSON.createObjectNode() : evidence;
        fresh = !org.treblereel.mcp.index.BuildStatusInspector.freshness(db, 0, 0).structureStale();
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
        used = true;
        // basis, scope and the never-proven flags are identical for every location; they are
        // stated once per response by writeDefaults.
        var evidence = node.putObject("source_evidence").put("status", result.path("status").asText());
        if (!"resolved".equals(result.path("status").asText())) return;
        evidence.put("generation", result.path("generation").asLong());
        if (result.path("truncated").asBoolean()) evidence.put("truncated", true);
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
            String excerpt = excerpt(source, reference.path("line").asInt());
            if (excerpt != null) location.put("excerpt", excerpt);
        }
        evidence.put("status", locations.isEmpty() ? "no_matching_source_reference" : "corroborated");
    }
    /** Writes the properties shared by every source_evidence object of this response. */
    void writeDefaults(ObjectNode root) {
        if (!used) return;
        root.putObject("source_evidence_defaults").put("basis", "compiler").put("scope", "indexed_source")
                .put("bytecode_source_identity_proven", false).put("reference_coverage_complete", false)
                .put("truncated", false);
    }
    private String excerpt(String source, int line) {
        if (excerpts >= MAX_EXCERPTS || line < 1 || root == null) return null;
        List<String> text = lines.computeIfAbsent(source, ignored -> {
            try { return java.nio.file.Files.readAllLines(root.resolve(source).normalize()); }
            catch (Exception unavailable) { return List.of(); }
        });
        if (line > text.size()) return null;
        excerpts++;
        String value = text.get(line - 1).strip();
        return value.length() <= MAX_EXCERPT_LENGTH ? value : value.substring(0, MAX_EXCERPT_LENGTH) + "…";
    }
    private static JsonNode status(String value) { return JSON.createObjectNode().put("status", value); }
}
