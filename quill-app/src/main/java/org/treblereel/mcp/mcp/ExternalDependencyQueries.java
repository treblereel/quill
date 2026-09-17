package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ExternalDepRecord;

/** Builds external-library dependency responses. */
final class ExternalDependencyQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        if (!IndexReader.hasExternalDeps(jdbi)) {
            return errorResponse("No external dependency data. Re-run 'quill init' to index external dependencies.");
        }

        ObjectNode root = JSON.createObjectNode();

        if (target != null) {
            var lookup = ClassTargetResolver.resolve(jdbi, target);
            if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
            ClassRecord cls = lookup.cls();

            root.put("target", cls.className());
            var deps = IndexReader.findExternalDeps(jdbi, cls.id());

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
            appendMeta(root, jdbi, cls.sourceTokens());
        } else if (library != null) {
            root.put("library_filter", library);
            var classes = IndexReader.findClassesUsingType(jdbi, library + ".%");
            ArrayNode arr = root.putArray("classes_using_library");
            for (var entry : classes) {
                arr.add(entry.getValue());
            }
            root.put("total_classes", classes.size());
            appendMeta(root, jdbi, 0);
        } else {
            ArrayNode arr = root.putArray("libraries");
            for (var entry : IndexReader.findExternalDepsByLibrary(jdbi, limit)) {
                ObjectNode node = arr.addObject();
                node.put("package", entry.getKey());
                node.put("used_by_classes", entry.getValue());
            }
            appendMeta(root, jdbi, 0);
        }

        return root.toString();
    }

}
