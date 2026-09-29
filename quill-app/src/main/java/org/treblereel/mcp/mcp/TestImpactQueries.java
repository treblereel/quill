package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;
import static org.treblereel.mcp.mcp.ToolResponseSupport.classLookupError;
import static org.treblereel.mcp.mcp.ToolResponseSupport.errorResponse;

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
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.CoChangeRecord;
import org.treblereel.mcp.model.DependencyRecord;

/** Ranks tests that are statically or historically coupled to changed classes. */
final class TestImpactQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_TARGETS = 20;
    private static final int MAX_EXPANDED_CLASSES = 5_000;

    String findImpactedTests(Jdbi jdbi, List<String> targets,
            boolean transitive, int maxDepth, int limit, int offset) {
        if (targets == null || targets.isEmpty()) {
            return errorResponse("At least one target is required");
        }
        if (targets.size() > MAX_TARGETS) {
            return errorResponse("At most " + MAX_TARGETS + " targets are allowed");
        }
        List<ClassRecord> resolvedTargets = new ArrayList<>();
        for (String target : targets) {
            ClassTargetResolver.Lookup lookup = ClassTargetResolver.resolve(jdbi, target);
            if (lookup.error() != null) return classLookupError(jdbi, lookup, target);
            if (resolvedTargets.stream().noneMatch(value -> value.id() == lookup.cls().id())) {
                resolvedTargets.add(lookup.cls());
            }
        }

        List<ClassRecord> allClasses = IndexReader.findAllClasses(jdbi);
        Map<Integer, ClassRecord> classesById = new HashMap<>();
        allClasses.forEach(value -> classesById.put(value.id(), value));
        int indexedTestClasses = (int) allClasses.stream()
                .filter(TestImpactQueries::isTestClass).count();
        Map<String, String> metadata = IndexReader.getMetadata(jdbi);
        List<String> indexedTestModules = metadataList(metadata, "compiled_test_modules");
        if (indexedTestModules.isEmpty() && indexedTestClasses > 0) {
            indexedTestModules = allClasses.stream().filter(TestImpactQueries::isTestClass)
                    .map(cls -> cls.module() == null ? "." : cls.module())
                    .distinct().sorted().toList();
        }
        List<String> missingTestModules = metadataList(metadata, "missing_test_output_modules");
        List<String> missingTestClasspathModules =
                metadataList(metadata, "missing_test_classpath_modules");
        List<String> staleTestOutputModules =
                metadataList(metadata, "stale_test_output_modules");
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        boolean[] truncatedTraversal = {false};
        int effectiveDepth = transitive ? maxDepth : 1;
        for (ClassRecord target : resolvedTargets) {
            collectStaticCandidates(jdbi, target, effectiveDepth, classesById,
                    candidates, truncatedTraversal);
            collectGitCandidates(jdbi, target, classesById, candidates);
        }

        List<Candidate> ranked = candidates.values().stream()
                .sorted(Comparator.comparingInt(Candidate::score).reversed()
                        .thenComparing(Candidate::identity))
                .toList();
        int from = Math.min(offset, ranked.size());
        int to = (int) Math.min((long) from + limit, ranked.size());
        List<Candidate> page = ranked.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        root.set("targets", JSON.valueToTree(
                resolvedTargets.stream().map(ClassRecord::className).toList()));
        root.put("transitive", transitive);
        root.put("max_depth", effectiveDepth);
        root.put("indexed_test_class_count", indexedTestClasses);
        root.put("compiled_test_outputs_indexed", indexedTestClasses > 0);
        root.put("git_history_available", IndexReader.hasGitData(jdbi));
        root.put("traversal_truncated", truncatedTraversal[0]);
        ObjectNode coverage = root.putObject("test_index_coverage");
        boolean incompleteCoverage = !missingTestModules.isEmpty()
                || !missingTestClasspathModules.isEmpty() || !staleTestOutputModules.isEmpty();
        String coverageStatus = indexedTestModules.isEmpty() ? "none"
                : incompleteCoverage ? "partial" : "complete";
        coverage.put("status", coverageStatus);
        coverage.set("indexed_modules", JSON.valueToTree(indexedTestModules));
        coverage.set("missing_modules", JSON.valueToTree(missingTestModules));
        coverage.set("missing_classpath_modules",
                JSON.valueToTree(missingTestClasspathModules));
        coverage.set("stale_modules", JSON.valueToTree(staleTestOutputModules));
        coverage.put("indexed_test_classes", indexedTestClasses);
        coverage.put("complete", coverageStatus.equals("complete"));
        root.put("static_evidence_available", indexedTestClasses > 0);
        root.put("historical_evidence_available", IndexReader.hasGitData(jdbi));
        root.put("answer_complete", coverageStatus.equals("complete") && !truncatedTraversal[0]);
        ArrayNode limitations = root.putArray("limitations");
        if (indexedTestClasses == 0) {
            limitations.add("No compiled test classes are indexed; static impact may be incomplete");
        }
        if (!IndexReader.hasGitData(jdbi)) {
            limitations.add("Git co-change evidence is unavailable");
        }
        if (!missingTestModules.isEmpty()) {
            limitations.add("Compiled test outputs are missing for modules: "
                    + String.join(", ", missingTestModules));
        }
        if (!missingTestClasspathModules.isEmpty()) {
            limitations.add("Captured test runtime classpaths are missing for modules: "
                    + String.join(", ", missingTestClasspathModules));
        }
        if (!staleTestOutputModules.isEmpty()) {
            limitations.add("Compiled test outputs are older than test sources for modules: "
                    + String.join(", ", staleTestOutputModules));
        }
        if (truncatedTraversal[0]) {
            limitations.add("Static traversal stopped at " + MAX_EXPANDED_CLASSES + " classes");
        }

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
                evidence.add(candidate.staticDepth() == 1
                        ? "static_direct" : "static_transitive");
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
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private void collectStaticCandidates(Jdbi jdbi, ClassRecord target, int maxDepth,
            Map<Integer, ClassRecord> classesById, Map<String, Candidate> candidates,
            boolean[] truncated) {
        ArrayDeque<PathNode> queue = new ArrayDeque<>();
        queue.add(new PathNode(target.id(), 0, List.of(target.className()), List.of()));
        Map<Integer, Integer> bestDepth = new HashMap<>();
        bestDepth.put(target.id(), 0);
        int expanded = 0;
        while (!queue.isEmpty()) {
            PathNode current = queue.removeFirst();
            ClassRecord currentClass = classesById.get(current.classId());
            if (currentClass != null && current.depth() > 0 && isTestClass(currentClass)) {
                mergeStatic(candidates, currentClass, current, target.className());
            }
            if (current.depth() >= maxDepth) continue;
            if (++expanded > MAX_EXPANDED_CLASSES) {
                truncated[0] = true;
                return;
            }
            for (DependencyRecord dependency : IndexReader.findDependencies(
                    jdbi, current.classId(), "inbound")) {
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

    private void collectGitCandidates(Jdbi jdbi, ClassRecord target,
            Map<Integer, ClassRecord> classesById, Map<String, Candidate> candidates) {
        if (!IndexReader.hasGitData(jdbi)) return;
        for (CoChangeRecord coChange : IndexReader.findCoChanges(jdbi, target.id(), 500)) {
            ClassRecord cls = coChange.classId() == null
                    ? null : classesById.get(coChange.classId());
            if ((cls == null || !isTestClass(cls)) && !isTestPath(coChange.filePath())) continue;
            String key = cls == null ? "file:" + coChange.filePath() : "class:" + cls.id();
            Candidate previous = candidates.get(key);
            int count = (previous == null ? 0 : previous.coChangeCount())
                    + coChange.coChangeCount();
            List<String> reasons = previous == null
                    ? new ArrayList<>() : new ArrayList<>(previous.reasons());
            reasons.add("Co-changed " + coChange.coChangeCount() + " time(s) with "
                    + target.className());
            Candidate updated = previous == null
                    ? new Candidate(key, cls == null ? null : cls.id(),
                            cls == null ? null : cls.className(), coChange.filePath(),
                            null, List.of(), List.of(), count, 0, List.copyOf(reasons))
                    : new Candidate(previous.identity(), previous.classId(), previous.className(),
                            previous.file(), previous.staticDepth(), previous.dependencyPath(),
                            previous.dependencyKinds(), count, previous.staticScore(),
                            List.copyOf(reasons));
            candidates.put(key, updated);
        }
    }

    private static void mergeStatic(Map<String, Candidate> candidates, ClassRecord testClass,
            PathNode path, String target) {
        String key = "class:" + testClass.id();
        Candidate previous = candidates.get(key);
        String reason = "Depends on " + target + " at depth " + path.depth();
        if (previous != null && previous.staticDepth() != null
                && previous.staticDepth() <= path.depth()) {
            if (!previous.reasons().contains(reason)) {
                List<String> reasons = new ArrayList<>(previous.reasons());
                reasons.add(reason);
                candidates.put(key, new Candidate(previous.identity(), previous.classId(),
                        previous.className(), previous.file(), previous.staticDepth(),
                        previous.dependencyPath(), previous.dependencyKinds(),
                        previous.coChangeCount(), previous.staticScore(), List.copyOf(reasons)));
            }
            return;
        }
        int staticScore = Math.max(20, 100 - (path.depth() - 1) * 15);
        List<String> reasons = previous == null
                ? new ArrayList<>() : new ArrayList<>(previous.reasons());
        reasons.add(reason);
        candidates.put(key, new Candidate(key, testClass.id(), testClass.className(),
                testClass.sourceFile(), path.depth(), path.classPath(), path.dependencyKinds(),
                previous == null ? 0 : previous.coChangeCount(), staticScore,
                List.copyOf(reasons)));
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    private static boolean isTestClass(ClassRecord cls) {
        return "test".equals(cls.sourceSet()) || isTestPath(cls.sourceFile());
    }

    private static boolean isTestPath(String path) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return normalized.contains("/src/test/") || normalized.startsWith("src/test/")
                || name.endsWith("Test.java") || name.endsWith("Tests.java")
                || name.endsWith("IT.java") || name.endsWith("Spec.kt");
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
