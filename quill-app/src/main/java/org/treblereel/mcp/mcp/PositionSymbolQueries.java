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
import org.treblereel.mcp.core.KotlinSourceText;
import org.treblereel.mcp.model.KotlinValueParameter;
import org.treblereel.mcp.db.IndexReader;

/** Resolves the identifier under a live source position against indexed declarations. */
final class PositionSymbolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String getSymbolAtPosition(Jdbi jdbi, Path root, String sourcePath, int line, int column) {
        String normalized = sourcePath.strip().replace('\\', '/');
        Path file = root.resolve(normalized).normalize();
        if (!file.startsWith(root.toAbsolutePath().normalize())) {
            return error("INVALID_SOURCE_PATH", "Source path escapes the project root");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException error) {
            return error("SOURCE_NOT_FOUND", "Could not read source file: " + normalized);
        }
        if (line < 1 || line > lines.size()) {
            return error("INVALID_POSITION", "Line is outside the source file");
        }
        String source = String.join("\n", lines);
        String code = KotlinSourceText.code(source);
        String sourceLine = code.split("\n", -1)[line - 1];
        if (column < 1 || column > sourceLine.length() + 1) {
            return error("INVALID_POSITION", "Column is outside the source line");
        }
        Identifier located = identifierAt(sourceLine, column - 1);
        if (located == null) {
            return error("SYMBOL_NOT_FOUND",
                    "No Java/Kotlin identifier at the requested position");
        }
        String identifier = located.value();

