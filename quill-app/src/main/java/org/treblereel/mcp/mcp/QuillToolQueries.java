package org.treblereel.mcp.mcp;

import java.util.List;
import org.jdbi.v3.core.Jdbi;

/** Routes tool calls to focused query components. */
public final class QuillToolQueries {

    private final StructureToolQueries structure = new StructureToolQueries();
    private final UsageToolQueries usages = new UsageToolQueries();
    private final SymbolToolQueries symbols = new SymbolToolQueries();
    private final TestImpactQueries testImpact = new TestImpactQueries();
    private final TypeHierarchyQueries typeHierarchy = new TypeHierarchyQueries();
    private final SymbolSearchQueries symbolSearch = new SymbolSearchQueries();
    private final CallHierarchyQueries callHierarchy = new CallHierarchyQueries();
    private final MethodOverrideQueries methodOverrides = new MethodOverrideQueries();
    private final UnusedClassQueries unusedClasses = new UnusedClassQueries();
    private final UnusedMethodQueries unusedMethods = new UnusedMethodQueries();
    private final UnusedFieldQueries unusedFields = new UnusedFieldQueries();
    private final GitToolQueries git = new GitToolQueries();
    private final ProjectOverviewQueries overview = new ProjectOverviewQueries(git);
    private final ChangeRiskQueries risk = new ChangeRiskQueries();
    private final ExternalDependencyQueries externalDependencies =
            new ExternalDependencyQueries();
    private final ServiceDescriptorQueries serviceDescriptors = new ServiceDescriptorQueries();

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier) {
        return structure.getBeans(jdbi, className, scope, kind, profile, qualifier, 50);
    }

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        return structure.searchClasses(jdbi, pattern, null, null, limit);
    }

    String searchClasses(Jdbi jdbi, String pattern, String module, String sourceSet, int limit) {
        return structure.searchClasses(jdbi, pattern, module, sourceSet, limit);
    }

    String searchClasses(Jdbi jdbi, String pattern, String module, String sourceSet,
            int limit, int offset) {
        return structure.searchClasses(jdbi, pattern, module, sourceSet, limit, offset);
    }

    String getAnnotatedClasses(Jdbi jdbi, String annotation,
            boolean includeMetaAnnotations, int limit, int offset) {
        return structure.getAnnotatedClasses(
                jdbi, annotation, includeMetaAnnotations, limit, offset);
    }

    String findUsages(Jdbi jdbi, String target, String usageKind,
            String module, int limit, int offset) {
        return usages.findUsages(jdbi, target, usageKind, module, limit, offset);
    }

    String getSymbolDetails(Jdbi jdbi, String target, boolean includeMembers,
            String memberKind, int memberLimit, int memberOffset) {
        return symbols.getSymbolDetails(
                jdbi, target, includeMembers, memberKind, memberLimit, memberOffset);
    }

    String findImpactedTests(Jdbi jdbi, List<String> targets,
            boolean transitive, int maxDepth, int limit, int offset) {
        return testImpact.findImpactedTests(
                jdbi, targets, transitive, maxDepth, limit, offset);
    }

    String getTypeHierarchy(Jdbi jdbi, String target, String direction,
            int maxDepth, int limit, int offset) {
        return typeHierarchy.getTypeHierarchy(
                jdbi, target, direction, maxDepth, limit, offset);
    }

    String searchSymbols(Jdbi jdbi, String pattern, String kind, int limit, int offset) {
        return symbolSearch.searchSymbols(jdbi, pattern, kind, limit, offset);
    }

    String getCallHierarchy(Jdbi jdbi, String target, String method,
            String direction, int limit, int offset) {
        return callHierarchy.getCallHierarchy(jdbi, target, method, direction, limit, offset);
    }

    String findMethodOverrides(Jdbi jdbi, String target, String method,
            String signature, boolean transitive, int limit, int offset) {
        return methodOverrides.findMethodOverrides(
                jdbi, target, method, signature, transitive, limit, offset);
    }

    String findUnusedClasses(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        return unusedClasses.findUnusedClasses(
                jdbi, module, includeGenerated, includeTests, limit, offset);
    }

    String findUnusedMethods(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        return unusedMethods.findUnusedMethods(
                jdbi, module, includeGenerated, includeTests, limit, offset);
    }

    String findUnusedFields(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, boolean includeWriteOnly, int limit, int offset) {
        return unusedFields.findUnusedFields(jdbi, module, includeGenerated,
                includeTests, includeWriteOnly, limit, offset);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, int limit) {
        return structure.getBeans(jdbi, className, scope, kind, profile, qualifier, limit);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet, int limit) {
        return structure.getBeans(jdbi, className, scope, kind, profile, qualifier,
                module, sourceSet, limit);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, String module, String sourceSet,
            int limit, int offset) {
        return structure.getBeans(jdbi, className, scope, kind, profile, qualifier,
                module, sourceSet, limit, offset);
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        return structure.getDependencies(jdbi, target, direction, depth);
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth,
            boolean includeNodes, int limit, int offset, String cursor) {
        return structure.getDependencies(jdbi, target, direction, depth,
                includeNodes, limit, offset, cursor);
    }

    String findImplementations(Jdbi jdbi, String target, boolean transitive,
            String module, String sourceSet, int limit, int offset) {
        return structure.findImplementations(
                jdbi, target, transitive, module, sourceSet, limit, offset);
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

    String getHotspots(Jdbi jdbi, int limit, int offset, String since,
            boolean includeHistorical) {
        return git.getHotspots(jdbi, limit, offset, since, includeHistorical);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit) {
        return git.getFileHistory(jdbi, target, limit);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit, int offset) {
        return git.getFileHistory(jdbi, target, limit, offset);
    }

    String resolveEntities(Jdbi jdbi, List<String> targets) {
        return git.resolveEntities(jdbi, targets);
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

    String getOverview(Jdbi jdbi, boolean details) {
        return overview.getOverview(jdbi, details);
    }

    String getRisk(Jdbi jdbi, String target) {
        return risk.getRisk(jdbi, target);
    }

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        return externalDependencies.getExternalDeps(jdbi, target, library, limit);
    }

    String inspectServiceDescriptors(Jdbi jdbi, String service) {
        return serviceDescriptors.inspect(jdbi, service);
    }
}
