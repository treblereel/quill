package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import java.util.function.ToIntFunction;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.db.GitRepository;
import org.treblereel.mcp.db.UsageRepository;
import org.treblereel.mcp.model.*;

/** Builds the project-wide overview response. */
final class ProjectOverviewQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PROBLEM_DETAILS = 50;
    private final GitToolQueries git;

    ProjectOverviewQueries(GitToolQueries git) {
        this.git = git;
    }

    String getOverview(ReadQueryContext reader) {
        return getOverview(reader, true);
    }

    String getOverview(ReadQueryContext reader, boolean details) {
        GitRepository gitRepository = reader.git;
        Map<String, String> meta = reader.index.getMetadata();
        ObjectNode root = JSON.createObjectNode();

        ObjectNode capabilities = root.putObject("capabilities");
        capabilities.put("contract_version", 1);
        capabilities.set("supported_languages", JSON.valueToTree(List.of("java", "kotlin")));
        capabilities.put("stable_symbol_ids", true);
        capabilities.put("symbol_id_version", 2);
        capabilities.put("symbol_id_scope", "project");
        capabilities.put("workspace_symbol_ids_require_project", true);
        capabilities.put("machine_readable_errors", true);
        capabilities.put("error_contract_version", 1);
        capabilities.set("symbol_id_consumers", JSON.valueToTree(List.of(
                "find_symbol_usages", "get_call_hierarchy",
                "analyze_execution_order", "find_method_overrides")));

        ObjectNode project = root.putObject("project");
        int classCount = reader.symbols.countClasses();
        int beanCount = reader.beans.countBeans();
        int totalTokens = reader.symbols.sumSourceTokens();
        project.put("framework", meta.getOrDefault("framework", "CDI"));
        project.put("classes", classCount);
        project.put("beans", beanCount);
        project.put("total_source_tokens", totalTokens);
        if (details) {
            project.put("index_id", meta.get("index_id"));
            project.put("indexed_at", meta.getOrDefault("indexed_at", "unknown"));
            project.put("last_commit", meta.getOrDefault("last_commit", "unknown"));
        }
        project.put("dependency_index", meta.getOrDefault("dependency_index", "unknown"));
        if (meta.containsKey("dependency_index_detail")) {
            project.put("dependency_index_detail", meta.get("dependency_index_detail"));
        }
        project.put("service_descriptors",
                Integer.parseInt(meta.getOrDefault("service_descriptors", "0")));
        project.put("service_registrations",
                Integer.parseInt(meta.getOrDefault("service_registrations", "0")));
        project.put("configuration_definitions",
                Integer.parseInt(meta.getOrDefault("configuration_definitions", "0")));
        project.put("configuration_usages",
                Integer.parseInt(meta.getOrDefault("configuration_usages", "0")));

        ObjectNode scopeNode = root.putObject("beans_by_scope");
        reader.beans.countBeansByScope().forEach(scopeNode::put);

        ObjectNode kindNode = root.putObject("beans_by_kind");
        reader.beans.countBeansByKind().forEach(kindNode::put);

        ArrayNode hubs = root.putArray("architecture_hubs");
        List<UsageRepository.ArchitectureHub> hubMetrics = reader.usages.findArchitectureHubs();
        Map<Integer, ClassRecord> hubClasses = reader.symbols.findClassesByIds(hubMetrics.stream().map(UsageRepository.ArchitectureHub::classId).toList());
        topHubs(hubMetrics, UsageRepository.ArchitectureHub::totalDependents).forEach(hub ->
                Optional.ofNullable(hubClasses.get(hub.classId()))
                        .ifPresent(cls -> appendHub(hubs, hub, cls, "combined")));

        if (details) {
            ObjectNode rankings = root.putObject("architecture_hub_rankings");
            writeHubRanking(rankings, "source", hubMetrics,
                    UsageRepository.ArchitectureHub::sourceDependents, hubClasses);
            writeHubRanking(rankings, "generated", hubMetrics,
                    UsageRepository.ArchitectureHub::generatedDependents, hubClasses);
            writeHubRanking(rankings, "production", hubMetrics,
                    UsageRepository.ArchitectureHub::productionDependents, hubClasses);
            writeHubRanking(rankings, "test", hubMetrics,
                    UsageRepository.ArchitectureHub::testDependents, hubClasses);
            rankings.put("scope_note", "production/test are classified by source_set; "
                    + "source/generated are independent origin dimensions");
        }

        ObjectNode problems = root.putObject("problems");
        List<InjectionPointRecord> unsatisfied = reader.beans.findUnsatisfiedInjectionPoints();
        List<InjectionPointRecord> ambiguous = reader.beans.findAmbiguousInjectionPoints();
        List<InjectionPointRecord> contextRequired =
                reader.beans.findContextRequiredInjectionPoints();
        List<InjectionPointRecord> unknown = reader.beans.findUnknownInjectionPoints();
        List<InjectionPointRecord> unsupported = reader.beans.findUnsupportedInjectionPoints();
        List<InjectionPointRecord> problemSample = new ArrayList<>();
        problemSample.addAll(unsatisfied.stream().limit(10).toList());
        problemSample.addAll(ambiguous.stream().limit(10).toList());
        problemSample.addAll(contextRequired.stream().limit(10).toList());
        problemSample.addAll(unknown.stream().limit(10).toList());
        problemSample.addAll(unsupported.stream().limit(10).toList());
        Map<Integer, BeanRecord> problemBeans = reader.beans.findBeansByIds(problemSample.stream().map(InjectionPointRecord::beanId).toList());
        Map<Integer, ClassRecord> problemClasses = reader.symbols.findClassesByIds(problemBeans.values().stream().map(BeanRecord::classId).toList());
        problems.put("unsatisfied_count", unsatisfied.size());
        if (details && !unsatisfied.isEmpty()) {
            List<InjectionPointRecord> unsatLimited = unsatisfied.size() > 10
                    ? unsatisfied.subList(0, 10) : unsatisfied;
            ArrayNode unsatArr = problems.putArray("unsatisfied_injection_points_sample");
            for (InjectionPointRecord ip : unsatLimited) {
                ObjectNode node = unsatArr.addObject();
                BeanRecord bean = problemBeans.get(ip.beanId());
                if (bean != null && problemClasses.containsKey(bean.classId())) {
                    node.put("bean", problemClasses.get(bean.classId()).className());
                }
                node.put("field", ip.fieldName());
                node.put("type", ip.targetType());
                appendResolutionEvidence(node, ip);
            }
        }
        problems.put("ambiguous_count", ambiguous.size());
        if (details && !ambiguous.isEmpty()) {
            List<InjectionPointRecord> ambLimited = ambiguous.size() > 10
                    ? ambiguous.subList(0, 10) : ambiguous;
            ArrayNode ambArr = problems.putArray("ambiguous_injection_points_sample");
            for (InjectionPointRecord ip : ambLimited) {
                ObjectNode node = ambArr.addObject();
                BeanRecord bean = problemBeans.get(ip.beanId());
                if (bean != null && problemClasses.containsKey(bean.classId())) {
                    node.put("bean", problemClasses.get(bean.classId()).className());
                }
                node.put("field", ip.fieldName());
                node.put("type", ip.targetType());
                appendResolutionEvidence(node, ip);
            }
        }
        writeResolutionGroup(problems, "unknown", unknown, problemBeans, problemClasses, details);
        writeResolutionGroup(problems, "context_required", contextRequired,
                problemBeans, problemClasses, details);
        writeResolutionGroup(problems, "unsupported_mechanism", unsupported,
                problemBeans, problemClasses, details);

        List<CdiProblem> cdiProblems = reader.beans.findCdiProblems();
        if (!cdiProblems.isEmpty()) {
            problems.put("cdi_spec_violation_count", cdiProblems.size());
            if (details) {
                ArrayNode cdiArr = problems.putArray("cdi_spec_violations");
                for (CdiProblem p : cdiProblems.stream().limit(MAX_PROBLEM_DETAILS).toList()) {
                    ObjectNode node = cdiArr.addObject();
                    node.put("class", p.className());
                    node.put("type", p.problemType());
                    node.put("message", p.message());
                }
                if (cdiProblems.size() > MAX_PROBLEM_DETAILS) {
                    problems.put("cdi_spec_violations_truncated", true);
                }
            }
        }

        if (reader.usages.hasExternalDeps()) {
            ArrayNode libsArr = root.putArray("top_libraries");
            for (var entry : reader.usages.findExternalDepsByLibrary(5)) {
                ObjectNode lib = libsArr.addObject();
                lib.put("package", entry.getKey());
                lib.put("used_by_classes", entry.getValue());
            }
        }

        if (gitRepository.hasGitData()) {
            ObjectNode gitSummary = root.putObject("git_summary");
            gitSummary.put("total_commits_indexed", gitRepository.countCommits());
            ArrayNode hotspotsArr = gitSummary.putArray("top_hotspots");
            for (GitFileStats s : git.currentHotspots(reader, meta, 3)) {
                ObjectNode h = hotspotsArr.addObject();
                h.put("file", s.filePath());
                h.put("commit_count", s.commitCount());
            }
        } else {
            root.putNull("git_summary");
        }

        appendMeta(root, reader, totalTokens);
        return root.toString();
    }

    private static void writeResolutionGroup(ObjectNode problems, String name,
            List<InjectionPointRecord> points, Map<Integer, BeanRecord> beans,
            Map<Integer, ClassRecord> classes, boolean details) {
        problems.put(name + "_count", points.size());
        if (!details || points.isEmpty()) return;
        ArrayNode sample = problems.putArray(name + "_injection_points_sample");
        for (InjectionPointRecord ip : points.stream().limit(10).toList()) {
            ObjectNode node = sample.addObject();
            BeanRecord bean = beans.get(ip.beanId());
            if (bean != null && classes.containsKey(bean.classId())) {
                node.put("bean", classes.get(bean.classId()).className());
            }
            node.put("field", ip.fieldName());
            node.put("type", ip.targetType());
            appendResolutionEvidence(node, ip);
        }
    }

    private static List<UsageRepository.ArchitectureHub> topHubs(
            List<UsageRepository.ArchitectureHub> hubs,
            ToIntFunction<UsageRepository.ArchitectureHub> metric) {
        return hubs.stream()
                .filter(hub -> metric.applyAsInt(hub) > 0)
                .sorted(Comparator.comparingInt(metric).reversed()
                        .thenComparingInt(UsageRepository.ArchitectureHub::classId))
                .limit(5)
                .toList();
    }

    private static void writeHubRanking(ObjectNode rankings, String name,
            List<UsageRepository.ArchitectureHub> metrics,
            ToIntFunction<UsageRepository.ArchitectureHub> rankingMetric,
            Map<Integer, ClassRecord> classes) {
        ArrayNode result = rankings.putArray(name);
        for (UsageRepository.ArchitectureHub hub : topHubs(metrics, rankingMetric)) {
            ClassRecord cls = classes.get(hub.classId());
            if (cls != null) appendHub(result, hub, cls, name);
        }
    }

    private static void appendHub(ArrayNode result, UsageRepository.ArchitectureHub metrics,
            ClassRecord cls, String rankedBy) {
        ObjectNode hub = result.addObject();
        hub.put("class", cls.className());
        hub.put("ranked_by", rankedBy);
        hub.put("total_dependents", metrics.totalDependents());
        hub.put("source_dependents", metrics.sourceDependents());
        hub.put("generated_dependents", metrics.generatedDependents());
        hub.put("production_dependents", metrics.productionDependents());
        hub.put("test_dependents", metrics.testDependents());
        hub.put("is_bean", cls.isBean());
        if (cls.module() != null) hub.put("module", cls.module());
        if (cls.sourceSet() != null) hub.put("source_set", cls.sourceSet());
    }

    private static void appendResolutionEvidence(
            ObjectNode node, InjectionPointRecord injectionPoint) {
        node.put("resolution_strategy", injectionPoint.resolutionStrategy());
        node.put("reason", injectionPoint.resolutionReason().name());
        node.put("confidence", injectionPoint.resolutionConfidence().name().toLowerCase());
        node.set("limitations", JSON.valueToTree(injectionPoint.limitations()));
    }

}