        boolean kotlin = normalized.endsWith(".kt") || normalized.endsWith(".kts");
        String imported = kotlin ? importedName(code, identifier) : null;
        String lookupName = imported == null ? identifier : imported.substring(imported.lastIndexOf('.') + 1);
        List<Candidate> candidates = declarations(jdbi, lookupName, normalized);
        if (kotlin) candidates = sourceCallables(jdbi, logicalProperties(jdbi, candidates, lookupName));
        if (imported != null) {
            String qualified = imported;
            candidates = candidates.stream().filter(candidate -> candidate.className().replace('$', '.').equals(qualified)
                    || (candidate.className().replace('$', '.') + "." + lookupName).equals(qualified)
                    || qualified.equals(candidate.className().substring(0,
                            candidate.className().lastIndexOf('.') + 1) + lookupName)
                        && topLevel(jdbi, candidate.className()))
                    .toList();
        }
        List<EnclosingClass> enclosing = enclosingClasses(jdbi, normalized, line);
        int lineOffset = 0;
        for (int i = 0; i < line - 1; i++) lineOffset += lines.get(i).length() + 1;
        String suffix = source.substring(lineOffset + located.end()).stripLeading();
        SourceContext sourceContext = sourceContext(sourceLine, located);
        if (kotlin && (suffix.startsWith("(") || suffix.startsWith("{")
                || suffix.matches("(?s)^[\\p{L}_$][\\p{L}\\p{N}_$]*@\\s*\\{.*"))) {
            sourceContext = new SourceContext(ContextKind.METHOD_CALL, arguments(suffix));
        }
        if (sourceContext.kind() == ContextKind.METHOD_CALL || sourceContext.kind() == ContextKind.CONSTRUCTOR_CALL) {
            sourceContext = new SourceContext(sourceContext.kind(), arguments(suffix));
        }
        final SourceContext context = sourceContext;
        String enclosingClass = kotlin || enclosing.isEmpty() ? null : enclosing.getFirst().className();
        List<RankedCandidate> ranked = candidates.stream()
                .map(candidate -> rank(candidate, context, enclosingClass, kotlin))
                .sorted(Comparator.comparingInt(RankedCandidate::score).reversed()
                        .thenComparing(value -> value.candidate().className())
                        .thenComparing(value -> value.candidate().kind()))
                .toList();
        RankedCandidate selected = ranked.isEmpty() ? null : ranked.getFirst();
        var callables = ranked.stream().filter(value -> value.candidate().kind().equals("METHOD")
                || value.candidate().kind().equals("CONSTRUCTOR")).toList();
        boolean noApplicable = kotlin && sourceContext.kind() == ContextKind.METHOD_CALL && !callables.isEmpty()
                && callables.stream().allMatch(value -> value.reasons().contains("arguments_not_applicable"));
        boolean decisive = selected != null
                && !selected.reasons().contains("arguments_not_applicable")
                && (!kotlin || sourceContext.kind() != ContextKind.METHOD_CALL || !selected.candidate().kind().equals("CLASS"))
                && (ranked.size() == 1 || selected.score() > ranked.get(1).score());
        ObjectNode result = JSON.createObjectNode();
        result.put("path", normalized);
        result.put("line", line);
        result.put("column", column);
        result.put("identifier", identifier);
        result.put("context", sourceContext.kind().name().toLowerCase());
        if (sourceContext.argumentCount() == null) result.putNull("argument_count");
        else result.put("argument_count", sourceContext.argumentCount());
        result.put("resolution", noApplicable ? "no_applicable_candidate" : decisive ? "resolved"
                : candidates.isEmpty() ? "enclosing_class_fallback" : "ambiguous");
        result.put("confidence", noApplicable ? "low" : decisive && selected.score() >= 100 && !kotlin ? "high"
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
                               0 AS is_suspend, 0 AS is_extension, 0 AS has_defaults,
                               NULL AS value_parameters,
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
                               COALESCE(kd.is_suspend, 0) AS is_suspend,
                               COALESCE(kd.is_extension, 0) AS is_extension,
                               COALESCE(kd.has_default_parameters, 0) AS has_defaults,
                               kd.value_parameters,
                               CASE WHEN c.source_file = :path OR f.project_path = :path
                                          OR f.repository_path = :path THEN 1 ELSE 0 END AS same_file
                        FROM class_members m JOIN classes c ON c.id = m.class_id
                        LEFT JOIN files f ON f.id = c.file_id
                        LEFT JOIN kotlin_declarations kd ON kd.class_id = m.class_id
                          AND ((kd.kind = 'CONSTRUCTOR' AND m.kind = 'CONSTRUCTOR'
                                AND kd.jvm_name = '<init>' AND kd.descriptor = m.descriptor)
                               OR (kd.kind = 'FUNCTION' AND kd.jvm_name = m.name
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
                        sourceParameterCount(row.getString("parameter_types"),
                                row.getInt("is_suspend") == 1, row.getInt("is_extension") == 1,
                                row.getString("value_parameters")),
                        row.getString("source_file"), row.getInt("source_line"),
                        row.getInt("same_file") == 1,
                        row.getString("semantic_kind"), row.getString("semantic_name"),
                        row.getInt("has_defaults") == 1,
                        IndexReader.kotlinParametersFromJson(row.getString("value_parameters"))))
                .list());
    }

    private static List<Candidate> sourceCallables(Jdbi jdbi, List<Candidate> candidates) {
        return candidates.stream().filter(candidate -> {
            if (candidate.semanticName() != null
                    || !("METHOD".equals(candidate.kind()) || "CONSTRUCTOR".equals(candidate.kind()))) return true;
            var owners = IndexReader.findClassesByName(jdbi, candidate.className()).stream()
                    .filter(owner -> java.util.Objects.equals(owner.sourceFile(), candidate.sourceFile())).toList();
            // Retain uncertain candidates, including duplicate owners with conflicting evidence.
            return owners.isEmpty() || !owners.stream().allMatch(owner -> {
                String method = "CONSTRUCTOR".equals(candidate.kind()) ? "<init>" : candidate.memberName();
                var target = KotlinUsageNormalizer.sourceTarget(jdbi, owner.id(), method, candidate.descriptor());
                return target.isPresent() && candidates.stream().anyMatch(source -> source.semanticName() != null
                        && source.kind().equals(candidate.kind())
                        && source.className().equals(target.get().className())
                        && source.descriptor().equals(target.get().descriptor())
                        && ("CONSTRUCTOR".equals(source.kind()) || source.memberName().equals(target.get().method()))
                        && java.util.Objects.equals(source.sourceFile(), target.get().sourceFile()));
            });
        }).toList();
    }

    private static String importedName(String code, String identifier) {
        var imports = java.util.regex.Pattern.compile(
                "(?m)^\\h*import\\h+([\\p{L}\\p{N}_$.`]+)(?:\\h+as\\h+(`[^`\\r\\n]+`|[\\p{L}\\p{N}_$]+))?\\h*;?\\h*$")
                .matcher(code);
        String result = null;
        while (imports.find()) {
            String qualified = imports.group(1).replace("`", "");
            String alias = imports.group(2) == null ? qualified.substring(qualified.lastIndexOf('.') + 1)
                    : imports.group(2).replace("`", "");
            if (identifier.equals(alias)) {
                if (result != null && !result.equals(qualified)) return null;
                result = qualified;
            }
        }
        return result;
    }

    private static boolean topLevel(Jdbi jdbi, String className) {
        return org.treblereel.mcp.db.IndexReader.findClassesByName(jdbi, className).stream()
                .anyMatch(cls -> org.treblereel.mcp.db.IndexReader.findKotlinDeclarations(jdbi, cls.id()).stream()
                        .anyMatch(value -> java.util.Set.of("FILE_FACADE", "MULTIFILE_PART", "MULTIFILE_FACADE")
                                .contains(value.kind())));
    }

    private static List<Candidate> logicalProperties(Jdbi jdbi, List<Candidate> candidates, String name) {
        var result = new java.util.LinkedHashMap<String, Candidate>();
        for (var candidate : candidates) {
            if (!"PROPERTY".equals(candidate.semanticKind()) || !name.equals(candidate.semanticName())) {
                result.put(candidate.toString(), candidate); continue;
            }
            for (var owner : org.treblereel.mcp.db.IndexReader.findClassesByName(jdbi, candidate.className())) {
                if (!java.util.Objects.equals(owner.sourceFile(), candidate.sourceFile())) continue;
                for (var property : KotlinProperties.list(jdbi, owner)) {
                    if (!property.name().equals(name)) continue;
                    result.put(property.symbolId(), new Candidate(owner.className(), "PROPERTY", name,
                            null, property.descriptor(), null, owner.sourceFile(), owner.sourceLine(),
                            candidate.sameFile(), "PROPERTY", name, false, null));
                }
            }
        }
        return List.copyOf(result.values());
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
        int opening = -1;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != '`') continue;
            if (opening < 0) opening = i;
            else {
                if (position >= opening && position <= i) {
                    return new Identifier(line.substring(opening + 1, i), opening, i + 1);
                }
                opening = -1;
            }
        }
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
        List<Argument> arguments = call ? arguments(after) : null;
        if (before.matches("(?s).*\\bnew\\s*$")) {
            return new SourceContext(ContextKind.CONSTRUCTOR_CALL, arguments);
        }
        if (call) return new SourceContext(ContextKind.METHOD_CALL, arguments);
        if (before.endsWith(".")) return new SourceContext(ContextKind.MEMBER_ACCESS, null);
        return new SourceContext(ContextKind.IDENTIFIER, null);
    }

    private static List<Argument> arguments(String after) {
        String masked = KotlinSourceText.codeWithLiteralMarkers(after);
        if (!after.startsWith("(")) return trailingLambda(masked, List.of());
        int parentheses = 0, brackets = 0, braces = 0, start = 1;
        List<Argument> result = new ArrayList<>();
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '`') {
                int end = masked.indexOf('`', i + 1);
                if (end < 0 || masked.substring(i + 1, end).contains("\n")) return null;
                i = end; continue;
            }
            if (c == '<' && braces == 0) return null; // Generic argument expressions remain conservative.
            if (c == '[') brackets++;
            if (c == ']') brackets--;
            if (c == '{') braces++;
            if (c == '}') braces--;
            if (c == '(') parentheses++;
            else if (c == ')' && --parentheses == 0) {
                if (brackets != 0 || braces != 0) return null;
                String tail = masked.substring(start, i).strip();
                if (!tail.isEmpty()) {
                    var argument = argument(tail);
                    if (argument == null) return null;
                    result.add(argument);
                }
                String suffix = masked.substring(i + 1).stripLeading();
                if (suffix.startsWith("{") || suffix.matches("(?s)^[\\p{L}_$][\\p{L}\\p{N}_$]*@\\s*\\{.*"))
                    return trailingLambda(suffix, result);
                return List.copyOf(result);
            } else if (c == ',' && parentheses == 1 && brackets == 0 && braces == 0) {
                String segment = masked.substring(start, i).strip();
                if (segment.isEmpty()) return null;
                var argument = argument(segment);
                if (argument == null) return null;
                result.add(argument); start = i + 1;
            }
            if (parentheses < 0 || brackets < 0 || braces < 0) return null;
        }
        return null;
    }

    private static Argument argument(String text) {
        var named = java.util.regex.Pattern.compile("^(`[^`\\r\\n]+`|[\\p{L}_$][\\p{L}\\p{N}_$]*)\\s*=(?!=)\\s*([\\s\\S]*)$").matcher(text);
        if (named.matches()) {
            String value = named.group(2).strip();
            return value.isEmpty() || value.equals("*") ? null : new Argument(named.group(1).replace("`", ""), value.startsWith("*"), false);
        }
        return text.equals("*") ? null : new Argument(null, text.startsWith("*"), false);
    }

    private static List<Argument> trailingLambda(String text, List<Argument> prefix) {
        String body = text.replaceFirst("^[\\p{L}_$][\\p{L}\\p{N}_$]*@\\s*", "");
        if (!body.startsWith("{")) return null;
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '`') {
                int end = body.indexOf('`', i + 1);
                if (end < 0) return null;
                i = end; continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) {
                if (body.substring(i + 1).stripLeading().startsWith("{")) return null;
                var result = new ArrayList<>(prefix);
                result.add(new Argument(null, false, true));
                return List.copyOf(result);
            }
        }
        return null;
    }

    private static Applicability applicable(Candidate candidate, List<Argument> arguments) {
        List<KotlinValueParameter> parameters = candidate.valueParameters();
        if (parameters == null || arguments == null) return Applicability.UNKNOWN;
        boolean[] supplied = new boolean[parameters.size()];
        int position = 0; boolean namedOnly = false;
        for (var argument : arguments) {
            int index;
            if (argument.trailingLambda()) {
                index = parameters.size() - 1;
                if (index < 0 || supplied[index]) return Applicability.NO_MATCH;
                // SAM and generic function types need semantic evidence; never reject them by JVM shape.
                try {
                    var types = org.objectweb.asm.Type.getArgumentTypes(candidate.descriptor());
                    int last = types.length - 1;
                    if (last >= 0 && types[last].getDescriptor().equals("Lkotlin/coroutines/Continuation;")) last--;
                    if (last < 0 || !types[last].getDescriptor().matches("Lkotlin/jvm/functions/Function[0-9]+;"))
                        return Applicability.UNKNOWN;
                } catch (IllegalArgumentException invalid) { return Applicability.UNKNOWN; }
            } else if (argument.name() != null) {
                index = -1;
                for (int i = 0; i < parameters.size(); i++)
                    if (parameters.get(i).name().equals(argument.name())) { index = i; break; }
                if (index < 0 || supplied[index]) return Applicability.NO_MATCH;
                if (index != position || parameters.get(index).vararg()) namedOnly = true;
                else position++;
            } else {
                if (namedOnly) return Applicability.NO_MATCH;
                index = position;
                if (index >= parameters.size()) return Applicability.NO_MATCH;
                if (!parameters.get(index).vararg()) position++;
            }
            if (argument.spread() && !parameters.get(index).vararg()) return Applicability.NO_MATCH;
            supplied[index] = true;
        }
        for (int i = 0; i < parameters.size(); i++)
            if (!supplied[i] && !parameters.get(i).hasDefault() && !parameters.get(i).vararg())
                return Applicability.NO_MATCH;
        return Applicability.MATCH;
    }

    private static RankedCandidate rank(
            Candidate candidate, SourceContext context, String enclosingClass, boolean kotlin) {
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
                if (candidate.kind().equals("METHOD") || kotlin && candidate.kind().equals("CONSTRUCTOR")) {
                    score += 80;
                    reasons.add("call_expression_method");
                } else score -= 30;
            }
            case MEMBER_ACCESS -> {
                if (candidate.kind().equals("FIELD") || candidate.kind().equals("PROPERTY")) {
                    score += 70;
                    reasons.add("member_access_field");
                }
            }
            case IDENTIFIER -> { }
        }
        Applicability applicability = kotlin && (candidate.kind().equals("METHOD") || candidate.kind().equals("CONSTRUCTOR"))
                ? applicable(candidate, context.arguments()) : Applicability.UNKNOWN;
        if (applicability == Applicability.NO_MATCH) {
            score -= 150;
            reasons.add("arguments_not_applicable");
        } else if (applicability == Applicability.MATCH) {
            score += 30;
            reasons.add("metadata_arguments_match");
        } else if (context.argumentCount() != null && candidate.parameterCount() != null) {
            if (context.argumentCount().equals(candidate.parameterCount())) {
                score += 30;
                reasons.add("argument_count_match");
            } else if (candidate.hasDefaults() && context.argumentCount() < candidate.parameterCount()) {
                score += 10;
                reasons.add("default_arguments_possible");
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

    private static Integer sourceParameterCount(String json, boolean suspend, boolean extension, String metadata) {
        var parameters = IndexReader.kotlinParametersFromJson(metadata);
        if (parameters != null) return parameters.size();
        Integer count = parameterCount(json);
        return count == null ? null : Math.max(0, count - (suspend ? 1 : 0) - (extension ? 1 : 0));
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
        if (candidate.valueParameters() != null) {
            item.set("value_parameters", JSON.valueToTree(candidate.valueParameters()));
            item.put("required_parameter_count", candidate.valueParameters().stream()
                    .filter(parameter -> !parameter.hasDefault() && !parameter.vararg()).count());
        }
        if (candidate.sourceFile() != null) item.put("source_file", candidate.sourceFile());
        item.put("same_file", candidate.sameFile());
        item.put("score", ranked.score());
        item.set("ranking_reasons", JSON.valueToTree(ranked.reasons()));
    }

    private static String error(String code, String message) {
        return ToolResponseSupport.errorResponse(code, message);
    }

    private record Identifier(String value, int start, int end) {}
    private enum ContextKind { CONSTRUCTOR_CALL, METHOD_CALL, MEMBER_ACCESS, IDENTIFIER }
    private record Argument(String name, boolean spread, boolean trailingLambda) {}
    private enum Applicability { MATCH, NO_MATCH, UNKNOWN }
    private record SourceContext(ContextKind kind, List<Argument> arguments) {
        Integer argumentCount() { return arguments == null ? null : arguments.size(); }
    }
    private record Candidate(String className, String kind, String memberName,
            String signature, String descriptor, Integer parameterCount, String sourceFile,
            int sourceLine, boolean sameFile,
            String semanticKind, String semanticName, boolean hasDefaults, List<KotlinValueParameter> valueParameters) {}
    private record RankedCandidate(Candidate candidate, int score, List<String> reasons) {}
    private record EnclosingClass(String className, int sourceLine) {}
}
