package org.treblereel.mcp.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.index.BuildProblemInspector;
import org.treblereel.mcp.index.BuildStatusInspector;
import org.treblereel.mcp.mcp.change.ChangePlanQueries;
import org.treblereel.mcp.mcp.change.ChangeRiskQueries;
import org.treblereel.mcp.mcp.change.ChangeSessionQueries;
import org.treblereel.mcp.mcp.change.ChangeVerificationQueries;
import org.treblereel.mcp.mcp.change.ContextQueries;
import org.treblereel.mcp.mcp.change.DesignImpactQueries;
import org.treblereel.mcp.mcp.change.TestImpactQueries;
import org.treblereel.mcp.mcp.config.ConfigurationReferenceQueries;
import org.treblereel.mcp.mcp.project.VerificationPlanQueries;
import org.treblereel.mcp.mcp.query.AnnotatedSymbolQueries;
import org.treblereel.mcp.mcp.query.ArchitectureViolationQueries;
import org.treblereel.mcp.mcp.query.CallHierarchyQueries;
import org.treblereel.mcp.mcp.query.CycleQueries;
import org.treblereel.mcp.mcp.query.EntryPointQueries;
import org.treblereel.mcp.mcp.query.ExecutionOrderQueries;
import org.treblereel.mcp.mcp.query.ExternalDependencyQueries;
import org.treblereel.mcp.mcp.query.FrameworkEndpointQueries;
import org.treblereel.mcp.mcp.query.GitToolQueries;
import org.treblereel.mcp.mcp.query.IndexComparisonQueries;
import org.treblereel.mcp.mcp.query.MethodOverrideQueries;
import org.treblereel.mcp.mcp.query.ModuleGraphQueries;
import org.treblereel.mcp.mcp.query.PackageGraphQueries;
import org.treblereel.mcp.mcp.query.ProjectOverviewQueries;
import org.treblereel.mcp.mcp.query.ResourceReferenceQueries;
import org.treblereel.mcp.mcp.query.ServiceDescriptorQueries;
import org.treblereel.mcp.mcp.query.StateLifecycleQueries;
import org.treblereel.mcp.mcp.query.StructureToolQueries;
import org.treblereel.mcp.mcp.query.SymbolSearchQueries;
import org.treblereel.mcp.mcp.query.SymbolToolQueries;
import org.treblereel.mcp.mcp.query.SymbolUsageQueries;
import org.treblereel.mcp.mcp.query.TypeHierarchyQueries;
import org.treblereel.mcp.mcp.query.UnusedClassQueries;
import org.treblereel.mcp.mcp.query.UnusedFieldQueries;
import org.treblereel.mcp.mcp.query.UnusedMethodQueries;
import org.treblereel.mcp.mcp.query.UsageToolQueries;
import org.treblereel.mcp.mcp.support.ProjectAvailabilityResponses;
import org.treblereel.mcp.mcp.support.ToolResponseSupport;

/** Routes tool calls to focused query components. */
public final class QuillToolQueries {

    private final StructureToolQueries structure = new StructureToolQueries();
    private final UsageToolQueries usages = new UsageToolQueries();
    private final SymbolToolQueries symbols = new SymbolToolQueries();
    private final TestImpactQueries testImpact = new TestImpactQueries();
    private final ContextQueries context = new ContextQueries();
    private final ChangePlanQueries changePlan = new ChangePlanQueries();
    private final ChangeVerificationQueries changeVerification = new ChangeVerificationQueries();
    private final ChangeSessionQueries changeSession = new ChangeSessionQueries();
    private final TypeHierarchyQueries typeHierarchy = new TypeHierarchyQueries();
    private final SymbolSearchQueries symbolSearch = new SymbolSearchQueries();
    private final AnnotatedSymbolQueries annotatedSymbols = new AnnotatedSymbolQueries();
    private final FrameworkEndpointQueries frameworkEndpoints = new FrameworkEndpointQueries();
    private final SymbolUsageQueries symbolUsages = new SymbolUsageQueries();
    private final CallHierarchyQueries callHierarchy = new CallHierarchyQueries();
    private final MethodOverrideQueries methodOverrides = new MethodOverrideQueries();
    private final UnusedClassQueries unusedClasses = new UnusedClassQueries();
    private final UnusedMethodQueries unusedMethods = new UnusedMethodQueries();
    private final UnusedFieldQueries unusedFields = new UnusedFieldQueries();
    private final EntryPointQueries entryPoints = new EntryPointQueries();
    private final ModuleGraphQueries moduleGraph = new ModuleGraphQueries();
    private final PackageGraphQueries packageGraph = new PackageGraphQueries();
    private final ArchitectureViolationQueries architectureViolations =
            new ArchitectureViolationQueries();
    private final CycleQueries cycles = new CycleQueries();
    private final IndexComparisonQueries indexComparison = new IndexComparisonQueries();
    private final GitToolQueries git = new GitToolQueries();
    private final ProjectOverviewQueries overview = new ProjectOverviewQueries(git);
    private final ChangeRiskQueries risk = new ChangeRiskQueries();
    private final ExternalDependencyQueries externalDependencies = new ExternalDependencyQueries();
    private final ServiceDescriptorQueries serviceDescriptors = new ServiceDescriptorQueries();
    private final ConfigurationReferenceQueries configurationReferences =
            new ConfigurationReferenceQueries();
    private final StateLifecycleQueries stateLifecycle = new StateLifecycleQueries();
    private final ExecutionOrderQueries executionOrder = new ExecutionOrderQueries();
    private final DesignImpactQueries designImpact = new DesignImpactQueries();
    private final ResourceReferenceQueries resourceReferences = new ResourceReferenceQueries();

