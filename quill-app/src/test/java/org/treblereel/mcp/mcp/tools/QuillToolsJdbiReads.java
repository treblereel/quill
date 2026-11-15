package org.treblereel.mcp.mcp.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.ReadQueryContext;

/** Test-only shortcuts that run one QuillToolQueries call against a fixture database. */
final class QuillToolsJdbiReads {

    private final QuillToolQueries queries = new QuillToolQueries();

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier) {
        return ReadQueryContext.read(jdbi, context -> queries.getBeans(context, className, scope, kind, profile, qualifier));
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, int limit) {
        return ReadQueryContext.read(jdbi, context -> queries.getBeans(context, className, scope, kind, profile, qualifier, limit));
    }

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        return ReadQueryContext.read(jdbi, context -> queries.searchClasses(context, pattern, limit));
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        return ReadQueryContext.read(jdbi, context -> queries.getDependencies(context, target, direction, depth));
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth,
            boolean includeNodes, int limit, int offset, String cursor) {
        return ReadQueryContext.read(jdbi, context -> queries.getDependencies(context, target, direction, depth,
                includeNodes, limit, offset, cursor));
    }

    String findImplementations(Jdbi jdbi, String target, boolean transitive,
            String module, String sourceSet, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findImplementations(
                context, target, transitive, module, sourceSet, limit, offset));
    }

    String findUsages(Jdbi jdbi, String target, String usageKind,
            String module, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findUsages(context, target, usageKind, module, limit, offset));
    }

    String getSymbolDetails(Jdbi jdbi, String target, boolean includeMembers,
            String memberKind, int memberLimit, int memberOffset) {
        return ReadQueryContext.read(jdbi, context -> queries.getSymbolDetails(
                context, target, includeMembers, memberKind, memberLimit, memberOffset));
    }

    String findImpactedTests(Jdbi jdbi, List<String> targets,
            boolean transitive, int maxDepth, int limit, int offset) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.findImpactedTests(context, targets, transitive, maxDepth, limit, offset));
    }

    String getTypeHierarchy(Jdbi jdbi, String target, String direction,
            int maxDepth, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.getTypeHierarchy(context, target, direction, maxDepth, limit, offset));
    }

    String searchSymbols(Jdbi jdbi, String pattern, String kind, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.searchSymbols(context, pattern, kind, limit, offset));
    }

    String searchSymbols(Jdbi jdbi, String pattern, String kind, String language,
            int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.searchSymbols(context, pattern, kind, language, limit, offset));
    }

    String getCallHierarchy(Jdbi jdbi, String target, String method,
            String direction, int limit, int offset) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.getCallHierarchy(context, target, method, direction, limit, offset));
    }

    String getCallHierarchy(Jdbi jdbi, String target, String method,
            String direction, boolean transitive, int maxDepth, int limit, int offset) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.getCallHierarchy(context, target, method, direction,
                transitive, maxDepth, limit, offset));
    }

    String findMethodOverrides(Jdbi jdbi, String target, String method,
            String signature, boolean transitive, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findMethodOverrides(
                context, target, method, signature, transitive, limit, offset));
    }

    String findUnusedClasses(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findUnusedClasses(
                context, module, includeGenerated, includeTests, limit, offset));
    }

    String findUnusedMethods(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findUnusedMethods(
                context, module, includeGenerated, includeTests, limit, offset));
    }

    String findUnusedFields(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, boolean includeWriteOnly, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findUnusedFields(context, module, includeGenerated,
                includeTests, includeWriteOnly, limit, offset));
    }

    String findEntryPoints(Jdbi jdbi, String kind, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findEntryPoints(context, kind, module,
                includeGenerated, includeTests, limit, offset));
    }

    String getModuleGraph(Jdbi jdbi, String module, String direction,
            int depth, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.getModuleGraph(context, module, direction, depth, limit, offset));
    }

    String findCycles(Jdbi jdbi, String scope, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findCycles(context, scope, module,
                includeGenerated, includeTests, limit, offset));
    }

    String compareIndex(Jdbi jdbi, Path projectRoot, String baseline, int detailLimit) {
        return ReadQueryContext.read(jdbi, context -> queries.compareIndex(context, projectRoot, baseline, detailLimit));
    }

    String getBuildStatus(Jdbi jdbi, Path projectRoot) {
        return ReadQueryContext.read(jdbi, context -> queries.getBuildStatus(context, projectRoot));
    }

    String getBuildProblems(Path projectRoot, String severity, String module,
            int limit, int offset) {
        return queries.getBuildProblems(projectRoot, severity, module, limit, offset);
    }

    String getFileProblems(Path projectRoot, List<String> paths, String severity,
            int limit, int offset) {
        return queries.getFileProblems(projectRoot, paths, severity, limit, offset);
    }

    String getInjectionPoints(Jdbi jdbi, String target) {
        return ReadQueryContext.read(jdbi, context -> queries.getInjectionPoints(context, target));
    }

    String getHotspots(Jdbi jdbi, int limit, String since) {
        return ReadQueryContext.read(jdbi, context -> queries.getHotspots(context, limit, since));
    }

    String getHotspots(Jdbi jdbi, int limit, String since, boolean includeHistorical) {
        return ReadQueryContext.read(jdbi, context -> queries.getHotspots(context, limit, since, includeHistorical));
    }

    String getFileHistory(Jdbi jdbi, String target, int limit) {
        return ReadQueryContext.read(jdbi, context -> queries.getFileHistory(context, target, limit));
    }

    String getFileHistory(Jdbi jdbi, String target, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.getFileHistory(context, target, limit, offset));
    }

    String resolveEntities(Jdbi jdbi, List<String> targets) {
        return ReadQueryContext.read(jdbi, context -> queries.resolveEntities(context, targets));
    }

    String inspectServiceDescriptors(Jdbi jdbi, String service) {
        return ReadQueryContext.read(jdbi, context -> queries.inspectServiceDescriptors(context, service));
    }

    String findConfigurationReferences(Jdbi jdbi, String key, String className,
            String kind, String module, int limit, int offset) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.findConfigurationReferences(
                context, key, className, kind, module, limit, offset));
    }

    String findResourceReferences(Jdbi jdbi, String path, String className,
            String module, int limit, int offset) {
        return ReadQueryContext.read(jdbi, context -> queries.findResourceReferences(context, path, className, module, limit, offset));
    }

    String getCoChanges(Jdbi jdbi, String target, int limit) {
        return ReadQueryContext.read(jdbi, context -> queries.getCoChanges(context, target, limit));
    }

    String getRecentChanges(Jdbi jdbi, int commitCount) {
        return ReadQueryContext.read(jdbi, context -> queries.getRecentChanges(context, commitCount));
    }

    String getRecentChanges(Jdbi jdbi, int commitCount, int fileLimit, int fileOffset,
            boolean details) {
        return ReadQueryContext.read(jdbi, context -> queries.getRecentChanges(context, commitCount, fileLimit, fileOffset, details));
    }

    String getOverview(Jdbi jdbi) {
        return ReadQueryContext.read(jdbi, context -> queries.getOverview(context));
    }

    String getRisk(Jdbi jdbi, String target) {
        return ReadQueryContext.read(jdbi, context -> queries.getRisk(context, target));
    }

    String getContext(Jdbi jdbi, List<String> targets, boolean includeMembers, int limit) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.getContext(context, targets, includeMembers, limit));
    }

    String planChange(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.planChange(context, projectRoot, targets, change, limit));
    }

    String verifyChange(Jdbi jdbi, Path projectRoot, List<String> targets, int limit) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.verifyChange(context, projectRoot, targets, limit));
    }

    String changeSession(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        return changeSession(jdbi, projectRoot, targets, change, limit, null);
    }

    String changeSession(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit, String checkout) {
        return ReadQueryContext.readWithGraph(jdbi, context -> queries.changeSession(context, projectRoot, targets, change, limit, "full", "all", checkout));
    }

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        return ReadQueryContext.read(jdbi, context -> queries.getExternalDeps(context, target, library, limit));
    }
}
