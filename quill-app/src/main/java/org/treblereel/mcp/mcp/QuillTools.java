package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jdbi.v3.core.Jdbi;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.treblereel.mcp.core.TokenCounter;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.model.CdiProblem;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.*;

public class QuillTools {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GRAPH_NODES = 200;
    private static final int MAX_RECENT_CHANGE_FILES = 200;
    private static final int MAX_PROBLEM_DETAILS = 50;

    private final ProjectRegistry registry;

    public QuillTools() {
        this(new ProjectRegistry());
    }

    public QuillTools(ProjectRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    private record ProjectResult(String name, String json, String error) {}

    private static final Set<String> NOT_FOUND_PREFIXES = Set.of(
            "{\"error\":\"Class not found:",
            "{\"error\":\"Not a bean:");

    private boolean isNotFoundError(String json) {
        if (json == null) return false;
        for (String prefix : NOT_FOUND_PREFIXES) {
            if (json.startsWith(prefix)) return true;
        }
        return false;
    }

    private String forAllProjects(String projectFilter, Function<ProjectRegistry.ProjectEntry, String> perProject) {
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
            if (result.error() != null) {
                return errorResponse("Project '" + result.name() + "': " + result.error());
            }
            return result.json();
        }

        List<ProjectResult> projectResults;
        if (projects.size() <= 1) {
            projectResults = new ArrayList<>();
            for (ProjectRegistry.ProjectEntry p : projects) {
                projectResults.add(runForProject(p, perProject));
            }
        } else {
            ConcurrentLinkedQueue<ProjectResult> queue = new ConcurrentLinkedQueue<>();
            CompletableFuture<?>[] futures = projects.stream()
                    .map(p -> CompletableFuture.runAsync(() -> queue.add(runForProject(p, perProject))))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
            projectResults = new ArrayList<>(queue);
        }
        projectResults.sort(Comparator.comparing(ProjectResult::name));

        List<ProjectResult> hits = new ArrayList<>();
        List<ProjectResult> notFound = new ArrayList<>();
        for (ProjectResult pr : projectResults) {
            if (pr.error() != null || isNotFoundError(pr.json())) {
                notFound.add(pr);
            } else {
                hits.add(pr);
            }
        }

        if (hits.isEmpty() && !notFound.isEmpty()) {
            hits = notFound;
        }

        ObjectNode root = JSON.createObjectNode();
        ArrayNode results = root.putArray("projects");
        for (ProjectResult pr : hits) {
            try {
                ObjectNode wrapper = results.addObject();
                wrapper.put("project", pr.name());
                if (pr.error() != null) {
                    wrapper.put("error", pr.error());
                } else {
                    wrapper.set("data", JSON.readTree(pr.json()));
                }
            } catch (Exception e) {
                ObjectNode wrapper = results.addObject();
                wrapper.put("project", pr.name());
                wrapper.put("error", e.getMessage());
            }
        }
        if (!notFound.isEmpty() && !hits.isEmpty() && hits != notFound) {
            root.put("skipped_projects", notFound.size());
        }
        if (!errors.isEmpty()) {
            ArrayNode errArr = root.putArray("uninitialized");
            for (String err : errors) {
                errArr.add(err);
            }
        }
        return root.toString();
    }

    private ProjectResult runForProject(ProjectRegistry.ProjectEntry p,
                                         Function<ProjectRegistry.ProjectEntry, String> perProject) {
        try {
            return new ProjectResult(p.name(), perProject.apply(p), null);
        } catch (Exception e) {
            return new ProjectResult(p.name(), null, ProjectRegistry.safeMessage(e));
        }
    }

