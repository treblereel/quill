package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jdbi.v3.core.Jdbi;

/** MCP-facing facade that routes requests and delegates query construction. */
public final class QuillTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> NOT_FOUND_PREFIXES = Set.of(
            "{\"error\":\"Class not found\"",
            "{\"error\":\"Annotation not found\"",
            "{\"error\":\"Not a bean:");

    private final ProjectRegistry registry;
    private final ProjectQueryExecutor executor;
    private final QuillToolQueries queries;
    private final ProjectDependencyQueries projectDependencies;
    private final FileNavigationQueries fileNavigation;
    private final WorktreeStatusQueries worktreeStatus;
    private final PositionSymbolQueries positionSymbols;
    private final ExternalSymbolQueries externalSymbols;
    private final WorkspaceToolQueries workspace;

    public QuillTools() {
        this(new ProjectRegistry());
    }

    public QuillTools(ProjectRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = new ProjectQueryExecutor(registry);
        this.queries = new QuillToolQueries();
        this.projectDependencies = new ProjectDependencyQueries();
        this.fileNavigation = new FileNavigationQueries();
        this.worktreeStatus = new WorktreeStatusQueries();
        this.positionSymbols = new PositionSymbolQueries();
        this.externalSymbols = new ExternalSymbolQueries();
        this.workspace = new WorkspaceToolQueries(registry);
    }

    @Tool(readOnly = true, structured = true, description = "List workspace repos.")
    public String list_workspace_repositories(
            @ToolArg(description = "Include modules") Optional<Boolean> include_modules,
            @ToolArg(description = "Page size") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset) {
        return ResponseBudget.apply(workspace.listRepositories(include_modules.orElse(false),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Workspace dependencies.")
    public String get_workspace_dependencies(
            @ToolArg(description = "Repository") Optional<String> repository,
            @ToolArg(description = "Direction") Optional<String> direction,
            @ToolArg(description = "Cross-repo") Optional<Boolean> cross_repository_only,
            @ToolArg(description = "Page size") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset) {
        return ResponseBudget.apply(workspace.getDependencies(repository.orElse(null),
                direction.orElse("both"), cross_repository_only.orElse(true),
                clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Resolve workspace entity.")
    public String resolve_workspace_entity(
            @ToolArg(description = "Name, path, GA, or class") String target) {
        return ResponseBudget.apply(workspace.resolveEntity(target));
    }

    @Tool(readOnly = true, structured = true, description = "Find workspace usages.")
    public String find_workspace_usages(
            @ToolArg(description = "Target") String target,
            @ToolArg(description = "Provider repository") Optional<String> provider_repository,
            @ToolArg(description = "Usage kind") Optional<String> usage_kind,
            @ToolArg(description = "Consumer repository page size") Optional<Integer> limit,
            @ToolArg(description = "Usage groups returned per consumer (default: 20, max: 100)") Optional<Integer> consumer_limit,
            @ToolArg(description = "Offset") Optional<Integer> offset) {
        return ResponseBudget.apply(workspace.findUsages(target,
                provider_repository.orElse(null), usage_kind.orElse(null),
                clamp(limit.orElse(20), 1, 100),
                clamp(consumer_limit.orElse(20), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Assess cross-repo change risk.")
    public String assess_workspace_change_risk(
            @ToolArg(description = "Class or source path") String target,
            @ToolArg(description = "Provider repository") Optional<String> provider_repository,
            @ToolArg(description = "Dependency depth") Optional<Integer> max_depth) {
        return ResponseBudget.apply(workspace.assessRisk(target,
                provider_repository.orElse(null), clamp(max_depth.orElse(3), 1, 10)));
    }

    @Tool(readOnly = true, structured = true, description = "Find CDI/Spring beans.")
    public String list_beans(
            @ToolArg(description = "Class, path, or wildcard") Optional<String> class_name,
            @ToolArg(description = "Scope") Optional<String> scope,
            @ToolArg(description = "Bean kind") Optional<String> kind,
            @ToolArg(description = "Build profile") Optional<String> profile,
            @ToolArg(description = "Qualifier") Optional<String> qualifier,
            @ToolArg(description = "Module") Optional<String> module,
            @ToolArg(description = "Source set") Optional<String> source_set,
            @ToolArg(description = "all, application, or dependency") Optional<String> origin,
            @ToolArg(description = "Page size") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> workspace.enrichDependencyBeans(
                queries.getBeans(p.jdbi(), class_name.orElse(null), scope.orElse(null),
                        kind.orElse(null), profile.orElse(null), qualifier.orElse(null),
                        module.orElse(null), source_set.orElse(null), origin.orElse("all"),
                        clamp(limit.orElse(50), 1, 100),
                        clamp(offset.orElse(0), 0, Integer.MAX_VALUE))));
    }

    @Tool(readOnly = true, structured = true, description = "Get class dependency metrics and graph.")
    public String get_dependencies(
            @ToolArg(description = "Class or source path") String target,
            @ToolArg(description = "inbound, outbound, or both") Optional<String> direction,
            @ToolArg(description = "Traversal depth") Optional<Integer> depth,
            @ToolArg(description = "Include graph nodes") Optional<Boolean> include_nodes,
            @ToolArg(description = "Page size") Optional<Integer> limit,
            @ToolArg(description = "Offset for depth 1") Optional<Integer> offset,
            @ToolArg(description = "Cursor for depth >1") Optional<String> cursor,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        String requestedDirection = direction.orElse("both");
        int requestedDepth = clamp(depth.orElse(1), 1, 5);
        boolean requestedNodes = include_nodes.orElse(true);
        int requestedLimit = clamp(limit.orElse(50), 1, 200);
        int requestedOffset = clamp(offset.orElse(0), 0, Integer.MAX_VALUE);
        String requestedCursor = cursor.orElse(null);
        return forAllProjects(project.orElse(null), p -> workspace.enrichClassDependencies(
                queries.getDependencies(p.jdbi(), target, requestedDirection, requestedDepth,
                        requestedNodes, requestedLimit, requestedOffset, requestedCursor),
                p.name(), target, requestedDirection, requestedDepth, requestedNodes,
                requestedLimit, requestedOffset, requestedCursor));
    }

    @Tool(readOnly = true, structured = true, description = "Find indexed implementations and their origins.")
    public String find_implementations(
            @ToolArg(description = "Current class or interface name (short or FQCN), or its source path") String target,
            @ToolArg(description = "Include indirect implementations through intermediate types (default: true)") Optional<Boolean> transitive,
            @ToolArg(description = "Logical implementation classes per page (default: 50, max: 100)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        boolean requestedTransitive = transitive.orElse(true);
        int requestedLimit = clamp(limit.orElse(50), 1, 100);
        int requestedOffset = clamp(offset.orElse(0), 0, Integer.MAX_VALUE);
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "find_implementations",
                jdbi -> queries.findImplementations(jdbi, target, requestedTransitive,
                        null, null, requestedLimit, requestedOffset)));
    }

    @Tool(readOnly = true, structured = true, description = "Find class usages from bytecode, DI, inheritance, annotations, and ServiceLoader evidence.")
    public String find_usages(
            @ToolArg(description = "Current class name (short or FQCN), or its source path") String target,
            @ToolArg(description = "Usage kind; omit or use all for every supported kind") Optional<String> usage_kind,
            @ToolArg(description = "Module path filter relative to the project root") Optional<String> module,
            @ToolArg(description = "Usage groups per page (default: 50, max: 200)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        String requestedKind = usage_kind.orElse(null);
        int requestedLimit = clamp(limit.orElse(50), 1, 200);
        int requestedOffset = clamp(offset.orElse(0), 0, Integer.MAX_VALUE);
        return forAllProjects(project.orElse(null), p -> workspace.routeMissingUsages(
                queries.findUsages(p.jdbi(), target, requestedKind, module.orElse(null),
                        requestedLimit, requestedOffset),
                p.name(), target, requestedKind, requestedLimit, requestedOffset));
    }

    @Tool(readOnly = true, structured = true, output = "symbol_usages",
            description = "Find exact method, constructor, or field usages.")
    public String find_symbol_usages(
            @ToolArg(description = "Class, path, or symbol_id") String target,
            @ToolArg(description = "Member name; omit for symbol_id or constructor") Optional<String> name,
            @ToolArg(description = "Kind; omit for symbol_id",
                    allowed = {"method", "constructor", "field"}) Optional<String> kind,
            @ToolArg(description = "Exact source signature or JVM descriptor; required when overloaded") Optional<String> signature,
            @ToolArg(description = "Field access",
                    allowed = {"all", "read", "write"}) Optional<String> access,
            @ToolArg(description = "Usage groups per page (default: 50, max: 200)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        String requestedName = name.orElse(null);
        String requestedSignature = signature.orElse(null);
        String requestedAccess = access.orElse("all");
        int requestedLimit = clamp(limit.orElse(50), 1, 200);
        int requestedOffset = clamp(offset.orElse(0), 0, Integer.MAX_VALUE);
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "find_symbol_usages",
                jdbi -> queries.findSymbolUsages(jdbi, target, requestedName,
                        kind.orElse(null),
                        requestedSignature, requestedAccess, requestedLimit, requestedOffset)));
    }

    @Tool(readOnly = true, structured = true, output = "symbol_details",
            description = "Inspect a class and its members.")
    public String get_symbol_details(
            @ToolArg(description = "Class or source path") String target,
            @ToolArg(description = "Include members; default true") Optional<Boolean> include_members,
            @ToolArg(description = "Member kind or all") Optional<String> member_kind,
            @ToolArg(description = "Page size; default 100") Optional<Integer> member_limit,
            @ToolArg(description = "Page offset") Optional<Integer> member_offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        boolean requestedMembers = include_members.orElse(true);
        String requestedKind = member_kind.orElse(null);
        int requestedLimit = clamp(member_limit.orElse(100), 1, 200);
        int requestedOffset = clamp(member_offset.orElse(0), 0, Integer.MAX_VALUE);
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "get_symbol_details",
                jdbi -> queries.getSymbolDetails(jdbi, target, requestedMembers,
                        requestedKind, requestedLimit, requestedOffset)));
    }

    @Tool(readOnly = true, structured = true,
            description = "Get a compact change-ready context card for one or more classes.")
    public String get_context(
            @ToolArg(description = "Classes or source paths (max 10)") List<String> targets,
            @ToolArg(description = "Include declared members; default false") Optional<Boolean> include_members,
            @ToolArg(description = "Members, usages, and tests per section; default 10, max 25") Optional<Integer> limit,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        boolean requestedMembers = include_members.orElse(false);
        int requestedLimit = clamp(limit.orElse(10), 1, 25);
        return forAllProjects(project.orElse(null), p -> queries.getContext(
                p.jdbi(), targets, requestedMembers, requestedLimit));
    }

    @Tool(readOnly = true, structured = true,
            description = "Build an evidence-backed change plan with files, dependency review, tests, risk, freshness warnings, and non-executing verification commands.")
    public String plan_change(
            @ToolArg(description = "Classes or source paths to change (max 10)") List<String> targets,
            @ToolArg(description = "Concise description of the intended change") String change,
            @ToolArg(description = "Members, usages, and tests per section; default 10, max 25") Optional<Integer> limit,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        int requestedLimit = clamp(limit.orElse(10), 1, 25);
        return forAllProjects(project.orElse(null), p -> queries.planChange(
                p.jdbi(), p.root(), targets, change, requestedLimit));
    }

    @Tool(readOnly = true, structured = true,
            description = "Verify a change from worktree, build, diagnostics, affected-test, and index-freshness evidence; recommend compile or test commands without running them.")
    public String verify_change(
            @ToolArg(description = "Changed classes or source paths; omit to infer up to 10 dirty JVM files") Optional<List<String>> targets,
            @ToolArg(description = "Changes, diagnostics, and tests per section; default 20, max 100") Optional<Integer> limit,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        int requestedLimit = clamp(limit.orElse(20), 1, 100);
        return forAllProjects(project.orElse(null), p -> queries.verifyChange(
                p.jdbi(), p.root(), targets.orElse(List.of()), requestedLimit));
    }

    @Tool(readOnly = true, structured = true,
            description = "Plan a code change before editing, or check what to do next after edits/builds. Returns a compact stateless snapshot with the primary action, evidence gaps, phase gate and verification receipt; never runs builds.")
    public String change_session(
            @ToolArg(description = "Classes or source paths; omit to infer up to 10 dirty JVM files") Optional<List<String>> targets,
            @ToolArg(description = "Concise description of the intended change") String change,
            @ToolArg(description = "Response detail: summary (default) or full") Optional<String> detail,
            @ToolArg(description = "View: auto (default), plan, verification, or all") Optional<String> view,
            @ToolArg(description = "Evidence per section; default 20, max 100") Optional<Integer> limit,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        int requestedLimit = clamp(limit.orElse(20), 1, 100);
        return forAllProjects(project.orElse(null), p -> queries.changeSession(
                p.jdbi(), p.root(), targets.orElse(List.of()), change, requestedLimit,
                detail.orElse("summary"), view.orElse("auto")));
    }

    @Tool(readOnly = true, structured = true, description = "Rank tests affected by changed classes.")
    public String find_impacted_tests(
            @ToolArg(description = "Classes or source paths") List<String> targets,
            @ToolArg(description = "Traverse dependencies; default true") Optional<Boolean> transitive,
            @ToolArg(description = "Traversal depth; default 3") Optional<Integer> max_depth,
            @ToolArg(description = "Page size; default 100") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findImpactedTests(
                p.jdbi(), targets, transitive.orElse(true),
                clamp(max_depth.orElse(3), 1, 5),
                clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Inspect ancestor and descendant paths for a type.")
    public String get_type_hierarchy(
            @ToolArg(description = "Current class, interface, or source path") String target,
            @ToolArg(description = "ancestors, descendants, or both; default both") Optional<String> direction,
            @ToolArg(description = "Maximum hierarchy depth; default 5") Optional<Integer> max_depth,
            @ToolArg(description = "Page size; default 100") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        String requestedDirection = direction.orElse("both");
        int requestedDepth = clamp(max_depth.orElse(5), 1, 20);
        int requestedLimit = clamp(limit.orElse(100), 1, 200);
        int requestedOffset = clamp(offset.orElse(0), 0, Integer.MAX_VALUE);
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "get_type_hierarchy",
                jdbi -> queries.getTypeHierarchy(jdbi, target, requestedDirection,
                        requestedDepth, requestedLimit, requestedOffset)));
    }

    @Tool(readOnly = true, structured = true, output = "symbol_search",
            description = "Search indexed symbols.")
    public String search_symbols(
            @ToolArg(description = "Name or signature pattern; * is a wildcard") String pattern,
            @ToolArg(description = "Symbol kind", allowed = {"all", "class", "interface",
                    "annotation", "enum", "record", "field", "constructor", "method"}) Optional<String> kind,
            @ToolArg(description = "Language",
                    allowed = {"all", "java", "kotlin"}) Optional<String> language,
            @ToolArg(description = "Page size; default 50") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.searchSymbols(
                p.jdbi(), pattern, kind.orElse(null), language.orElse(null),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, output = "call_hierarchy",
            description = "Inspect bytecode callers and callees.")
    public String get_call_hierarchy(
            @ToolArg(description = "Class, path, or symbol_id") String target,
            @ToolArg(description = "Method; omit for symbol_id; <init> for constructors") Optional<String> method,
            @ToolArg(description = "Exact indexed signature or JVM descriptor for overload selection") Optional<String> signature,
            @ToolArg(description = "Direction",
                    allowed = {"inbound", "outbound", "both"}) Optional<String> direction,
            @ToolArg(description = "Traverse calls; default false") Optional<Boolean> transitive,
            @ToolArg(description = "Traversal depth; default 3") Optional<Integer> max_depth,
            @ToolArg(description = "Noise scope",
                    allowed = {"all", "cross_class", "cross_package"}) Optional<String> scope,
            @ToolArg(description = "Page size; default 100") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        String requestedMethod = method.orElse(null);
        String requestedSignature = signature.orElse(null);
        String requestedDirection = direction.orElse("both");
        boolean requestedTransitive = transitive.orElse(false);
        int requestedDepth = clamp(max_depth.orElse(3), 1, 8);
        int requestedLimit = clamp(limit.orElse(100), 1, 200);
        int requestedOffset = clamp(offset.orElse(0), 0, Integer.MAX_VALUE);
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "get_call_hierarchy",
                jdbi -> queries.getCallHierarchy(jdbi, target, requestedMethod,
                        requestedSignature, requestedDirection, requestedTransitive,
                        requestedDepth, scope.orElse("all"), requestedLimit, requestedOffset)));
    }

    @Tool(readOnly = true, structured = true,
            description = "Trace state lifecycle.")
    public String trace_state_lifecycle(
            @ToolArg(description = "Class or source path") String target,
            @ToolArg(description = "Row limit") Optional<Integer> evidence_limit,
            @ToolArg(description = "Repo") Optional<String> project) {
        int requestedLimit = clamp(evidence_limit.orElse(50), 1, 200);
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "trace_state_lifecycle",
                jdbi -> queries.traceStateLifecycle(jdbi, target, requestedLimit)));
    }

    @Tool(readOnly = true, structured = true, output = "execution_order",
            description = "Analyze call instruction order in one method.")
    public String analyze_execution_order(
            @ToolArg(description = "Class, path, or symbol_id") String target,
            @ToolArg(description = "Method; omit for symbol_id; <init> for constructors") Optional<String> method,
            @ToolArg(description = "Signature or JVM descriptor for overloads") Optional<String> signature,
            @ToolArg(description = "Before terms") Optional<String> before_terms,
            @ToolArg(description = "After terms") Optional<String> after_terms,
            @ToolArg(description = "Repo") Optional<String> project) {
        Set<String> before = semanticTerms(before_terms.orElse(null),
                Set.of("persist", "save", "store", "repository", "persistence",
                        "entitymanager", "dao"));
        Set<String> after = semanticTerms(after_terms.orElse(null),
                Set.of("dispatch", "submit", "publish", "send", "enqueue", "worker",
                        "invoke", "execute"));
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "analyze_execution_order",
                jdbi -> queries.analyzeExecutionOrder(jdbi, target, method.orElse(null),
                        signature.orElse(null), before, after)));
    }

    @Tool(readOnly = true, structured = true, description = "Compare design-host change surfaces.")
    public String compare_design_impact(
            @ToolArg(description = "Candidate classes") List<String> candidates,
            @ToolArg(description = "Test depth") Optional<Integer> test_depth,
            @ToolArg(description = "Repo") Optional<String> project) {
        int requestedDepth = clamp(test_depth.orElse(3), 1, 5);
        return forAllProjects(project.orElse(null), p ->
                queries.compareDesignImpact(p.jdbi(), candidates, requestedDepth));
    }

    @Tool(readOnly = true, structured = true, output = "method_overrides",
            description = "Find method overrides in indexed descendants.")
    public String find_method_overrides(
            @ToolArg(description = "Class, interface, path, or symbol_id") String target,
            @ToolArg(description = "Declared method; omit for symbol_id") Optional<String> method,
            @ToolArg(description = "Optional exact signature to select one overload") Optional<String> signature,
            @ToolArg(description = "Include indirect descendants; default true") Optional<Boolean> transitive,
            @ToolArg(description = "Page size; default 50") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        String requestedSignature = signature.orElse(null);
        boolean requestedTransitive = transitive.orElse(true);
        int requestedLimit = clamp(limit.orElse(50), 1, 200);
        int requestedOffset = clamp(offset.orElse(0), 0, Integer.MAX_VALUE);
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "find_method_overrides",
                jdbi -> queries.findMethodOverrides(jdbi, target, method.orElse(null),
                        requestedSignature, requestedTransitive, requestedLimit,
                        requestedOffset)));
    }

    @Tool(readOnly = true, structured = true, description = "Find conservative candidates for unused indexed classes; results are not proof of dead code.")
    public String find_unused_classes(
            @ToolArg(description = "Module path filter; omit for all modules") Optional<String> module,
            @ToolArg(description = "Include generated classes; default false") Optional<Boolean> include_generated,
            @ToolArg(description = "Include test classes; default false") Optional<Boolean> include_tests,
            @ToolArg(description = "Page size; default 50") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findUnusedClasses(
                p.jdbi(), module.orElse(null), include_generated.orElse(false),
                include_tests.orElse(false), clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find private methods without indexed inbound calls; results are conservative dead-code candidates.")
    public String find_unused_methods(
            @ToolArg(description = "Module path filter; omit for all modules") Optional<String> module,
            @ToolArg(description = "Include generated classes; default false") Optional<Boolean> include_generated,
            @ToolArg(description = "Include test classes; default false") Optional<Boolean> include_tests,
            @ToolArg(description = "Page size; default 50") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findUnusedMethods(
                p.jdbi(), module.orElse(null), include_generated.orElse(false),
                include_tests.orElse(false), clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find private fields without indexed reads; write-only fields are opt-in and results are conservative dead-code candidates.")
    public String find_unused_fields(
            @ToolArg(description = "Module path filter; omit for all modules") Optional<String> module,
            @ToolArg(description = "Include generated classes; default false") Optional<Boolean> include_generated,
            @ToolArg(description = "Include test classes; default false") Optional<Boolean> include_tests,
            @ToolArg(description = "Include fields that are written but never read; default false") Optional<Boolean> include_write_only,
            @ToolArg(description = "Page size; default 50") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findUnusedFields(
                p.jdbi(), module.orElse(null), include_generated.orElse(false),
                include_tests.orElse(false), include_write_only.orElse(false),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find statically identifiable application and framework entry points with detection evidence.")
    public String find_entry_points(
            @ToolArg(description = "Kind: main, rest_resource, rest_endpoint, observer, scheduled, message_consumer, annotation_processor, service_provider, or all") Optional<String> kind,
            @ToolArg(description = "Module path filter; omit for all modules") Optional<String> module,
            @ToolArg(description = "Include generated classes; default false") Optional<Boolean> include_generated,
            @ToolArg(description = "Include test classes; default false") Optional<Boolean> include_tests,
            @ToolArg(description = "Page size; default 100") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findEntryPoints(
                p.jdbi(), kind.orElse(null), module.orElse(null),
                include_generated.orElse(false), include_tests.orElse(false),
                clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Inspect direct project-module dependencies and transitive classpath visibility.")
    public String get_module_graph(
            @ToolArg(description = "Module path relative to the project root; omit for the complete direct-dependency graph") Optional<String> module,
            @ToolArg(description = "For a selected module: inbound, outbound, or both (default: both)") Optional<String> direction,
            @ToolArg(description = "Maximum stored visibility distance (default: 2, max: 10)") Optional<Integer> depth,
            @ToolArg(description = "Relations per page (default: 100, max: 200)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getModuleGraph(
                p.jdbi(), module.orElse(null), direction.orElse("both"),
                clamp(depth.orElse(2), 1, 10), clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true,
            description = "List artifacts visible to main or test code from captured classpaths and reactor inference without invoking a build; reports evidence and completeness.")
    public String get_project_dependencies(
            @ToolArg(description = "Module path relative to the project root; omit for all") Optional<String> module,
            @ToolArg(description = "Classpath source set: main or test; omit for both") Optional<String> source_set,
            @ToolArg(description = "Substring of group, artifact, version, or JAR name") Optional<String> query,
            @ToolArg(description = "Directness filter: direct, transitive, mixed, or unknown") Optional<String> directness,
            @ToolArg(description = "Group-id substring") Optional<String> group,
            @ToolArg(description = "Artifact-id substring") Optional<String> artifact,
            @ToolArg(description = "Declared Maven scope or Gradle configuration") Optional<String> scope,
            @ToolArg(description = "Page size; default 100, max 200") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p ->
                workspace.enrichProjectDependencies(projectDependencies.getProjectDependencies(
                        p.jdbi(), p.root(), module.orElse(null),
                        source_set.orElse(null),
                        query.orElse(null), directness.orElse(null), group.orElse(null),
                        artifact.orElse(null), scope.orElse(null),
                        clamp(limit.orElse(100), 1, 200),
                        clamp(offset.orElse(0), 0, Integer.MAX_VALUE)), p.name()));
    }

    @Tool(readOnly = true, structured = true,
            description = "List the indexed project tree with module, source-set, lifecycle, and worktree context.")
    public String list_project_tree(
            @ToolArg(description = "Repository-relative directory; default project root") Optional<String> path,
            @ToolArg(description = "Tree depth; default 2, max 10") Optional<Integer> depth,
            @ToolArg(description = "Include deleted or historical indexed paths; default false") Optional<Boolean> include_deleted,
            @ToolArg(description = "Entries per page; default 100, max 500") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> fileNavigation.listProjectTree(
                p.jdbi(), path.orElse("."), clamp(depth.orElse(2), 1, 10),
                include_deleted.orElse(false), clamp(limit.orElse(100), 1, 500),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true,
            description = "Search indexed file paths with module, kind, lifecycle, and worktree context.")
    public String search_files(
            @ToolArg(description = "Path substring or glob pattern supporting *, **, and ?") String pattern,
            @ToolArg(description = "Exact module path; omit for all") Optional<String> module,
            @ToolArg(description = "Exact indexed kind, e.g. java, kotlin, or resource") Optional<String> kind,
            @ToolArg(description = "Repository-relative directory prefix; omit for the project root") Optional<String> directory,
            @ToolArg(description = "File extension with or without a leading dot, e.g. java") Optional<String> extension,
            @ToolArg(description = "Include deleted or historical indexed paths; default false") Optional<Boolean> include_deleted,
            @ToolArg(description = "Results per page; default 50, max 200") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> fileNavigation.searchFiles(
                p.jdbi(), pattern, module.orElse(null), kind.orElse(null),
                directory.orElse(null), extension.orElse(null),
                include_deleted.orElse(false), clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true,
            description = "Inspect the live Git branch, indexed versus current commit, and dirty worktree files.")
    public String get_worktree_status(
            @ToolArg(description = "Status filter: added, modified, deleted, untracked, or conflicting") Optional<String> status,
            @ToolArg(description = "Results per page; default 100, max 500") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> worktreeStatus.getWorktreeStatus(
                p.jdbi(), p.root(), status.orElse(null),
                clamp(limit.orElse(100), 1, 500),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, output = "position_symbol",
            description = "Resolve the Java or Kotlin identifier at a live source position to indexed declarations.")
    public String get_symbol_at_position(
            @ToolArg(description = "Repository-relative Java or Kotlin source path") String path,
            @ToolArg(description = "One-based source line") Integer line,
            @ToolArg(description = "One-based source column") Integer column,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> positionSymbols.getSymbolAtPosition(
                p.jdbi(), p.root(), path, line, column));
    }

    @Tool(readOnly = true, structured = true,
            description = "Search class and member declarations indexed from external dependency bytecode.")
    public String search_external_symbols(
            @ToolArg(description = "Symbol name or signature pattern; supports * wildcard") String pattern,
            @ToolArg(description = "Kind: class, interface, annotation, enum, record, field, constructor, method, or all") Optional<String> kind,
            @ToolArg(description = "External class short name or FQCN; required for field, constructor, and method searches") Optional<String> class_name,
            @ToolArg(description = "Dependency package prefix or wildcard") Optional<String> library,
            @ToolArg(description = "Match mode: exact, prefix, or contains (default)") Optional<String> match_mode,
            @ToolArg(description = "Include full dependency JAR paths; default false") Optional<Boolean> include_occurrences,
            @ToolArg(description = "Results per page; default 50, max 200") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> externalSymbols.search(
                p.jdbi(), p.root(), pattern, kind.orElse(null), class_name.orElse(null),
                library.orElse(null), match_mode.orElse("contains"),
                include_occurrences.orElse(false),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true,
            description = "Inspect an external dependency class and its bytecode-indexed members.")
    public String get_external_symbol_details(
            @ToolArg(description = "External class short name or FQCN") String class_name,
            @ToolArg(description = "Members per page; default 100, max 200") Optional<Integer> limit,
            @ToolArg(description = "Member page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> externalSymbols.details(
                p.jdbi(), p.root(), class_name, clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Inspect package dependency coupling.")
    public String get_package_graph(
            @ToolArg(description = "Package name/suffix; omit for all") Optional<String> package_name,
            @ToolArg(description = "inbound, outbound, or both") Optional<String> direction,
            @ToolArg(description = "Module filter") Optional<String> module,
            @ToolArg(description = "Include generated classes") Optional<Boolean> include_generated,
            @ToolArg(description = "Include test classes") Optional<Boolean> include_tests,
            @ToolArg(description = "Page size; default 100") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getPackageGraph(
                p.jdbi(), package_name.orElse(null), direction.orElse("both"),
                module.orElse(null), include_generated.orElse(false),
                include_tests.orElse(false), clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find package or module dependency violations.")
    public String find_architecture_violations(
            @ToolArg(description = "package (default) or module") Optional<String> scope,
            @ToolArg(description = "Source glob; '..' includes subpackages") String from,
            @ToolArg(description = "Forbidden target globs") List<String> forbidden,
            @ToolArg(description = "Comma-separated dependency kinds") Optional<String> kinds,
            @ToolArg(description = "Page size") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findArchitectureViolations(
                p.jdbi(), scope.orElse("package"), from, forbidden,
                kinds.map(value -> java.util.Arrays.stream(value.split(","))
                                .map(String::strip).filter(item -> !item.isEmpty()).toList())
                        .orElse(List.of()), false, false, clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find class or module dependency cycles.")
    public String find_cycles(
            @ToolArg(description = "class (default) or module") Optional<String> scope,
            @ToolArg(description = "Class-scope module filter") Optional<String> module,
            @ToolArg(description = "Include generated classes") Optional<Boolean> include_generated,
            @ToolArg(description = "Include test classes") Optional<Boolean> include_tests,
            @ToolArg(description = "Page size; default 20") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findCycles(
                p.jdbi(), scope.orElse("class"), module.orElse(null),
                include_generated.orElse(false), include_tests.orElse(false),
                clamp(limit.orElse(20), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Compare the active index with an earlier local generation.")
    public String compare_index(
            @ToolArg(description = "Index id or commit; default previous") Optional<String> baseline,
            @ToolArg(description = "Details per category; default 50") Optional<Integer> limit,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.compareIndex(
                p.jdbi(), p.root(), baseline.orElse(null),
                clamp(limit.orElse(50), 1, 200)));
    }

    @Tool(readOnly = true, structured = true, description = "Inspect build integration and compiled-index freshness without running a build.")
    public String get_build_status(
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.getBuildStatus(p.jdbi(), p.root()));
    }

    @Tool(readOnly = true, structured = true, description = "Read errors captured by the last Maven or Gradle build.")
    public String get_build_problems(
            @ToolArg(description = "Severity: all, error, or warning") Optional<String> severity,
            @ToolArg(description = "Exact module filter") Optional<String> module,
            @ToolArg(description = "Limit; max 200") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getBuildProblems(
                p.root(), severity.orElse("all"), module.orElse(null),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true,
            description = "Read last-build diagnostics for one or more repository-relative source files without running a build.")
    public String get_file_problems(
            @ToolArg(description = "Repository-relative source paths") List<String> paths,
            @ToolArg(description = "Severity: all, error, or warning") Optional<String> severity,
            @ToolArg(description = "Limit; max 200") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        if (paths.isEmpty()) return errorResponse("paths must not be empty");
        return forAllProjects(project.orElse(null), p -> queries.getFileProblems(
                p.root(), paths, severity.orElse("all"),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Inspect bean injection resolution and candidates.")
    public String list_injection_points(
            @ToolArg(description = "Bean class name (short or FQCN)") String target,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "list_injection_points",
                jdbi -> queries.getInjectionPoints(jdbi, target)));
    }

    @Tool(readOnly = true, structured = true, description = "Rank files/classes by Git churn with lifecycle, authors, dates, and worktree changes.")
    public String find_git_hotspots(
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Only commits after this date, ISO format YYYY-MM-DD") Optional<String> since,
            @ToolArg(description = "Include deleted/historical paths (default: false)") Optional<Boolean> include_historical,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getHotspots(p.jdbi(),
                clamp(limit.orElse(10), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE), since.orElse(null),
                include_historical.orElse(false)));
    }

    @Tool(readOnly = true, structured = true, description = "Get commit history for one current class or file, "
            + "including author labels/emails and explicit returned-window counts.")
    public String get_file_history(
            @ToolArg(description = "Class name (short or FQCN), project path, or repository path") String target,
            @ToolArg(description = "Max commits to return (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getFileHistory(
                p.jdbi(), target, clamp(limit.orElse(10), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Resolve up to 20 names/paths across the current tree and Git history, including deleted paths.")
    public String resolve_entities(
            @ToolArg(description = "Class names, file names, or repository paths to resolve") List<String> targets,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.resolveEntities(p.jdbi(), targets));
    }

    @Tool(readOnly = true, structured = true, description = "Find files that frequently change with a class/path and report coupling ratios.")
    public String find_co_changed_files(
            @ToolArg(description = "Class name (short or FQCN), project path, or repository path") String target,
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getCoChanges(
                p.jdbi(), target, clamp(limit.orElse(10), 1, 100)));
    }

    @Tool(readOnly = true, structured = true, description = "Get recent commits and their changed files/classes.")
    public String get_recent_changes(
            @ToolArg(description = "Number of recent commits to inspect (default: 10)") Optional<Integer> commits,
            @ToolArg(description = "Changed files per page (default: 200, max: 200)") Optional<Integer> file_limit,
            @ToolArg(description = "Changed-file offset (default: 0)") Optional<Integer> file_offset,
            @ToolArg(description = "Include commit and resolved-class details (default: true)") Optional<Boolean> details,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getRecentChanges(
                p.jdbi(), clamp(commits.orElse(10), 1, 200),
                clamp(file_limit.orElse(200), 1, 200),
                clamp(file_offset.orElse(0), 0, Integer.MAX_VALUE),
                details.orElse(true)));
    }

    @Tool(readOnly = true, structured = true, description = "Inspect ordered META-INF/services providers with lines and order-sensitivity analysis.")
    public String inspect_service_descriptors(
            @ToolArg(description = "Optional service FQCN or short name, e.g. javax.annotation.processing.Processor") Optional<String> service,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.inspectServiceDescriptors(p.jdbi(), service.orElse(null)));
    }

    @Tool(readOnly = true, structured = true, description = "Find configuration definitions and annotation/programmatic consumers without exposing values.")
    public String find_configuration_references(
            @ToolArg(description = "Exact key or * wildcard; omit for all") Optional<String> key,
            @ToolArg(description = "Consumer class FQCN or short name") Optional<String> class_name,
            @ToolArg(description = "Kind: all, property, prefix, or persistence") Optional<String> kind,
            @ToolArg(description = "Exact module filter") Optional<String> module,
            @ToolArg(description = "Limit; max 200") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findConfigurationReferences(
                p.jdbi(), key.orElse(null), class_name.orElse(null), kind.orElse("all"),
                module.orElse(null), clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find classpath resources and programmatic consumers without reading resource contents.")
    public String find_resource_references(
            @ToolArg(description = "Resource path or * wildcard, e.g. templates/order.html") String path,
            @ToolArg(description = "Consumer class FQCN or short name") Optional<String> class_name,
            @ToolArg(description = "Exact module filter") Optional<String> module,
            @ToolArg(description = "Limit; max 200") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findResourceReferences(
                p.jdbi(), path, class_name.orElse(null), module.orElse(null),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, output = "overview",
            description = "Start code analysis here: orient on indexed projects, frameworks, architecture hubs, DI problems, Git activity and freshness. For planning or checking a code change, follow with change_session when available.")
    public String get_overview(
            @ToolArg(description = "Include diagnostic samples and all hub rankings (default: false)") Optional<Boolean> details,
            @ToolArg(description = "Project to query; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.getOverview(p.jdbi(), details.orElse(false)));
    }

    @Tool(readOnly = true, structured = true, description = "Search current classes by wildcard name with source, origin, module, and bean context.")
    public String search_classes(
            @ToolArg(description = "Class name pattern (supports * wildcard, e.g. '*Service', 'io.casehub.*.model.*')") String pattern,
            @ToolArg(description = "Module path filter relative to the project root, or '.' for the root module") Optional<String> module,
            @ToolArg(description = "Source set filter, e.g. main or test") Optional<String> source_set,
            @ToolArg(description = "Max results (default: 30)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.searchClasses(
                p.jdbi(), pattern, module.orElse(null), source_set.orElse(null),
                clamp(limit.orElse(30), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find classes carrying an annotation directly or "
            + "through a resolvable meta-annotation.")
    public String get_annotated_classes(
            @ToolArg(description = "Annotation short name or FQCN, with optional @ prefix") String annotation,
            @ToolArg(description = "Include meta-annotation matches (default: true)") Optional<Boolean> include_meta_annotations,
            @ToolArg(description = "Max results (default: 50)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getAnnotatedClasses(
                p.jdbi(), annotation, include_meta_annotations.orElse(true),
                clamp(limit.orElse(50), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find annotated type, method, field, and constructor declarations.")
    public String find_annotated_symbols(
            @ToolArg(description = "Annotation short name or FQCN, with optional @ prefix") String annotation,
            @ToolArg(description = "Kind: all, type, method, field, constructor, or parameter") Optional<String> kind,
            @ToolArg(description = "Include type meta-annotation matches (default: true)") Optional<Boolean> include_meta_annotations,
            @ToolArg(description = "Max results (default: 50, max: 200)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findAnnotatedSymbols(
                p.jdbi(), annotation, kind.orElse("all"),
                include_meta_annotations.orElse(true),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Find Spring MVC and JAX-RS routes.")
    public String find_framework_endpoints(
            @ToolArg(description = "spring, jax-rs, or all") Optional<String> framework,
            @ToolArg(description = "HTTP method") Optional<String> http_method,
            @ToolArg(description = "Route path prefix") Optional<String> path_prefix,
            @ToolArg(description = "Module filter") Optional<String> module,
            @ToolArg(description = "Include generated") Optional<Boolean> include_generated,
            @ToolArg(description = "Include tests") Optional<Boolean> include_tests,
            @ToolArg(description = "Limit; max 100") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findFrameworkEndpoints(
                p.jdbi(), framework.orElse(null), http_method.orElse(null),
                path_prefix.orElse(null), module.orElse(null),
                include_generated.orElse(false), include_tests.orElse(false),
                clamp(limit.orElse(50), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(readOnly = true, structured = true, description = "Score class/file change risk from coupling, criticality, churn, bus factor, and fan-in/out.")
    public String assess_change_risk(
            @ToolArg(description = "Class name (short or FQCN), or any project/repository file path") String target,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> routeClassQuery(
                p, target, "assess_change_risk",
                jdbi -> queries.getRisk(jdbi, target)));
    }

    @Tool(readOnly = true, structured = true, description = "Inspect third-party types used by a class or classes using a library.")
    public String list_external_dependencies(
            @ToolArg(description = "Class name to inspect (short or FQCN). If omitted, shows project-wide library usage summary.") Optional<String> target,
            @ToolArg(description = "Filter by library package prefix, e.g. 'com.fasterxml.jackson' or 'jakarta.persistence'") Optional<String> library,
            @ToolArg(description = "Max results for library summary (default: 20)") Optional<Integer> limit,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        String requestedTarget = target.orElse(null);
        String requestedLibrary = library.orElse(null);
        int requestedLimit = clamp(limit.orElse(20), 1, 100);
        return forAllProjects(project.orElse(null), p -> requestedTarget == null
                ? queries.getExternalDeps(p.jdbi(), null, requestedLibrary, requestedLimit)
                : routeClassQuery(p, requestedTarget, "list_external_dependencies",
                        jdbi -> queries.getExternalDeps(jdbi, requestedTarget,
                                requestedLibrary, requestedLimit)));
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier) {
        return queries.getBeans(jdbi, className, scope, kind, profile, qualifier);
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind,
            String profile, String qualifier, int limit) {
        return queries.getBeans(jdbi, className, scope, kind, profile, qualifier, limit);
    }

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        return queries.searchClasses(jdbi, pattern, limit);
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        return queries.getDependencies(jdbi, target, direction, depth);
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth,
            boolean includeNodes, int limit, int offset, String cursor) {
        return queries.getDependencies(jdbi, target, direction, depth,
                includeNodes, limit, offset, cursor);
    }

    String findImplementations(Jdbi jdbi, String target, boolean transitive,
            String module, String sourceSet, int limit, int offset) {
        return queries.findImplementations(
                jdbi, target, transitive, module, sourceSet, limit, offset);
    }

    String findUsages(Jdbi jdbi, String target, String usageKind,
            String module, int limit, int offset) {
        return queries.findUsages(jdbi, target, usageKind, module, limit, offset);
    }

    String getSymbolDetails(Jdbi jdbi, String target, boolean includeMembers,
            String memberKind, int memberLimit, int memberOffset) {
        return queries.getSymbolDetails(
                jdbi, target, includeMembers, memberKind, memberLimit, memberOffset);
    }

    String findImpactedTests(Jdbi jdbi, List<String> targets,
            boolean transitive, int maxDepth, int limit, int offset) {
        return queries.findImpactedTests(jdbi, targets, transitive, maxDepth, limit, offset);
    }

    String getTypeHierarchy(Jdbi jdbi, String target, String direction,
            int maxDepth, int limit, int offset) {
        return queries.getTypeHierarchy(jdbi, target, direction, maxDepth, limit, offset);
    }

    String searchSymbols(Jdbi jdbi, String pattern, String kind, int limit, int offset) {
        return queries.searchSymbols(jdbi, pattern, kind, limit, offset);
    }

    String searchSymbols(Jdbi jdbi, String pattern, String kind, String language,
            int limit, int offset) {
        return queries.searchSymbols(jdbi, pattern, kind, language, limit, offset);
    }

    String getCallHierarchy(Jdbi jdbi, String target, String method,
            String direction, int limit, int offset) {
        return queries.getCallHierarchy(jdbi, target, method, direction, limit, offset);
    }

    String getCallHierarchy(Jdbi jdbi, String target, String method,
            String direction, boolean transitive, int maxDepth, int limit, int offset) {
        return queries.getCallHierarchy(jdbi, target, method, direction,
                transitive, maxDepth, limit, offset);
    }

    String findMethodOverrides(Jdbi jdbi, String target, String method,
            String signature, boolean transitive, int limit, int offset) {
        return queries.findMethodOverrides(
                jdbi, target, method, signature, transitive, limit, offset);
    }

    String findUnusedClasses(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        return queries.findUnusedClasses(
                jdbi, module, includeGenerated, includeTests, limit, offset);
    }

    String findUnusedMethods(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, int limit, int offset) {
        return queries.findUnusedMethods(
                jdbi, module, includeGenerated, includeTests, limit, offset);
    }

    String findUnusedFields(Jdbi jdbi, String module, boolean includeGenerated,
            boolean includeTests, boolean includeWriteOnly, int limit, int offset) {
        return queries.findUnusedFields(jdbi, module, includeGenerated,
                includeTests, includeWriteOnly, limit, offset);
    }

    String findEntryPoints(Jdbi jdbi, String kind, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        return queries.findEntryPoints(jdbi, kind, module,
                includeGenerated, includeTests, limit, offset);
    }

    String getModuleGraph(Jdbi jdbi, String module, String direction,
            int depth, int limit, int offset) {
        return queries.getModuleGraph(jdbi, module, direction, depth, limit, offset);
    }

    String findCycles(Jdbi jdbi, String scope, String module,
            boolean includeGenerated, boolean includeTests, int limit, int offset) {
        return queries.findCycles(jdbi, scope, module,
                includeGenerated, includeTests, limit, offset);
    }

    String compareIndex(Jdbi jdbi, Path projectRoot, String baseline, int detailLimit) {
        return queries.compareIndex(jdbi, projectRoot, baseline, detailLimit);
    }

    String getBuildStatus(Jdbi jdbi, Path projectRoot) {
        return queries.getBuildStatus(jdbi, projectRoot);
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
        return queries.getInjectionPoints(jdbi, target);
    }

    String getHotspots(Jdbi jdbi, int limit, String since) {
        return queries.getHotspots(jdbi, limit, since);
    }

    String getHotspots(Jdbi jdbi, int limit, String since, boolean includeHistorical) {
        return queries.getHotspots(jdbi, limit, since, includeHistorical);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit) {
        return queries.getFileHistory(jdbi, target, limit);
    }

    String getFileHistory(Jdbi jdbi, String target, int limit, int offset) {
        return queries.getFileHistory(jdbi, target, limit, offset);
    }

    String resolveEntities(Jdbi jdbi, List<String> targets) {
        return queries.resolveEntities(jdbi, targets);
    }

    String inspectServiceDescriptors(Jdbi jdbi, String service) {
        return queries.inspectServiceDescriptors(jdbi, service);
    }

    String findConfigurationReferences(Jdbi jdbi, String key, String className,
            String kind, String module, int limit, int offset) {
        return queries.findConfigurationReferences(
                jdbi, key, className, kind, module, limit, offset);
    }

    String findResourceReferences(Jdbi jdbi, String path, String className,
            String module, int limit, int offset) {
        return queries.findResourceReferences(jdbi, path, className, module, limit, offset);
    }

    String getCoChanges(Jdbi jdbi, String target, int limit) {
        return queries.getCoChanges(jdbi, target, limit);
    }

    String getRecentChanges(Jdbi jdbi, int commitCount) {
        return queries.getRecentChanges(jdbi, commitCount);
    }

    String getRecentChanges(Jdbi jdbi, int commitCount, int fileLimit, int fileOffset,
            boolean details) {
        return queries.getRecentChanges(jdbi, commitCount, fileLimit, fileOffset, details);
    }

    String getOverview(Jdbi jdbi) {
        return queries.getOverview(jdbi);
    }

    String getRisk(Jdbi jdbi, String target) {
        return queries.getRisk(jdbi, target);
    }

    String getContext(Jdbi jdbi, List<String> targets, boolean includeMembers, int limit) {
        return queries.getContext(jdbi, targets, includeMembers, limit);
    }

    String planChange(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        return queries.planChange(jdbi, projectRoot, targets, change, limit);
    }

    String verifyChange(Jdbi jdbi, Path projectRoot, List<String> targets, int limit) {
        return queries.verifyChange(jdbi, projectRoot, targets, limit);
    }

    String changeSession(Jdbi jdbi, Path projectRoot, List<String> targets,
            String change, int limit) {
        return queries.changeSession(jdbi, projectRoot, targets, change, limit, "full", "all");
    }

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        return queries.getExternalDeps(jdbi, target, library, limit);
    }

    private String routeClassQuery(ProjectRegistry.ProjectEntry project, String target,
            String operation, Function<Jdbi, String> query) {
        return workspace.routeMissingClassQuery(query.apply(project.jdbi()),
                project.name(), target, operation, query);
    }

    private String forAllProjects(String projectFilter,
            Function<ProjectRegistry.ProjectEntry, String> perProject) {
        boolean filtered = projectFilter != null && !projectFilter.isBlank();
        ProjectQueryExecutor.Batch batch = executor.execute(projectFilter, perProject);
        ProjectRegistry.Resolution resolution = batch.resolution();
        List<ProjectRegistry.ProjectEntry> projects = resolution.projects();
        List<String> errors = resolution.errors();
        if (filtered && projects.isEmpty()) {
            if (!resolution.issues().isEmpty()) {
                return ProjectAvailabilityResponses.error(resolution.issues().getFirst());
            }
            if (!errors.isEmpty()) {
                return errorResponse(String.join("; ", errors));
            }
            String filter = projectFilter.strip();
            List<String> available = registry.configuredProjectNames();
            return errorResponse("Project '" + filter + "' not found. Available: " + available);
        }
        if (projects.isEmpty() && errors.isEmpty()) {
            return errorResponse("No projects configured. Start quill with --project <path>.");
        }
        if (projects.isEmpty() && !resolution.issues().isEmpty()) {
            return ProjectAvailabilityResponses.error(resolution.issues());
        }
        if (filtered) {
            errors = List.of();
        }
        if (projects.size() == 1 && errors.isEmpty()) {
            ProjectQueryExecutor.QueryResult result = batch.results().getFirst();
            return result.error() == null
                    ? ResponseBudget.apply(appendProjectWarnings(
                            result.json(), resolution.issues()))
                    : errorResponse("Project '" + result.project() + "': " + result.error());
        }

        List<ProjectQueryExecutor.QueryResult> projectResults = batch.results();

        List<ProjectQueryExecutor.QueryResult> hits = new ArrayList<>();
        List<ProjectQueryExecutor.QueryResult> misses = new ArrayList<>();
        for (ProjectQueryExecutor.QueryResult result : projectResults) {
            if (result.error() != null || isNotFoundError(result.json())) misses.add(result);
            else hits.add(result);
        }
        if (hits.isEmpty() && !misses.isEmpty()) hits = misses;

        ObjectNode root = JSON.createObjectNode();
        ArrayNode results = root.putArray("projects");
        for (ProjectQueryExecutor.QueryResult result : hits) {
            ObjectNode wrapper = results.addObject().put("project", result.project());
            try {
                if (result.error() != null) wrapper.put("error", result.error());
                else wrapper.set("data", JSON.readTree(result.json()));
            } catch (Exception error) {
                wrapper.put("error", error.getMessage());
            }
        }
        if (!misses.isEmpty() && !hits.isEmpty() && hits != misses) {
            root.put("skipped_projects", misses.size());
        }
        if (!errors.isEmpty()) {
            ArrayNode uninitialized = root.putArray("uninitialized");
            Set<String> issueMessages = resolution.issues().stream()
                    .map(ProjectRegistry.ProjectIssue::legacyMessage)
                    .collect(java.util.stream.Collectors.toSet());
            errors.stream().filter(error -> !issueMessages.contains(error))
                    .forEach(uninitialized::add);
            if (uninitialized.isEmpty()) root.remove("uninitialized");
        }
        if (!resolution.issues().isEmpty()) {
            Set<String> queryable = projects.stream().map(ProjectRegistry.ProjectEntry::name)
                    .collect(java.util.stream.Collectors.toSet());
            List<ProjectRegistry.ProjectIssue> warnings = resolution.issues().stream()
                    .filter(issue -> queryable.contains(issue.project())).toList();
            List<ProjectRegistry.ProjectIssue> unavailable = resolution.issues().stream()
                    .filter(issue -> !queryable.contains(issue.project())).toList();
            ProjectAvailabilityResponses.append(root, "project_warnings", warnings);
            ProjectAvailabilityResponses.append(root, unavailable);
        }
        return ResponseBudget.apply(root.toString());
    }

    private static String appendProjectWarnings(
            String json, List<ProjectRegistry.ProjectIssue> issues) {
        if (issues.isEmpty()) return json;
        try {
            JsonNode parsed = JSON.readTree(json);
            if (!(parsed instanceof ObjectNode root)) return json;
            ProjectAvailabilityResponses.append(root, "project_warnings", issues);
            annotateScopedBuildWarnings(root);
            return root.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    static void annotateScopedBuildWarnings(ObjectNode root) {
        JsonNode build = root.has("build") ? root.path("build") : root.path("verification").path("build");
        JsonNode scope = build.path("verification_scope");
        if (!scope.path("kind").asText().equals("modules_with_prerequisites")) return;
        Set<String> modules = scope.path("modules").valueStream().map(JsonNode::asText)
                .collect(java.util.stream.Collectors.toSet());
        if (modules.isEmpty()) return;
        for (JsonNode warning : root.path("project_warnings")) {
            if (!(warning instanceof ObjectNode object)
                    || !warning.path("build_reason").asText().equals("classes_stale")
                    || warning.path("stale_modules").isEmpty()) continue;
            boolean outside = warning.path("stale_modules").valueStream()
                    .map(JsonNode::asText).noneMatch(modules::contains);
            if (outside) {
                object.put("scope", "repository_outside_change");
                object.put("blocking_for_change", false);
                object.put("recommended_action", "Advisory outside this verification scope; "
                        + "follow the change directive. Rebuild these modules only for repository-wide verification.");
            }
        }
    }

    private static boolean isNotFoundError(String json) {
        if (json == null) return false;
        try {
            JsonNode parsed = JSON.readTree(json);
            if ("CLASS_NOT_FOUND".equals(parsed.path("error_code").asText())) return true;
            String message = parsed.path("message").asText();
            if (!message.isBlank()) {
                return message.startsWith("Annotation not found")
                        || message.startsWith("Not a bean:");
            }
        } catch (Exception ignored) {
            // Fall through for older internal query payloads that are not valid JSON.
        }
        return NOT_FOUND_PREFIXES.stream().anyMatch(json::startsWith);
    }

    private static String errorResponse(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static Set<String> semanticTerms(String value, Set<String> defaults) {
        if (value == null || value.isBlank()) return defaults;
        Set<String> terms = java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .map(term -> term.toLowerCase(java.util.Locale.ROOT))
                .filter(term -> !term.isBlank())
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        return terms.isEmpty() ? defaults : Set.copyOf(terms);
    }

}
