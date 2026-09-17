package org.treblereel.mcp.mcp;

import org.jdbi.v3.core.Jdbi;

/** Routes tool calls to focused query components. */
public final class QuillToolQueries {

    private final StructureToolQueries structure = new StructureToolQueries();
    private final GitToolQueries git = new GitToolQueries();
    private final ProjectOverviewQueries overview = new ProjectOverviewQueries(git);
    private final ChangeRiskQueries risk = new ChangeRiskQueries();
    private final ExternalDependencyQueries externalDependencies =
            new ExternalDependencyQueries();

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier) {
        return structure.getBeans(jdbi, className, scope, kind, profile, qualifier, 50);
    }

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        return structure.searchClasses(jdbi, pattern, limit);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, int limit) {
        return structure.getBeans(jdbi, className, scope, kind, profile, qualifier, limit);
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        return structure.getDependencies(jdbi, target, direction, depth);
    }

    String getInjectionPoints(Jdbi jdbi, String target) {
        return structure.getInjectionPoints(jdbi, target);
    }

    String getHotspots(Jdbi jdbi, int limit, String since) {
        return git.getHotspots(jdbi, limit, since);
    }

    String getHotspots(
            Jdbi jdbi, int limit, String since, boolean includeHistorical) {
        return git.getHotspots(jdbi, limit, since, includeHistorical);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit) {
        return git.getFileHistory(jdbi, target, limit);
    }

    String getCoChanges(Jdbi jdbi, String target, int limit) {
        return git.getCoChanges(jdbi, target, limit);
    }

    String getRecentChanges(Jdbi jdbi, int commitCount) {
        return git.getRecentChanges(jdbi, commitCount);
    }

    String getOverview(Jdbi jdbi) {
        return overview.getOverview(jdbi);
    }

    String getRisk(Jdbi jdbi, String target) {
        return risk.getRisk(jdbi, target);
    }

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        return externalDependencies.getExternalDeps(jdbi, target, library, limit);
    }
}
