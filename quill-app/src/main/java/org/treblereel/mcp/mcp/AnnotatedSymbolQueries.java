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
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.AnnotatedSymbolResult;

/** Finds annotated type and member declarations from the compiled index. */
final class AnnotatedSymbolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS =
            Set.of("ALL", "TYPE", "METHOD", "FIELD", "CONSTRUCTOR");

    String findAnnotatedSymbols(Jdbi jdbi, String annotation, String kind,
            boolean includeMetaAnnotations, int limit, int offset) {
        String normalizedKind = kind == null ? "ALL" : kind.strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid kind: expected all, type, method, field, or constructor");
        }
        String requested = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        List<String> names = IndexReader.findSymbolAnnotationNames(jdbi, requested);
        String resolved = names.stream().filter(requested::equals).findFirst().orElse(null);
        if (resolved == null && names.size() == 1) resolved = names.getFirst();
        if (resolved == null) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", names.isEmpty()
                    ? "Annotation not found" : "Ambiguous annotation name");
            error.put("annotation", requested);
            error.set("candidates", JSON.valueToTree(names));
            appendMeta(error, jdbi, 0);
            return error.toString();
        }

        List<AnnotatedSymbolResult> symbols = IndexReader.findAnnotatedSymbols(
                jdbi, resolved, normalizedKind, includeMetaAnnotations, limit, offset);
        int total = IndexReader.countAnnotatedSymbols(
                jdbi, resolved, normalizedKind, includeMetaAnnotations);
        ObjectNode root = JSON.createObjectNode();
        root.put("annotation", resolved);
        root.put("kind", normalizedKind.toLowerCase(Locale.ROOT));
        root.put("include_meta_annotations", includeMetaAnnotations);
        root.put("meta_annotation_scope", "type_declarations_only");
        ArrayNode values = root.putArray("symbols");
        int naiveTokens = 0;
        for (AnnotatedSymbolResult symbol : symbols) {
            ObjectNode node = values.addObject();
            node.put("kind", typeKind(symbol.symbolKind()) ? "TYPE" : symbol.symbolKind());
            node.put("declared_kind", symbol.symbolKind());
            node.put("class", symbol.className());
            node.put("name", symbol.symbolName());
            if (symbol.signature() != null) node.put("signature", symbol.signature());
            if (symbol.descriptor() != null) node.put("descriptor", symbol.descriptor());
            if (symbol.typeName() != null) node.put("type", symbol.typeName());
            if (!symbol.parameterTypes().isEmpty()) {
                node.set("parameter_types", JSON.valueToTree(symbol.parameterTypes()));
            }
            if (symbol.modifiers() != null && !symbol.modifiers().isBlank()) {
                node.put("modifiers", symbol.modifiers());
            }
            node.put("match", symbol.match());
            if (symbol.viaAnnotation() != null) {
                node.put("via_annotation", symbol.viaAnnotation());
            }
            if (symbol.sourceFile() != null) {
                node.put("source", symbol.sourceLine() > 0
                        ? symbol.sourceFile() + ":" + symbol.sourceLine()
                        : symbol.sourceFile());
            }
            node.put("origin", symbol.origin());
            if (symbol.module() != null) node.put("module", symbol.module());
            if (symbol.sourceSet() != null) node.put("source_set", symbol.sourceSet());
            naiveTokens += symbol.sourceTokens();
        }
        root.putArray("limitations")
                .add("Meta-annotation expansion is indexed for type declarations only")
                .add("Method parameter annotations are reported on their owning method because parameter positions are not stored")
                .add("Only annotations preserved in compiled bytecode can be discovered");
        appendPage(root, symbols.size(), total, limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static boolean typeKind(String kind) {
        return !"METHOD".equals(kind) && !"FIELD".equals(kind)
                && !"CONSTRUCTOR".equals(kind);
    }
}
