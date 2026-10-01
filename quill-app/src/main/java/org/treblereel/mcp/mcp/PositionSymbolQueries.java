package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        Identifier located = identifierAt(sourceLine, column - 1);
        if (located == null) return error("No Java/Kotlin identifier at the requested position");
        String identifier = located.value();

        List<Candidate> candidates = declarations(jdbi, identifier, normalized);
        List<EnclosingClass> enclosing = enclosingClasses(jdbi, normalized, line);
        SourceContext sourceContext = sourceContext(sourceLine, located);
        String enclosingClass = enclosing.isEmpty() ? null : enclosing.getFirst().className();
        List<RankedCandidate> ranked = candidates.stream()
                .map(candidate -> rank(candidate, sourceContext, enclosingClass))
                .sorted(Comparator.comparingInt(RankedCandidate::score).reversed()
                        .thenComparing(value -> value.candidate().className())
                        .thenComparing(value -> value.candidate().kind()))
                .toList();
        RankedCandidate selected = ranked.isEmpty() ? null : ranked.getFirst();
        boolean decisive = selected != null
                && (ranked.size() == 1 || selected.score() > ranked.get(1).score());
        ObjectNode result = JSON.createObjectNode();
        result.put("path", normalized);
        result.put("line", line);
        result.put("column", column);
        result.put("identifier", identifier);
        result.put("context", sourceContext.kind().name().toLowerCase());
        if (sourceContext.argumentCount() == null) result.putNull("argument_count");
        else result.put("argument_count", sourceContext.argumentCount());
        result.put("resolution", decisive ? "resolved"
                : candidates.isEmpty() ? "enclosing_class_fallback" : "ambiguous");
        result.put("confidence", decisive && selected.score() >= 100 ? "high"
                : decisive ? "medium" : candidates.isEmpty() && !enclosing.isEmpty() ? "low" : "medium");
        if (decisive) {
            ObjectNode selectedNode = result.putObject("selected");
            addCandidate(selectedNode, selected);
        }
        ArrayNode matches = result.putArray("candidates");
        ranked.stream().limit(50).forEach(candidate -> {
            ObjectNode item = matches.addObject();
            addCandidate(item, candidate);
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
                .add("Argument-count ranking does not perform full Java or Kotlin overload type inference");
        return result.toString();
    }

    private static List<Candidate> declarations(Jdbi jdbi, String identifier, String path) {
        return jdbi.withHandle(handle -> handle.createQuery("""
                        SELECT c.class_name, 'CLASS' AS kind, c.class_name AS member_name,
                               NULL AS signature, NULL AS descriptor, NULL AS parameter_types,
                               c.source_file, c.source_line,
                               NULL AS semantic_kind, NULL AS semantic_name,
                               CASE WHEN c.source_file = :path OR f.project_path = :path
                                          OR f.repository_path = :path THEN 1 ELSE 0 END AS same_file
                        FROM classes c LEFT JOIN files f ON f.id = c.file_id
                        WHERE c.lifecycle = 'current'
                          AND (c.class_name = :identifier
                               OR c.class_name LIKE '%.' || :identifier
                               OR c.class_name LIKE '%$' || :identifier)
                        UNION ALL
                        SELECT c.class_name, m.kind, m.name, m.signature, m.descriptor,
                               m.parameter_types, c.source_file, c.source_line,
                               kd.kind AS semantic_kind,
                               kd.name AS semantic_name,
                               CASE WHEN c.source_file = :path OR f.project_path = :path
                                          OR f.repository_path = :path THEN 1 ELSE 0 END AS same_file
                        FROM class_members m JOIN classes c ON c.id = m.class_id
                        LEFT JOIN files f ON f.id = c.file_id
                        LEFT JOIN kotlin_declarations kd ON kd.class_id = m.class_id
                          AND ((kd.kind = 'FUNCTION' AND kd.jvm_name = m.name
                                AND kd.descriptor = m.descriptor)
                               OR (kd.kind = 'PROPERTY' AND kd.jvm_name = m.name
                                   AND (kd.descriptor = '' OR kd.descriptor = m.descriptor)))
                        WHERE c.lifecycle = 'current'
                          AND (m.name = :identifier OR kd.name = :identifier)
                        ORDER BY same_file DESC, class_name, kind, signature
                        LIMIT 200
                        """)
                .bind("identifier", identifier).bind("path", path)
                .map((row, context) -> new Candidate(
                        row.getString("class_name"), row.getString("kind"),
                        row.getString("member_name"), row.getString("signature"),
                        row.getString("descriptor"),
                        parameterCount(row.getString("parameter_types")),
                        row.getString("source_file"), row.getInt("source_line"),
                        row.getInt("same_file") == 1,
                        row.getString("semantic_kind"), row.getString("semantic_name")))
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

    private static Identifier identifierAt(String line, int cursor) {
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
        return Character.isJavaIdentifierStart(value.charAt(0))
                ? new Identifier(value, start, end) : null;
    }

    private static SourceContext sourceContext(String line, Identifier identifier) {
        String before = line.substring(0, identifier.start()).stripTrailing();
        String after = line.substring(identifier.end()).stripLeading();
        boolean call = after.startsWith("(");
        Integer arguments = call ? argumentCount(after) : null;
        if (before.matches("(?s).*\\bnew\\s*$")) {
            return new SourceContext(ContextKind.CONSTRUCTOR_CALL, arguments);
        }
        if (call) return new SourceContext(ContextKind.METHOD_CALL, arguments);
        if (before.endsWith(".")) return new SourceContext(ContextKind.MEMBER_ACCESS, null);
        return new SourceContext(ContextKind.IDENTIFIER, null);
    }

    private static Integer argumentCount(String after) {
        if (!after.startsWith("(")) return null;
        int depth = 0;
        int commas = 0;
        boolean content = false;
        boolean quoted = false;
        char quote = 0;
        for (int index = 0; index < after.length(); index++) {
            char value = after.charAt(index);
            if (quoted) {
                if (value == quote && (index == 0 || after.charAt(index - 1) != '\\')) quoted = false;
                continue;
            }
            if (value == '\'' || value == '"') {
                quoted = true;
                quote = value;
                content = true;
            } else if (value == '(') {
                depth++;
                if (depth > 1) content = true;
            } else if (value == ')') {
                depth--;
                if (depth == 0) return content ? commas + 1 : 0;
            } else if (value == ',' && depth == 1) {
                commas++;
            } else if (depth == 1 && !Character.isWhitespace(value)) {
                content = true;
            }
        }
        return null;
    }

    private static RankedCandidate rank(
            Candidate candidate, SourceContext context, String enclosingClass) {
        int score = candidate.sameFile() ? 20 : 0;
        List<String> reasons = new ArrayList<>();
        if (candidate.sameFile()) reasons.add("same_file");
        if (enclosingClass != null && enclosingClass.equals(candidate.className())) {
            score += 25;
            reasons.add("enclosing_class");
        }
        switch (context.kind()) {
            case CONSTRUCTOR_CALL -> {
                if (candidate.kind().equals("CONSTRUCTOR")) {
                    score += 100;
                    reasons.add("new_expression_constructor");
                } else if (candidate.kind().equals("CLASS")) {
                    score += 40;
                    reasons.add("new_expression_type");
                } else score -= 50;
            }
            case METHOD_CALL -> {
                if (candidate.kind().equals("METHOD")) {
                    score += 80;
                    reasons.add("call_expression_method");
                } else score -= 30;
            }
            case MEMBER_ACCESS -> {
                if (candidate.kind().equals("FIELD")) {
                    score += 70;
                    reasons.add("member_access_field");
                }
            }
            case IDENTIFIER -> { }
        }
        if (context.argumentCount() != null && candidate.parameterCount() != null) {
            if (context.argumentCount().equals(candidate.parameterCount())) {
                score += 30;
                reasons.add("argument_count_match");
            } else {
                score -= 20;
                reasons.add("argument_count_mismatch");
            }
        }
        return new RankedCandidate(candidate, score, List.copyOf(reasons));
    }

    private static Integer parameterCount(String json) {
        if (json == null) return null;
        try {
            var parsed = JSON.readTree(json);
            return parsed.isArray() ? parsed.size() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void addCandidate(ObjectNode item, RankedCandidate ranked) {
        Candidate candidate = ranked.candidate();
        item.put("class_name", candidate.className());
        item.put("kind", candidate.kind());
        if (candidate.memberName() != null) item.put("member_name", candidate.memberName());
        if (candidate.signature() != null) item.put("signature", candidate.signature());
        SymbolContract.append(item, candidate.className(), candidate.kind(),
                candidate.memberName(), candidate.descriptor(), candidate.semanticName(),
                candidate.sourceFile(), candidate.sourceLine(),
                candidate.semanticName() != null
                        || SymbolContract.language(candidate.sourceFile(), false).equals("kotlin"));
        if (candidate.semanticName() != null) {
            item.put("kotlin_kind", candidate.semanticKind().toLowerCase(Locale.ROOT));
            item.put("kotlin_name", candidate.semanticName());
        }
        if (candidate.parameterCount() != null) item.put("parameter_count", candidate.parameterCount());
        if (candidate.sourceFile() != null) item.put("source_file", candidate.sourceFile());
        item.put("same_file", candidate.sameFile());
        item.put("score", ranked.score());
        item.set("ranking_reasons", JSON.valueToTree(ranked.reasons()));
    }

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private record Identifier(String value, int start, int end) {}
    private enum ContextKind { CONSTRUCTOR_CALL, METHOD_CALL, MEMBER_ACCESS, IDENTIFIER }
    private record SourceContext(ContextKind kind, Integer argumentCount) {}
    private record Candidate(String className, String kind, String memberName,
            String signature, String descriptor, Integer parameterCount, String sourceFile,
            int sourceLine, boolean sameFile,
            String semanticKind, String semanticName) {}
    private record RankedCandidate(Candidate candidate, int score, List<String> reasons) {}
    private record EnclosingClass(String className, int sourceLine) {}
}
