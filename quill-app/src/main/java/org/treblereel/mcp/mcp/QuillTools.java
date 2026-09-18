package org.treblereel.mcp.mcp;

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
    private final QuillToolQueries queries;
    private final ProjectDependencyQueries projectDependencies;
    private final FileNavigationQueries fileNavigation;
    private final WorktreeStatusQueries worktreeStatus;
    private final PositionSymbolQueries positionSymbols;
    private final ExternalSymbolQueries externalSymbols;

    public QuillTools() {
        this(new ProjectRegistry());
    }

    public QuillTools(ProjectRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.queries = new QuillToolQueries();
        this.projectDependencies = new ProjectDependencyQueries();
        this.fileNavigation = new FileNavigationQueries();
        this.worktreeStatus = new WorktreeStatusQueries();
        this.positionSymbols = new PositionSymbolQueries();
        this.externalSymbols = new ExternalSymbolQueries();
    }

    @Tool(structured = true, description = "Find CDI/Spring beans, producers, interceptors, or decorators. Returns DI and source context.")
    public String list_beans(
            @ToolArg(description = "Short class name, FQCN, source path, or * wildcard filter") Optional<String> class_name,
            @ToolArg(description = "Scope filter, e.g. @ApplicationScoped or @Singleton") Optional<String> scope,
            @ToolArg(description = "Bean kind: CLASS, PRODUCER_METHOD, PRODUCER_FIELD, INTERCEPTOR, DECORATOR") Optional<String> kind,
            @ToolArg(description = "Build profile filter, e.g. dev") Optional<String> profile,
            @ToolArg(description = "Qualifier filter, e.g. @Premium or @Qualifier(\"stripe\")") Optional<String> qualifier,
            @ToolArg(description = "Module path filter relative to the project root, or '.' for the root module") Optional<String> module,
            @ToolArg(description = "Source set filter, e.g. main or test") Optional<String> source_set,
            @ToolArg(description = "Max results to return (default: 50)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getBeans(p.jdbi(),
                class_name.orElse(null), scope.orElse(null), kind.orElse(null),
                profile.orElse(null), qualifier.orElse(null), module.orElse(null),
                source_set.orElse(null), clamp(limit.orElse(50), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Get complete dependency metrics and an optionally paged class graph with call-site evidence.")
    public String get_dependencies(
            @ToolArg(description = "Current class name (short or FQCN) or its project/repository source path") String target,
            @ToolArg(description = "Direction: inbound, outbound, or both (default: both)") Optional<String> direction,
            @ToolArg(description = "Graph traversal depth (default: 1)") Optional<Integer> depth,
            @ToolArg(description = "Include dependency relations; false returns compact metrics only (default: true)") Optional<Boolean> include_nodes,
            @ToolArg(description = "Relations per page (default: 50, max: 200)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for depth=1 (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Opaque continuation cursor for depth>1") Optional<String> cursor,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getDependencies(
                p.jdbi(), target, direction.orElse("both"), clamp(depth.orElse(1), 1, 5),
                include_nodes.orElse(true), clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE), cursor.orElse(null)));
    }

    @Tool(structured = true, description = "Find implementations across indexed reactor outputs, with generated FQCN occurrences and explicit discovery scope.")
    public String find_implementations(
            @ToolArg(description = "Current class or interface name (short or FQCN), or its source path") String target,
            @ToolArg(description = "Include indirect implementations through intermediate types (default: true)") Optional<Boolean> transitive,
            @ToolArg(description = "Logical implementation classes per page (default: 50, max: 100)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findImplementations(
                p.jdbi(), target, transitive.orElse(true), null, null,
                clamp(limit.orElse(50), 1, 100),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Find class usages from bytecode, DI, inheritance, annotations, and ServiceLoader evidence.")
    public String find_usages(
            @ToolArg(description = "Current class name (short or FQCN), or its source path") String target,
            @ToolArg(description = "Usage kind; omit or use all for every supported kind") Optional<String> usage_kind,
            @ToolArg(description = "Module path filter relative to the project root") Optional<String> module,
            @ToolArg(description = "Usage groups per page (default: 50, max: 200)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findUsages(
                p.jdbi(), target, usage_kind.orElse(null), module.orElse(null),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Find exact bytecode usages of a method, constructor, or field declaration.")
    public String find_symbol_usages(
            @ToolArg(description = "Declaring class name or source path") String target,
            @ToolArg(description = "Member name; optional for constructors") Optional<String> name,
            @ToolArg(description = "Symbol kind: method, constructor, or field") String kind,
            @ToolArg(description = "Exact source signature or JVM descriptor; required when overloaded") Optional<String> signature,
            @ToolArg(description = "For fields: all, read, or write (default: all)") Optional<String> access,
            @ToolArg(description = "Usage groups per page (default: 50, max: 200)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findSymbolUsages(
                p.jdbi(), target, name.orElse(null), kind, signature.orElse(null),
                access.orElse("all"), clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Inspect a class and its members.")
    public String get_symbol_details(
            @ToolArg(description = "Class or source path") String target,
            @ToolArg(description = "Include members; default true") Optional<Boolean> include_members,
            @ToolArg(description = "Member kind or all") Optional<String> member_kind,
            @ToolArg(description = "Page size; default 100") Optional<Integer> member_limit,
            @ToolArg(description = "Page offset") Optional<Integer> member_offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getSymbolDetails(
                p.jdbi(), target, include_members.orElse(true), member_kind.orElse(null),
                clamp(member_limit.orElse(100), 1, 200),
                clamp(member_offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Rank tests affected by changed classes.")
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

    @Tool(structured = true, description = "Inspect ancestor and descendant paths for a type.")
    public String get_type_hierarchy(
            @ToolArg(description = "Current class, interface, or source path") String target,
            @ToolArg(description = "ancestors, descendants, or both; default both") Optional<String> direction,
            @ToolArg(description = "Maximum hierarchy depth; default 5") Optional<Integer> max_depth,
            @ToolArg(description = "Page size; default 100") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getTypeHierarchy(
                p.jdbi(), target, direction.orElse("both"),
                clamp(max_depth.orElse(5), 1, 20),
                clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Search indexed type, method, field, and constructor declarations.")
    public String search_symbols(
            @ToolArg(description = "Name or signature pattern; * is a wildcard") String pattern,
            @ToolArg(description = "Symbol kind or all; default all") Optional<String> kind,
            @ToolArg(description = "Page size; default 50") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.searchSymbols(
                p.jdbi(), pattern, kind.orElse(null),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Inspect method callers and callees from bytecode evidence.")
    public String get_call_hierarchy(
            @ToolArg(description = "Current class or source path") String target,
            @ToolArg(description = "Optional method name; use <init> for constructors") Optional<String> method,
            @ToolArg(description = "Exact indexed signature or JVM descriptor for overload selection") Optional<String> signature,
            @ToolArg(description = "inbound, outbound, or both; default both") Optional<String> direction,
            @ToolArg(description = "Traverse calls; default false") Optional<Boolean> transitive,
            @ToolArg(description = "Traversal depth; default 3") Optional<Integer> max_depth,
            @ToolArg(description = "Page size; default 100") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getCallHierarchy(
                p.jdbi(), target, method.orElse(null), signature.orElse(null),
                direction.orElse("both"),
                transitive.orElse(false), clamp(max_depth.orElse(3), 1, 8),
                clamp(limit.orElse(100), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Find declared method overrides in indexed subclasses and implementors.")
    public String find_method_overrides(
            @ToolArg(description = "Current class, interface, or source path") String target,
            @ToolArg(description = "Declared method name") String method,
            @ToolArg(description = "Optional exact signature to select one overload") Optional<String> signature,
            @ToolArg(description = "Include indirect descendants; default true") Optional<Boolean> transitive,
            @ToolArg(description = "Page size; default 50") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findMethodOverrides(
                p.jdbi(), target, method, signature.orElse(null), transitive.orElse(true),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Find conservative candidates for unused indexed classes; results are not proof of dead code.")
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

    @Tool(structured = true, description = "Find private methods without indexed inbound calls; results are conservative dead-code candidates.")
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

    @Tool(structured = true, description = "Find private fields without indexed reads; write-only fields are opt-in and results are conservative dead-code candidates.")
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

    @Tool(structured = true, description = "Find statically identifiable application and framework entry points with detection evidence.")
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

    @Tool(structured = true, description = "Inspect direct project-module dependencies and transitive classpath visibility.")
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

    @Tool(structured = true,
            description = "List resolved Maven or Gradle dependency artifacts and the modules that use them without invoking a build.")
    public String get_project_dependencies(
            @ToolArg(description = "Module path relative to the project root; omit for all") Optional<String> module,
            @ToolArg(description = "Substring of group, artifact, version, or JAR name") Optional<String> query,
            @ToolArg(description = "Page size; default 100, max 200") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p ->
                projectDependencies.getProjectDependencies(p.jdbi(), p.root(), module.orElse(null),
                        query.orElse(null), clamp(limit.orElse(100), 1, 200),
                        clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true,
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

    @Tool(structured = true,
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

    @Tool(structured = true,
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

    @Tool(structured = true,
            description = "Resolve the Java or Kotlin identifier at a live source position to indexed declarations.")
    public String get_symbol_at_position(
            @ToolArg(description = "Repository-relative Java or Kotlin source path") String path,
            @ToolArg(description = "One-based source line") Integer line,
            @ToolArg(description = "One-based source column") Integer column,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> positionSymbols.getSymbolAtPosition(
                p.jdbi(), p.root(), path, line, column));
    }

    @Tool(structured = true,
            description = "Search class and member declarations indexed from external dependency bytecode.")
    public String search_external_symbols(
            @ToolArg(description = "Symbol name or signature pattern; supports * wildcard") String pattern,
            @ToolArg(description = "Kind: class, interface, annotation, enum, record, field, constructor, method, or all") Optional<String> kind,
            @ToolArg(description = "External class short name or FQCN; required for field, constructor, and method searches") Optional<String> class_name,
            @ToolArg(description = "Dependency package prefix or wildcard") Optional<String> library,
            @ToolArg(description = "Results per page; default 50, max 200") Optional<Integer> limit,
            @ToolArg(description = "Page offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> externalSymbols.search(
                p.jdbi(), p.root(), pattern, kind.orElse(null), class_name.orElse(null),
                library.orElse(null),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true,
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

    @Tool(structured = true, description = "Inspect package dependency coupling.")
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

    @Tool(structured = true, description = "Find package or module dependency violations.")
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

    @Tool(structured = true, description = "Find class or module dependency cycles.")
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

    @Tool(structured = true, description = "Compare the active index with an earlier local generation.")
    public String compare_index(
            @ToolArg(description = "Index id or commit; default previous") Optional<String> baseline,
            @ToolArg(description = "Details per category; default 50") Optional<Integer> limit,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.compareIndex(
                p.jdbi(), p.root(), baseline.orElse(null),
                clamp(limit.orElse(50), 1, 200)));
    }

    @Tool(structured = true, description = "Inspect build integration and compiled-index freshness without running a build.")
    public String get_build_status(
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.getBuildStatus(p.jdbi(), p.root()));
    }

    @Tool(structured = true, description = "Read errors captured by the last Maven or Gradle build.")
    public String get_build_problems(
            @ToolArg(description = "Severity: all or error") Optional<String> severity,
            @ToolArg(description = "Exact module filter") Optional<String> module,
            @ToolArg(description = "Limit; max 200") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getBuildProblems(
                p.root(), severity.orElse("all"), module.orElse(null),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true,
            description = "Read last-build diagnostics for one or more repository-relative source files without running a build.")
    public String get_file_problems(
            @ToolArg(description = "Repository-relative source paths") List<String> paths,
            @ToolArg(description = "Severity: all or error") Optional<String> severity,
            @ToolArg(description = "Limit; max 200") Optional<Integer> limit,
            @ToolArg(description = "Offset") Optional<Integer> offset,
            @ToolArg(description = "Project; omit for all") Optional<String> project) {
        if (paths.isEmpty()) return errorResponse("paths must not be empty");
        return forAllProjects(project.orElse(null), p -> queries.getFileProblems(
                p.root(), paths, severity.orElse("all"),
                clamp(limit.orElse(50), 1, 200),
                clamp(offset.orElse(0), 0, Integer.MAX_VALUE)));
    }

    @Tool(structured = true, description = "Inspect bean injections with resolution status, reason, confidence, limitations, and candidate trace.")
    public String list_injection_points(
            @ToolArg(description = "Bean class name (short or FQCN)") String target,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.getInjectionPoints(p.jdbi(), target));
    }

    @Tool(structured = true, description = "Rank files/classes by Git churn with lifecycle, authors, dates, and worktree changes.")
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

    @Tool(structured = true, description = "Get commit history for one current class or file, "
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

    @Tool(structured = true, description = "Resolve up to 20 names/paths across the current tree and Git history, including deleted paths.")
    public String resolve_entities(
            @ToolArg(description = "Class names, file names, or repository paths to resolve") List<String> targets,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.resolveEntities(p.jdbi(), targets));
    }

    @Tool(structured = true, description = "Find files that frequently change with a class/path and report coupling ratios.")
    public String find_co_changed_files(
            @ToolArg(description = "Class name (short or FQCN), project path, or repository path") String target,
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getCoChanges(
                p.jdbi(), target, clamp(limit.orElse(10), 1, 100)));
    }

    @Tool(structured = true, description = "Get recent commits and their changed files/classes.")
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

    @Tool(structured = true, description = "Inspect ordered META-INF/services providers with lines and order-sensitivity analysis.")
    public String inspect_service_descriptors(
            @ToolArg(description = "Optional service FQCN or short name, e.g. javax.annotation.processing.Processor") Optional<String> service,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.inspectServiceDescriptors(p.jdbi(), service.orElse(null)));
    }

    @Tool(structured = true, description = "Find configuration definitions and annotation/programmatic consumers without exposing values.")
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

    @Tool(structured = true, description = "Find classpath resources and programmatic consumers without reading resource contents.")
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

    @Tool(structured = true, description = "Summarize frameworks, beans/classes, architecture hubs, DI problems, libraries, Git activity, and freshness.")
    public String get_overview(
            @ToolArg(description = "Include diagnostic samples and all hub rankings (default: false)") Optional<Boolean> details,
            @ToolArg(description = "Project to query; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.getOverview(p.jdbi(), details.orElse(false)));
    }

    @Tool(structured = true, description = "Search current classes by wildcard name with source, origin, module, and bean context.")
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

    @Tool(structured = true, description = "Find classes carrying an annotation directly or "
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

    @Tool(structured = true, description = "Find annotated type, method, field, and constructor declarations.")
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

    @Tool(structured = true, description = "Find Spring MVC and JAX-RS routes.")
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

    @Tool(structured = true, description = "Score class/file change risk from coupling, criticality, churn, bus factor, and fan-in/out.")
    public String assess_change_risk(
            @ToolArg(description = "Class name (short or FQCN), or any project/repository file path") String target,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getRisk(p.jdbi(), target));
    }

    @Tool(structured = true, description = "Inspect third-party types used by a class or classes using a library.")
    public String list_external_dependencies(
            @ToolArg(description = "Class name to inspect (short or FQCN). If omitted, shows project-wide library usage summary.") Optional<String> target,
            @ToolArg(description = "Filter by library package prefix, e.g. 'com.fasterxml.jackson' or 'jakarta.persistence'") Optional<String> library,
            @ToolArg(description = "Max results for library summary (default: 20)") Optional<Integer> limit,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getExternalDeps(p.jdbi(),
                target.orElse(null), library.orElse(null), clamp(limit.orElse(20), 1, 100)));
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

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        return queries.getExternalDeps(jdbi, target, library, limit);
    }

    private String forAllProjects(String projectFilter,
            Function<ProjectRegistry.ProjectEntry, String> perProject) {
        ProjectRegistry.Resolution resolution = registry.resolve();
        List<ProjectRegistry.ProjectEntry> projects = resolution.projects();
        List<String> errors = resolution.errors();
        if (projects.isEmpty() && errors.isEmpty()) {
            return errorResponse("No projects configured. Start quill with --project <path>.");
        }
        if (projectFilter != null && !projectFilter.isBlank()) {
            String filter = projectFilter.strip();
            projects = projects.stream()
                    .filter(p -> p.name().equalsIgnoreCase(filter))
                    .toList();
            if (projects.isEmpty()) {
                List<String> available = resolution.projects().stream()
                        .map(ProjectRegistry.ProjectEntry::name).toList();
                return errorResponse("Project '" + filter + "' not found. Available: " + available);
            }
            errors = List.of();
        }
        if (projects.size() == 1 && errors.isEmpty()) {
            ProjectResult result = runForProject(projects.get(0), perProject);
            return result.error() == null
                    ? ResponseBudget.apply(result.json())
                    : errorResponse("Project '" + result.name() + "': " + result.error());
        }

        List<ProjectResult> projectResults = new ArrayList<>();
        for (ProjectRegistry.ProjectEntry project : projects) {
            projectResults.add(runForProject(project, perProject));
        }
        projectResults.sort(Comparator.comparing(ProjectResult::name));

        List<ProjectResult> hits = new ArrayList<>();
        List<ProjectResult> misses = new ArrayList<>();
        for (ProjectResult result : projectResults) {
            if (result.error() != null || isNotFoundError(result.json())) misses.add(result);
            else hits.add(result);
        }
        if (hits.isEmpty() && !misses.isEmpty()) hits = misses;

        ObjectNode root = JSON.createObjectNode();
        ArrayNode results = root.putArray("projects");
        for (ProjectResult result : hits) {
            ObjectNode wrapper = results.addObject().put("project", result.name());
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
            errors.forEach(uninitialized::add);
        }
        return ResponseBudget.apply(root.toString());
    }

    private ProjectResult runForProject(ProjectRegistry.ProjectEntry project,
            Function<ProjectRegistry.ProjectEntry, String> query) {
        try {
            String json = project.jdbi().inTransaction(handle -> query.apply(project));
            return new ProjectResult(project.name(), json, null);
        } catch (Exception error) {
            return new ProjectResult(project.name(), null, ProjectRegistry.safeMessage(error));
        }
    }

    private static boolean isNotFoundError(String json) {
        if (json == null) return false;
        return NOT_FOUND_PREFIXES.stream().anyMatch(json::startsWith);
    }

    private static String errorResponse(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ProjectResult(String name, String json, String error) {}
}