    @Tool(description = "List beans (CDI or Spring) with optional filtering. Use instead of grep/find when looking for injectable services, producers, interceptors, or decorators. "
            + "Returns: {beans: [{class, kind, scope, qualifiers, bean_types, profiles, source}], total, showing, _meta}")
    public String list_beans(
            @ToolArg(description = "Class name filter (supports * wildcard)") Optional<String> class_name,
            @ToolArg(description = "Scope filter, e.g. @ApplicationScoped or @Singleton") Optional<String> scope,
            @ToolArg(description = "Bean kind: CLASS, PRODUCER_METHOD, PRODUCER_FIELD, INTERCEPTOR, DECORATOR") Optional<String> kind,
            @ToolArg(description = "Build profile filter, e.g. dev") Optional<String> profile,
            @ToolArg(description = "Qualifier filter, e.g. @Premium or @Qualifier(\"stripe\")") Optional<String> qualifier,
            @ToolArg(description = "Max results to return (default: 50)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getBeans(p.jdbi(), class_name.orElse(null), scope.orElse(null),
                kind.orElse(null), profile.orElse(null), qualifier.orElse(null), clamp(limit.orElse(50), 1, 100)));
    }

    @Tool(description = "Alias for list_beans. List beans (CDI or Spring) with optional filtering. "
            + "Returns: {beans: [{class, kind, scope, qualifiers, bean_types, profiles, source}], total, showing, _meta}")
    public String list_cdi_beans(
            @ToolArg(description = "Class name filter (supports * wildcard)") Optional<String> class_name,
            @ToolArg(description = "Scope filter, e.g. @ApplicationScoped") Optional<String> scope,
            @ToolArg(description = "Bean kind: CLASS, PRODUCER_METHOD, PRODUCER_FIELD, INTERCEPTOR, DECORATOR") Optional<String> kind,
            @ToolArg(description = "Build profile filter, e.g. dev") Optional<String> profile,
            @ToolArg(description = "Qualifier filter, e.g. @Premium") Optional<String> qualifier,
            @ToolArg(description = "Max results to return (default: 50)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return list_beans(class_name, scope, kind, profile, qualifier, limit, project);
    }

    @Tool(description = "Get dependency graph for a specific bean or class. Use instead of grep for imports/references when you need to understand what a class uses or what uses it. "
            + "Returns: {target, is_bean, scope?, depends_on: [{class, kind}], depended_by: [{class, kind}], _meta}")
    public String get_dependencies(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Direction: inbound, outbound, or both (default: both)") Optional<String> direction,
            @ToolArg(description = "Graph traversal depth (default: 1)") Optional<Integer> depth,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getDependencies(p.jdbi(), target, direction.orElse("both"), clamp(depth.orElse(1), 1, 5)));
    }

    @Tool(description = "Get injection points for a bean with resolution status. Use when checking what a bean injects and whether injections resolve correctly. "
            + "Returns: {target, injection_points: [{kind, field, required_type, qualifiers, resolved_to, resolution}], unsatisfied: [], ambiguous: [], _meta}")
    public String list_injection_points(
            @ToolArg(description = "Bean class name (short or FQCN)") String target,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getInjectionPoints(p.jdbi(), target));
    }

    @Tool(description = "Get most frequently changed files/classes by git commit count. Use instead of git log when looking for volatile or high-churn areas of the codebase. "
            + "Returns: {hotspots: [{file, class?, is_bean?, commit_count, distinct_authors, last_modified, last_author}], total, _meta}")
    public String find_git_hotspots(
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Only commits after this date, ISO format YYYY-MM-DD") Optional<String> since,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getHotspots(p.jdbi(), clamp(limit.orElse(10), 1, 100), since.orElse(null)));
    }

    @Tool(description = "Get git commit history for a specific class or file. Use instead of git log when you need change history for a particular class. "
            + "Returns: {target, file, commits: [{hash, author, date, message}], total_commits, _meta}")
    public String get_file_history(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Max commits to return (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getFileHistory(p.jdbi(), target, clamp(limit.orElse(10), 1, 100)));
    }

    @Tool(description = "Find files that frequently change together with a given class. Reveals hidden coupling. Use before refactoring to find files you might also need to change. "
            + "Returns: {target, co_changes: [{file, class?, co_change_count, coupling_ratio}], _meta}")
    public String find_co_changed_files(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Max results (default: 10)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getCoChanges(p.jdbi(), target, clamp(limit.orElse(10), 1, 100)));
    }

    @Tool(description = "Get recently changed classes from git history. Use to understand what was modified recently and by whom. "
            + "Returns: {recent_changes: [{commit, author, date, message, files: [{file, change_type, class?, is_bean?}]}], _meta}")
    public String get_recent_changes(
            @ToolArg(description = "Number of recent commits to inspect (default: 10)") Optional<Integer> commits,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getRecentChanges(p.jdbi(), clamp(commits.orElse(10), 1, 200)));
    }

    @Tool(description = "Get a high-level project overview. Call this FIRST when starting work on a project — gives framework, class/bean counts, architecture hubs, and known problems. "
            + "Returns: {project: {framework, classes, beans, total_source_tokens, indexed_at, last_commit, dependency_index, dependency_index_detail?}, "
            + "beans_by_scope: {...}, beans_by_kind: {...}, "
            + "architecture_hubs: [{class, dependents, is_bean}], "
            + "problems: {unsatisfied_count, unsatisfied_injection_points_sample: [{bean, field, type}], ambiguous_count, ambiguous_injection_points_sample: [...]}, "
            + "top_libraries: [{package, used_by_classes}], "
            + "git_summary: {total_commits_indexed, top_hotspots: [{file, commit_count}]}, _meta}. "
            + "For multi-project: {projects: [{project, data: <above>}], uninitialized?: [...]}")
    public String get_overview(
            @ToolArg(description = "Project name to query. Omit to get overview of all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getOverview(p.jdbi()));
    }

    @Tool(description = "Search for classes by name pattern (supports * wildcard). Returns all classes, not just beans. Use instead of grep/find when looking for a class by name. "
            + "Returns: {classes: [{class, source, is_bean, scope?, source_tokens}], showing, total, _meta}")
    public String search_classes(
            @ToolArg(description = "Class name pattern (supports * wildcard, e.g. '*Service', 'io.casehub.*.model.*')") String pattern,
            @ToolArg(description = "Max results (default: 30)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> searchClasses(p.jdbi(), pattern, clamp(limit.orElse(30), 1, 100)));
    }

    @Tool(description = "Assess the risk of changing a specific class. Use before modifying a class to understand blast radius, change frequency, and bus factor. "
            + "Returns: {target, risk_score (0-10), risk_level (LOW/MEDIUM/HIGH/CRITICAL), "
            + "signals: {fan_in, fan_out, git_churn, bus_factor, coupling: {value, score, weight, note}}, recommendation, _meta}")
    public String assess_change_risk(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getRisk(p.jdbi(), target));
    }

    @Tool(description = "Show external library dependencies. Use to find which classes use a specific library (e.g. Jackson, JPA) or what third-party types a class depends on. "
            + "Per-class: {target, external_dependencies: {field: [...], extends: [...]}, total_external_types}. "
            + "Per-library: {library_filter, classes_using_library: [...]}. "
            + "Summary: {libraries: [{package, used_by_classes}]}")
    public String list_external_dependencies(
            @ToolArg(description = "Class name to inspect (short or FQCN). If omitted, shows project-wide library usage summary.") Optional<String> target,
            @ToolArg(description = "Filter by library package prefix, e.g. 'com.fasterxml.jackson' or 'jakarta.persistence'") Optional<String> library,
            @ToolArg(description = "Max results for library summary (default: 20)") Optional<Integer> limit,
            @ToolArg(description = "Project name to query (from get_overview). Omit to query all projects.") Optional<String> project) {
        return forAllProjects(project.orElse(null), p -> getExternalDeps(p.jdbi(), target.orElse(null), library.orElse(null), clamp(limit.orElse(20), 1, 100)));
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind, String profile, String qualifier) {
        return getBeans(jdbi, className, scope, kind, profile, qualifier, 50);
    }

    String searchClasses(Jdbi jdbi, String pattern, int limit) {
        List<ClassRecord> classes = IndexReader.searchClasses(jdbi, pattern, limit + 1);
        int total = classes.size() > limit ? classes.size() : classes.size();
        boolean hasMore = classes.size() > limit;
        List<ClassRecord> limited = hasMore ? classes.subList(0, limit) : classes;
        Map<Integer, BeanRecord> beansByClass = IndexReader.findBeansByClassIds(
                jdbi, limited.stream().map(ClassRecord::id).toList());

        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("classes");
        int naiveTokens = 0;
        for (ClassRecord c : limited) {
            ObjectNode node = arr.addObject();
            node.put("class", c.className());
            node.put("source", c.sourceFile() + ":" + c.sourceLine());
            node.put("is_bean", c.isBean());
            BeanRecord bean = beansByClass.get(c.id());
            if (bean != null) node.put("scope", bean.scope());
            node.put("source_tokens", c.sourceTokens());
            naiveTokens += c.sourceTokens();
        }
        root.put("showing", limited.size());
        if (hasMore) {
            root.put("total", ">" + limit + " (use a more specific pattern)");
        } else {
            root.put("total", limited.size());
        }
        appendMeta(root, jdbi, naiveTokens);
        return root.toString();
    }

    String getBeans(Jdbi jdbi, String className, String scope, String kind, String profile, String qualifier, int limit) {
        Map<String, String> filter = new HashMap<>();
        if (className != null) filter.put("class_name", className);
        if (scope != null) filter.put("scope", scope);
        if (kind != null) filter.put("kind", kind);
        if (profile != null) filter.put("profile", profile);
        if (qualifier != null) filter.put("qualifier", qualifier);

        List<BeanRecord> beans = IndexReader.findBeans(jdbi, filter.isEmpty() ? null : filter);
        int total = beans.size();
        List<BeanRecord> limited = beans.size() > limit ? beans.subList(0, limit) : beans;
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(
                jdbi, limited.stream().map(BeanRecord::classId).toList());

        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("beans");
        int[] naiveTokensWrapper = {0};

        for (BeanRecord b : limited) {
            ObjectNode node = arr.addObject();
            ClassRecord beanClass = classesById.get(b.classId());
            String fqcn = beanClass != null ? beanClass.className() : "unknown";
            node.put("class", fqcn);
            if (b.memberName() != null) {
                node.put("member", b.memberName());
            }
            if (isProducer(b.kind()) && b.beanTypes() != null && !b.beanTypes().isEmpty()) {
                node.put("produced_type", b.beanTypes().get(0));
            }
            node.put("kind", b.kind());
            node.put("scope", b.scope());
            node.set("qualifiers", JSON.valueToTree(b.qualifiers()));
            node.set("bean_types", JSON.valueToTree(b.beanTypes()));
            node.set("profiles", JSON.valueToTree(b.profiles()));
            if (beanClass != null) {
                node.put("source", beanClass.sourceFile() + ":" + beanClass.sourceLine());
                naiveTokensWrapper[0] += beanClass.sourceTokens();
            }
        }
        root.put("showing", limited.size());
        root.put("total", total);

        appendMeta(root, jdbi, naiveTokensWrapper[0]);
        return root.toString();
    }

    String getDependencies(Jdbi jdbi, String target, String direction, int depth) {
        var lookup = resolveClass(jdbi, target);
        if (lookup.error() != null) return errorResponse(lookup.error());
        ClassRecord cls = lookup.cls();

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("is_bean", cls.isBean());

        if (cls.isBean()) {
            IndexReader.findBeanByClassId(jdbi, cls.id()).ifPresent(b -> {
                root.put("scope", b.scope());
            });
        }

        int[] naiveTokens = {cls.sourceTokens()};
        Set<Integer> visited = new HashSet<>();
        visited.add(cls.id());
        GraphBudget budget = new GraphBudget(MAX_GRAPH_NODES);

        expandDependencies(jdbi, cls.id(), direction, depth, root, visited,
                t -> naiveTokens[0] += t, budget);
        if (budget.truncated) {
            root.put("truncated", true);
            root.put("node_limit", MAX_GRAPH_NODES);
        }

        appendMeta(root, jdbi, naiveTokens[0]);
        return root.toString();
    }

    private void expandDependencies(Jdbi jdbi, int classId, String direction, int depth,
                                     ObjectNode node, Set<Integer> visited, IntConsumer tokenAccum,
                                     GraphBudget budget) {
        List<DependencyRecord> deps = IndexReader.findDependencies(jdbi, classId, direction);

        ArrayNode dependsOn = node.putArray("depends_on");
        ArrayNode dependedBy = node.putArray("depended_by");

        for (DependencyRecord d : deps) {
            if (d.fromClassId() == classId) {
                if (!budget.claim()) break;
                IndexReader.findClassById(jdbi, d.toClassId()).ifPresent(c -> {
                    ObjectNode child = dependsOn.addObject();
                    child.put("class", c.className());
                    child.put("kind", d.kind());
                    tokenAccum.accept(c.sourceTokens());
                    if (depth > 1 && visited.add(c.id())) {
                        expandDependencies(jdbi, c.id(), direction, depth - 1, child,
                                visited, tokenAccum, budget);
                    }
                });
            }
            if (d.toClassId() == classId) {
                if (!budget.claim()) break;
                IndexReader.findClassById(jdbi, d.fromClassId()).ifPresent(c -> {
                    ObjectNode child = dependedBy.addObject();
                    child.put("class", c.className());
                    child.put("kind", d.kind());
                    tokenAccum.accept(c.sourceTokens());
                    if (depth > 1 && visited.add(c.id())) {
                        expandDependencies(jdbi, c.id(), direction, depth - 1, child,
                                visited, tokenAccum, budget);
                    }
                });
            }
        }
    }

    String getInjectionPoints(Jdbi jdbi, String target) {
        var lookup = resolveClass(jdbi, target);
        if (lookup.error() != null) return errorResponse(lookup.error());
        ClassRecord cls = lookup.cls();

        var beanOpt = IndexReader.findBeanByClassId(jdbi, cls.id());
        if (beanOpt.isEmpty()) return errorResponse("Not a bean: " + target);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());

        List<InjectionPointRecord> ips = IndexReader.findInjectionPoints(jdbi, beanOpt.get().id());
        Map<Integer, BeanRecord> resolvedBeans = IndexReader.findBeansByIds(jdbi, ips.stream()
                .map(InjectionPointRecord::resolvedBeanId).filter(Objects::nonNull).toList());
        Map<Integer, ClassRecord> resolvedClasses = IndexReader.findClassesByIds(jdbi, resolvedBeans.values()
                .stream().map(BeanRecord::classId).toList());

        ArrayNode arr = root.putArray("injection_points");
        ArrayNode unsatisfied = root.putArray("unsatisfied");
        ArrayNode ambiguous = root.putArray("ambiguous");

        for (InjectionPointRecord ip : ips) {
            ObjectNode node = arr.addObject();
            node.put("kind", ip.kind());
            node.put("field", ip.fieldName());
            node.put("required_type", ip.targetType());
            node.set("qualifiers", JSON.valueToTree(ip.qualifiers()));

            if (ip.isAmbiguous()) {
                node.put("resolved_to", (String) null);
                node.put("resolution", "ambiguous");
                ambiguous.add(ip.fieldName());
            } else if (ip.resolvedBeanId() != null) {
                BeanRecord resolved = resolvedBeans.get(ip.resolvedBeanId());
                if (resolved != null) {
                    ClassRecord resolvedClass = resolvedClasses.get(resolved.classId());
                    if (resolvedClass != null) node.put("resolved_to", resolvedClass.className());
                    if (resolved.memberName() != null) {
                        node.put("resolved_member", resolved.memberName());
                    }
                    if (isProducer(resolved.kind()) && resolved.beanTypes() != null
                            && !resolved.beanTypes().isEmpty()) {
                        node.put("resolved_produced_type", resolved.beanTypes().get(0));
                    }
                }
                node.put("resolution", "unique");
            } else {
                node.put("resolved_to", (String) null);
                node.put("resolution", "unsatisfied");
                unsatisfied.add(ip.fieldName());
            }
        }

        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    private static final String NO_GIT_MESSAGE = "No git data available. "
            + "Initialize a git repository and re-run 'quill init' to enable git intelligence: "
            + "git init && git add -A && git commit -m 'initial'";

    String getHotspots(Jdbi jdbi, int limit, String since) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        List<GitFileStats> hotspots = IndexReader.findHotspots(jdbi, limit, since);
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, hotspots.stream()
                .map(GitFileStats::classId).filter(Objects::nonNull).toList());
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("hotspots");

        for (GitFileStats s : hotspots) {
            ObjectNode node = arr.addObject();
            node.put("file", s.filePath());
            if (s.classId() != null) {
                Optional.ofNullable(classesById.get(s.classId())).ifPresent(c -> {
                    node.put("class", c.className());
                    node.put("is_bean", c.isBean());
                });
            }
            node.put("commit_count", s.commitCount());
            node.put("distinct_authors", s.distinctAuthors());
            node.put("last_modified", s.lastModified());
            node.put("last_author", s.lastAuthor());
        }
        root.put("total", hotspots.size());
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    String getFileHistory(Jdbi jdbi, String target, int limit) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        var lookup = resolveClass(jdbi, target);
        if (lookup.error() != null) return errorResponse(lookup.error());
        ClassRecord cls = lookup.cls();

        List<GitCommitRecord> commits = IndexReader.findFileHistory(jdbi, cls.id(), limit);
        var statsOpt = IndexReader.findFileStatsByClassId(jdbi, cls.id());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("file", cls.sourceFile());

        ArrayNode arr = root.putArray("commits");
        for (GitCommitRecord c : commits) {
            ObjectNode node = arr.addObject();
            node.put("hash", c.shortHash());
            node.put("author", c.author());
            node.put("date", c.committedAt());
            node.put("message", c.message());
        }
        statsOpt.ifPresent(s -> root.put("total_commits", s.commitCount()));
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    String getCoChanges(Jdbi jdbi, String target, int limit) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        var lookup = resolveClass(jdbi, target);
        if (lookup.error() != null) return errorResponse(lookup.error());
        ClassRecord cls = lookup.cls();

        var statsOpt = IndexReader.findFileStatsByClassId(jdbi, cls.id());
        int targetCommitCount = statsOpt.map(GitFileStats::commitCount).orElse(1);

        List<CoChangeRecord> coChanges = IndexReader.findCoChanges(jdbi, cls.id(), limit);
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, coChanges.stream()
                .map(CoChangeRecord::classId).filter(Objects::nonNull).toList());

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        ArrayNode arr = root.putArray("co_changes");
        for (CoChangeRecord co : coChanges) {
            ObjectNode node = arr.addObject();
            node.put("file", co.filePath());
            if (co.classId() != null) {
                Optional.ofNullable(classesById.get(co.classId())).ifPresent(c -> {
                    node.put("class", c.className());
                });
            }
            node.put("co_change_count", co.coChangeCount());
            double ratio = (double) co.coChangeCount() / targetCommitCount;
            node.put("coupling_ratio", Math.round(ratio * 100.0) / 100.0);
        }
        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    String getRecentChanges(Jdbi jdbi, int commitCount) {
        if (!IndexReader.hasGitData(jdbi)) return errorResponse(NO_GIT_MESSAGE);

        List<GitCommitRecord> commits = IndexReader.findRecentCommits(jdbi, commitCount);
        Map<Integer, List<GitCommitFile>> filesByCommit = IndexReader.findCommitFiles(jdbi,
                commits.stream().map(GitCommitRecord::id).toList(), MAX_RECENT_CHANGE_FILES + 1);
        Map<Integer, ClassRecord> classesById = IndexReader.findClassesByIds(jdbi, filesByCommit.values()
                .stream().flatMap(Collection::stream).map(GitCommitFile::classId)
                .filter(Objects::nonNull).toList());
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("recent_changes");
        int fileCount = 0;
        boolean truncated = false;

        recentCommits:
        for (GitCommitRecord c : commits) {
            ObjectNode node = arr.addObject();
            node.put("commit", c.shortHash());
            node.put("author", c.author());
            node.put("date", c.committedAt());
            node.put("message", c.message());

            List<GitCommitFile> files = filesByCommit.getOrDefault(c.id(), List.of());
            ArrayNode filesArr = node.putArray("files");
            for (GitCommitFile f : files) {
                if (fileCount >= MAX_RECENT_CHANGE_FILES) {
                    truncated = true;
                    break recentCommits;
                }
                ObjectNode fNode = filesArr.addObject();
                fileCount++;
                fNode.put("file", f.filePath());
                fNode.put("change_type", f.changeType());
                if (f.classId() != null) {
                    Optional.ofNullable(classesById.get(f.classId())).ifPresent(cl -> {
                        fNode.put("class", cl.className());
                        fNode.put("is_bean", cl.isBean());
                    });
                }
            }
        }
        if (truncated) {
            root.put("truncated", true);
            root.put("file_limit", MAX_RECENT_CHANGE_FILES);
        }
        appendMeta(root, jdbi, 0);
        return root.toString();
    }

    String getOverview(Jdbi jdbi) {
        Map<String, String> meta = IndexReader.getMetadata(jdbi);
        ObjectNode root = JSON.createObjectNode();

        ObjectNode project = root.putObject("project");
        int classCount = IndexReader.countClasses(jdbi);
        int beanCount = IndexReader.countBeans(jdbi);
        List<ClassRecord> allClasses = IndexReader.findAllClasses(jdbi);
        Map<Integer, ClassRecord> classesById = new LinkedHashMap<>();
        allClasses.forEach(record -> classesById.put(record.id(), record));
        int totalTokens = allClasses.stream().mapToInt(ClassRecord::sourceTokens).sum();
        project.put("framework", meta.getOrDefault("framework", "CDI"));
        project.put("classes", classCount);
        project.put("beans", beanCount);
        project.put("total_source_tokens", totalTokens);
        project.put("indexed_at", meta.getOrDefault("indexed_at", "unknown"));
        project.put("last_commit", meta.getOrDefault("last_commit", "unknown"));
        project.put("dependency_index", meta.getOrDefault("dependency_index", "unknown"));
        if (meta.containsKey("dependency_index_detail")) {
            project.put("dependency_index_detail", meta.get("dependency_index_detail"));
        }

        ObjectNode scopeNode = root.putObject("beans_by_scope");
        IndexReader.countBeansByScope(jdbi).forEach(scopeNode::put);

        ObjectNode kindNode = root.putObject("beans_by_kind");
        IndexReader.countBeansByKind(jdbi).forEach(kindNode::put);

        ArrayNode hubs = root.putArray("architecture_hubs");
        for (var entry : IndexReader.findMostDependedOn(jdbi, 5)) {
            Optional.ofNullable(classesById.get(entry.getKey())).ifPresent(c -> {
                ObjectNode hub = hubs.addObject();
                hub.put("class", c.className());
                hub.put("dependents", entry.getValue());
                hub.put("is_bean", c.isBean());
            });
        }

        ObjectNode problems = root.putObject("problems");
        List<InjectionPointRecord> unsatisfied = IndexReader.findUnsatisfiedInjectionPoints(jdbi);
        List<InjectionPointRecord> ambiguous = IndexReader.findAmbiguousInjectionPoints(jdbi);
        List<InjectionPointRecord> problemSample = new ArrayList<>();
        problemSample.addAll(unsatisfied.stream().limit(10).toList());
        problemSample.addAll(ambiguous.stream().limit(10).toList());
        Map<Integer, BeanRecord> problemBeans = IndexReader.findBeansByIds(jdbi,
                problemSample.stream().map(InjectionPointRecord::beanId).toList());
        problems.put("unsatisfied_count", unsatisfied.size());
        if (!unsatisfied.isEmpty()) {
            List<InjectionPointRecord> unsatLimited = unsatisfied.size() > 10
                    ? unsatisfied.subList(0, 10) : unsatisfied;
            ArrayNode unsatArr = problems.putArray("unsatisfied_injection_points_sample");
            for (InjectionPointRecord ip : unsatLimited) {
                ObjectNode node = unsatArr.addObject();
                BeanRecord bean = problemBeans.get(ip.beanId());
                if (bean != null && classesById.containsKey(bean.classId())) {
                    node.put("bean", classesById.get(bean.classId()).className());
                }
                node.put("field", ip.fieldName());
                node.put("type", ip.targetType());
            }
        }
        problems.put("ambiguous_count", ambiguous.size());
        if (!ambiguous.isEmpty()) {
            List<InjectionPointRecord> ambLimited = ambiguous.size() > 10
                    ? ambiguous.subList(0, 10) : ambiguous;
            ArrayNode ambArr = problems.putArray("ambiguous_injection_points_sample");
            for (InjectionPointRecord ip : ambLimited) {
                ObjectNode node = ambArr.addObject();
                BeanRecord bean = problemBeans.get(ip.beanId());
                if (bean != null && classesById.containsKey(bean.classId())) {
                    node.put("bean", classesById.get(bean.classId()).className());
                }
                node.put("field", ip.fieldName());
                node.put("type", ip.targetType());
            }
        }

        List<CdiProblem> cdiProblems = IndexReader.findCdiProblems(jdbi);
        if (!cdiProblems.isEmpty()) {
            problems.put("cdi_spec_violation_count", cdiProblems.size());
            ArrayNode cdiArr = problems.putArray("cdi_spec_violations");
            for (CdiProblem p : cdiProblems.stream().limit(MAX_PROBLEM_DETAILS).toList()) {
                ObjectNode node = cdiArr.addObject();
                node.put("class", p.className());
                node.put("type", p.problemType());
                node.put("message", p.message());
            }
            if (cdiProblems.size() > MAX_PROBLEM_DETAILS) {
                problems.put("cdi_spec_violations_truncated", true);
            }
        }

        if (IndexReader.hasExternalDeps(jdbi)) {
            ArrayNode libsArr = root.putArray("top_libraries");
            for (var entry : IndexReader.findExternalDepsByLibrary(jdbi, 5)) {
                ObjectNode lib = libsArr.addObject();
                lib.put("package", entry.getKey());
                lib.put("used_by_classes", entry.getValue());
            }
        }

        if (IndexReader.hasGitData(jdbi)) {
            ObjectNode gitSummary = root.putObject("git_summary");
            gitSummary.put("total_commits_indexed", IndexReader.countCommits(jdbi));
            ArrayNode hotspotsArr = gitSummary.putArray("top_hotspots");
            for (GitFileStats s : IndexReader.findHotspots(jdbi, 3, null)) {
                ObjectNode h = hotspotsArr.addObject();
                h.put("file", s.filePath());
                h.put("commit_count", s.commitCount());
            }
        } else {
            root.putNull("git_summary");
        }

        appendMeta(root, jdbi, totalTokens);
        return root.toString();
    }

    String getRisk(Jdbi jdbi, String target) {
        var lookup = resolveClass(jdbi, target);
        if (lookup.error() != null) return errorResponse(lookup.error());
        ClassRecord cls = lookup.cls();

        int fanIn = IndexReader.countDependents(jdbi, cls.id());
        int fanOut = IndexReader.countDependencies(jdbi, cls.id());

        boolean hasGit = IndexReader.hasGitData(jdbi);
        var statsOpt = hasGit ? IndexReader.findFileStatsByClassId(jdbi, cls.id()) : Optional.<GitFileStats>empty();
        int churn = statsOpt.map(GitFileStats::commitCount).orElse(0);
        int authors = statsOpt.map(GitFileStats::distinctAuthors).orElse(0);
        int coChangeCount = 0;
        if (hasGit) {
            coChangeCount = IndexReader.findCoChanges(jdbi, cls.id(), 100).size();
        }

        double fanInScore = scaleScore(fanIn, 0, 3, 8, 15);
        double fanOutScore = scaleScore(fanOut, 0, 3, 5, 8);
        double churnScore = hasGit ? scaleScore(churn, 2, 10, 30, 50) : 0;
        double busFactorScore = hasGit ? busFactorScore(authors) : 0;
        double couplingScore = hasGit ? scaleScore(coChangeCount, 0, 3, 5, 8) : 0;

        double score = fanInScore * 0.30
                + fanOutScore * 0.10
                + churnScore * 0.25
                + busFactorScore * 0.20
                + couplingScore * 0.15;
        score = Math.round(score * 10.0) / 10.0;

        String level;
        if (score >= 8) level = "CRITICAL";
        else if (score >= 6) level = "HIGH";
        else if (score >= 3) level = "MEDIUM";
        else level = "LOW";

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("risk_score", score);
        root.put("risk_level", level);

        ObjectNode signals = root.putObject("signals");
        addSignal(signals, "fan_in", fanIn, fanInScore, 0.30,
                fanIn + " classes depend on this");
        addSignal(signals, "fan_out", fanOut, fanOutScore, 0.10,
                "depends on " + fanOut + " classes");
        if (hasGit) {
            addSignal(signals, "git_churn", churn, churnScore, 0.25,
                    churn + " commits — " + (churn >= 30 ? "high" : churn >= 10 ? "moderate" : "low") + " change frequency");
            addSignal(signals, "bus_factor", authors, busFactorScore, 0.20,
                    authors <= 1 ? "only 1 author — single point of knowledge"
                            : authors + " authors");
            addSignal(signals, "coupling", coChangeCount, couplingScore, 0.15,
                    coChangeCount + " files frequently co-change");
        } else {
            ObjectNode gitNote = signals.putObject("git");
            gitNote.put("note", "Git data unavailable — git signals excluded from score. Run 'quill init' in a git repository.");
        }

        root.put("recommendation", buildRecommendation(cls, fanIn, churn, authors, level, hasGit));

        appendMeta(root, jdbi, cls.sourceTokens());
        return root.toString();
    }

    String getExternalDeps(Jdbi jdbi, String target, String library, int limit) {
        if (!IndexReader.hasExternalDeps(jdbi)) {
            return errorResponse("No external dependency data. Re-run 'quill init' to index external dependencies.");
        }

        ObjectNode root = JSON.createObjectNode();

        if (target != null) {
            var lookup = resolveClass(jdbi, target);
            if (lookup.error() != null) return errorResponse(lookup.error());
            ClassRecord cls = lookup.cls();

            root.put("target", cls.className());
            var deps = IndexReader.findExternalDeps(jdbi, cls.id());

            Map<String, List<ExternalDepRecord>> byKind = new LinkedHashMap<>();
            for (ExternalDepRecord d : deps) {
                byKind.computeIfAbsent(d.usageKind(), k -> new ArrayList<>()).add(d);
            }

            ObjectNode depsNode = root.putObject("external_dependencies");
            for (var entry : byKind.entrySet()) {
                ArrayNode arr = depsNode.putArray(entry.getKey().toLowerCase());
                for (ExternalDepRecord d : entry.getValue()) {
                    arr.add(d.externalType());
                }
            }
            root.put("total_external_types", deps.size());
            appendMeta(root, jdbi, cls.sourceTokens());
        } else if (library != null) {
            root.put("library_filter", library);
            var classes = IndexReader.findClassesUsingType(jdbi, library + ".%");
            ArrayNode arr = root.putArray("classes_using_library");
            for (var entry : classes) {
                arr.add(entry.getValue());
            }
            root.put("total_classes", classes.size());
            appendMeta(root, jdbi, 0);
        } else {
            ArrayNode arr = root.putArray("libraries");
            for (var entry : IndexReader.findExternalDepsByLibrary(jdbi, limit)) {
                ObjectNode node = arr.addObject();
                node.put("package", entry.getKey());
                node.put("used_by_classes", entry.getValue());
            }
            appendMeta(root, jdbi, 0);
        }

        return root.toString();
    }

    private static double scaleScore(int value, int low, int mid, int high, int max) {
        if (value <= low) return 0;
        if (value >= max) return 10;
        if (value <= mid) return 5.0 * (value - low) / (mid - low);
        if (value <= high) return 5.0 + 3.0 * (value - mid) / (high - mid);
        return 8.0 + 2.0 * (value - high) / (max - high);
    }

    private static double busFactorScore(int authors) {
        if (authors <= 0) return 0;
        if (authors == 1) return 10;
        if (authors == 2) return 5;
        if (authors == 3) return 2;
        return 0;
    }

    private static void addSignal(ObjectNode signals, String name, int value, double score, double weight, String note) {
        ObjectNode s = signals.putObject(name);
        s.put("value", value);
        s.put("score", Math.round(score * 10.0) / 10.0);
        s.put("weight", weight);
        s.put("note", note);
    }

    private static String buildRecommendation(ClassRecord cls, int fanIn, int churn, int authors, String level, boolean hasGit) {
        List<String> parts = new ArrayList<>();
        String shortName = cls.className().contains(".")
                ? cls.className().substring(cls.className().lastIndexOf('.') + 1) : cls.className();

        if ("CRITICAL".equals(level) || "HIGH".equals(level)) {
            parts.add(level + "-risk change target.");
        } else if ("MEDIUM".equals(level)) {
            parts.add("Moderate risk.");
        } else {
            parts.add("Low risk — safe to modify.");
        }

        if (fanIn > 0) {
            parts.add(fanIn + " dependents will be affected. Review with get_dependencies(\"" + shortName + "\", direction=\"inbound\").");
        }
        if (hasGit && authors <= 1) {
            parts.add("Single author — ensure review coverage.");
        }
        if (hasGit && churn >= 30) {
            parts.add("Frequently changed — check get_file_history(\"" + shortName + "\") for recent context.");
        }
        if (!hasGit) {
            parts.add("Git data unavailable — risk may be underestimated.");
        }
        return String.join(" ", parts);
    }

    private void appendMeta(ObjectNode root, Jdbi jdbi, int naiveTokens) {
        String responseJson = root.toString();
        int responseTokens = TokenCounter.count(responseJson);
        MetaEnvelope meta = MetaEnvelope.from(jdbi, responseTokens, naiveTokens);
        ObjectNode metaNode = root.putObject("_meta");
        metaNode.put("indexed_at", meta.indexedAt());
        metaNode.put("last_commit", meta.lastCommit());
        metaNode.put("stale_warning", meta.staleWarning());
        metaNode.put("response_tokens", meta.responseTokens());
        metaNode.put("naive_tokens", meta.naiveTokens());
        metaNode.put("compression", meta.compression());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class GraphBudget {
        private int remaining;
        private boolean truncated;

        private GraphBudget(int limit) {
            this.remaining = limit;
        }

        private boolean claim() {
            if (remaining == 0) {
                truncated = true;
                return false;
            }
            remaining--;
            return true;
        }
    }

    private static boolean isProducer(String kind) {
        return "PRODUCER_METHOD".equals(kind) || "PRODUCER_FIELD".equals(kind);
    }

    private String errorResponse(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private record ClassLookup(ClassRecord cls, String error) {
        static ClassLookup of(ClassRecord cls) { return new ClassLookup(cls, null); }
        static ClassLookup error(String msg) { return new ClassLookup(null, msg); }
    }

    private ClassLookup resolveClass(Jdbi jdbi, String target) {
        var opt = IndexReader.findClassByName(jdbi, target);
        if (opt.isPresent()) return ClassLookup.of(opt.get());

        if (!target.contains(".")) {
            var candidates = IndexReader.findClassesByShortName(jdbi, target);
            if (candidates.size() > 1) {
                var names = candidates.stream().map(ClassRecord::className).toList();
                return ClassLookup.error("Ambiguous class name '" + target
                        + "'. Matches: " + names + ". Use the fully qualified name.");
            }
        }
        return ClassLookup.error("Class not found: " + target);
    }
}
