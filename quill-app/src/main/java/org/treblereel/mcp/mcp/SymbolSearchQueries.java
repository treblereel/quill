package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.IndexReader.SymbolSearchResult;

/** Searches indexed type and member declarations without reading source files. */
final class SymbolSearchQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS = Set.of(
            "CLASS", "INTERFACE", "ANNOTATION", "ENUM", "RECORD",
            "FIELD", "CONSTRUCTOR", "METHOD");

    String searchSymbols(Jdbi jdbi, String pattern, String kind, int limit, int offset) {
        if (pattern == null || pattern.isBlank()) {
            return errorResponse("Search pattern must not be blank");
        }
        String normalizedKind = normalizeKind(kind);
        if (normalizedKind != null && !KINDS.contains(normalizedKind)) {
            return errorResponse("Invalid kind: expected class, interface, annotation, enum, "
                    + "record, field, constructor, method, or all");
        }
        var matches = IndexReader.searchSymbols(
                jdbi, pattern.trim(), normalizedKind, limit, offset);
        int total = IndexReader.countSymbols(jdbi, pattern.trim(), normalizedKind);

        ObjectNode root = JSON.createObjectNode();
        root.put("pattern", pattern.trim());
        if (normalizedKind == null) root.putNull("kind");
        else root.put("kind", normalizedKind.toLowerCase(Locale.ROOT));
        ArrayNode symbols = root.putArray("symbols");
        int naiveTokens = 0;
        Set<Integer> countedClasses = new HashSet<>();
        for (SymbolSearchResult match : matches) {
            ObjectNode node = symbols.addObject();
            node.put("kind", match.symbolKind().toLowerCase(Locale.ROOT));
            node.put("name", match.symbolName());
            node.put("declaring_class", match.className());
            node.put("class_kind", match.classKind().toLowerCase(Locale.ROOT));
            node.put("source", match.sourceFile() + ":" + match.sourceLine());
            node.put("origin", match.origin());
            if (match.module() == null) node.putNull("module");
            else node.put("module", match.module());
            if (match.sourceSet() == null) node.putNull("source_set");
            else node.put("source_set", match.sourceSet());
            if (match.signature() != null) {
                node.put("signature", match.signature());
                node.put("descriptor", match.descriptor());
                node.put("type", match.typeName());
                node.set("parameters", JSON.valueToTree(match.parameterTypes()));
                node.put("modifiers", match.modifiers());
                node.set("annotations", JSON.valueToTree(match.annotations()));
            }
            appendKotlinSemantics(node, match);
            if (countedClasses.add(match.classId())) naiveTokens += match.sourceTokens();
        }
        appendPage(root, matches.size(), total, limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static void appendKotlinSemantics(ObjectNode node, SymbolSearchResult match) {
        if (!"kotlin".equals(match.language())) return;
        node.put("language", "kotlin");
        node.put("kotlin_kind", match.semanticKind().toLowerCase(Locale.ROOT));
        node.put("kotlin_name", match.semanticName());
        if (match.isSuspend()) node.put("suspend", true);
        if (match.extension()) node.put("extension", true);
        if (match.hasDefaultParameters()) node.put("default_parameters", true);
        if (match.mutable()) node.put("mutable", true);
        if (match.lateinit()) node.put("lateinit", true);
        if (match.delegated()) node.put("delegated", true);
    }

    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind)) return null;
        return kind.trim().toUpperCase(Locale.ROOT);
    }
}
