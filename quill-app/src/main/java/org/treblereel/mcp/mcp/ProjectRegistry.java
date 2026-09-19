package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.command.ProjectIndexStore;
import org.treblereel.mcp.command.ProjectInitializer;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectCodeExpectation;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.workspace.WorkspaceCoordinateCatalog;
import org.treblereel.mcp.workspace.WorkspaceDependencyGraph;

public class ProjectRegistry {

    private static final Executor PREWARM_EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "quill-mcp-prewarm");
        thread.setDaemon(true);
        return thread;
    });

    public record ProjectEntry(String name, Path root, Jdbi jdbi) {}
    public record ProjectIssue(
            String project,
            Path projectRoot,
            String code,
            String buildSystem,
            String message,
            String recommendedAction,
            boolean buildWasStarted) {

        String legacyMessage() {
            return "Project '" + project + "': " + message;
        }
    }

    public record Resolution(
            List<ProjectEntry> projects, List<String> errors, List<ProjectIssue> issues) {
        public Resolution(List<ProjectEntry> projects, List<String> errors) {
            this(projects, errors, List.of());
        }

        public Resolution {
            projects = List.copyOf(projects);
            errors = List.copyOf(errors);
            issues = List.copyOf(issues);
        }
    }

    private final ProjectScope scope;
    private final SingleProjectScope mutableScope;
    private record IndexHandle(Path projectRoot, Jdbi jdbi, AtomicLong lastSeen) {}

    private final ConcurrentHashMap<Path, IndexHandle> databases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, AtomicLong> projectRoots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, Object> schemaRepairLocks = new ConcurrentHashMap<>();
    private final AtomicLong resolutionSequence = new AtomicLong();
    private final AtomicLong readinessScopeRevision = new AtomicLong(Long.MIN_VALUE);
    private final BuildEventConsumer buildEvents = new BuildEventConsumer();
    private final ProjectReadinessCache readiness =
            new ProjectReadinessCache(Duration.ofSeconds(5));

    public ProjectRegistry() {
        this(new SingleProjectScope());
    }

    public ProjectRegistry(ProjectScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.mutableScope = scope instanceof SingleProjectScope single ? single : null;
    }

    public void register(Path projectPath) {
        if (mutableScope == null) {
            throw new IllegalStateException("Projects are managed by "
                    + scope.getClass().getSimpleName());
        }
        mutableScope.register(projectPath);
    }

    public List<ProjectEntry> initialized() {
        return resolve().projects();
    }

    public Resolution resolve() {
        return resolve(null);
    }

    public Resolution resolve(String selector) {
        long resolutionId = resolutionSequence.incrementAndGet();
        List<ProjectEntry> result = new ArrayList<>();
        ProjectScope.Snapshot snapshot = scope.snapshot();
        List<String> errors = new ArrayList<>(snapshot.diagnostics());
        List<ProjectIssue> issues = new ArrayList<>();
        Set<Path> activeDatabases = ConcurrentHashMap.newKeySet();
        Set<Path> configuredRoots = snapshot.projects().stream()
                .map(project -> project.root().toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        long previousReadinessRevision = readinessScopeRevision.getAndAccumulate(
                snapshot.revision(), Math::max);
        if (snapshot.revision() > previousReadinessRevision) readiness.clear();
        readiness.retain(configuredRoots);
        Set<Path> selectedRoots = ConcurrentHashMap.newKeySet();
        for (Path projectRoot : configuredRoots) {
            projectRoots.compute(projectRoot, (ignored, seen) -> {
                if (seen == null) return new AtomicLong(resolutionId);
                seen.accumulateAndGet(resolutionId, Math::max);
                return seen;
            });
        }
        for (ProjectScope.Project p : scope.select(snapshot, selector)) {
            Path projectRoot = p.root().toAbsolutePath().normalize();
            selectedRoots.add(projectRoot);
            if (BuildEventConsumer.hasPendingEvents(projectRoot)) {
                readiness.invalidate(projectRoot);
            }
            String buildEventError = buildEvents.consume(p.root());
            if (buildEventError != null) {
                errors.add("Project '" + p.name() + "': " + buildEventError);
            }
            Path candidateDb = ProjectIndexStore.findBestAvailableDb(p.root());
            if (candidateDb == null) {
                repairOutdatedSchema(p, projectRoot, errors);
                candidateDb = ProjectIndexStore.findBestAvailableDb(p.root());
            }
            final Path dbPath = candidateDb;
            if (dbPath == null) {
                ProjectIssue issue = unavailableProjectIssue(p);
                issues.add(issue);
                errors.add(issue.legacyMessage());
                continue;
            }
            try {
                Path normalizedDb = dbPath.toAbsolutePath().normalize();
                activeDatabases.add(normalizedDb);
                IndexHandle handle = databases.compute(normalizedDb, (path, cached) -> {
                    if (cached == null) {
                        return new IndexHandle(projectRoot, QuillDatabase.open(path),
                                new AtomicLong(resolutionId));
                    }
                    cached.lastSeen().accumulateAndGet(resolutionId, Math::max);
                    return cached;
                });
                result.add(new ProjectEntry(p.name(), p.root(), handle.jdbi()));
                ProjectIssue buildIssue = buildRequiredIssue(p);
                if (buildIssue != null) issues.add(buildIssue);
            } catch (RuntimeException e) {
                ProjectIssue issue = new ProjectIssue(p.name(), projectRoot,
                        "index_unavailable", buildSystem(projectRoot), safeMessage(e),
                        "Repair or recreate the Quill index, then retry the MCP request", false);
                issues.add(issue);
                errors.add(issue.legacyMessage());
            }
        }
        evictInactive(resolutionId, activeDatabases, configuredRoots, selectedRoots);
        return new Resolution(result, errors, issues);
    }

    private ProjectIssue unavailableProjectIssue(ProjectScope.Project project) {
        Path root = project.root().toAbsolutePath().normalize();
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(root);
        } catch (IllegalArgumentException unsupported) {
            return new ProjectIssue(project.name(), root, "unsupported_project", null,
                    "Quill could not detect a supported Maven or Gradle Java project",
                    "Select a supported repository or configure the workspace exclusions", false);
        }
        ProjectIssue buildIssue = buildRequiredIssue(project, buildSystem);
        if (buildIssue != null) return buildIssue;
        return new ProjectIssue(project.name(), root, "index_required",
                buildSystem.name().toLowerCase(java.util.Locale.ROOT),
                "Compiled classes are available, but no usable Quill index was found",
                "Run quill init for this project, then retry the MCP request", false);
    }

    private ProjectIssue buildRequiredIssue(ProjectScope.Project project) {
        try {
            return buildRequiredIssue(project, BuildSystem.detect(project.root()));
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private ProjectIssue buildRequiredIssue(
            ProjectScope.Project project, BuildSystem buildSystem) {
        return readiness.get(project.root(), () ->
                buildRequiredIssueUncached(project, buildSystem));
    }

    private static ProjectIssue buildRequiredIssueUncached(
            ProjectScope.Project project, BuildSystem buildSystem) {
        Path root = project.root().toAbsolutePath().normalize();
        if (!ProjectInitializer.findClassesDirs(root).isEmpty()) return null;
        if (ProjectCodeExpectation.inspect(root, buildSystem)
                == ProjectCodeExpectation.State.METADATA_ONLY) {
            return new ProjectIssue(project.name(), root, "metadata_only",
                    buildSystem.name().toLowerCase(java.util.Locale.ROOT),
                    "Build metadata does not declare JVM code that should produce main classes",
                    "No build or Quill code index is required unless JVM modules are added", false);
        }
        String action = buildSystem == BuildSystem.MAVEN
                ? "Decide whether to run the project's Maven compile/package command, then retry"
                : "Decide whether to run the project's Gradle classes/build command, then retry";
        return new ProjectIssue(project.name(), root, "build_required",
                buildSystem.name().toLowerCase(java.util.Locale.ROOT),
                "No compiled main classes were found; Quill did not start a build",
                action, false);
    }

    private static String buildSystem(Path root) {
        try {
            return BuildSystem.detect(root).name().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void evictInactive(long resolutionId, Set<Path> activeDatabases,
            Set<Path> configuredRoots, Set<Path> selectedRoots) {
        databases.entrySet().removeIf(entry -> entry.getValue().lastSeen().get() < resolutionId
                && (!configuredRoots.contains(entry.getValue().projectRoot())
                || (selectedRoots.contains(entry.getValue().projectRoot())
                && !activeDatabases.contains(entry.getKey()))));
        projectRoots.entrySet().removeIf(entry -> {
            if (configuredRoots.contains(entry.getKey())
                    || entry.getValue().get() >= resolutionId) return false;
            WorktreeSnapshotCache.shared().invalidate(entry.getKey());
            schemaRepairLocks.remove(entry.getKey());
            readiness.invalidate(entry.getKey());
            return true;
        });
    }

    private void repairOutdatedSchema(ProjectScope.Project project, Path projectRoot,
            List<String> errors) {
        Object repairLock = schemaRepairLocks.computeIfAbsent(projectRoot, ignored -> new Object());
        synchronized (repairLock) {
            if (ProjectIndexStore.findBestAvailableDb(projectRoot) != null) return;
            if (!ProjectIndexStore.hasOutdatedGenerations(projectRoot)) return;
            ProjectInitializer.InitializationResult repair =
                    ProjectInitializer.initializeDetailed(projectRoot, true);
            if (!repair.successful()) {
                errors.add("Project '" + project.name()
                        + "': could not rebuild its outdated index: " + repair.diagnostic());
            }
        }
    }

    public void prewarm() {
        if (scope instanceof WorkspaceProjectScope workspace) {
            WorkspaceCoordinateCatalog.discover(workspace.manifest());
            WorkspaceDependencyGraph.discover(workspace.manifest());
            // Opening and validating the available SQLite generations is part of MCP startup.
            // Otherwise the first routed request pays this cost once per consumer repository.
            resolve();
        }
        prewarm(PREWARM_EXECUTOR);
    }

    void prewarm(Executor executor) {
        List<ProjectScope.Project> snapshot = scope.snapshot().projects();
        executor.execute(() -> {
            try {
                Resolution resolution = resolve();
                resolution.projects().forEach(entry ->
                        ProjectDependencyQueries.prewarm(entry.jdbi(), entry.root()));
            } catch (RuntimeException ignored) {
                // A normal request will report the same project-specific error.
            }
        });
        for (ProjectScope.Project project : snapshot) {
            executor.execute(() -> {
                try {
                    WorktreeSnapshotCache.shared().get(project.root());
                } catch (RuntimeException ignored) {
                    // Live worktree metadata is optional and can be retried by a request.
                }
            });
        }
    }

    public List<String> uninitializedErrors() {
        return resolve().errors();
    }

    static String safeMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        try {
            String message = current.getMessage();
            return message == null || message.isBlank()
                    ? current.getClass().getSimpleName() : message;
        } catch (RuntimeException messageFailure) {
            return current.getClass().getSimpleName();
        }
    }

    public boolean isEmpty() {
        return scope.snapshot().projects().isEmpty();
    }

    public List<String> configuredProjectNames() {
        return scope.snapshot().projects().stream().map(ProjectScope.Project::name).toList();
    }

    WorkspaceProjectScope workspaceScope() {
        return scope instanceof WorkspaceProjectScope workspace ? workspace : null;
    }

    int cachedDatabaseCount() {
        return databases.size();
    }

    int cachedReadinessCount() {
        return readiness.size();
    }
}
