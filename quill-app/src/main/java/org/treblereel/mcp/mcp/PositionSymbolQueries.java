package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** Resolves the identifier under a live source position against indexed declarations. */
final class PositionSymbolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String getSymbolAtPosition(Jdbi jdbi, Path root, String sourcePath, int line, int column) {
        String normalized = sourcePath.strip().replace('\\', '/');
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root.toAbsolutePath().normalize())) {
            return error("Source path escapes the project root");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException error) {
            return error("Could not read source file: " + normalized);
        }
        if (line < 1 || line > lines.size()) return error("Line is outside the source file");
        String sourceLine = lines.get(line - 1);
        if (column < 1 || column > sourceLine.length() + 1) {
            return error("Column is outside the source line");
        }
        String identifier = identifierAt(sourceLine, column - 1);
        if (identifier == null) return error("No Java/Kotlin identifier at the requested position");

        List<Candidate> candidates = declarations(jdbi, identifier, normalized);
        List<EnclosingClass> enclosing = enclosingClasses(jdbi, normalized, line);
        ObjectNode result = JSON.createObjectNode();
        result.put("path", normalized);
        result.put("line", line);
        result.put("column", column);
        result.put("identifier", identifier);
        result.put("resolution", candidates.size() == 1 ? "resolved"
                : candidates.isEmpty() ? "enclosing_class_fallback" : "ambiguous");
        result.put("confidence", candidates.size() == 1 ? "high"
                : candidates.isEmpty() && !enclosing.isEmpty() ? "low" : "medium");
        ArrayNode matches = result.putArray("candidates");
        candidates.stream().limit(50).forEach(candidate -> {
            ObjectNode item = matches.addObject();
            item.put("class_name", candidate.className());
            item.put("kind", candidate.kind());
            if (candidate.memberName() != null) item.put("member_name", candidate.memberName());
            if (candidate.signature() != null) item.put("signature", candidate.signature());
            if (candidate.sourceFile() != null) item.put("source_file", candidate.sourceFile());
            item.put("same_file", candidate.sameFile());
        });
        if (!enclosing.isEmpty()) {
            EnclosingClass value = enclosing.getFirst();
            ObjectNode owner = result.putObject("enclosing_class");
            owner.put("class_name", value.className());
            owner.put("declaration_line", value.sourceLine());
        }
        result.put("candidate_count", candidates.size());
        result.putArray("limitations")
                .add("Resolution uses the live identifier text and indexed declarations; local variables are not indexed")
                .add("Overloads and same-name members may require get_symbol_details or find_symbol_usages");
        return result.toString();
    }

    private static List<Candidate> declarations(Jdbi jdbi, String identifier, String path) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT c.class_name, 'CLASS' AS kind, NULL AS member_name,
                               NULL AS signature, c.source_file,
                               CASE WHEN c.source_file = :path OR f.project_path = :path
                                          OR f.repository_path = :path THEN 1 ELSE 0 END AS same_file
                        FROM classes c LEFT JOIN files f ON f.id = c.file_id
                        WHERE c.lifecycle = 'current'
                          AND (c.class_name = :identifier
                               OR c.class_name LIKE '%.' || :identifier
                               OR c.class_name LIKE '%$' || :identifier)
                        UNION ALL
                        SELECT c.class_name, m.kind, m.name, m.signature, c.source_file,
                               CASE WHEN c.source_file = :path OR f.project_path = :path
                                          OR f.repository_path = :path THEN 1 ELSE 0 END AS same_file
                        FROM class_members m JOIN classes c ON c.id = m.class_id
                        LEFT JOIN files f ON f.id = c.file_id
                        WHERE c.lifecycle = 'current' AND m.name = :identifier
                        ORDER BY same_file DESC, class_name, kind, signature
                        LIMIT 200
                        """)
                .bind("identifier", identifier).bind("path", path)
                .map((row, context) -> new Candidate(
                        row.getString("class_name"), row.getString("kind"),
                        row.getString("member_name"), row.getString("signature"),
                        row.getString("source_file"), row.getInt("same_file") == 1))
                .list());
    }

    private static List<EnclosingClass> enclosingClasses(Jdbi jdbi, String path, int line) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT c.class_name, c.source_line
                        FROM classes c LEFT JOIN files f ON f.id = c.file_id
                        WHERE c.lifecycle = 'current' AND c.source_line <= :line
                          AND (c.source_file = :path OR f.project_path = :path
                               OR f.repository_path = :path)
                        ORDER BY c.source_line DESC LIMIT 10
                        """)
                .bind("line", line).bind("path", path)
                .map((row, context) -> new EnclosingClass(
                        row.getString("class_name"), row.getInt("source_line")))
                .list());
    }

    private static String identifierAt(String line, int cursor) {
        if (line.isEmpty()) return null;
        int position = Math.min(cursor, line.length() - 1);
        if (!Character.isJavaIdentifierPart(line.charAt(position)) && position > 0
                && Character.isJavaIdentifierPart(line.charAt(position - 1))) position--;
        if (!Character.isJavaIdentifierPart(line.charAt(position))) return null;
        int start = position;
        int end = position + 1;
        while (start > 0 && Character.isJavaIdentifierPart(line.charAt(start - 1))) start--;
        while (end < line.length() && Character.isJavaIdentifierPart(line.charAt(end))) end++;
        String value = line.substring(start, end);
        return Character.isJavaIdentifierStart(value.charAt(0)) ? value : null;
    }

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private record Candidate(String className, String kind, String memberName,
            String signature, String sourceFile, boolean sameFile) {}
    private record EnclosingClass(String className, int sourceLine) {}
}
