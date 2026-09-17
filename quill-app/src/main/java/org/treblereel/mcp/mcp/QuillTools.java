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

    @Tool(structured = true, description = "Find direct/transitive implementations across indexed reactor outputs, including duplicate generated FQCNs by module.")
    public String find_implementations(
            @ToolArg(description = "Current class or interface name (short or FQCN), or its source path") String target,
            @ToolArg(description = "Include indirect implementations through intermediate types (default: true)") Optional<Boolean> transitive,
            @ToolArg(description = "Only occurrences from this project-relative module") Optional<String> module,
            @ToolArg(description = "Source set filter, e.g. main or test") Optional<String> source_set,
            @ToolArg(description = "Logical implementation classes per page (default: 50, max: 100)") Optional<Integer> limit,
            @ToolArg(description = "Result offset for pagination (default: 0)") Optional<Integer> offset,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.findImplementations(
                p.jdbi(), target, transitive.orElse(true), module.orElse(null),
                source_set.orElse(null), clamp(limit.orElse(50), 1, 100),
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

    @Tool(structured = true, description = "Get commit history for one current class or file.")
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
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> queries.getRecentChanges(
                p.jdbi(), clamp(commits.orElse(10), 1, 200)));
    }

    @Tool(structured = true, description = "Inspect ordered META-INF/services providers with lines and order-sensitivity analysis.")
    public String inspect_service_descriptors(
            @ToolArg(description = "Optional service FQCN or short name, e.g. javax.annotation.processing.Processor") Optional<String> service,
            @ToolArg(description = "Project from get_overview; omit for all") Optional<String> project) {
        return forAllProjects(project.orElse(null),
                p -> queries.inspectServiceDescriptors(p.jdbi(), service.orElse(null)));
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
