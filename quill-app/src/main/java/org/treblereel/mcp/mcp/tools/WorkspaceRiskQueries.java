package org.treblereel.mcp.mcp.tools;

import static org.treblereel.mcp.mcp.tools.WorkspaceResponses.*;

import org.treblereel.mcp.workspace.WorkspaceProjectScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.workspace.WorkspaceDependencyGraph;
import org.treblereel.mcp.mcp.change.ChangeRiskQueries;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.project.ProjectRegistry;
import org.treblereel.mcp.mcp.change.TestImpactQueries;
import org.treblereel.mcp.mcp.query.UsageToolQueries;
import org.treblereel.mcp.core.Json;

/** Provider change risk extended with downstream repository reachability. */
final class WorkspaceRiskQueries {

    private static final ObjectMapper JSON = Json.MAPPER;

    private final ProjectRegistry registry;
    private final WorkspaceProviders providerLookup;

    WorkspaceRiskQueries(ProjectRegistry registry, WorkspaceProviders providers) {
        this.registry = registry;
        this.providerLookup = providers;
    }

    String assessRisk(String target, String providerRepository, int maxDepth) {
        WorkspaceProjectScope scope = registry.workspaceScope();
        if (scope == null) return workspaceRequired();
        if (providerRepository != null && !providerRepository.isBlank()) {
            ProjectRegistry.Resolution availability = registry.resolve(providerRepository);
            if (availability.projects().isEmpty() && !availability.issues().isEmpty()) {
                return ProjectAvailabilityResponses.error(availability.issues());
            }
        }
        List<ProviderClass> providers = providerLookup.findProviderClasses(target, providerRepository);
        if (providers.isEmpty()) return error("Workspace class not found: " + target);
        if (providers.size() > 1) {
            ObjectNode root = JSON.createObjectNode();
            root.put("error", "ambiguous_workspace_class");
            ArrayNode values = root.putArray("candidates");
            providers.forEach(provider -> values.add(providerJson(provider)));
            return root.toString();
        }
        ProviderClass provider = providers.getFirst();
        ProjectRegistry.Resolution providerResolution = registry.resolve(provider.repository());
        if (providerResolution.projects().isEmpty()) {
            return error("Provider repository is not indexed: " + provider.repository());
        }
        com.fasterxml.jackson.databind.JsonNode localRisk;
        try {
            localRisk = JSON.readTree(providerResolution.projects().getFirst().<String>read(context -> new ChangeRiskQueries().getRisk(context, provider.className())));
        } catch (Exception invalid) {
            return error("Could not calculate provider risk: " + invalid.getMessage());
        }

        WorkspaceDependencyGraph.Result graph =
                WorkspaceDependencyGraph.discover(scope.manifest());
        Map<String, Integer> depths = downstreamDepths(
                graph.edges(), provider.repository(), maxDepth);
        List<DownstreamRisk> downstream = new ArrayList<>();
        int drifted = 0;
        for (var entry : depths.entrySet()) {
            WorkspaceDependencyGraph.Edge evidence = graph.edges().stream()
                    .filter(edge -> edge.consumerRepository().equals(entry.getKey()))
                    .filter(WorkspaceDependencyGraph.Edge::crossRepository)
                    .findFirst().orElse(null);
            if (evidence == null) continue;
            if ("binary_behind_checkout".equals(evidence.status())) drifted++;
            int usageGroups = 0;
            int impactedTests = 0;
            if (entry.getValue() == 1) {
                ProjectRegistry.Resolution consumer = registry.resolve(entry.getKey());
                if (!consumer.projects().isEmpty()) {
                    try (ReadQueryContext reader = ReadQueryContext.open(consumer.projects().getFirst().jdbi())) {
                    try {
                        var usage = JSON.readTree(new UsageToolQueries().findUsages(
                                reader, provider.className(), null, null, 1, 0));
                        usageGroups = usage.path("usage_group_count").asInt();
                        var tests = JSON.readTree(new TestImpactQueries().findImpactedTests(
                                reader, List.of(provider.className()), true, 3, 1, 0));
                        impactedTests = tests.path("total").asInt();
                    } catch (Exception ignored) {
                        // The dependency edge remains valid even when detailed evidence is absent.
                    }
                }
}
            }
            downstream.add(new DownstreamRisk(entry.getKey(), entry.getValue(),
                    evidence.coordinate(), evidence.status(), usageGroups, impactedTests,
                    entry.getValue() == 1 ? "high" : "medium"));
        }
        downstream.sort(java.util.Comparator.comparingInt(DownstreamRisk::depth)
                .thenComparing(DownstreamRisk::repository));

        double localScore = localRisk.path("risk_score").asDouble();
        long direct = depths.values().stream().filter(depth -> depth == 1).count();
        long transitive = depths.size() - direct;
        double workspaceScore = Math.min(10.0, Math.round((localScore + direct * 0.5
                + transitive * 0.2 + drifted * 0.5) * 10.0) / 10.0);
        ObjectNode root = JSON.createObjectNode();
        root.put("target", provider.className());
        root.put("provider_repository", provider.repository());
        root.put("workspace_risk_score", workspaceScore);
        root.put("workspace_risk_level", riskLevel(workspaceScore));
        root.set("provider_risk", localRisk);
        root.put("direct_consumer_count", direct);
        root.put("transitive_consumer_count", transitive);
        root.put("version_drift_count", drifted);
        ArrayNode downstreamValues = root.putArray("downstream");
        downstream.forEach(value -> downstreamValues.add(downstreamJson(value)));
        root.put("complete", graph.complete());
        appendDiagnostics(root, graph.diagnostics());
        root.putArray("limitations")
                .add("Transitive impact is repository-level dependency reachability")
                .add("Usage and test evidence is calculated only for direct consumers");
        ObjectNode scoring = root.putObject("scoring");
        scoring.put("strategy", "provider_risk_plus_downstream_reachability");
        scoring.put("direct_consumer_weight", 0.5);
        scoring.put("transitive_consumer_weight", 0.2);
        scoring.put("version_drift_weight", 0.5);
        return root.toString();
    }

    private static Map<String, Integer> downstreamDepths(
            List<WorkspaceDependencyGraph.Edge> edges, String provider, int maxDepth) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(provider);
        depths.put(provider, 0);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            int depth = depths.get(current);
            if (depth >= maxDepth) continue;
            for (WorkspaceDependencyGraph.Edge edge : edges) {
                if (!edge.crossRepository()
                        || !edge.providerRepository().equals(current)) continue;
                int candidateDepth = depth + 1;
                Integer previous = depths.get(edge.consumerRepository());
                if (previous == null || candidateDepth < previous) {
                    depths.put(edge.consumerRepository(), candidateDepth);
                    queue.addLast(edge.consumerRepository());
                }
            }
        }
        depths.remove(provider);
        return depths;
    }

    private static String riskLevel(double score) {
        if (score >= 8) return "CRITICAL";
        if (score >= 6) return "HIGH";
        if (score >= 3) return "MEDIUM";
        return "LOW";
    }

    private static ObjectNode downstreamJson(DownstreamRisk risk) {
        ObjectNode node = JSON.createObjectNode();
        node.put("repository", risk.repository());
        node.put("depth", risk.depth());
        node.put("coordinate", risk.coordinate());
        node.put("versionStatus", risk.versionStatus());
        node.put("usageGroups", risk.usageGroups());
        node.put("impactedTests", risk.impactedTests());
        node.put("confidence", risk.confidence());
        return node;
    }

    private record DownstreamRisk(String repository, int depth, String coordinate,
            String versionStatus, int usageGroups, int impactedTests, String confidence) {}
}
