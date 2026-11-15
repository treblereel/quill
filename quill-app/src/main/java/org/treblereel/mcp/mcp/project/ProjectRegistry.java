package org.treblereel.mcp.mcp.project;

import org.treblereel.mcp.index.MavenExtensionUpdater;
import org.treblereel.mcp.workspace.ProjectScope;
import org.treblereel.mcp.workspace.SingleProjectScope;
import org.treblereel.mcp.workspace.WorkspaceProjectScope;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import org.jdbi.v3.core.Jdbi;
import org.treblereel.mcp.index.CompiledOutputInspector;
import org.treblereel.mcp.index.ProjectIndexStore;
import org.treblereel.mcp.index.ProjectInitializer;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.ProjectCodeExpectation;
import org.treblereel.mcp.core.WorktreeSnapshotCache;
import org.treblereel.mcp.db.IndexGeneration;
import org.treblereel.mcp.core.Json;

public class ProjectRegistry implements AutoCloseable {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = Json.MAPPER;

    public record ProjectEntry(String name, Path root, IndexGeneration generation,
            org.treblereel.mcp.model.ProjectReadSnapshot observations) {
        public ProjectEntry(String name, Path root, IndexGeneration generation) { this(name, root, generation, null); }
        public ProjectEntry { Objects.requireNonNull(generation, "generation"); }
        public Jdbi jdbi() { return generation.jdbi(); }
        public <T> T read(java.util.function.Function<org.treblereel.mcp.db.ReadQueryContext,T> work) {
            return generation.read(context -> { context.useSnapshot(observations); return work.apply(context); });
        }
        public <T> T readWithGraph(java.util.function.Function<org.treblereel.mcp.db.ReadQueryContext,T> work) {
            return generation.readWithGraph(context -> { context.useSnapshot(observations); return work.apply(context); });
        }
    }
    public record ProjectIssue(
            String project,
            Path projectRoot,
            String code,
            String buildSystem,
            String message,
            String recommendedAction,
            boolean buildWasStarted,
            String buildReason,
            List<String> staleModules,
            ReadinessReason reason,
            String stage,
            Long elapsedMs,
            Long retryAfterMs,
            boolean previousGenerationAvailable,
            com.fasterxml.jackson.databind.JsonNode verificationPlan,
            com.fasterxml.jackson.databind.JsonNode diagnostics) {

        public ProjectIssue(String project, Path projectRoot, String code, String buildSystem,
                String message, String recommendedAction, boolean buildWasStarted,
                String buildReason, List<String> staleModules) {
            this(project, projectRoot, code, buildSystem, message, recommendedAction, buildWasStarted,
                    buildReason, staleModules, ReadinessReason.forCode(code, buildReason), null,
                    null, null, false, null, null);
        }

        ProjectIssue readiness(ReadinessReason reason, String stage, Long elapsedMs, Long retryAfterMs,
                boolean previousGenerationAvailable) {
            return new ProjectIssue(project, projectRoot, code, buildSystem, message, recommendedAction,
                    buildWasStarted, buildReason, staleModules, reason, stage, elapsedMs, retryAfterMs,
                    previousGenerationAvailable, verificationPlan, diagnostics);
        }

        public ProjectIssue buildEvidence(com.fasterxml.jackson.databind.JsonNode plan,
                com.fasterxml.jackson.databind.JsonNode diagnostics) {
            return new ProjectIssue(project, projectRoot, code, buildSystem, message, recommendedAction,
                    buildWasStarted, buildReason, staleModules, reason, stage, elapsedMs, retryAfterMs,
                    previousGenerationAvailable, plan, diagnostics);
        }

        public ProjectIssue(String project, Path projectRoot, String code, String buildSystem,
                String message, String recommendedAction, boolean buildWasStarted) {
            this(project, projectRoot, code, buildSystem, message, recommendedAction,
                    buildWasStarted, null, List.of());
        }

        public ProjectIssue {
            staleModules = staleModules == null ? List.of() : List.copyOf(staleModules);
        }

        public String legacyMessage() {
            return "Project '" + project + "': " + message;
        }
    }

