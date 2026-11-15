package org.treblereel.mcp.mcp.tools;

import java.util.function.Function;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.project.ProjectRegistry;

/** Entry point for the workspace tools; each concern lives in its own Workspace* class. */
final class WorkspaceToolQueries {

    private final WorkspaceCatalogQueries catalog;
    private final WorkspaceEnrichment enrichment;
    private final WorkspaceUsageQueries usages;
    private final WorkspaceRouting routing;
    private final WorkspaceRiskQueries risk;

    WorkspaceToolQueries(ProjectRegistry registry) {
        WorkspaceProviders providers = new WorkspaceProviders(registry);
        this.catalog = new WorkspaceCatalogQueries(registry);
        this.enrichment = new WorkspaceEnrichment(registry, providers);
        this.usages = new WorkspaceUsageQueries(registry, providers);
        this.routing = new WorkspaceRouting(registry, providers);
        this.risk = new WorkspaceRiskQueries(registry, providers);
    }

    String enrichProjectDependencies(String response, String consumerRepository) {
        return enrichment.enrichProjectDependencies(response, consumerRepository);
    }

    String enrichClassDependencies(String response, String consumerRepository, String target,
            String direction, int depth, boolean includeNodes, int limit, int offset,
            String cursor) {
        return enrichment.enrichClassDependencies(response, consumerRepository, target,
                direction, depth, includeNodes, limit, offset, cursor);
    }

    String enrichDependencyBeans(String response) {
        return enrichment.enrichDependencyBeans(response);
    }

    String getCompactOverview(int limit, int offset) {
        return catalog.getCompactOverview(limit, offset);
    }

    String listRepositories(boolean includeModules, int limit, int offset) {
        return catalog.listRepositories(includeModules, limit, offset);
    }

    String getDependencies(String repository, String direction, boolean crossRepositoryOnly,
            int limit, int offset) {
        return catalog.getDependencies(repository, direction, crossRepositoryOnly, limit, offset);
    }

    String resolveEntity(String target) {
        return catalog.resolveEntity(target);
    }

    String findUsages(String target, String providerRepository, String usageKind,
            int limit, int consumerLimit, int offset) {
        return usages.findUsages(target, providerRepository, usageKind, limit, consumerLimit, offset);
    }

    String findUsages(String target, String providerRepository, String usageKind,
            int limit, int consumerLimit, int offset, int consumerOffset) {
        return usages.findUsages(target, providerRepository, usageKind, limit, consumerLimit,
                offset, consumerOffset);
    }

    String routeMissingUsages(String response, String consumerRepository, String target,
            String usageKind, int limit, int offset) {
        return usages.routeMissingUsages(response, consumerRepository, target, usageKind, limit, offset);
    }

    String routeMissingClassQuery(String response, String consumerRepository, String target,
            String operation, Function<ReadQueryContext, String> providerQuery, boolean withGraph) {
        return routing.routeMissingClassQuery(response, consumerRepository, target, operation,
                providerQuery, withGraph);
    }

    String assessRisk(String target, String providerRepository, int maxDepth) {
        return risk.assessRisk(target, providerRepository, maxDepth);
    }
}
