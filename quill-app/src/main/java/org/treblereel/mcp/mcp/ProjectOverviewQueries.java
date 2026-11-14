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

        appendCapabilities(root.putObject("capabilities"), details);

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

        List<String> auxiliary = auxiliaryBuildProjects(reader);
        if (!auxiliary.isEmpty()) {
            // Separate builds next to the indexed project (for example a schema generator with its
            // own pom.xml) are part of the repository inventory but were not indexed.
            project.set("auxiliary_build_projects", JSON.valueToTree(auxiliary));
        }

        root.set("suggested_tools", JSON.valueToTree(suggestedTools(beanCount,
                Integer.parseInt(meta.getOrDefault("configuration_usages", "0")),
                reader.symbols.countAnnotatedClasses("jakarta.ws.rs.Path", true)
                        + reader.symbols.countAnnotatedClasses(
                                "org.springframework.web.bind.annotation.RestController", true))));

        root.set("beans_by_scope", countsInOwnTerms(reader.beans.countBeansByScope(), BeanTerms::scope));
        root.set("beans_by_kind", countsInOwnTerms(reader.beans.countBeansByKind(), BeanTerms::kind));

        root.put("architecture_hubs_ranked_by", "combined");
        ArrayNode hubs = root.putArray("architecture_hubs");
        List<UsageRepository.ArchitectureHub> hubMetrics = reader.usages.findArchitectureHubs();
        Map<Integer, ClassRecord> hubClasses = reader.symbols.findClassesByIds(hubMetrics.stream().map(UsageRepository.ArchitectureHub::classId).toList());
        if (!details) root.set("architecture_hub_columns", JSON.valueToTree(HUB_COLUMNS));
        topHubs(hubMetrics, UsageRepository.ArchitectureHub::totalDependents).forEach(hub ->
                Optional.ofNullable(hubClasses.get(hub.classId()))
                        .ifPresent(cls -> {
                            if (details) appendHub(hubs, hub, cls);
                            else appendHubRow(hubs, hub, cls);
                        }));

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

    /** Immutable per index generation; bounded because a server may serve many generations. */
    private static final Map<String, List<String>> AUXILIARY_BY_INDEX =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static List<String> auxiliaryBuildProjects(ReadQueryContext reader) {
        String indexId = reader.index.getMetadata().get("index_id");
        if (indexId == null) {
            return auxiliaryBuildProjects(reader.index.findIndexedModules(), reader.index.findBuildDescriptors());
        }
        if (AUXILIARY_BY_INDEX.size() > 16) AUXILIARY_BY_INDEX.clear();
        return AUXILIARY_BY_INDEX.computeIfAbsent(indexId, ignored -> auxiliaryBuildProjects(
                reader.index.findIndexedModules(), reader.index.findBuildDescriptors()));
    }

    /**
     * Tools that fit this project, so an agent can call them by name instead of spending rounds
     * on catalog search (in router mode Codex issued 74 search_tools calls for 29 executions).
     */
    static List<String> suggestedTools(int beans, int configurationUsages, int endpointClasses) {
        List<String> tools = new ArrayList<>(List.of(
                "search_classes", "get_symbol_details", "find_symbol_usages", "get_call_hierarchy",
                "find_impacted_tests", "change_session"));
        if (beans > 0) tools.addAll(List.of("list_beans", "list_injection_points"));
        if (configurationUsages > 0) tools.add("find_configuration_references");
        if (endpointClasses > 0) tools.add("find_framework_endpoints");
        return List.copyOf(tools);
    }

    static List<String> auxiliaryBuildProjects(java.util.Collection<String> indexedModules,
            List<String> buildDescriptors) {
        java.util.Set<String> modules = new java.util.HashSet<>(indexedModules);
        java.util.TreeSet<String> candidates = new java.util.TreeSet<>();
        for (String descriptor : buildDescriptors) {
            int slash = descriptor.lastIndexOf('/');
            if (slash < 0) continue; // the root build itself
            String directory = descriptor.substring(0, slash);
            if (modules.contains(directory) || directory.contains("/src/")
                    || directory.startsWith("src/") || directory.contains("/target/")
                    || directory.startsWith("target/")) continue;
            // An aggregator whose children are indexed modules belongs to the indexed build.
            if (modules.stream().anyMatch(module -> module.startsWith(directory + "/"))) continue;
            candidates.add(directory);
        }
        // Report each separate build once, by its top directory (Gradle subprojects included).
        List<String> result = new ArrayList<>();
        for (String directory : candidates) {
            if (result.stream().noneMatch(top -> directory.startsWith(top + "/"))) result.add(directory);
            if (result.size() >= 20) break;
        }
        return List.copyOf(result);
    }

    /**
     * The capability contract never changes between calls; the default overview, requested at the
     * start of every task, only names the contract version and how to get the rest.
     */
    private static void appendCapabilities(ObjectNode capabilities, boolean details) {
        capabilities.put("contract_version", 1);
        if (!details) {
            capabilities.put("details_request", "get_overview details=true");
            return;
        }
        capabilities.set("supported_languages", JSON.valueToTree(List.of("java", "kotlin")));
        capabilities.put("stable_symbol_ids", true);
        capabilities.put("symbol_id_version", 3);
        capabilities.put("symbol_id_scope", "project");
        capabilities.put("workspace_symbol_ids_require_project", true);
        capabilities.put("machine_readable_errors", true);
        capabilities.put("error_contract_version", 1);
        capabilities.set("symbol_id_consumers", JSON.valueToTree(List.of(
                "find_symbol_usages", "get_call_hierarchy",
                "analyze_execution_order", "find_method_overrides")));
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
            if (cls != null) appendHub(result, hub, cls);
        }
    }

    /** Column order of the compact hub rows returned by the default overview. */
    static final List<String> HUB_COLUMNS = List.of("class", "total_dependents",
            "source_dependents", "generated_dependents", "production_dependents",
            "test_dependents", "is_bean", "module", "source_set");

    /**
     * The default overview starts every task, so its hubs are rows in HUB_COLUMNS order rather
     * than objects repeating nine keys each; details=true keeps the object form.
     */
    private static void appendHubRow(ArrayNode result, UsageRepository.ArchitectureHub metrics,
            ClassRecord cls) {
        ArrayNode row = result.addArray();
        row.add(cls.className()).add(metrics.totalDependents()).add(metrics.sourceDependents())
                .add(metrics.generatedDependents()).add(metrics.productionDependents())
                .add(metrics.testDependents()).add(cls.isBean());
        if (cls.module() == null) row.addNull(); else row.add(cls.module());
        if (cls.sourceSet() == null) row.addNull(); else row.add(cls.sourceSet());
    }

    private static void appendHub(ArrayNode result, UsageRepository.ArchitectureHub metrics,
            ClassRecord cls) {
        // The ranking is named once by the enclosing list (architecture_hubs_ranked_by or the
        // architecture_hub_rankings key), not repeated on every hub.
        ObjectNode hub = result.addObject();
        hub.put("class", cls.className());
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


    /** Spring and CDI counts under each framework's own names (Spring singleton, not @Singleton), largest first. */
    private static ObjectNode countsInOwnTerms(List<org.treblereel.mcp.db.BeanRepository.FrameworkCount> rows,
            java.util.function.BinaryOperator<String> terms) {
        Map<String, Integer> merged = new java.util.LinkedHashMap<>();
        for (var row : rows) merged.merge(String.valueOf(terms.apply(row.framework(), row.value())), row.count(), Integer::sum);
        ObjectNode node = JSON.createObjectNode();
        merged.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> node.put(entry.getKey(), entry.getValue()));
        return node;
    }
}