    ObjectNode getBeans(
            ReadQueryContext reader,
            String className,
            String scope,
            String kind,
            String profile,
            String qualifier) {
        return structure.getBeans(reader, className, scope, kind, profile, qualifier, 50);
    }

    ObjectNode searchClasses(ReadQueryContext reader, String pattern, int limit) {
        return structure.searchClasses(reader, pattern, null, null, limit);
    }

    ObjectNode searchClasses(
            ReadQueryContext reader, String pattern, String module, String sourceSet, int limit) {
        return structure.searchClasses(reader, pattern, module, sourceSet, limit);
    }

    ObjectNode searchClasses(
            ReadQueryContext reader,
            String pattern,
            String module,
            String sourceSet,
            int limit,
            int offset) {
        return structure.searchClasses(reader, pattern, module, sourceSet, limit, offset);
    }

    ObjectNode getAnnotatedClasses(
            ReadQueryContext reader,
            String annotation,
            boolean includeMetaAnnotations,
            int limit,
            int offset) {
        return structure.getAnnotatedClasses(
                reader, annotation, includeMetaAnnotations, limit, offset);
    }

    ObjectNode findUsages(
            ReadQueryContext reader,
            String target,
            String usageKind,
            String module,
            int limit,
            int offset) {
        return usages.findUsages(reader, target, usageKind, module, limit, offset);
    }

    ObjectNode getSymbolDetails(
            ReadQueryContext reader,
            String target,
            boolean includeMembers,
            String memberKind,
            int memberLimit,
            int memberOffset) {
        return symbols.getSymbolDetails(
                reader, target, includeMembers, memberKind, memberLimit, memberOffset);
    }

    ObjectNode findImpactedTests(
            ReadQueryContext reader,
            List<String> targets,
            boolean transitive,
            int maxDepth,
            int limit,
            int offset) {
        return testImpact.findImpactedTests(reader, targets, transitive, maxDepth, limit, offset);
    }

    ObjectNode getContext(
            ReadQueryContext reader, List<String> targets, boolean includeMembers, int limit) {
        return context.getContext(reader, targets, includeMembers, limit);
    }

    ObjectNode getContext(
            ReadQueryContext reader,
            List<String> targets,
            boolean includeMembers,
            int limit,
            String view) {
        return context.getContext(reader, targets, includeMembers, limit, view);
    }

    ObjectNode planChange(
            ReadQueryContext reader,
            Path projectRoot,
            List<String> targets,
            String change,
            int limit) {
        return changePlan.planChange(reader, projectRoot, targets, change, limit);
    }

    ObjectNode verifyChange(
            ReadQueryContext reader, Path projectRoot, List<String> targets, int limit) {
        return changeVerification.verifyChange(reader, projectRoot, targets, limit);
    }

    ObjectNode changeSession(
            ReadQueryContext reader,
            Path projectRoot,
            List<String> targets,
            String change,
            int limit,
            String detail,
            String view,
            String checkout) {
        return changeSession.snapshot(
                reader, projectRoot, targets, change, limit, detail, view, checkout);
    }

    ObjectNode getTypeHierarchy(
            ReadQueryContext reader,
            String target,
            String direction,
            int maxDepth,
            int limit,
            int offset) {
        return typeHierarchy.getTypeHierarchy(reader, target, direction, maxDepth, limit, offset);
    }

