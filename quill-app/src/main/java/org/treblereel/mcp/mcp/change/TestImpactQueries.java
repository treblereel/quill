package org.treblereel.mcp.mcp.change;

import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.classLookupErrorNode;
import static org.treblereel.mcp.mcp.support.ToolResponseSupport.errorNode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.core.JvmSourceFiles;
import org.treblereel.mcp.db.GitRepository;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.diagnostics.QuillLog;
import org.treblereel.mcp.mcp.query.SymbolUsageQueries;
import org.treblereel.mcp.mcp.semantic.JvmDescriptors;
import org.treblereel.mcp.mcp.semantic.KotlinMemberNames;
import org.treblereel.mcp.mcp.semantic.SymbolContract;
import org.treblereel.mcp.mcp.support.ClassTargetResolver;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.CoChangeRecord;
import org.treblereel.mcp.model.DependencyRecord;

/** Ranks tests that are statically or historically coupled to changed classes. */
public final class TestImpactQueries {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int MAX_EXPANDED_CLASSES = 5_000;

    private ObjectNode methodImpact(
            ReadQueryContext reader,
            String target,
            String method,
            String signature,
            boolean transitive,
            int maxDepth,
            int limit,
            int offset) {
        ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, target);
        if (lookup.error() != null) return classLookupErrorNode(reader, lookup, target);
        ClassRecord owner = lookup.cls();
        int parameters = method.indexOf('(');
        if (signature == null && parameters > 0 && method.lastIndexOf(')') > parameters) {
            String selector = method;
            String name = method.substring(0, parameters).strip();
            String requested = method.substring(parameters);
            var declarations = IndexReader.findKotlinDeclarations(reader.jdbi(), owner.id());
            List<String> matches =
                    IndexReader.findJvmClassMembers(reader.jdbi(), owner.id()).stream()
                            .filter(
                                    member ->
                                            "METHOD".equals(member.kind())
                                                    && KotlinMemberNames.matches(
                                                            name, member, declarations)
                                                    && JvmDescriptors.matchesSignature(
                                                            member, requested))
                            .map(JvmDescriptors::methodDescriptor)
                            .filter(Objects::nonNull)
                            .toList();
            method = name;
            // A missing match must remain an error; never fall back to another overload.
            signature =
                    matches.size() == 1 ? matches.getFirst() : matches.isEmpty() ? selector : null;
        }
        ObjectNode references =
                new SymbolUsageQueries()
                        .findSymbolUsages(
                                reader, target, method, "METHOD", signature, null, limit, offset);
        if (references.has("error_code")) return references;
        String classId = SymbolContract.id(owner, owner.kind(), owner.className(), "");
        ObjectNode candidates =
                findImpactedTests(reader, List.of(classId), transitive, maxDepth, limit, offset);
        if (candidates.has("error_code")) return candidates;
        ObjectNode result = JSON.createObjectNode();
        for (String field : List.of("target", "name", "descriptor", "symbol_id")) {
            result.set(field, references.get(field));
        }
        result.put("test_ranking_scope", "selected_method_reference_page");
        result.put("runtime_coverage_measured", false);
        appendDirectTests(reader, result, references);
        if (!candidates.path("test_index_coverage").path("complete").asBoolean()) {
            result.put("answer_complete", false);
        }
        result.set("selected_method_references", references);
        result.set("class_level_candidates", candidates);
        result.set("limitations", references.path("limitations").deepCopy());
        ((ArrayNode) result.get("limitations"))
                .add(
                        "Direct tests contain selected-member references from this page, not measured runtime coverage; "
                                + "class_level_candidates ranks class dependencies and history, not selected-method execution");
        result.set("_meta", candidates.get("_meta"));
        return result;
    }

    private void appendDirectTests(
            ReadQueryContext reader, ObjectNode result, ObjectNode references) {
        Map<String, List<ClassRecord>> classes =
                reader.symbols.findAllClasses().stream()
                        .collect(Collectors.groupingBy(ClassRecord::className));
        Map<Integer, ObjectNode> tests = new LinkedHashMap<>();
        int index = 0;
        int unclassified = 0;
        for (var usage : references.path("usages")) {
            var caller = usage.path("caller");
            String source = caller.path("source").asText();
            String module = caller.path("module").asText();
            List<ClassRecord> matches =
                    classes.getOrDefault(caller.path("class").asText(), List.of()).stream()
                            .filter(cls -> module.isEmpty() || Objects.equals(module, cls.module()))
                            .filter(
                                    cls ->
                                            source.isEmpty()
                                                    || cls.sourceFile() != null
                                                            && (source.equals(cls.sourceFile())
                                                                    || source.startsWith(
                                                                            cls.sourceFile()
                                                                                    + ":")))
                            .toList();
            int referenceIndex = index++;
            if (matches.size() != 1) {
                unclassified++;
                continue;
            }
            ClassRecord cls = matches.getFirst();
            if (!isTestClass(cls)) continue;
            ObjectNode test =
                    tests.computeIfAbsent(
                            cls.id(),
                            ignored -> {
                                ObjectNode value = JSON.createObjectNode();
                                value.put("class", cls.className());
                                value.put("file", cls.sourceFile());
                                value.putArray("reference_indexes");
                                value.put("reference_occurrences", 0);
                                value.put("invocation_instruction_occurrences", 0);
                                return value;
                            });
            ((ArrayNode) test.get("reference_indexes")).add(referenceIndex);
            int count = usage.path("occurrence_count").asInt();
            test.put("reference_occurrences", test.path("reference_occurrences").asInt() + count);
            if ("invocation_instruction".equals(usage.path("execution_semantics").asText())) {
                test.put(
                        "invocation_instruction_occurrences",
                        test.path("invocation_instruction_occurrences").asInt() + count);
            }
        }
        result.set("tests", JSON.valueToTree(tests.values()));
        result.put("unclassified_reference_groups", unclassified);
        result.put(
                "answer_complete",
                references.path("answer_complete").asBoolean()
                        && !references.path("has_more").asBoolean()
                        && unclassified == 0);
    }

    public ObjectNode findImpactedTests(
            ReadQueryContext reader,
            List<String> targets,
            boolean transitive,
            int maxDepth,
            int limit,
            int offset) {
        GitRepository gitRepository = reader.git;
        if (targets == null || targets.isEmpty()) {
            return errorNode("At least one target is required");
        }
        for (String target : targets) {
            try {
                var member =
                        SymbolContract.parse(target)
                                .filter(reference -> "METHOD".equals(reference.kind()))
                                .orElse(null);
                int separator = target == null ? -1 : target.lastIndexOf('#');
                if (member == null && separator < 0) continue;
                if (targets.size() != 1) {
                    return errorNode("INVALID_ARGUMENT", "A method requires exactly one target");
                }
                String owner = member == null ? target.substring(0, separator) : target;
                String name = member == null ? target.substring(separator + 1) : member.jvmName();
                if (owner.isBlank() || name.isBlank()) {
                    return errorNode(
                            "INVALID_ARGUMENT", "Expected Class#method or a method symbol_id");
                }
                return methodImpact(
                        reader,
                        owner,
                        name,
                        member == null ? null : member.descriptor(),
                        transitive,
                        maxDepth,
                        limit,
                        offset);
            } catch (SymbolContract.ParseException error) {
                return errorNode(error.errorCode(), error.getMessage());
            }
        }
        List<ClassRecord> resolvedTargets = new ArrayList<>();
        for (String target : targets) {
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(reader, target);
            if (lookup.error() != null) return classLookupErrorNode(reader, lookup, target);
            if (resolvedTargets.stream().noneMatch(value -> value.id() == lookup.cls().id())) {
                resolvedTargets.add(lookup.cls());
            }
        }

        List<ClassRecord> allClasses = reader.symbols.findAllClasses();
        Map<Integer, ClassRecord> classesById = new HashMap<>();
        allClasses.forEach(value -> classesById.put(value.id(), value));
        int indexedTestClasses =
                (int) allClasses.stream().filter(TestImpactQueries::isTestClass).count();
        Map<String, String> metadata = reader.index.getMetadata();
        List<String> indexedTestModules = metadataList(metadata, "compiled_test_modules");
        if (indexedTestModules.isEmpty() && indexedTestClasses > 0) {
            indexedTestModules =
                    allClasses.stream()
                            .filter(TestImpactQueries::isTestClass)
                            .map(cls -> cls.module() == null ? "." : cls.module())
                            .distinct()
                            .sorted()
                            .toList();
        }
        List<String> missingTestModules = metadataList(metadata, "missing_test_output_modules");
        List<String> missingTestClasspathModules =
                metadataList(metadata, "missing_test_classpath_modules");
        List<String> staleTestOutputModules = metadataList(metadata, "stale_test_output_modules");
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        boolean[] truncatedTraversal = {false};
        int effectiveDepth = transitive ? maxDepth : 1;
        Map<Integer, List<DependencyRecord>> inbound = new HashMap<>();
        for (ClassRecord target : resolvedTargets) {
            collectStaticCandidates(
                    reader,
                    target,
                    effectiveDepth,
                    classesById,
                    candidates,
                    truncatedTraversal,
                    inbound);
            collectGitCandidates(reader, target, classesById, candidates);
        }

        List<Candidate> ranked =
                candidates.values().stream()
                        .sorted(
                                Comparator.comparingInt(Candidate::score)
                                        .reversed()
                                        .thenComparing(Candidate::identity))
                        .toList();
        int from = Math.min(offset, ranked.size());
        int to = (int) Math.min((long) from + limit, ranked.size());
        List<Candidate> page = ranked.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        root.set(
                "targets",
                JSON.valueToTree(resolvedTargets.stream().map(ClassRecord::className).toList()));
        root.put("granularity", "class");
        root.put("runtime_coverage_measured", false);
        root.put("transitive", transitive);
        root.put("max_depth", effectiveDepth);
        root.put("indexed_test_class_count", indexedTestClasses);
        root.put("compiled_test_outputs_indexed", indexedTestClasses > 0);
        root.put("git_history_available", gitRepository.hasGitData());
        root.put("traversal_truncated", truncatedTraversal[0]);
        ObjectNode coverage = root.putObject("test_index_coverage");
        boolean incompleteCoverage =
                !missingTestModules.isEmpty()
                        || !missingTestClasspathModules.isEmpty()
                        || !staleTestOutputModules.isEmpty();
        String coverageStatus =
                indexedTestModules.isEmpty() ? "none" : incompleteCoverage ? "partial" : "complete";
        coverage.put("status", coverageStatus);
        coverage.set("indexed_modules", JSON.valueToTree(indexedTestModules));
        coverage.set("missing_modules", JSON.valueToTree(missingTestModules));
        coverage.set("missing_classpath_modules", JSON.valueToTree(missingTestClasspathModules));
        coverage.set("stale_modules", JSON.valueToTree(staleTestOutputModules));
        coverage.put("indexed_test_classes", indexedTestClasses);
        coverage.put("complete", coverageStatus.equals("complete"));
        root.put("static_evidence_available", indexedTestClasses > 0);
        root.put("historical_evidence_available", gitRepository.hasGitData());
        root.put("answer_complete", coverageStatus.equals("complete") && !truncatedTraversal[0]);
        ArrayNode limitations = root.putArray("limitations");
        if (indexedTestClasses == 0) {
            limitations.add(
                    "No compiled test classes are indexed; static impact may be incomplete");
        }
        if (!gitRepository.hasGitData()) {
            limitations.add("Git co-change evidence is unavailable");
        }
        if (!missingTestModules.isEmpty()) {
            limitations.add(
                    "Compiled test outputs are missing for modules: "
                            + String.join(", ", missingTestModules));
        }
        if (!missingTestClasspathModules.isEmpty()) {
            limitations.add(
                    "Captured test runtime classpaths are missing for modules: "
                            + String.join(", ", missingTestClasspathModules));
        }
        if (!staleTestOutputModules.isEmpty()) {
            limitations.add(
                    "Compiled test outputs are older than test sources for modules: "
                            + String.join(", ", staleTestOutputModules));
        }
        if (truncatedTraversal[0]) {
            limitations.add(
                    "Static traversal stopped at "
                            + MAX_EXPANDED_CLASSES
                            + " classes across all targets");
        }

        limitations.add(
                "Static paths reference classes, not a selected method; a CALLS edge may target a different member. This ranking does not establish method execution or runtime test coverage");

        ArrayNode tests = root.putArray("tests");
        int naiveTokens = resolvedTargets.stream().mapToInt(ClassRecord::sourceTokens).sum();
        for (Candidate candidate : page) {
            ObjectNode node = tests.addObject();
            if (candidate.className() == null) node.putNull("class");
            else node.put("class", candidate.className());
            node.put("file", candidate.file());
            node.put("score", candidate.score());
            node.put("confidence", confidence(candidate));
            node.put("static", candidate.staticDepth() != null);
            ArrayNode evidence = node.putArray("evidence");
            if (candidate.staticDepth() != null) {
                evidence.add(candidate.staticDepth() == 1 ? "static_direct" : "static_transitive");
            }
            if (candidate.coChangeCount() > 0) evidence.add("historical_co_change");
            if (candidate.staticDepth() != null) {
                node.put("dependency_depth", candidate.staticDepth());
                node.set("dependency_path", JSON.valueToTree(candidate.dependencyPath()));
                node.set("dependency_kinds", JSON.valueToTree(candidate.dependencyKinds()));
            }
            node.put("co_change_count", candidate.coChangeCount());
            node.set("reasons", JSON.valueToTree(candidate.reasons()));
            if (candidate.classId() != null) {
                ClassRecord testClass = classesById.get(candidate.classId());
                if (testClass != null) naiveTokens += testClass.sourceTokens();
            }
        }
        appendPage(root, page.size(), ranked.size(), limit, offset);
        appendMeta(root, reader, naiveTokens);
        return root;
    }

    private void collectStaticCandidates(
            ReadQueryContext reader,
            ClassRecord target,
            int maxDepth,
            Map<Integer, ClassRecord> classesById,
            Map<String, Candidate> candidates,
            boolean[] truncated,
            Map<Integer, List<DependencyRecord>> inbound) {
        ArrayDeque<PathNode> queue = new ArrayDeque<>();
        queue.add(new PathNode(target.id(), 0, List.of(target.className()), List.of()));
        Map<Integer, Integer> bestDepth = new HashMap<>();
        bestDepth.put(target.id(), 0);
        while (!queue.isEmpty()) {
            PathNode current = queue.removeFirst();
            ClassRecord currentClass = classesById.get(current.classId());
            if (currentClass != null && current.depth() > 0 && isTestClass(currentClass)) {
                mergeStatic(candidates, currentClass, current, target.className());
            }
            if (current.depth() >= maxDepth) continue;
            if (!inbound.containsKey(current.classId()) && inbound.size() >= MAX_EXPANDED_CLASSES) {
                truncated[0] = true;
                return;
            }
            for (DependencyRecord dependency :
                    inbound.computeIfAbsent(
                            current.classId(),
                            id -> reader.usages.findDependencies(id, "inbound"))) {
                ClassRecord caller = classesById.get(dependency.fromClassId());
                if (caller == null) continue;
                int nextDepth = current.depth() + 1;
                Integer previous = bestDepth.get(caller.id());
                if (previous != null && previous <= nextDepth) continue;
                bestDepth.put(caller.id(), nextDepth);
                List<String> path = append(current.classPath(), caller.className());
                List<String> kinds = append(current.dependencyKinds(), dependency.kind());
                queue.addLast(new PathNode(caller.id(), nextDepth, path, kinds));
            }
        }
    }

    private void collectGitCandidates(
            ReadQueryContext reader,
            ClassRecord target,
            Map<Integer, ClassRecord> classesById,
            Map<String, Candidate> candidates) {
        GitRepository gitRepository = reader.git;
        if (!gitRepository.hasGitData()) return;
        for (CoChangeRecord coChange : gitRepository.findCoChanges(target.id(), 500)) {
            ClassRecord cls =
                    coChange.classId() == null ? null : classesById.get(coChange.classId());
            if ((cls == null || !isTestClass(cls)) && !isTestPath(coChange.filePath())) continue;
            String key = cls == null ? "file:" + coChange.filePath() : "class:" + cls.id();
            Candidate previous = candidates.get(key);
            int count =
                    (previous == null ? 0 : previous.coChangeCount()) + coChange.coChangeCount();
            List<String> reasons =
                    previous == null ? new ArrayList<>() : new ArrayList<>(previous.reasons());
            reasons.add(
                    "Co-changed "
                            + coChange.coChangeCount()
                            + " time(s) with "
                            + target.className());
            Candidate updated =
                    previous == null
                            ? new Candidate(
                                    key,
                                    cls == null ? null : cls.id(),
                                    cls == null ? null : cls.className(),
                                    coChange.filePath(),
                                    null,
                                    List.of(),
                                    List.of(),
                                    count,
                                    0,
                                    List.copyOf(reasons))
                            : new Candidate(
                                    previous.identity(),
                                    previous.classId(),
                                    previous.className(),
                                    previous.file(),
                                    previous.staticDepth(),
                                    previous.dependencyPath(),
                                    previous.dependencyKinds(),
                                    count,
                                    previous.staticScore(),
                                    List.copyOf(reasons));
            candidates.put(key, updated);
        }
    }

    private static void mergeStatic(
            Map<String, Candidate> candidates,
            ClassRecord testClass,
            PathNode path,
            String target) {
        String key = "class:" + testClass.id();
        Candidate previous = candidates.get(key);
        String reason = "Depends on " + target + " at depth " + path.depth();
        if (previous != null
                && previous.staticDepth() != null
                && previous.staticDepth() <= path.depth()) {
            if (!previous.reasons().contains(reason)) {
                List<String> reasons = new ArrayList<>(previous.reasons());
                reasons.add(reason);
                candidates.put(
                        key,
                        new Candidate(
                                previous.identity(),
                                previous.classId(),
                                previous.className(),
                                previous.file(),
                                previous.staticDepth(),
                                previous.dependencyPath(),
                                previous.dependencyKinds(),
                                previous.coChangeCount(),
                                previous.staticScore(),
                                List.copyOf(reasons)));
            }
            return;
        }
        int staticScore = Math.max(20, 100 - (path.depth() - 1) * 15);
        List<String> reasons =
                previous == null ? new ArrayList<>() : new ArrayList<>(previous.reasons());
        reasons.add(reason);
        candidates.put(
                key,
                new Candidate(
                        key,
                        testClass.id(),
                        testClass.className(),
                        testClass.sourceFile(),
                        path.depth(),
                        path.classPath(),
                        path.dependencyKinds(),
                        previous == null ? 0 : previous.coChangeCount(),
                        staticScore,
                        List.copyOf(reasons)));
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    static boolean isTestClass(ClassRecord cls) {
        return cls.inTestSourceSet() || isTestPath(cls.sourceFile());
    }

    private static boolean isTestPath(String path) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/');
        return normalized.contains("/src/test/")
                || normalized.startsWith("src/test/")
                || JvmSourceFiles.isConventionalTestName(normalized);
    }

    private static String confidence(Candidate candidate) {
        if (candidate.staticDepth() != null && candidate.staticDepth() <= 1) return "high";
        if (candidate.staticDepth() != null || candidate.coChangeCount() >= 2) return "medium";
        return "low";
    }

    private static List<String> metadataList(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) return List.of();
        try {
            return JSON.readTree(value).valueStream().map(node -> node.asText()).toList();
        } catch (Exception ignored) {
            QuillLog.swallowed(ignored);
            return List.of();
        }
    }

    private record PathNode(
            int classId, int depth, List<String> classPath, List<String> dependencyKinds) {}

    private record Candidate(
            String identity,
            Integer classId,
            String className,
            String file,
            Integer staticDepth,
            List<String> dependencyPath,
            List<String> dependencyKinds,
            int coChangeCount,
            int staticScore,
            List<String> reasons) {

        int score() {
            return Math.min(100, staticScore + Math.min(30, coChangeCount * 5));
        }
    }
}
