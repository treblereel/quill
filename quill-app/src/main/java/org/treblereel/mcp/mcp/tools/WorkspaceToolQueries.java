package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

    ObjectNode enrichProjectDependencies(ObjectNode response, String consumerRepository) {
        return enrichment.enrichProjectDependencies(response, consumerRepository);
    }

    ObjectNode enrichClassDependencies(
            ObjectNode response,
            String consumerRepository,
            String target,
            String direction,
            int depth,
            boolean includeNodes,
            int limit,
            int offset,
            String cursor) {
        return enrichment.enrichClassDependencies(
                response,
                consumerRepository,
                target,
                direction,
                depth,
                includeNodes,
                limit,
                offset,
                cursor);
    }

    ObjectNode enrichDependencyBeans(ObjectNode response) {
        return enrichment.enrichDependencyBeans(response);
    }

    ObjectNode getCompactOverview(int limit, int offset) {
        return catalog.getCompactOverview(limit, offset);
    }

    ObjectNode listRepositories(boolean includeModules, int limit, int offset) {
        return catalog.listRepositories(includeModules, limit, offset);
    }

    ObjectNode getDependencies(
            String repository,
            String direction,
            boolean crossRepositoryOnly,
            int limit,
            int offset) {
        return catalog.getDependencies(repository, direction, crossRepositoryOnly, limit, offset);
    }

    ObjectNode resolveEntity(String target) {
        return catalog.resolveEntity(target);
    }

    ObjectNode findUsages(
            String target,
            String providerRepository,
            String usageKind,
            int limit,
            int consumerLimit,
            int offset) {
        return usages.findUsages(
                target, providerRepository, usageKind, limit, consumerLimit, offset);
    }

    ObjectNode findUsages(
            String target,
            String providerRepository,
            String usageKind,
            int limit,
            int consumerLimit,
            int offset,
            int consumerOffset) {
        return usages.findUsages(
                target,
                providerRepository,
                usageKind,
                limit,
                consumerLimit,
                offset,
                consumerOffset);
    }

    ObjectNode routeMissingUsages(
            ObjectNode response,
            String consumerRepository,
            String target,
            String usageKind,
            int limit,
            int offset) {
        return usages.routeMissingUsages(
                response, consumerRepository, target, usageKind, limit, offset);
    }

    ObjectNode routeMissingClassQuery(
            ObjectNode response,
            String consumerRepository,
            String target,
            String operation,
            Function<ReadQueryContext, ObjectNode> providerQuery,
            boolean withGraph) {
        return routing.routeMissingClassQuery(
                response, consumerRepository, target, operation, providerQuery, withGraph);
    }

    ObjectNode assessRisk(String target, String providerRepository, int maxDepth) {
        return risk.assessRisk(target, providerRepository, maxDepth);
    }
}
