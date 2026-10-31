package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.ReferenceRepository.Resolution;
import org.treblereel.mcp.db.ReferenceRepository.ResolutionKey;

/** Searches normalized configuration definitions and their consumers. */
final class ConfigurationReferenceQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS = Set.of("all", "property", "prefix", "persistence");

    String find(ReadQueryContext reader, String key, String className, String kind, String module,
            int limit, int offset) {
        String normalizedKind = kind == null ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid kind: expected all, property, prefix, or persistence");
        }
        try {
            var page = reader.references
                    .findConfiguration(key, className, normalizedKind, module, limit, offset);
            int definitionCount = page.definitionCount();
            int usageCount = page.usageCount();
            int total = definitionCount + usageCount;
            var references = page.references();
            var resolutions = page.resolutions();

            ObjectNode result = JSON.createObjectNode();
            putNullable(result, "key", key);
            putNullable(result, "class", className);
            result.put("kind", normalizedKind);
            putNullable(result, "module", module);
            ArrayNode values = result.putArray("references");
            for (org.treblereel.mcp.db.ReferenceRepository.ConfigurationReference reference : references) {
                ObjectNode node = values.addObject();
                String entryType = text(reference.entryType());
                node.put("entry_type", entryType);
                node.put("key", text(reference.key()));
                node.put("kind", text(reference.kind()));
                putNullable(node, "module", reference.module());
                putNullable(node, "sourceSet", reference.sourceSet());
                if (entryType.equals("definition")) {
                    node.put("file", text(reference.file()));
                    node.put("line", reference.line());
                } else {
                    node.put("classId", reference.classId());
                    node.put("className", text(reference.className()));
                    putNullable(node, "member", reference.member());
                    putNullable(node, "parameterIndex", reference.parameterIndex());
                    node.put("annotation", text(reference.annotation()));
                    putNullable(node, "source", reference.source());
                    String usageKind = text(reference.kind());
                    boolean dynamic = usageKind.equals("dynamic_config_key");
                    Resolution resolution = dynamic ? new Resolution(0, List.of())
                            : resolutions.getOrDefault(
                                    new ResolutionKey(text(reference.key()), usageKind),
                                    new Resolution(0, List.of()));
                    node.put("resolved", !dynamic && resolution.count() > 0);
                    node.put("resolution_status", dynamic ? "unknown"
                            : resolution.count() > 0 ? "resolved" : "unsatisfied");
                    node.put("resolution_strategy", dynamic ? "dynamic_programmatic_lookup"
                            : text(reference.annotation()).contains("#")
                                    ? "constant_programmatic_lookup" : "annotation_lookup");
                    node.put("confidence", dynamic ? 0.0 : 1.0);
                    node.put("definition_count", resolution.count());
                    ArrayNode files = node.putArray("definition_files");
                    resolution.files().forEach(files::add);
                }
            }
            result.put("definition_count", definitionCount);
            result.put("usage_count", usageCount);
            result.put("values_indexed", false);
            result.putArray("limitations")
                    .add("Configuration values are intentionally not stored")
                    .add("Consumers cover supported annotations and common programmatic APIs")
                    .add("Environment-variable and YAML list lookups are not resolved")
                    .add("Dynamic programmatic keys are reported as unknown without guessing their value");
            appendPage(result, references.size(), total, limit, offset);
            appendMeta(result, reader, 0);
            return result.toString();
        } catch (RuntimeException error) {
            return errorResponse("Could not query indexed configuration references");
        }
    }













    private static String text(String value) {
        return value == null ? "" : value;
    }


    private static void putNullable(ObjectNode node, String key, Object value) {
        if (value == null || value.toString().isBlank()) node.putNull(key);
        else if (value instanceof Number number) node.put(key, number.intValue());
        else node.put(key, value.toString());
    }



}