    public enum ReadinessReason {
        PREPARATION_QUEUED("wait"), INPUT_PREPARATION_RUNNING("wait"),
        INPUTS_CHANGING("wait"), INPUT_OBSERVATION_RECOVERING("wait"),
        PREPARATION_RETRY_SCHEDULED("wait"), BUILD_EVENTS_QUEUED("wait"),
        BUILD_EVENTS_PROCESSING("wait"), INDEX_REFRESH_RUNNING("wait"), BUILD_RUNNING("wait"),
        INDEX_REFRESH_FAILED("inspect_error"), BUILD_EVENT_PROCESSING_FAILED("inspect_error"),
        BUILD_FAILED("fix_build"), INPUTS_CHANGED_AFTER_BUILD("build"),
        TEST_EVIDENCE_MISSING("build"), SOURCE_SET_INPUTS_CHANGED("build"), READY("none"), INDEX_OUTDATED("refresh_index"),
        BUILD_INTEGRATION_REQUIRED("repair_integration"),
        CLASSES_MISSING("build"), CLASSES_STALE("build"),
        INDEX_MISSING("initialize_index"), METADATA_ONLY("none"),
        UNSUPPORTED_PROJECT("select_project"), INDEX_UNAVAILABLE("inspect_error");

        public final String action;
        ReadinessReason(String action) { this.action = action; }
        public String code() { return name().toLowerCase(java.util.Locale.ROOT); }

        public static ReadinessReason forCode(String code, String buildReason) {
            return switch (code) {
                case "build_required" -> "source_set_inputs_changed".equals(buildReason) ? SOURCE_SET_INPUTS_CHANGED
                        : "classes_stale".equals(buildReason) ? CLASSES_STALE : CLASSES_MISSING;
                case "build_failed" -> BUILD_FAILED;
                case "refresh_pending" -> PREPARATION_QUEUED;
                case "index_refresh_failed" -> INDEX_REFRESH_FAILED;
                case "index_required" -> INDEX_MISSING;
                case "metadata_only" -> METADATA_ONLY;
                case "unsupported_project" -> UNSUPPORTED_PROJECT;
                default -> INDEX_UNAVAILABLE;
            };
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

    private final java.util.concurrent.locks.ReentrantReadWriteLock lifecycle =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private boolean closed;
    private volatile ProjectPreparation preparation;
    private final KotlinDirectoryWatcher.Factory watcherFactory;
    private final ProjectScope scope;
    private final SingleProjectScope mutableScope;
    private record IndexHandle(Path projectRoot, IndexGeneration generation, AtomicLong lastSeen) {}

    private final org.treblereel.mcp.db.IndexGenerationCache graphCache = new org.treblereel.mcp.db.IndexGenerationCache();

    /** Keeps a current and a preparing generation of every project within the graph cache's count. */
    void expectProjects(int count) { graphCache.expectProjects(count); }
    private final ConcurrentHashMap<Path, IndexHandle> databases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, AtomicLong> projectRoots = new ConcurrentHashMap<>();
    /** Background rebuilds of outdated indexes; a failure is kept briefly to avoid rebuild loops. */
    /** {@code failures} counts the consecutive failed rebuilds before this one. */
    private record SchemaRepair(CompletableFuture<String> result, long startedNanos, int failures) {}
    private static final long FAILED_REPAIR_RETRY_NANOS = Duration.ofMinutes(1).toNanos();
    private static final long MAX_FAILED_REPAIR_RETRY_NANOS = Duration.ofHours(1).toNanos();

    /** A failing rebuild is a full indexing run, so its retries back off: 1, 2, 4 ... 60 minutes. */
    static long repairRetryNanos(int failures) {
        return Math.min(MAX_FAILED_REPAIR_RETRY_NANOS, FAILED_REPAIR_RETRY_NANOS << Math.min(failures - 1, 6));
    }
    private final ConcurrentHashMap<Path, SchemaRepair> schemaRepairs = new ConcurrentHashMap<>();
    /** Projects whose background work another Quill server leads; see {@link BackgroundLeadership}. */
    private final Set<Path> followedRoots = ConcurrentHashMap.newKeySet();
    /** Stands for a build result the leading server is processing; it never completes here. */
    private static final CompletableFuture<String> LEADER_REFRESH = new CompletableFuture<>();

    void backgroundRole(Path projectRoot, boolean leading) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (leading) followedRoots.remove(root); else followedRoots.add(root);
    }