    ObjectNode searchSymbols(
            ReadQueryContext reader, String pattern, String kind, int limit, int offset) {
        return symbolSearch.searchSymbols(reader, pattern, kind, limit, offset);
    }

    ObjectNode searchSymbols(
            ReadQueryContext reader,
            String pattern,
            String kind,
            String language,
            int limit,
            int offset) {
        return symbolSearch.searchSymbols(reader, pattern, kind, language, limit, offset);
    }

    ObjectNode findAnnotatedSymbols(
            ReadQueryContext reader,
            String annotation,
            String kind,
            boolean includeMetaAnnotations,
            int limit,
            int offset) {
        return annotatedSymbols.findAnnotatedSymbols(
                reader, annotation, kind, includeMetaAnnotations, limit, offset);
    }

    ObjectNode findFrameworkEndpoints(
            ReadQueryContext reader,
            String framework,
            String httpMethod,
            String pathPrefix,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return frameworkEndpoints.findFrameworkEndpoints(
                reader,
                framework,
                httpMethod,
                pathPrefix,
                module,
                includeGenerated,
                includeTests,
                limit,
                offset);
    }

    ObjectNode findSymbolUsages(
            ReadQueryContext reader,
            String target,
            String name,
            String kind,
            String signature,
            String access,
            int limit,
            int offset) {
        return symbolUsages.findSymbolUsages(
                reader, target, name, kind, signature, access, limit, offset);
    }

    ObjectNode getCallHierarchy(
            ReadQueryContext reader,
            String target,
            String method,
            String direction,
            int limit,
            int offset) {
        return callHierarchy.getCallHierarchy(
                reader, target, method, null, direction, false, 1, limit, offset);
    }

    ObjectNode getCallHierarchy(
            ReadQueryContext reader,
            String target,
            String method,
            String direction,
            boolean transitive,
            int maxDepth,
            int limit,
            int offset) {
        return getCallHierarchy(
                reader, target, method, null, direction, transitive, maxDepth, limit, offset);
    }

    ObjectNode getCallHierarchy(
            ReadQueryContext reader,
            String target,
            String method,
            String signature,
            String direction,
            boolean transitive,
            int maxDepth,
            int limit,
            int offset) {
        return callHierarchy.getCallHierarchy(
                reader, target, method, signature, direction, transitive, maxDepth, limit, offset);
    }

    ObjectNode traceStateLifecycle(ReadQueryContext reader, String target, int evidenceLimit) {
        return stateLifecycle.trace(reader, target, evidenceLimit);
    }

    ObjectNode analyzeExecutionOrder(
            ReadQueryContext reader, String target, String method, String signature) {
        return executionOrder.analyze(reader, target, method, signature);
    }

    ObjectNode analyzeExecutionOrder(
            ReadQueryContext reader,
            String target,
            String method,
            String signature,
            Set<String> beforeTerms,
            Set<String> afterTerms) {
        return executionOrder.analyze(reader, target, method, signature, beforeTerms, afterTerms);
    }

    ObjectNode compareDesignImpact(
            ReadQueryContext reader, List<String> candidates, int testDepth) {
        return designImpact.compare(reader, candidates, testDepth);
    }

    ObjectNode getCallHierarchy(
            ReadQueryContext reader,
            String target,
            String method,
            String signature,
            String direction,
            boolean transitive,
            int maxDepth,
            String scope,
            int limit,
            int offset) {
        return callHierarchy.getCallHierarchy(
                reader,
                target,
                method,
                signature,
                direction,
                transitive,
                maxDepth,
                scope,
                limit,
                offset);
    }

    ObjectNode findMethodOverrides(
            ReadQueryContext reader,
            String target,
            String method,
            String signature,
            boolean transitive,
            int limit,
            int offset) {
        return methodOverrides.findMethodOverrides(
                reader, target, method, signature, transitive, limit, offset);
    }

    ObjectNode findUnusedClasses(
            ReadQueryContext reader,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return unusedClasses.findUnusedClasses(
                reader, module, includeGenerated, includeTests, limit, offset);
    }

    ObjectNode findUnusedMethods(
            ReadQueryContext reader,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return unusedMethods.findUnusedMethods(
                reader, module, includeGenerated, includeTests, limit, offset);
    }

    ObjectNode findUnusedFields(
            ReadQueryContext reader,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            boolean includeWriteOnly,
            int limit,
            int offset) {
        return unusedFields.findUnusedFields(
                reader, module, includeGenerated, includeTests, includeWriteOnly, limit, offset);
    }

