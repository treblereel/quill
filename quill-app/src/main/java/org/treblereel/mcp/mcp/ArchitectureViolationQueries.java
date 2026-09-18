package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;

/** Evaluates explicit package or module dependency boundaries against indexed bytecode. */
final class ArchitectureViolationQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SCOPES = Set.of("package", "module");

    String findArchitectureViolations(Jdbi jdbi, String scope, String from,
            List<String> forbidden, List<String> dependencyKinds,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        String normalizedScope = scope == null
                ? "package" : scope.strip().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(normalizedScope)) {
            return errorResponse("Invalid scope: expected package or module");
        }
        if (from == null || from.isBlank()) return errorResponse("The from pattern is required");
        List<String> targets = forbidden == null ? List.of() : forbidden.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::strip).distinct()
                .toList();
        if (targets.isEmpty()) return errorResponse("At least one forbidden pattern is required");
        List<String> kinds = dependencyKinds == null ? List.of() : dependencyKinds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toUpperCase(Locale.ROOT)).distinct().toList();

        Pattern sourcePattern;
        List<Pattern> targetPatterns = new ArrayList<>();
        try {
            sourcePattern = compileGlob(from.strip(), normalizedScope);
            for (String target : targets) targetPatterns.add(compileGlob(target, normalizedScope));
        } catch (IllegalArgumentException invalidPattern) {
            return errorResponse(invalidPattern.getMessage());
        }

        List<ClassRecord> classes = IndexReader.findAllClasses(jdbi);
        Map<Integer, ClassRecord> classesById = new HashMap<>();
        classes.forEach(cls -> classesById.put(cls.id(), cls));
        List<ClassRecord> eligibleClasses = classes.stream()
                .filter(cls -> includeGenerated || !"generated".equals(cls.origin()))
                .filter(cls -> includeTests || !"test".equals(cls.sourceSet()))
                .toList();
        long matchedSourceClasses = eligibleClasses.stream()
                .map(cls -> boundary(cls, normalizedScope)).filter(java.util.Objects::nonNull)
                .filter(value -> sourcePattern.matcher(value).matches()).count();
        long matchedForbiddenClasses = eligibleClasses.stream()
                .map(cls -> boundary(cls, normalizedScope)).filter(java.util.Objects::nonNull)
                .filter(value -> targetPatterns.stream()
                        .anyMatch(pattern -> pattern.matcher(value).matches()))
                .count();
        if (matchedSourceClasses == 0) {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", "Source boundary pattern matched no indexed classes");
            error.put("scope", normalizedScope);
            error.put("from", from.strip());
            error.set("available_boundaries", JSON.valueToTree(eligibleClasses.stream()
                    .map(cls -> boundary(cls, normalizedScope))
                    .filter(java.util.Objects::nonNull).distinct().sorted().limit(20).toList()));
            appendMeta(error, jdbi, 0);
            return error.toString();
        }
        List<Violation> violations = new ArrayList<>();
        for (DependencyRecord dependency : IndexReader.findCurrentDependencyGraph(
                jdbi, null, includeGenerated, includeTests)) {
            ClassRecord source = classesById.get(dependency.fromClassId());
            ClassRecord target = classesById.get(dependency.toClassId());
            if (source == null || target == null) continue;
            if (!kinds.isEmpty() && !kinds.contains(dependency.kind().toUpperCase(Locale.ROOT))) {
                continue;
            }
            String sourceBoundary = boundary(source, normalizedScope);
            String targetBoundary = boundary(target, normalizedScope);
            if (sourceBoundary == null || targetBoundary == null
                    || !sourcePattern.matcher(sourceBoundary).matches()) continue;
            String matched = null;
            for (int index = 0; index < targetPatterns.size(); index++) {
                if (targetPatterns.get(index).matcher(targetBoundary).matches()) {
                    matched = targets.get(index);
                    break;
                }
            }
            if (matched != null) {
                violations.add(new Violation(source, target, sourceBoundary, targetBoundary,
                        dependency.kind(), dependency.occurrenceCount(),
                        dependency.evidenceLines(), matched));
            }
        }
        violations.sort(Comparator.comparingInt(Violation::occurrences).reversed()
                .thenComparing(value -> value.source().className())
                .thenComparing(value -> value.target().className())
                .thenComparing(Violation::kind));

        int pageStart = Math.min(offset, violations.size());
        int pageEnd = Math.min(violations.size(), pageStart + limit);
        List<Violation> page = violations.subList(pageStart, pageEnd);
        ObjectNode root = JSON.createObjectNode();
        ObjectNode rule = root.putObject("rule");
        rule.put("scope", normalizedScope);
        rule.put("from", from.strip());
        rule.set("must_not_depend_on", JSON.valueToTree(targets));
        rule.set("dependency_kinds", JSON.valueToTree(kinds));
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("matched_source_classes", matchedSourceClasses);
        root.put("matched_forbidden_classes", matchedForbiddenClasses);
        root.put("compliant", violations.isEmpty());
        root.put("violating_class_pairs", violations.stream()
                .map(value -> value.source().id() + ":" + value.target().id())
                .distinct().count());
        root.put("occurrence_count", violations.stream()
                .mapToInt(Violation::occurrences).sum());
        Map<String, Integer> byKind = new TreeMap<>();
        violations.forEach(value -> byKind.merge(value.kind(), value.occurrences(), Integer::sum));
        root.set("occurrences_by_kind", JSON.valueToTree(byKind));

        ArrayNode values = root.putArray("violations");
        Map<Integer, Integer> countedTokens = new LinkedHashMap<>();
        for (Violation violation : page) {
            ObjectNode node = values.addObject();
            node.put("from_class", violation.source().className());
            node.put("from_boundary", violation.sourceBoundary());
            node.put("to_class", violation.target().className());
            node.put("to_boundary", violation.targetBoundary());
            node.put("matched_forbidden_pattern", violation.matchedPattern());
            node.put("dependency_kind", violation.kind());
            node.put("occurrence_count", violation.occurrences());
            node.set("evidence_lines", JSON.valueToTree(violation.evidenceLines()));
            if (violation.source().sourceFile() != null) {
                node.put("source", sourceLocation(violation.source()));
            }
            if (violation.target().sourceFile() != null) {
                node.put("target_source", sourceLocation(violation.target()));
            }
            countedTokens.put(violation.source().id(), violation.source().sourceTokens());
            countedTokens.put(violation.target().id(), violation.target().sourceTokens());
        }
        root.putArray("limitations")
                .add("Rules are evaluated against current static dependencies in compiled application bytecode")
                .add("Reflection, runtime-generated links, and uncompiled source changes are not inferred")
                .add("A dependency row may aggregate multiple bytecode occurrences; evidence_lines contains known source-line evidence");
        appendPage(root, page.size(), violations.size(), limit, offset);
        appendMeta(root, jdbi, countedTokens.values().stream().mapToInt(Integer::intValue).sum());
        return root.toString();
    }

    private static Pattern compileGlob(String value, String scope) {
        if (value.isBlank()) throw new IllegalArgumentException("Architecture patterns cannot be blank");
        StringBuilder regex = new StringBuilder("^");
        for (int index = 0; index < value.length();) {
            if (index + 1 < value.length() && value.charAt(index) == '.'
                    && value.charAt(index + 1) == '.') {
                regex.append("(?:\\..*)?");
                index += 2;
            } else if (value.charAt(index) == '*') {
                regex.append(".*");
                index++;
            } else {
                regex.append(Pattern.quote(String.valueOf(value.charAt(index++))));
            }
        }
        regex.append('$');
        try {
            return Pattern.compile(regex.toString());
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid " + scope + " pattern: " + value);
        }
    }

    private static String boundary(ClassRecord cls, String scope) {
        if ("module".equals(scope)) return cls.module();
        String name = cls.className();
        int nested = name.indexOf('$');
        if (nested >= 0) name = name.substring(0, nested);
        int separator = name.lastIndexOf('.');
        return separator < 0 ? "<default>" : name.substring(0, separator);
    }

    private static String sourceLocation(ClassRecord cls) {
        return cls.sourceLine() > 0 ? cls.sourceFile() + ":" + cls.sourceLine() : cls.sourceFile();
    }

    private record Violation(ClassRecord source, ClassRecord target,
                             String sourceBoundary, String targetBoundary,
                             String kind, int occurrences, List<Integer> evidenceLines,
                             String matchedPattern) {}
}
