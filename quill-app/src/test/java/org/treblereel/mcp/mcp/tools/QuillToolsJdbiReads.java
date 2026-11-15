package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.db.ReadQueryContext;

/** Test-only shortcuts that run one QuillToolQueries call against a fixture database. */
final class QuillToolsJdbiReads {

    private final QuillToolQueries queries = new QuillToolQueries();

    ObjectNode getBeans(
            Jdbi jdbi,
            String className,
            String scope,
            String kind,
            String profile,
            String qualifier) {
        return ReadQueryContext.read(
                jdbi,
                context -> queries.getBeans(context, className, scope, kind, profile, qualifier));
    }

    ObjectNode getBeans(
            Jdbi jdbi,
            String className,
            String scope,
            String kind,
            String profile,
            String qualifier,
            int limit) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.getBeans(
                                context, className, scope, kind, profile, qualifier, limit));
    }

    ObjectNode searchClasses(Jdbi jdbi, String pattern, int limit) {
        return ReadQueryContext.read(
                jdbi, context -> queries.searchClasses(context, pattern, limit));
    }

    ObjectNode getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        return ReadQueryContext.read(
                jdbi, context -> queries.getDependencies(context, target, direction, depth));
    }

    ObjectNode getDependencies(
            Jdbi jdbi,
            String target,
            String direction,
            int depth,
            boolean includeNodes,
            int limit,
            int offset,
            String cursor) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.getDependencies(
                                context,
                                target,
                                direction,
                                depth,
                                includeNodes,
                                limit,
                                offset,
                                cursor));
    }

    ObjectNode findImplementations(
            Jdbi jdbi,
            String target,
            boolean transitive,
            String module,
            String sourceSet,
            int limit,
            int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findImplementations(
                                context, target, transitive, module, sourceSet, limit, offset));
    }

    ObjectNode findUsages(
            Jdbi jdbi, String target, String usageKind, String module, int limit, int offset) {
        return ReadQueryContext.read(
                jdbi,
                context -> queries.findUsages(context, target, usageKind, module, limit, offset));
    }

    ObjectNode getSymbolDetails(
            Jdbi jdbi,
            String target,
            boolean includeMembers,
            String memberKind,
            int memberLimit,
            int memberOffset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.getSymbolDetails(
                                context,
                                target,
                                includeMembers,
                                memberKind,
                                memberLimit,
                                memberOffset));
    }

    ObjectNode findImpactedTests(
            Jdbi jdbi,
            List<String> targets,
            boolean transitive,
            int maxDepth,
            int limit,
            int offset) {
        return ReadQueryContext.readWithGraph(
                jdbi,
                context ->
                        queries.findImpactedTests(
                                context, targets, transitive, maxDepth, limit, offset));
    }

    ObjectNode getTypeHierarchy(
            Jdbi jdbi, String target, String direction, int maxDepth, int limit, int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.getTypeHierarchy(
                                context, target, direction, maxDepth, limit, offset));
    }

    ObjectNode searchSymbols(Jdbi jdbi, String pattern, String kind, int limit, int offset) {
        return ReadQueryContext.read(
                jdbi, context -> queries.searchSymbols(context, pattern, kind, limit, offset));
    }

    ObjectNode searchSymbols(
            Jdbi jdbi, String pattern, String kind, String language, int limit, int offset) {
        return ReadQueryContext.read(
                jdbi,
                context -> queries.searchSymbols(context, pattern, kind, language, limit, offset));
    }

    ObjectNode getCallHierarchy(
            Jdbi jdbi, String target, String method, String direction, int limit, int offset) {
        return ReadQueryContext.readWithGraph(
                jdbi,
                context ->
                        queries.getCallHierarchy(
                                context, target, method, direction, limit, offset));
    }

    ObjectNode getCallHierarchy(
            Jdbi jdbi,
            String target,
            String method,
            String direction,
            boolean transitive,
            int maxDepth,
            int limit,
            int offset) {
        return ReadQueryContext.readWithGraph(
                jdbi,
                context ->
                        queries.getCallHierarchy(
                                context,
                                target,
                                method,
                                direction,
                                transitive,
                                maxDepth,
                                limit,
                                offset));
    }

    ObjectNode findMethodOverrides(
            Jdbi jdbi,
            String target,
            String method,
            String signature,
            boolean transitive,
            int limit,
            int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findMethodOverrides(
                                context, target, method, signature, transitive, limit, offset));
    }

    ObjectNode findUnusedClasses(
            Jdbi jdbi,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findUnusedClasses(
                                context, module, includeGenerated, includeTests, limit, offset));
    }

    ObjectNode findUnusedMethods(
            Jdbi jdbi,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findUnusedMethods(
                                context, module, includeGenerated, includeTests, limit, offset));
    }

    ObjectNode findUnusedFields(
            Jdbi jdbi,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            boolean includeWriteOnly,
            int limit,
            int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findUnusedFields(
                                context,
                                module,
                                includeGenerated,
                                includeTests,
                                includeWriteOnly,
                                limit,
                                offset));
    }

    ObjectNode findEntryPoints(
            Jdbi jdbi,
            String kind,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findEntryPoints(
                                context,
                                kind,
                                module,
                                includeGenerated,
                                includeTests,
                                limit,
                                offset));
    }

    ObjectNode getModuleGraph(
            Jdbi jdbi, String module, String direction, int depth, int limit, int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.getModuleGraph(context, module, direction, depth, limit, offset));
    }

    ObjectNode findCycles(
            Jdbi jdbi,
            String scope,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findCycles(
                                context,
                                scope,
                                module,
                                includeGenerated,
                                includeTests,
                                limit,
                                offset));
    }

    ObjectNode compareIndex(Jdbi jdbi, Path projectRoot, String baseline, int detailLimit) {
        return ReadQueryContext.read(
                jdbi, context -> queries.compareIndex(context, projectRoot, baseline, detailLimit));
    }

    String getBuildStatus(Jdbi jdbi, Path projectRoot) {
        return ReadQueryContext.read(jdbi, context -> queries.getBuildStatus(context, projectRoot));
    }

    ObjectNode getBuildProblems(
            Path projectRoot, String severity, String module, int limit, int offset) {
        return queries.getBuildProblems(projectRoot, severity, module, limit, offset);
    }

    ObjectNode getFileProblems(
            Path projectRoot, List<String> paths, String severity, int limit, int offset) {
        return queries.getFileProblems(projectRoot, paths, severity, limit, offset);
    }

    ObjectNode getInjectionPoints(Jdbi jdbi, String target) {
        return ReadQueryContext.read(jdbi, context -> queries.getInjectionPoints(context, target));
    }

    ObjectNode getHotspots(Jdbi jdbi, int limit, String since) {
        return ReadQueryContext.read(jdbi, context -> queries.getHotspots(context, limit, since));
    }

    ObjectNode getHotspots(Jdbi jdbi, int limit, String since, boolean includeHistorical) {
        return ReadQueryContext.read(
                jdbi, context -> queries.getHotspots(context, limit, since, includeHistorical));
    }

    ObjectNode getFileHistory(Jdbi jdbi, String target, int limit) {
        return ReadQueryContext.read(
                jdbi, context -> queries.getFileHistory(context, target, limit));
    }

    ObjectNode getFileHistory(Jdbi jdbi, String target, int limit, int offset) {
        return ReadQueryContext.read(
                jdbi, context -> queries.getFileHistory(context, target, limit, offset));
    }

    ObjectNode resolveEntities(Jdbi jdbi, List<String> targets) {
        return ReadQueryContext.read(jdbi, context -> queries.resolveEntities(context, targets));
    }

    ObjectNode inspectServiceDescriptors(Jdbi jdbi, String service) {
        return ReadQueryContext.read(
                jdbi, context -> queries.inspectServiceDescriptors(context, service));
    }

    ObjectNode findConfigurationReferences(
            Jdbi jdbi,
            String key,
            String className,
            String kind,
            String module,
            int limit,
            int offset) {
        return ReadQueryContext.readWithGraph(
                jdbi,
                context ->
                        queries.findConfigurationReferences(
                                context, key, className, kind, module, limit, offset));
    }

    ObjectNode findResourceReferences(
            Jdbi jdbi, String path, String className, String module, int limit, int offset) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.findResourceReferences(
                                context, path, className, module, limit, offset));
    }

    ObjectNode getCoChanges(Jdbi jdbi, String target, int limit) {
        return ReadQueryContext.read(jdbi, context -> queries.getCoChanges(context, target, limit));
    }

    ObjectNode getRecentChanges(Jdbi jdbi, int commitCount) {
        return ReadQueryContext.read(
                jdbi, context -> queries.getRecentChanges(context, commitCount));
    }

    ObjectNode getRecentChanges(
            Jdbi jdbi, int commitCount, int fileLimit, int fileOffset, boolean details) {
        return ReadQueryContext.read(
                jdbi,
                context ->
                        queries.getRecentChanges(
                                context, commitCount, fileLimit, fileOffset, details));
    }

    ObjectNode getOverview(Jdbi jdbi) {
        return ReadQueryContext.read(jdbi, context -> queries.getOverview(context));
    }

    ObjectNode getRisk(Jdbi jdbi, String target) {
        return ReadQueryContext.read(jdbi, context -> queries.getRisk(context, target));
    }

    ObjectNode getContext(Jdbi jdbi, List<String> targets, boolean includeMembers, int limit) {
        return ReadQueryContext.readWithGraph(
                jdbi, context -> queries.getContext(context, targets, includeMembers, limit));
    }

    ObjectNode planChange(
            Jdbi jdbi, Path projectRoot, List<String> targets, String change, int limit) {
        return ReadQueryContext.readWithGraph(
                jdbi, context -> queries.planChange(context, projectRoot, targets, change, limit));
    }

    ObjectNode verifyChange(Jdbi jdbi, Path projectRoot, List<String> targets, int limit) {
        return ReadQueryContext.readWithGraph(
                jdbi, context -> queries.verifyChange(context, projectRoot, targets, limit));
    }

    ObjectNode changeSession(
            Jdbi jdbi, Path projectRoot, List<String> targets, String change, int limit) {
        return changeSession(jdbi, projectRoot, targets, change, limit, null);
    }

    ObjectNode changeSession(
            Jdbi jdbi,
            Path projectRoot,
            List<String> targets,
            String change,
            int limit,
            String checkout) {
        return ReadQueryContext.readWithGraph(
                jdbi,
                context ->
                        queries.changeSession(
                                context,
                                projectRoot,
                                targets,
                                change,
                                limit,
                                "full",
                                "all",
                                checkout));
    }

    ObjectNode getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        return ReadQueryContext.read(
                jdbi, context -> queries.getExternalDeps(context, target, library, limit));
    }
}
