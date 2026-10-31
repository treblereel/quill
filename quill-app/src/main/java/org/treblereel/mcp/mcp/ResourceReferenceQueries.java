package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.treblereel.mcp.db.ReadQueryContext;

/** Finds classpath resources and bytecode-visible consumers. */
final class ResourceReferenceQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String find(ReadQueryContext reader, String path, String className, String module,
            int limit, int offset) {
        if (path == null || path.isBlank()) return errorResponse("Resource path is required");
        try {
            var page = reader.references
                    .findResources(normalize(path), className, module, limit, offset);
            int definitionCount = page.definitionCount();
            int usageCount = page.usageCount();
            var rows = page.references();
            var definitions = page.definitions();

            ObjectNode result = JSON.createObjectNode();
            result.put("path", path);
            if (className == null || className.isBlank()) result.putNull("class");
            else result.put("class", className);
            if (module == null || module.isBlank()) result.putNull("module");
            else result.put("module", module);
            ArrayNode references = result.putArray("references");
            for (org.treblereel.mcp.db.ReferenceRepository.ResourceReference row : rows) appendReference(
                    references, row, definitions.getOrDefault(org.treblereel.mcp.db.ReferenceRepository.resourceKey(row), List.of()));
            result.put("definition_count", definitionCount);
            result.put("usage_count", usageCount);
            result.putArray("limitations")
                    .add("Only constant paths passed to supported classpath resource APIs are resolved")
                    .add("Dynamic paths are reported as unknown")
                    .add("Non-classpath Spring resources are reported as unsupported, not missing");
            appendPage(result, rows.size(), definitionCount + usageCount, limit, offset);
            appendMeta(result, reader, 0);
            return result.toString();
        } catch (RuntimeException error) {
            return errorResponse("Could not query indexed resource references");
        }
    }

    private static void appendReference(
            ArrayNode target, org.treblereel.mcp.db.ReferenceRepository.ResourceReference row, List<String> files) {
        ObjectNode node = target.addObject();
        String type = text(row.entryType());
        node.put("entry_type", type);
        node.put("path", text(row.resourcePath()));
        node.put("kind", text(row.kind()));
        nullable(node, "module", row.module());
        nullable(node, "sourceSet", row.sourceSet());
        if (type.equals("definition")) {
            node.put("file", text(row.file()));
            return;
        }
        node.put("classId", row.classId());
        node.put("className", text(row.className()));
        node.put("member", text(row.member()));
        node.put("api", text(row.api()));
        nullable(node, "source", row.source());
        String kind = text(row.kind());
        boolean dynamic = kind.equals("dynamic_resource");
        boolean external = kind.equals("external_resource");
        node.put("resolution_status", dynamic ? "unknown" : external
                ? "unsupported_mechanism" : files.isEmpty() ? "unsatisfied" : "resolved");
        node.put("resolution_strategy", dynamic ? "dynamic_resource_path" : external
                ? "external_resource" : kind.equals("resource_bundle")
                        ? "resource_bundle_family" : "classpath_resource");
        node.put("confidence", dynamic ? 0.0 : 1.0);
        ArrayNode definitions = node.putArray("definition_files");
        files.forEach(definitions::add);
    }









    private static String normalize(String value) {
        String result = value.strip().replace('\\', '/');
        if (result.startsWith("classpath:")) result = result.substring(10);
        while (result.startsWith("/")) result = result.substring(1);
        return result;
    }





    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void nullable(ObjectNode node, String key, Object value) {
        if (value == null || value.toString().isBlank()) node.putNull(key);
        else node.put(key, value.toString());
    }


}
