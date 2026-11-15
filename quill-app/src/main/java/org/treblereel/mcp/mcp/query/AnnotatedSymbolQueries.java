package org.treblereel.mcp.mcp.query;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.SymbolRepository;
import org.treblereel.mcp.db.SymbolRepository.AnnotatedSymbolResult;
import org.treblereel.mcp.core.Json;

/** Finds annotated type and member declarations from the compiled index. */
public final class AnnotatedSymbolQueries {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Set<String> KINDS =
            Set.of("ALL", "TYPE", "METHOD", "FIELD", "CONSTRUCTOR", "PARAMETER");

    public String findAnnotatedSymbols(ReadQueryContext reader, String annotation, String kind,
            boolean includeMetaAnnotations, int limit, int offset) {
        String normalizedKind = kind == null ? "ALL" : kind.strip().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(normalizedKind)) {
            return errorResponse(
                    "Invalid kind: expected all, type, method, field, constructor, or parameter");
        }
        String requested = annotation.startsWith("@") ? annotation.substring(1) : annotation;
        List<String> names = reader.symbols.findSymbolAnnotationNames(requested);
        String resolved = names.stream().filter(requested::equals).findFirst().orElse(null);
        if (resolved == null && names.size() == 1) resolved = names.getFirst();
        if (resolved == null && names.isEmpty()) {
            ObjectNode empty = unindexedAnnotation(requested, "symbols");
            appendMeta(empty, reader, 0);
            return empty.toString();
        }
        if (resolved == null) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "Ambiguous annotation name");
            error.put("annotation", requested);
            error.set("candidates", JSON.valueToTree(names));
            appendMeta(error, reader, 0);
            return error.toString();
        }

        List<AnnotatedSymbolResult> symbols = reader.symbols.findAnnotatedSymbols(resolved, normalizedKind, includeMetaAnnotations, limit, offset);
        int total = reader.symbols.countAnnotatedSymbols(resolved, normalizedKind, includeMetaAnnotations);
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
            node.put("declared_kind", symbol.declarationKind());
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
            if (!symbol.annotationValues().isEmpty()) {
                // Explicitly written members only; defaults declared by the annotation are absent.
                node.set("values", JSON.valueToTree(symbol.annotationValues()));
            }
            node.put("annotation_target", symbol.annotationTarget());
            if (symbol.parameterIndex() != null) {
                ObjectNode parameter = node.putObject("parameter");
                parameter.put("index", symbol.parameterIndex());
                if (symbol.parameterName() != null) {
                    parameter.put("name", symbol.parameterName());
                }
                if (symbol.parameterType() != null) {
                    parameter.put("type", symbol.parameterType());
                }
            }
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
                .add("Only annotations preserved in compiled bytecode can be discovered");
        appendPage(root, symbols.size(), total, limit, offset);
        appendMeta(root, reader, naiveTokens);
        return root.toString();
    }

    /**
     * An annotation absent from the index is an empty answer, not a failure: the agent should
     * conclude "no indexed usages" instead of retrying or falling back to source search.
     */
    static ObjectNode unindexedAnnotation(String annotation, String resultField) {
        ObjectNode root = JSON.createObjectNode();
        root.put("annotation", annotation);
        root.put("annotation_indexed", false);
        root.putArray(resultField);
        root.put("showing", 0).put("total", 0).put("has_more", false);
        root.put("note", "No indexed declaration carries this annotation. Annotations with "
                + "SOURCE retention, or used only by external dependencies, are not indexed.");
        return root;
    }

    private static boolean typeKind(String kind) {
        return switch (kind) {
            case "CLASS", "INTERFACE", "ENUM", "ANNOTATION", "RECORD" -> true;
            default -> false;
        };
    }
}
