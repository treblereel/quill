package org.treblereel.mcp.mcp;

import static org.treblereel.mcp.mcp.ToolResponseSupport.appendMeta;
import static org.treblereel.mcp.mcp.ToolResponseSupport.appendPage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.UnusedClassCandidate;

/** Conservatively identifies classes with no indexed inbound evidence. */
final class UnusedClassQueries {

    private static final ObjectMapper JSON = new ObjectMapper();

    String findUnusedClasses(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        ServiceProviders serviceProviders = readServiceProviders(jdbi);
        Map<String, Integer> excluded = new LinkedHashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        int analyzed = 0;
        for (UnusedClassCandidate evidence : IndexReader.findUnusedClassCandidates(jdbi)) {
            ClassRecord cls = evidence.classRecord();
            if (!inRequestedScope(cls, module, includeGenerated, includeTests, excluded)) continue;
            analyzed++;

            boolean protectedRoot = false;
            protectedRoot |= exclude(excluded, "inbound_reference",
                    evidence.inboundClassCount() > 0);
            protectedRoot |= exclude(excluded, "hierarchy_reference",
                    evidence.hierarchyUserCount() > 0);
            protectedRoot |= exclude(excluded, "di_bean", cls.isBean());
            protectedRoot |= exclude(excluded, "direct_class_annotation",
                    evidence.directAnnotationCount() > 0);
            protectedRoot |= exclude(excluded, "main_entry_point", evidence.hasMainMethod());
            protectedRoot |= exclude(excluded, "service_provider",
                    serviceProviders.providers().contains(cls.className()));
            protectedRoot |= exclude(excluded, "metadata_class", isMetadataClass(cls));
            if (protectedRoot) continue;
            candidates.add(new Candidate(evidence, confidence(cls)));
        }
        candidates.sort(Comparator
                .comparingInt((Candidate candidate) -> confidenceOrder(candidate.confidence()))
                .thenComparing(Comparator.comparingInt(
                        (Candidate candidate) -> candidate.evidence().classRecord().sourceTokens())
                        .reversed())
                .thenComparing(candidate -> candidate.evidence().classRecord().className()));

        int from = Math.min(offset, candidates.size());
        int to = (int) Math.min((long) from + limit, candidates.size());
        List<Candidate> page = candidates.subList(from, to);

        ObjectNode root = JSON.createObjectNode();
        root.put("classification", "candidates_not_proven_dead_code");
        if (module == null) root.putNull("module_filter");
        else root.put("module_filter", module);
        root.put("include_generated", includeGenerated);
        root.put("include_tests", includeTests);
        root.put("analyzed_classes", analyzed);
        root.set("excluded_reason_counts", JSON.valueToTree(excluded));
        root.put("service_descriptor_evidence_available", serviceProviders.available());
        ArrayNode limitations = root.putArray("limitations");
        limitations.add("Reflection, native lookups, serialization, configuration files, templates, and external consumers may use a class without an indexed bytecode edge");
        limitations.add("Directly annotated classes, DI beans, service providers, and main classes are conservatively treated as framework or runtime roots");
        limitations.add("A candidate means no usage was found in the indexed compiled snapshot; inspect freshness metadata before deleting code");

        ArrayNode values = root.putArray("candidates");
        int naiveTokens = 0;
        for (Candidate candidate : page) {
            UnusedClassCandidate evidence = candidate.evidence();
            ClassRecord cls = evidence.classRecord();
            ObjectNode node = values.addObject();
            node.put("class", cls.className());
            node.put("kind", cls.kind());
            node.put("confidence", candidate.confidence());
            node.put("reason", "no_indexed_inbound_or_hierarchy_references");
            node.put("source", cls.sourceFile() + ":" + cls.sourceLine());
            node.put("origin", cls.origin());
            if (cls.module() == null) node.putNull("module");
            else node.put("module", cls.module());
            if (cls.sourceSet() == null) node.putNull("source_set");
            else node.put("source_set", cls.sourceSet());
            node.put("source_tokens", cls.sourceTokens());
            node.put("inbound_classes", evidence.inboundClassCount());
            node.put("inbound_occurrences", evidence.inboundOccurrenceCount());
            node.put("production_inbound_classes", evidence.productionInboundClassCount());
            node.put("test_inbound_classes", evidence.testInboundClassCount());
            node.put("hierarchy_users", evidence.hierarchyUserCount());
            ArrayNode cautions = node.putArray("cautions");
            if (!"CLASS".equals(cls.kind())) {
                cautions.add("Non-class API types are more likely to have external or declarative consumers");
            }
            if (!serviceProviders.available()) {
                cautions.add("Service descriptor metadata is unavailable");
            }
            naiveTokens += cls.sourceTokens();
        }
        appendPage(root, page.size(), candidates.size(), limit, offset);
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    private static boolean inRequestedScope(ClassRecord cls, String module,
            boolean includeGenerated, boolean includeTests, Map<String, Integer> excluded) {
        if (module != null && !module.equals(cls.module())) {
            increment(excluded, "module_filter");
            return false;
        }
        if ("dependency".equals(cls.origin())) {
            increment(excluded, "dependency_class");
            return false;
        }
        if (!includeGenerated && "generated".equals(cls.origin())) {
            increment(excluded, "generated_class");
            return false;
        }
        if (!includeTests && "test".equals(cls.sourceSet())) {
            increment(excluded, "test_class");
            return false;
        }
        return true;
    }

    private static boolean exclude(
            Map<String, Integer> excluded, String reason, boolean condition) {
        if (condition) increment(excluded, reason);
        return condition;
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.merge(key, 1, Integer::sum);
    }

    private static boolean isMetadataClass(ClassRecord cls) {
        return cls.className().endsWith("package-info")
                || cls.className().equals("module-info")
                || cls.className().endsWith(".module-info");
    }

    private static String confidence(ClassRecord cls) {
        return "CLASS".equals(cls.kind()) && "source".equals(cls.origin())
                ? "medium" : "low";
    }

    private static int confidenceOrder(String confidence) {
        return "medium".equals(confidence) ? 0 : 1;
    }

    private static ServiceProviders readServiceProviders(Jdbi jdbi) {
        String detail = IndexReader.getMetadata(jdbi).get("service_registrations_detail");
        if (detail == null) return new ServiceProviders(Set.of(), false);
        try {
            JsonNode registrations = JSON.readTree(detail);
            Set<String> providers = new HashSet<>();
            for (JsonNode registration : registrations) {
                String provider = registration.path("providerType").asText(null);
                if (provider != null) providers.add(provider);
            }
            return new ServiceProviders(Set.copyOf(providers), true);
        } catch (Exception ignored) {
            return new ServiceProviders(Set.of(), false);
        }
    }

    private record Candidate(UnusedClassCandidate evidence, String confidence) {}

    private record ServiceProviders(Set<String> providers, boolean available) {}
}
