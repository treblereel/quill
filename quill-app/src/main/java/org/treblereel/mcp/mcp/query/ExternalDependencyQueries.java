package org.treblereel.mcp.mcp.query;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import org.treblereel.mcp.mcp.support.ClassTargetResolver;
import org.treblereel.mcp.core.Json;

/** Builds external-library dependency responses. */
public final class ExternalDependencyQueries {

    private static final ObjectMapper JSON = Json.MAPPER;

    public String getExternalDeps(ReadQueryContext reader, String target, String library, int limit) {
        ClassRecord targetClass = null;
        if (target != null) {
            var lookup = ClassTargetResolver.resolve(reader, target);
            if (lookup.error() != null) return classLookupError(reader, lookup, target);
            targetClass = lookup.cls();
        }
        if (!reader.usages.hasExternalDeps()) {
            return errorResponse("No external dependency data. Re-run 'quill init' to index external dependencies.");
        }

        ObjectNode root = JSON.createObjectNode();

        if (targetClass != null) {
            ClassRecord cls = targetClass;

            root.put("target", cls.className());
            var deps = reader.usages.findExternalDeps(cls.id());

            Map<String, List<ExternalDepRecord>> byKind = new LinkedHashMap<>();
            for (ExternalDepRecord d : deps) {
                byKind.computeIfAbsent(d.usageKind(), k -> new ArrayList<>()).add(d);
            }

            ObjectNode depsNode = root.putObject("external_dependencies");
            for (var entry : byKind.entrySet()) {
                ArrayNode arr = depsNode.putArray(entry.getKey().toLowerCase());
                for (ExternalDepRecord d : entry.getValue()) {
                    arr.add(d.externalType());
                }
            }
            root.put("total_external_types", deps.size());
            appendMeta(root, reader, cls.sourceTokens());
        } else if (library != null) {
            root.put("library_filter", library);
            var classes = reader.usages.findClassesUsingType(library + ".%");
            ArrayNode arr = root.putArray("classes_using_library");
            for (var entry : classes) {
                arr.add(entry.getValue());
            }
            root.put("total_classes", classes.size());
            appendMeta(root, reader, 0);
        } else {
            ArrayNode arr = root.putArray("libraries");
            for (var entry : reader.usages.findExternalDepsByLibrary(limit)) {
                ObjectNode node = arr.addObject();
                node.put("package", entry.getKey());
                node.put("used_by_classes", entry.getValue());
            }
            appendMeta(root, reader, 0);
        }

        return root.toString();
    }

}