    boolean leads(Path projectRoot) {
        return !followedRoots.contains(projectRoot.toAbsolutePath().normalize());
    }
    private final java.util.concurrent.ExecutorService schemaRepairWorkers =
            java.util.concurrent.Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("quill-schema-repair-", 0).factory());
    private final AtomicLong resolutionSequence = new AtomicLong();
    private final AtomicLong readinessScopeRevision = new AtomicLong(Long.MIN_VALUE);
    private final BuildEventConsumer buildEvents;
    private final ProjectReadinessCache readiness =
            new ProjectReadinessCache(Duration.ofSeconds(5));

    public ProjectRegistry() {
        this(new SingleProjectScope());
    }

    public ProjectRegistry(ProjectScope scope) {
        this(scope, new BuildEventConsumer());
    }

    public ProjectRegistry(ProjectScope scope, BuildEventConsumer buildEvents) {
        this(scope, buildEvents, KotlinDirectoryWatcher.DEFAULT);
    }

    ProjectRegistry(ProjectScope scope, BuildEventConsumer buildEvents, KotlinDirectoryWatcher.Factory watcherFactory) {
        this.buildEvents = Objects.requireNonNull(buildEvents, "buildEvents");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.mutableScope = scope instanceof SingleProjectScope single ? single : null;
        this.watcherFactory = Objects.requireNonNull(watcherFactory, "watcherFactory");
    }

    public int configuredProjectCount() {
        return scope.snapshot().projects().size();
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
        ProjectPreparation prepared = preparation;
        return prepared == null ? resolveUnprepared(selector, false) : prepared.resolve(selector);
    }