    ObjectNode findEntryPoints(
            ReadQueryContext reader,
            String kind,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return entryPoints.findEntryPoints(
                reader, kind, module, includeGenerated, includeTests, limit, offset);
    }

    ObjectNode findEntryPoints(
            ReadQueryContext reader,
            String kind,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset,
            String eventType,
            String detail) {
        return entryPoints.findEntryPoints(
                reader,
                kind,
                module,
                includeGenerated,
                includeTests,
                limit,
                offset,
                eventType,
                detail);
    }

    ObjectNode getModuleGraph(
            ReadQueryContext reader,
            String module,
            String direction,
            int depth,
            int limit,
            int offset) {
        return moduleGraph.getModuleGraph(reader, module, direction, depth, limit, offset);
    }

    ObjectNode getPackageGraph(
            ReadQueryContext reader,
            String packageName,
            String direction,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return packageGraph.getPackageGraph(
                reader,
                packageName,
                direction,
                module,
                includeGenerated,
                includeTests,
                limit,
                offset);
    }

    ObjectNode findArchitectureViolations(
            ReadQueryContext reader,
            String scope,
            String from,
            List<String> forbidden,
            List<String> dependencyKinds,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return architectureViolations.findArchitectureViolations(
                reader,
                scope,
                from,
                forbidden,
                dependencyKinds,
                includeGenerated,
                includeTests,
                limit,
                offset);
    }

    ObjectNode findCycles(
            ReadQueryContext reader,
            String scope,
            String module,
            boolean includeGenerated,
            boolean includeTests,
            int limit,
            int offset) {
        return cycles.findCycles(
                reader, scope, module, includeGenerated, includeTests, limit, offset);
    }

    ObjectNode compareIndex(
            ReadQueryContext reader, Path projectRoot, String baseline, int detailLimit) {
        return indexComparison.compareIndex(reader, projectRoot, baseline, detailLimit);
    }

    String getBuildStatus(ReadQueryContext reader, Path projectRoot) {
        return getBuildStatus(reader, projectRoot, null);
    }

    String getBuildStatus(ReadQueryContext reader, Path projectRoot, String module) {
        try {
            var json = Json.MAPPER;
            var result =
                    (com.fasterxml.jackson.databind.node.ObjectNode)
                            json.readTree(
                                    BuildStatusInspector.inspect(
                                            projectRoot,
                                            reader,
                                            module == null ? null : List.of(module)));
            if (module != null
                    && result.path("compiled_outputs").path("count").asInt() == 0
                    && result.path("java_compiler_settings").isEmpty())
                return ToolResponseSupport.errorResponse("Unknown module: " + module);
            var diagnostics = BuildProblemInspector.inspect(projectRoot, "all", module, 20, 0);
            ProjectAvailabilityResponses.appendBuildGuidance(result, diagnostics);
            if (result.path("action").asText().equals("build")
                    || result.path("action").asText().equals("fix_build")) {
                var contexts = json.createArrayNode();
                result.path("compiled_outputs")
                        .path("stale_modules")
                        .forEach(
                                staleModule ->
                                        contexts.addObject()
                                                .putObject("resolution")
                                                .put("module", staleModule.asText()));
                result.set(
                        "verification_plan",
                        new VerificationPlanQueries()
                                .plan(reader, projectRoot, contexts, json.createObjectNode()));
                if (result.path("action").asText().equals("fix_build"))
                    result.set("diagnostics", diagnostics);
            }
            return result.toString();
        } catch (java.io.IOException malformed) {
            throw new IllegalStateException(malformed);
        }
    }

    ObjectNode getBuildProblems(
            Path projectRoot, String severity, String module, int limit, int offset) {
        return BuildProblemInspector.inspect(projectRoot, severity, module, limit, offset);
    }

    ObjectNode getFileProblems(
            Path projectRoot, List<String> paths, String severity, int limit, int offset) {
        return BuildProblemInspector.inspect(projectRoot, severity, null, paths, limit, offset);
    }

    ObjectNode getBeans(
            ReadQueryContext reader,
            String className,
            String scope,
            String kind,
            String profile,
            String qualifier,
            int limit) {
        return structure.getBeans(reader, className, scope, kind, profile, qualifier, limit);
    }

    ObjectNode getBeans(
            ReadQueryContext reader,
            String className,
            String scope,
            String kind,
            String profile,
            String qualifier,
            String module,
            String sourceSet,
            int limit) {
        return structure.getBeans(
                reader, className, scope, kind, profile, qualifier, module, sourceSet, limit);
    }

