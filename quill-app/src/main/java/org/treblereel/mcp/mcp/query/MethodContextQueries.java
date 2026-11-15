package org.treblereel.mcp.mcp.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.core.SourceFileHashes;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.diagnostics.QuillLog;
import org.treblereel.mcp.mcp.semantic.JavaSemanticQueries;
import org.treblereel.mcp.mcp.semantic.JavaSyntaxRanges;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Compiler declaration ranges, or without captured compiler settings the declaration found in the
 * file's syntax and placed by the method's bytecode lines. Never guesses a source window from line
 * numbers alone.
 */
public final class MethodContextQueries {
    private static final ObjectMapper JSON = Json.MAPPER;

    public JsonNode get(ReadQueryContext db, ClassRecord cls, String selector) {
        ObjectNode result = JSON.createObjectNode().put("source_complete", false);
        try {
            int parameters = selector.indexOf('(');
            String name = parameters < 0 ? selector : selector.substring(0, parameters);
            String signature = parameters < 0 ? null : selector.substring(parameters);
            JsonNode symbol =
                    new SymbolUsageQueries()
                            .findMethodDeclaration(db, cls.className(), name, signature);
            if (symbol.has("error") || symbol.has("error_code")) return symbol;
            for (String field : new String[] {"target", "name", "descriptor", "symbol_id"})
                result.set(field, symbol.get(field));
            var metadata = db.index.getMetadata();
            if (org.treblereel.mcp.index.BuildStatusInspector.freshness(db, 0, 0).structureStale())
                return result.put("status", "index_stale");
            if (cls.sourceFile() == null
                    || !cls.sourceFile().endsWith(".java")
                    || !metadata.containsKey("project_root"))
                return result.put("status", "java_source_unavailable");
            Path root = Path.of(metadata.get("project_root")).toRealPath();
            Path file = root.resolve(cls.sourceFile()).toRealPath();
            if (!file.startsWith(root)) return result.put("status", "source_outside_root");
            String relative = root.relativize(file).toString().replace('\\', '/');
            String expected =
                    JSON.readTree(metadata.getOrDefault("java_source_hashes", "{}"))
                            .path(relative)
                            .asText();
            if (expected.isBlank() || !expected.equals(SourceFileHashes.hash(file)))
                return result.put("status", "source_hash_unavailable_or_changed");
            if (Files.size(file) > 2_000_000) return result.put("status", "source_file_limit");
            String text = Files.readString(file);
            JsonNode declaration =
                    JavaSemanticQueries.declaration(
                            db,
                            root,
                            file,
                            cls.className(),
                            name,
                            symbol.path("descriptor").asText());
            result.set("declaration", declaration);
            if (!"resolved".equals(declaration.path("status").asText())) {
                if (COMPILER_ONLY.contains(declaration.path("status").asText()))
                    return syntax(
                            db,
                            cls,
                            name,
                            symbol.path("descriptor").asText(),
                            text,
                            relative,
                            expected,
                            declaration,
                            result);
                return result.put("status", declaration.path("status").asText());
            }
            if (!Path.of(declaration.path("source_file").asText())
                            .toRealPath()
                            .equals(file.toRealPath())
                    || !expected.equals(SourceFileHashes.hash(file)))
                return result.put("status", "inputs_changed");
            int first = declaration.path("source_line").asInt(),
                    last = declaration.path("source_end_line").asInt();
            int start = offset(text, first, declaration.path("source_column").asInt());
            int end = offset(text, last, declaration.path("source_end_column").asInt());
            if (start < 0 || end < start || end > text.length())
                return result.put("status", "invalid_source_range");
            if (last - first >= 400 || end - start > 24_000)
                return result.put("status", "source_range_exceeds_limit");
            return result.put("status", "resolved")
                    .put("source_file", relative)
                    .put("source_sha256", expected)
                    .put("source", text.substring(start, end))
                    .put("source_complete", true);
        } catch (Exception unavailable) {
            QuillLog.swallowed(unavailable);
            return result.put("status", "source_unavailable");
        }
    }

    /** Statuses that say only that javac could not run; the file's syntax still answers. */
    private static final java.util.Set<String> COMPILER_ONLY =
            java.util.Set.of(
                    "source_set_unknown",
                    "compiler_settings_missing",
                    "compiler_settings_limit",
                    "source_set_ambiguous",
                    "compiler_settings_protocol_unsupported",
                    "compiler_not_executed",
                    "compiler_unsupported",
                    "compiler_settings_unavailable",
                    "preview_unsupported",
                    "compiler_arguments_unsupported",
                    "release_settings_unsupported",
                    "jdk_unavailable",
                    "jdk_unsupported",
                    "jdk_changed",
                    "compiler_build_inputs_missing",
                    "compiler_settings_stale",
                    "module_unknown",
                    "module_path_unsupported",
                    "source_limit",
                    "classpath_missing");

    private static JsonNode syntax(
            ReadQueryContext db,
            ClassRecord cls,
            String name,
            String descriptor,
            String text,
            String relative,
            String expected,
            JsonNode compiler,
            ObjectNode result) {
        String simple =
                cls.className()
                        .substring(
                                Math.max(
                                                cls.className().lastIndexOf('.'),
                                                cls.className().lastIndexOf('$'))
                                        + 1);
        var ranges =
                JavaSyntaxRanges.declarations(
                        text,
                        name.equals("<init>") ? simple : name,
                        org.objectweb.asm.Type.getArgumentTypes(descriptor).length);
        java.util.List<Integer> lines = bytecodeLines(db, cls.id(), name, descriptor);
        var placed =
                ranges.stream().filter(range -> lines.stream().allMatch(range::contains)).toList();
        if (placed.size() != 1)
            return result.put(
                    "status",
                    placed.isEmpty() ? "source_range_unverified" : "source_range_ambiguous");
        var range = placed.getFirst();
        if (range.endLine() - range.startLine() >= 400 || range.end() - range.start() > 24_000)
            return result.put("status", "source_range_exceeds_limit");
        result.putObject("declaration")
                .put("status", "resolved")
                .put("basis", "java_syntax")
                .put("source_line", range.startLine())
                .put("source_end_line", range.endLine())
                .put("compiler_status", compiler.path("status").asText());
        return result.put("status", "resolved")
                .put("source_file", relative)
                .put("source_sha256", expected)
                .put("source", text.substring(range.start(), range.end()))
                .put("source_complete", true);
    }

    /** Lines of the method's own calls and field accesses; empty for a method without code. */
    private static java.util.List<Integer> bytecodeLines(
            ReadQueryContext db, int classId, String name, String descriptor) {
        java.util.List<String> rows = db.usages.findMethodEvidenceLines(classId, name, descriptor);
        java.util.List<Integer> lines = new java.util.ArrayList<>();
        for (String row : rows) {
            try {
                for (JsonNode line : JSON.readTree(row))
                    if (line.isInt() && line.asInt() > 0) lines.add(line.asInt());
            } catch (Exception ignored) {
                QuillLog.swallowed(ignored); /* A malformed row places nothing. */
            }
        }
        return lines;
    }

    static int offset(String text, int line, int column) {
        if (line < 1 || column < 1) return -1;
        int start = 0;
        for (int n = 1; n < line; n++) {
            int next = text.indexOf('\n', start);
            if (next < 0) return -1;
            start = next + 1;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        return start + column - 1 <= end ? start + column - 1 : -1;
    }
}