    Resolution resolveUnprepared(String selector, boolean retainPrevious) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Project registry is closed");
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
                boolean refreshing = false;
                // Only the leading server consumes build results; a follower waits for its publication.
                CompletableFuture<String> refresh = leads(projectRoot) ? buildEvents.consumeAsync(projectRoot)
                        : BuildEventConsumer.hasPendingEvents(projectRoot) ? LEADER_REFRESH : null;
                if (refresh != null) {
                    readiness.invalidate(projectRoot);
                    boolean pending = !refresh.isDone();
                    String error = pending ? null : refresh.getNow(null);
                    if (pending || error != null) {
                        ProjectIssue issue = new ProjectIssue(p.name(), projectRoot,
                                pending ? "refresh_pending" : "index_refresh_failed", null,
                                pending ? "A build result is queued or being processed in the background" : error,
                                pending ? "Retry the same query after index refresh; do not narrow targets or start another build"
                                        : "Inspect the refresh failure, then retry the same query; rebuilding is not required unless inputs changed", false)
                                .readiness(pending ? buildEvents.reason(projectRoot)
                                                : buildEvents.stage(projectRoot).equals("refreshing_index")
                                                        ? ReadinessReason.INDEX_REFRESH_FAILED : ReadinessReason.BUILD_EVENT_PROCESSING_FAILED,
                                        buildEvents.stage(projectRoot),
                                        buildEvents.elapsedMs(projectRoot), pending ? 2000L : null,
                                        ProjectIndexStore.findBestAvailableDb(projectRoot) != null);
                        issues.add(issue);
                        errors.add(issue.legacyMessage());
                        if (!retainPrevious || !issue.previousGenerationAvailable()) {
                            selectedRoots.remove(projectRoot);
                            continue;
                        }
                        refreshing = true;
                    }
                }
                Path candidateDb = ProjectIndexStore.findBestAvailableDb(p.root());
                if (candidateDb == null) {
                    ProjectIssue repairIssue = leads(projectRoot) ? outdatedSchemaRepair(p, projectRoot)
                            : leaderSchemaRepair(p, projectRoot);
                    if (repairIssue != null) {
                        issues.add(repairIssue);
                        errors.add(repairIssue.legacyMessage());
                        selectedRoots.remove(projectRoot);
                        continue;
                    }
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
                        if (cached == null || !cached.generation().matches(path)) {
                            if(cached!=null)cached.generation().close();
                            return new IndexHandle(projectRoot, IndexGeneration.open(path, graphCache),
                                    new AtomicLong(resolutionId));
                        }
                        cached.lastSeen().accumulateAndGet(resolutionId, Math::max);
                        return cached;
                    });
                    result.add(new ProjectEntry(p.name(), p.root(), handle.generation()));
                    ProjectIssue buildIssue = refreshing ? null : buildRequiredIssue(p);
                    if (buildIssue != null) issues.add(buildIssue);
                } catch (RuntimeException e) {
                    ProjectIssue issue = new ProjectIssue(p.name(), projectRoot,
                            "index_unavailable", buildSystem(projectRoot), safeMessage(e),
                            "Repair or recreate the Quill index, then retry the MCP request", false);
                    issues.add(issue);
                    errors.add(issue.legacyMessage());
                }
            }
            evictInactive(resolutionId, activeDatabases, configuredRoots, retainPrevious ? Set.of() : selectedRoots);
            return new Resolution(result, errors, issues);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    @Override
    public void close() {
        ProjectPreparation prepared = preparation;
        if (prepared != null) prepared.close();
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            // Interrupts any outdated-schema rebuild; the next server start retries it.
            schemaRepairWorkers.shutdownNow();
            databases.values().forEach(handle -> handle.generation().close());
            databases.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
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
                scope instanceof WorkspaceProjectScope workspace
                        ? "Run quill workspace refresh --project " + workspace.root()
                                + ", then retry the MCP request"
                        : "Run quill init for this project, then retry the MCP request",
                false);
    }

    void retireRemoved(Set<Path> roots) {
        lifecycle.writeLock().lock();
        try {
            databases.entrySet().removeIf(entry -> {
                if (roots.contains(entry.getValue().projectRoot())) return false;
                entry.getValue().generation().close(); return true;
            });
            projectRoots.keySet().removeIf(root -> {
                if (roots.contains(root)) return false;
                WorktreeSnapshotCache.shared().invalidate(root);
                schemaRepairs.remove(root); readiness.invalidate(root); return true;
            });
        } finally { lifecycle.writeLock().unlock(); }
    }

    void invalidateReadiness(Path root) { readiness.invalidate(root); }

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
        if (ProjectCodeExpectation.inspect(root, buildSystem)
                == ProjectCodeExpectation.State.METADATA_ONLY) {
            return new ProjectIssue(project.name(), root, "metadata_only",
                    buildSystem.name().toLowerCase(java.util.Locale.ROOT),
                    "Build metadata does not declare JVM code that should produce main classes",
                    "No build or Quill code index is required unless JVM modules are added", false);
        }
        CompiledOutputInspector.Report compiled = CompiledOutputInspector.inspect(root);
        com.fasterxml.jackson.databind.JsonNode diagnostics;
        try {
            diagnostics = JSON.readTree(
                    org.treblereel.mcp.index.BuildProblemInspector.inspect(root, "all", null, 20, 0));
        } catch (java.io.IOException unavailable) { throw new IllegalStateException(unavailable); }
        boolean failed = diagnostics.path("build_status").asText().equals("failed");
        com.fasterxml.jackson.databind.JsonNode state;
        try {
            Path file = root.resolve(".quill/build-state.json");
            state = java.nio.file.Files.isRegularFile(file)
                    ? JSON.readTree(file.toFile()) : null;
        } catch (java.io.IOException unreadable) { state = null; }
        boolean newerInputs = state != null && state.path("indexRefreshReason").asText().equals("inputs_changed_after_build");
        if (compiled.state() == CompiledOutputInspector.State.FRESH && !failed && !newerInputs) return null;
        String action = "Run the quick_compile command in verification_plan, then retry the same query";
        boolean stale = compiled.state() == CompiledOutputInspector.State.STALE;
        String reason = stale ? "classes_stale" : "classes_missing";
        String message = stale
                ? "Compiled main classes are older than project build inputs"
                : "No compiled main classes were found; Quill did not start a build";
        var modules = new java.util.ArrayList<>(compiled.staleModules());
        if (newerInputs && modules.isEmpty()) {
            Path input = root.resolve(state.path("changedInput").asText()).normalize();
            org.treblereel.mcp.index.ProjectLayout.discoverClassesDirs(root, false).moduleDirectories().stream()
                    .filter(input::startsWith).max(java.util.Comparator.comparingInt(Path::getNameCount))
                    .ifPresent(module -> modules.add(module.equals(root) ? "." : root.relativize(module).toString().replace('\\', '/')));
        }
        var contexts = JSON.createArrayNode();
        modules.forEach(module -> contexts.addObject().putObject("resolution").put("module", module));
        var plan = new VerificationPlanQueries().plan(root, contexts,
                JSON.createObjectNode());
        return new ProjectIssue(project.name(), root, "build_required",
                buildSystem.name().toLowerCase(java.util.Locale.ROOT),
                failed ? "The last build failed; inspect captured diagnostics before rebuilding"
                        : newerInputs ? "Indexed inputs changed after the last successful build: " + state.path("changedInput").asText() : message,
                failed ? "Fix the reported build errors, run the supplied quick_compile command, then retry"
                        : action,
                false, failed ? "last_build_failed" : newerInputs ? "inputs_changed_after_build" : reason, modules)
                .readiness(failed ? ReadinessReason.BUILD_FAILED : newerInputs ? ReadinessReason.INPUTS_CHANGED_AFTER_BUILD
                                : stale ? ReadinessReason.CLASSES_STALE : ReadinessReason.CLASSES_MISSING,
                        null, null, null, ProjectIndexStore.findBestAvailableDb(root) != null)
                .buildEvidence(plan, failed ? diagnostics : null);
    }

    private static String buildSystem(Path root) {
        try {
            return BuildSystem.detect(root).name().toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    void retireSuperseded(ProjectEntry active) {
        lifecycle.readLock().lock();
        try {
            databases.entrySet().removeIf(entry -> {
                boolean obsolete = entry.getValue().projectRoot().equals(active.root().toAbsolutePath().normalize())
                        && entry.getValue().generation() != active.generation();
                if (obsolete) entry.getValue().generation().close();
                return obsolete;
            });
        } finally { lifecycle.readLock().unlock(); }
    }

    private void evictInactive(long resolutionId, Set<Path> activeDatabases,
            Set<Path> configuredRoots, Set<Path> selectedRoots) {
        databases.entrySet().removeIf(entry -> {
            boolean evict=entry.getValue().lastSeen().get() < resolutionId
                    && (!configuredRoots.contains(entry.getValue().projectRoot())
                    || (selectedRoots.contains(entry.getValue().projectRoot()) && !activeDatabases.contains(entry.getKey())));
            if(evict) entry.getValue().generation().close();
            return evict;
        });
        projectRoots.entrySet().removeIf(entry -> {
            if (configuredRoots.contains(entry.getKey())
                    || entry.getValue().get() >= resolutionId) return false;
            WorktreeSnapshotCache.shared().invalidate(entry.getKey());
            schemaRepairs.remove(entry.getKey());
            readiness.invalidate(entry.getKey());
            return true;
        });
    }

    /**
     * Rebuilds an index whose only generations use an outdated schema. The rebuild runs in the
     * background, like a build-triggered refresh, so a request never blocks on a full index
     * initialization (which can exceed the MCP request timeout); until it finishes the project
     * reports retryable refresh_pending. Returns null when no repair is needed or it succeeded.
     */
    private ProjectIssue outdatedSchemaRepair(ProjectScope.Project project, Path projectRoot) {
        if (!ProjectIndexStore.hasOutdatedGenerations(projectRoot)) return null;
        SchemaRepair repair = schemaRepairs.compute(projectRoot, (root, existing) -> {
            boolean failed = existing != null && existing.result().isDone() && existing.result().getNow(null) != null;
            boolean retry = failed && System.nanoTime() - existing.startedNanos() > repairRetryNanos(existing.failures() + 1);
            if (existing != null && !retry) return existing;
            int failures = failed ? existing.failures() + 1 : 0;
            return new SchemaRepair(CompletableFuture.supplyAsync(() -> {
                ProjectInitializer.InitializationResult result =
                        ProjectInitializer.initializeDetailed(root, true);
                if (result.successful()) return null;
                System.err.println("[quill] Could not rebuild the outdated index of " + root + " (attempt "
                        + (failures + 1) + "); next attempt in "
                        + Duration.ofNanos(repairRetryNanos(failures + 1)).toMinutes() + " min: " + result.diagnostic());
                return result.diagnostic();
            }, schemaRepairWorkers).exceptionally(ProjectRegistry::safeMessage), System.nanoTime(), failures);
        });
        if (!repair.result().isDone()) {
            return new ProjectIssue(project.name(), projectRoot, "refresh_pending", null,
                    "The index uses an outdated schema and is being rebuilt in the background",
                    "Retry the same query after the rebuild; no build is required", false)
                    .readiness(ReadinessReason.INDEX_REFRESH_RUNNING, "rebuilding_outdated_index",
                            (System.nanoTime() - repair.startedNanos()) / 1_000_000L, 2000L, false);
        }
        String failure = repair.result().getNow(null);
        if (failure == null) {
            schemaRepairs.remove(projectRoot, repair);
            return null;
        }
        return new ProjectIssue(project.name(), projectRoot, "index_refresh_failed", null,
                "Could not rebuild the outdated index: " + failure,
                "Inspect the failure, then retry; the rebuild is retried after "
                        + Duration.ofNanos(repairRetryNanos(repair.failures() + 1)).toMinutes() + " minute(s)", false)
                .readiness(ReadinessReason.INDEX_REFRESH_FAILED, "rebuilding_outdated_index",
                        null, null, false);
    }

    /** A follower's view of an outdated index: the leading server rebuilds it. */
    private ProjectIssue leaderSchemaRepair(ProjectScope.Project project, Path projectRoot) {
        if (!ProjectIndexStore.hasOutdatedGenerations(projectRoot)) return null;
        return new ProjectIssue(project.name(), projectRoot, "refresh_pending", null,
                "The index uses an outdated schema and the leading Quill server is rebuilding it",
                "Retry the same query after the rebuild; no build is required", false)
                .readiness(ReadinessReason.INDEX_REFRESH_RUNNING, "rebuilding_outdated_index", null, 2000L, false);
    }

    public void upgradeBuildIntegrations() {
        scope.snapshot().projects().stream().map(ProjectScope.Project::root).distinct()
                .forEach(org.treblereel.mcp.index.MavenExtensionUpdater::upgrade);
    }

    public CompletableFuture<Void> awaitPrepared(String selector) {
        var active = preparation;
        return active == null ? CompletableFuture.completedFuture(null) : active.awaitReady(selector);
    }

    public int waitingRequests() { return preparation == null ? 0 : preparation.waitingRequests(); }

    public List<String> waitStatus() { return preparation == null ? List.of() : preparation.waitStatus(); }

    public void prewarm() {
        prewarmAsync().join();
    }

    public synchronized CompletableFuture<Void> prewarmAsync() {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Project registry is closed");
            if (preparation == null) {
                preparation = new ProjectPreparation(this, scope, watcherFactory);
                preparation.startAsync();
            }
            return preparation.initialPreparation();
        } finally { lifecycle.readLock().unlock(); }
    }

    void prewarm(Executor executor) {
        List<ProjectScope.Project> snapshot = scope.snapshot().projects();
        executor.execute(() -> {
            try {
                Resolution resolution = resolve();
                resolution.projects().forEach(entry ->
                        entry.read(context -> { ProjectDependencyQueries.prewarm(context, entry.root()); return null; }));
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

    public List<ProjectScope.Project> configuredProjects(String selector) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Project registry is closed");
            return preparation == null ? scope.select(scope.snapshot(), selector) : preparation.selectedProjects(selector);
        } finally { lifecycle.readLock().unlock(); }
    }

    public List<String> uninitializedErrors() {
        return resolve().errors();
    }

    public static String safeMessage(Throwable failure) {
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
        return preparation == null ? scope.snapshot().projects().isEmpty() : preparation.projectNames().isEmpty();
    }

    public List<String> configuredProjectNames() {
        return preparation == null ? scope.snapshot().projects().stream().map(ProjectScope.Project::name).toList() : preparation.projectNames();
    }

    public WorkspaceProjectScope workspaceScope() {
        return scope instanceof WorkspaceProjectScope workspace ? workspace : null;
    }

    int cachedDatabaseCount() {
        return databases.size();
    }

    int cachedReadinessCount() {
        return readiness.size();
    }
}