    ObjectNode getBeans(
            ReadQueryContext reader,
            String className,
            String scope,
            String kind,
            String profile,
            String qualifier,
            String module,
            String sourceSet,
            int limit,
            int offset) {
        return structure.getBeans(
                reader, className, scope, kind, profile, qualifier, module, sourceSet, limit,
                offset);
    }

    ObjectNode getBeans(
            ReadQueryContext reader,
            String className,
            String scope,
            String kind,
            String profile,
            String qualifier,
            String module,
            String sourceSet,
            String origin,
            int limit,
            int offset) {
        return structure.getBeans(
                reader, className, scope, kind, profile, qualifier, module, sourceSet, origin,
                limit, offset);
    }

    ObjectNode getDependencies(
            ReadQueryContext reader, String target, String direction, int depth) {
        return structure.getDependencies(reader, target, direction, depth);
    }

    ObjectNode getDependencies(
            ReadQueryContext reader,
            String target,
            String direction,
            int depth,
            boolean includeNodes,
            int limit,
            int offset,
            String cursor) {
        return structure.getDependencies(
                reader, target, direction, depth, includeNodes, limit, offset, cursor);
    }

    ObjectNode findImplementations(
            ReadQueryContext reader,
            String target,
            boolean transitive,
            String module,
            String sourceSet,
            int limit,
            int offset) {
        return structure.findImplementations(
                reader, target, transitive, module, sourceSet, limit, offset);
    }

    ObjectNode getInjectionPoints(ReadQueryContext reader, String target) {
        return structure.getInjectionPoints(reader, target);
    }

    ObjectNode getHotspots(ReadQueryContext reader, int limit, String since) {
        return git.getHotspots(reader, limit, since);
    }

    ObjectNode getHotspots(
            ReadQueryContext reader, int limit, String since, boolean includeHistorical) {
        return git.getHotspots(reader, limit, since, includeHistorical);
    }

    ObjectNode getHotspots(
            ReadQueryContext reader,
            int limit,
            int offset,
            String since,
            boolean includeHistorical) {
        return git.getHotspots(reader, limit, offset, since, includeHistorical);
    }

    ObjectNode getFileHistory(ReadQueryContext reader, String target, int limit) {
        return git.getFileHistory(reader, target, limit);
    }

    ObjectNode getFileHistory(ReadQueryContext reader, String target, int limit, int offset) {
        return git.getFileHistory(reader, target, limit, offset);
    }

    ObjectNode resolveEntities(ReadQueryContext reader, List<String> targets) {
        return git.resolveEntities(reader, targets);
    }

    ObjectNode getCoChanges(ReadQueryContext reader, String target, int limit) {
        return git.getCoChanges(reader, target, limit);
    }

    ObjectNode getRecentChanges(ReadQueryContext reader, int commitCount) {
        return git.getRecentChanges(reader, commitCount);
    }

    ObjectNode getRecentChanges(
            ReadQueryContext reader,
            int commitCount,
            int fileLimit,
            int fileOffset,
            boolean details) {
        return git.getRecentChanges(reader, commitCount, fileLimit, fileOffset, details);
    }

    ObjectNode getOverview(ReadQueryContext reader) {
        return overview.getOverview(reader);
    }

    ObjectNode getOverview(ReadQueryContext reader, boolean details) {
        return overview.getOverview(reader, details);
    }

    ObjectNode getRisk(ReadQueryContext reader, String target) {
        return risk.getRisk(reader, target);
    }

    ObjectNode getExternalDeps(ReadQueryContext reader, String target, String library, int limit) {
        return externalDependencies.getExternalDeps(reader, target, library, limit);
    }

    ObjectNode inspectServiceDescriptors(ReadQueryContext reader, String service) {
        return serviceDescriptors.inspect(reader, service);
    }

    ObjectNode findConfigurationReferences(
            ReadQueryContext reader,
            String key,
            String className,
            String kind,
            String module,
            int limit,
            int offset) {
        return configurationReferences.find(reader, key, className, kind, module, limit, offset);
    }

    ObjectNode findConfigurationReferences(
            ReadQueryContext reader,
            String key,
            String className,
            String kind,
            String module,
            int limit,
            int offset,
            String view) {
        return configurationReferences.find(
                reader, key, className, kind, module, limit, offset, view);
    }

    ObjectNode findResourceReferences(
            ReadQueryContext reader,
            String path,
            String className,
            String module,
            int limit,
            int offset) {
        return resourceReferences.find(reader, path, className, module, limit, offset);
    }
}
