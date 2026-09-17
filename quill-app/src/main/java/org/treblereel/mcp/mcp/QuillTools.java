package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
            "{\"error\":\"Not a bean:");

    private final ProjectRegistry registry;
    private final QuillToolQueries queries;

    public QuillTools() {
        this(new ProjectRegistry());
    }

    public QuillTools(ProjectRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.queries = new QuillToolQueries();
    }

    @Tool(structured = true, description = "List beans (CDI or Spring) with optional filtering. Use instead of grep/find when looking for injectable services, producers, interceptors, or decorators. "
            + "Returns: {beans: [{class, kind, scope, qualifiers, bean_types, profiles, source, module, source_set}], total, showing, _meta}")
    public String list_beans(
            @ToolArg(description = "Class name filter (supports * wildcard)") Optional<String> class_name,
            @ToolArg(description = "Scope filter, e.g. @ApplicationScoped or @Singleton") Optional<String> scope,
            @ToolArg(description = "Bean kind: CLASS, PRODUCER_METHOD, PRODUCER_FIELD, INTERCEPTOR, DECORATOR") Optional<String> kind,
            @ToolArg(description = "Build profile filter, e.g. dev") Optional<String> profile,
            @ToolArg(description = "Qualifier filter, e.g. @Premium or @Qualifier(\"stripe\")") Optional<String> qualifier,
            @ToolArg(description = "Module path filter relative to the project root, or '.' for the root module") Optional<String> module,
            @ToolArg(description = "Source set filter, e.g. main or test") Optional<String> source_set,
            @ToolArg(description = "Max results to return (default: 50)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getBeans(p.jdbi(),
                class_name.orElse(null), scope.orElse(null), kind.orElse(null),
                profile.orElse(null), qualifier.orElse(null), module.orElse(null),
                source_set.orElse(null), clamp(limit.orElse(50), 1, 100)));
    }

    @Tool(structured = true, description = "Get dependency graph for a specific bean or class. Use instead of grep for imports/references when you need to understand what a class uses or what uses it. "
            + "Returns unique fan-in/fan-out separately from reference occurrence counts and groups metrics by source/generated origin. "
            + "Returns: {target, origin, lifecycle, metrics, depends_on: [{class, kind, occurrences}], depended_by: [{class, kind, occurrences}], _meta}")
    public String get_dependencies(
            @ToolArg(description = "Current class name (short or FQCN) or its project/repository source path") String target,
            @ToolArg(description = "Direction: inbound, outbound, or both (default: both)") Optional<String> direction,
            @ToolArg(description = "Graph traversal depth (default: 1)") Optional<Integer> depth,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getDependencies(
                p.jdbi(), target, direction.orElse("both"), clamp(depth.orElse(1), 1, 5)));
    }

    @Tool(structured = true, description = "Get injection points for a bean with resolution status. Use when checking what a bean injects and whether injections resolve correctly. "
            + "Returns: {target, module, source_set, injection_points: [{kind, field, required_type, qualifiers, resolved_to, resolution, resolution_strategy, reason, confidence, limitations, resolution_trace: {candidates: [{class, file, origin, module, source_set, kind, member, qualifiers, disposition, reason, related_class, rules}], applied_rules, unsupported_rules}}], unsatisfied: [], ambiguous: [], context_required: [], unknown: [], unsupported_mechanism: [], _meta}")
    public String list_injection_points(
            @ToolArg(description = "Bean class name (short or FQCN)") String target,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.getInjectionPoints(p.jdbi(), target));
    }

    @Tool(structured = true, description = "Get most frequently changed files/classes by git commit count. Use instead of git log when looking for volatile or high-churn areas of the codebase. "
            + "Returns: {worktree_changes, hotspots: [{file, lifecycle, class?, is_bean?, commit_count, distinct_authors, last_modified, last_author}], showing, total, truncated, _meta}")
    public String find_git_hotspots(
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Only commits after this date, ISO format YYYY-MM-DD") Optional<String> since,
            @ToolArg(description = "Include deleted/historical paths (default: false)") Optional<Boolean> include_historical,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getHotspots(p.jdbi(),
                clamp(limit.orElse(10), 1, 100), since.orElse(null),
                include_historical.orElse(false)));
    }

    @Tool(structured = true, description = "Get git commit history for a specific class or file. Use instead of git log when you need change history for a particular class. "
            + "Returns: {target, file, commits: [{hash, author, date, message}], total_commits, _meta}")
    public String get_file_history(
            @ToolArg(description = "Class name (short or FQCN), project path, or repository path") String target,
            @ToolArg(description = "Max commits to return (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getFileHistory(
                p.jdbi(), target, clamp(limit.orElse(10), 1, 100)));
    }

    @Tool(structured = true, description = "Find files that frequently change together with a given class. Reveals hidden coupling. Use before refactoring to find files you might also need to change. "
            + "Returns: {target, co_changes: [{file, class?, co_change_count, coupling_ratio}], _meta}")
    public String find_co_changed_files(
            @ToolArg(description = "Class name (short or FQCN), project path, or repository path") String target,
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getCoChanges(
                p.jdbi(), target, clamp(limit.orElse(10), 1, 100)));
    }

    @Tool(structured = true, description = "Get recently changed classes from git history. Use to understand what was modified recently and by whom. "
            + "Returns: {recent_changes: [{commit, author, date, message, files: [{file, change_type, class?, is_bean?}]}], _meta}")
    public String get_recent_changes(
            @ToolArg(description = "Number of recent commits to inspect (default: 10)") Optional<Integer> commits,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getRecentChanges(
                p.jdbi(), clamp(commits.orElse(10), 1, 200)));
    }

    @Tool(structured = true, description = "Get a high-level project overview. Call this FIRST when starting work on a project — gives framework, class/bean counts, architecture hubs, and known problems. "
            + "Returns: {project: {framework, classes, beans, total_source_tokens, indexed_at, last_commit, dependency_index, dependency_index_detail?, service_descriptors, service_registrations}, "
            + "beans_by_scope: {...}, beans_by_kind: {...}, "
            + "architecture_hubs: [{class, total_dependents, source_dependents, generated_dependents, production_dependents, test_dependents, is_bean}], "
            + "architecture_hub_rankings: {source: [...], generated: [...], production: [...], test: [...], scope_note}, "
            + "problems: {unsatisfied_count, ambiguous_count, context_required_count, unknown_count, unsupported_mechanism_count, *_injection_points_sample: [{bean, field, type, resolution_strategy, reason, confidence, limitations}]}, "
            + "top_libraries: [{package, used_by_classes}], "
            + "git_summary: {total_commits_indexed, top_hotspots: [{file, commit_count}]}, _meta}. "
            + "For multi-project: {projects: [{project, data: <above>}], uninitialized?: [...]}")
    public String get_overview(
            @ToolArg(description = "Project name to query. Omit to get overview of all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getOverview(p.jdbi()));
    }

    @Tool(structured = true, description = "Search for classes by name pattern (supports * wildcard). Returns all classes, not just beans. Use instead of grep/find when looking for a class by name. "
            + "Returns: {classes: [{class, source, origin, lifecycle, module, source_set, is_bean, scope?, source_tokens}], showing, total, _meta}")
    public String search_classes(
            @ToolArg(description = "Class name pattern (supports * wildcard, e.g. '*Service', 'io.casehub.*.model.*')") String pattern,
            @ToolArg(description = "Module path filter relative to the project root, or '.' for the root module") Optional<String> module,
            @ToolArg(description = "Source set filter, e.g. main or test") Optional<String> source_set,
            @ToolArg(description = "Max results (default: 30)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.searchClasses(
                p.jdbi(), pattern, module.orElse(null), source_set.orElse(null),
                clamp(limit.orElse(30), 1, 100)));
    }

    @Tool(structured = true, description = "Assess the risk of changing a class or project file. Use before modifying code, build configuration, resources, service descriptors, or CI configuration. "
            + "Returns: {target, risk_score (0-10), risk_level (LOW/MEDIUM/HIGH/CRITICAL), "
            + "target_type (class/file), signals: {fan_in?, fan_out?, file_criticality?, git_churn?, bus_factor?, coupling?: {value, edges?, breakdown?, score, weight, note}}, recommendation, _meta}")
    public String assess_change_risk(
            @ToolArg(description = "Class name (short or FQCN), or any project/repository file path") String target,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getRisk(p.jdbi(), target));
    }

    @Tool(structured = true, description = "Show external library dependencies. Use to find which classes use a specific library (e.g. Jackson, JPA) or what third-party types a class depends on. "
            + "Per-class: {target, external_dependencies: {field: [...], extends: [...]}, total_external_types}. "
            + "Per-library: {library_filter, classes_using_library: [...]}. "
            + "Summary: {libraries: [{package, used_by_classes}]}")
    public String list_external_dependencies(
            @ToolArg(description = "Class name to inspect (short or FQCN). If omitted, shows project-wide library usage summary.") Optional<String> target,
            @ToolArg(description = "Filter by library package prefix, e.g. 'com.fasterxml.jackson' or 'jakarta.persistence'") Optional<String> library,
            @ToolArg(description = "Max results for library summary (default: 20)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
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

    String getCoChanges(Jdbi jdbi, String target, int limit) {
        return queries.getCoChanges(jdbi, target, limit);
    }

    String getRecentChanges(Jdbi jdbi, int commitCount) {
        return queries.getRecentChanges(jdbi, commitCount);
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
                    ? result.json()
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
        return root.toString();
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
