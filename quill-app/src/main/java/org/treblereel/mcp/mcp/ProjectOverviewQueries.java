package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.*;

/** Builds the project-wide overview response. */
final class ProjectOverviewQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PROBLEM_DETAILS = 50;
    private final GitToolQueries git;

    ProjectOverviewQueries(GitToolQueries git) {
        this.git = git;
    }

    String getOverview(Jdbi jdbi) {
        Map<String, String> meta = IndexReader.getMetadata(jdbi);
        ObjectNode root = JSON.createObjectNode();

        ObjectNode project = root.putObject("project");
        int classCount = IndexReader.countClasses(jdbi);
        int beanCount = IndexReader.countBeans(jdbi);
        int totalTokens = IndexReader.sumSourceTokens(jdbi);
        project.put("framework", meta.getOrDefault("framework", "CDI"));
        project.put("classes", classCount);
        project.put("beans", beanCount);
        project.put("total_source_tokens", totalTokens);
        project.put("index_id", meta.get("index_id"));
        project.put("indexed_at", meta.getOrDefault("indexed_at", "unknown"));
        project.put("last_commit", meta.getOrDefault("last_commit", "unknown"));
        project.put("dependency_index", meta.getOrDefault("dependency_index", "unknown"));
        if (meta.containsKey("dependency_index_detail")) {
            project.put("dependency_index_detail", meta.get("dependency_index_detail"));
        }
        project.put("service_descriptors",
                Integer.parseInt(meta.getOrDefault("service_descriptors", "0")));
        project.put("service_registrations",
                Integer.parseInt(meta.getOrDefault("service_registrations", "0")));

        ObjectNode scopeNode = root.putObject("beans_by_scope");
        IndexReader.countBeansByScope(jdbi).forEach(scopeNode::put);

        ObjectNode kindNode = root.putObject("beans_by_kind");
        IndexReader.countBeansByKind(jdbi).forEach(kindNode::put);

        ArrayNode hubs = root.putArray("architecture_hubs");
        List<Map.Entry<Integer, Integer>> hubEntries =
                IndexReader.findMostDependedOn(jdbi, 5);
        Map<Integer, ClassRecord> hubClasses = IndexReader.findClassesByIds(jdbi,
                hubEntries.stream().map(Map.Entry::getKey).toList());
        for (var entry : hubEntries) {
            Optional.ofNullable(hubClasses.get(entry.getKey())).ifPresent(c -> {
                ObjectNode hub = hubs.addObject();
                hub.put("class", c.className());
                hub.put("dependents", entry.getValue());
                hub.put("is_bean", c.isBean());
            });
        }

        ObjectNode problems = root.putObject("problems");
        List<InjectionPointRecord> unsatisfied = IndexReader.findUnsatisfiedInjectionPoints(jdbi);
        List<InjectionPointRecord> ambiguous = IndexReader.findAmbiguousInjectionPoints(jdbi);
        List<InjectionPointRecord> contextRequired =
                IndexReader.findContextRequiredInjectionPoints(jdbi);
        List<InjectionPointRecord> unknown = IndexReader.findUnknownInjectionPoints(jdbi);
        List<InjectionPointRecord> unsupported = IndexReader.findUnsupportedInjectionPoints(jdbi);
        List<InjectionPointRecord> problemSample = new ArrayList<>();
        problemSample.addAll(unsatisfied.stream().limit(10).toList());
        problemSample.addAll(ambiguous.stream().limit(10).toList());
        problemSample.addAll(contextRequired.stream().limit(10).toList());
        problemSample.addAll(unknown.stream().limit(10).toList());
        problemSample.addAll(unsupported.stream().limit(10).toList());
        Map<Integer, BeanRecord> problemBeans = IndexReader.findBeansByIds(jdbi,
                problemSample.stream().map(InjectionPointRecord::beanId).toList());
        Map<Integer, ClassRecord> problemClasses = IndexReader.findClassesByIds(jdbi,
                problemBeans.values().stream().map(BeanRecord::classId).toList());
        problems.put("unsatisfied_count", unsatisfied.size());
        if (!unsatisfied.isEmpty()) {
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
        if (!ambiguous.isEmpty()) {
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
        writeResolutionGroup(problems, "unknown", unknown, problemBeans, problemClasses);
        writeResolutionGroup(problems, "context_required", contextRequired,
                problemBeans, problemClasses);
        writeResolutionGroup(problems, "unsupported_mechanism", unsupported,
                problemBeans, problemClasses);

        List<CdiProblem> cdiProblems = IndexReader.findCdiProblems(jdbi);
        if (!cdiProblems.isEmpty()) {
            problems.put("cdi_spec_violation_count", cdiProblems.size());
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

        if (IndexReader.hasExternalDeps(jdbi)) {
            ArrayNode libsArr = root.putArray("top_libraries");
            for (var entry : IndexReader.findExternalDepsByLibrary(jdbi, 5)) {
                ObjectNode lib = libsArr.addObject();
                lib.put("package", entry.getKey());
                lib.put("used_by_classes", entry.getValue());
            }
        }

        if (IndexReader.hasGitData(jdbi)) {
            ObjectNode gitSummary = root.putObject("git_summary");
            gitSummary.put("total_commits_indexed", IndexReader.countCommits(jdbi));
            ArrayNode hotspotsArr = gitSummary.putArray("top_hotspots");
            for (GitFileStats s : git.currentHotspots(jdbi, meta, 3)) {
                ObjectNode h = hotspotsArr.addObject();
                h.put("file", s.filePath());
                h.put("commit_count", s.commitCount());
            }
        } else {
            root.putNull("git_summary");
        }

        appendMeta(root, jdbi, totalTokens);
        return root.toString();
    }

    private static void writeResolutionGroup(ObjectNode problems, String name,
            List<InjectionPointRecord> points, Map<Integer, BeanRecord> beans,
            Map<Integer, ClassRecord> classes) {
        problems.put(name + "_count", points.size());
        if (points.isEmpty()) return;
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

    private static void appendResolutionEvidence(
            ObjectNode node, InjectionPointRecord injectionPoint) {
        node.put("resolution_strategy", injectionPoint.resolutionStrategy());
        node.put("reason", injectionPoint.resolutionReason());
        node.put("confidence", injectionPoint.resolutionConfidence().name().toLowerCase());
        node.set("limitations", JSON.valueToTree(injectionPoint.limitations()));
    }

}
