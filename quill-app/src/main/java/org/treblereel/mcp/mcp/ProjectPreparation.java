package org.treblereel.mcp.mcp;

import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.treblereel.mcp.core.*;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.index.BuildStatusInspector;
import org.treblereel.mcp.index.ProjectUpdater;
import org.treblereel.mcp.model.*;
import org.treblereel.mcp.workspace.*;

/** Owns event-driven preparation; only completed observations are exposed to requests. */
final class ProjectPreparation implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProjectRegistry registry;
    private final ProjectScope scope;
    private final KotlinDirectoryWatcher.Factory watcherFactory;
    private final ConcurrentHashMap<Path, State> states = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("quill-project-prepare-", 0).factory());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("quill-project-observe-", 0).factory());
    private final Semaphore slots = new Semaphore(2, true);
    private volatile ProjectScope.Snapshot projects;
    private volatile boolean closed;
    private long nextReconcile;
    private final CompletableFuture<Void> initial = new CompletableFuture<>();
    private volatile String startupFailure;
    private final long startupStarted = System.nanoTime();
    private boolean integrationsUpgraded;

    CompletableFuture<Void> initialPreparation() { return initial; }

    void startAsync() {
        workers.execute(() -> {
            try {
                start();
                if (closed) return;
                if (scope instanceof WorkspaceProjectScope workspace)
                    WorkspaceDependencyGraph.discover(workspace.manifest());
                initial.complete(null);
            } catch (Throwable failure) {
                startupFailure = ProjectRegistry.safeMessage(failure);
                initial.completeExceptionally(failure);
            }
        });
    }

    ProjectPreparation(ProjectRegistry registry, ProjectScope scope) {
        this(registry, scope, KotlinDirectoryWatcher.DEFAULT);
    }

    ProjectPreparation(ProjectRegistry registry, ProjectScope scope, KotlinDirectoryWatcher.Factory watcherFactory) {
        this.registry = registry;
        this.scope = scope;
        this.watcherFactory = watcherFactory;
    }

    void start() {
        timer.scheduleWithFixedDelay(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        reconcile();
        for (State state : states.values()) state.awaitInitial();
    }

    List<String> projectNames() { return projects == null ? List.of() : projects.projects().stream().map(ProjectScope.Project::name).toList(); }

    private synchronized void reconcile() {
        if (closed) return;
        var snapshot = scope.snapshot();
        if (closed) return;
        if (!integrationsUpgraded) {
            registry.upgradeBuildIntegrations();
            integrationsUpgraded = true;
        }
        Set<Path> retained = new HashSet<>();
        for (var project : snapshot.projects()) {
            retained.add(project.root());
            states.compute(project.root(), (ignored, current) -> {
                if (current != null && current.project.equals(project)) return current;
                if (current != null) current.close();
                return new State(project);
            });
        }
        boolean removed = false;
        for (var entry : states.entrySet()) {
            if (!retained.contains(entry.getKey()) && states.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().close(); removed = true;
            }
        }
        if (removed) registry.retireRemoved(retained);
        projects = snapshot;
    }

    private void tick() {
        if (closed) return;
        try {
            if (System.nanoTime() >= nextReconcile) {
                nextReconcile = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                reconcile();
            }
            states.values().forEach(State::tick);
        } catch (RuntimeException failure) {
            states.values().forEach(State::changed);
        }
    }

    ProjectRegistry.Resolution resolve(String selector) {
        if (closed) throw new IllegalStateException("Project registry is closed");
        var snapshot = projects;
        if (snapshot == null) {
            Path root = scope instanceof WorkspaceProjectScope workspace ? workspace.root()
                    : Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            var issue = new ProjectRegistry.ProjectIssue(selector == null ? "server" : selector, root,
                    "refresh_pending", null, startupFailure == null ? "Discovering projects and preparing indexes"
                            : "Project preparation will retry: " + startupFailure,
                    "Retry the same query after preparation; do not start another build", false)
                    .readiness(startupFailure == null ? ProjectRegistry.ReadinessReason.INPUT_PREPARATION_RUNNING
                                    : ProjectRegistry.ReadinessReason.PREPARATION_RETRY_SCHEDULED,
                            startupFailure == null ? "discovering_projects" : "retry_scheduled",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startupStarted), 1000L, false);
            return new ProjectRegistry.Resolution(List.of(), List.of(issue.legacyMessage()), List.of(issue));
        }
        List<ProjectRegistry.ProjectEntry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>(snapshot.diagnostics());
        List<ProjectRegistry.ProjectIssue> issues = new ArrayList<>();
        // Name and path selection use the already reconciled scope; coordinate aliases are
        // resolved by the workspace catalog during background reconciliation.
        var selected = select(snapshot, selector).stream().map(p -> states.get(p.root())).filter(Objects::nonNull).toList();
        var barriers = new ArrayList<CompletableFuture<Void>>();
        for (State state : selected) {
            state.checkControls();
            if (state.current() && state.events.active()) barriers.add(
                    CompletableFuture.supplyAsync(state.events::awaitDelivery, workers).thenCompose(barrier -> barrier));
        }
        try { CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new)).get(500, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Input observation interrupted", interrupted); }
        catch (ExecutionException | TimeoutException unavailable) {
            selected.forEach(state -> {
                state.changed();
                workers.execute(state.events::deliveryMissing);
            });
        }
        for (State state : selected) {
            if (state == null) continue;
            state.checkControls();
            var result = state.result();
            entries.addAll(result.projects()); errors.addAll(result.errors()); issues.addAll(result.issues());
        }
        return new ProjectRegistry.Resolution(entries, errors, issues);
    }

    List<ProjectScope.Project> selectedProjects(String selector) { return projects == null ? List.of() : select(projects, selector); }

    private volatile Map<String, Set<Path>> coordinates = Map.of();
    private List<ProjectScope.Project> select(ProjectScope.Snapshot snapshot, String selector) {
        if (selector != null && selector.contains(":")) {
            Set<Path> roots = coordinates.get(selector);
            if (roots != null) return snapshot.projects().stream().filter(p -> roots.contains(p.root())).toList();
        }
        if (scope instanceof WorkspaceProjectScope && selector != null && selector.contains(":")) return List.of();
        return scope.select(snapshot, selector);
    }

    private final class State implements AutoCloseable {
        final ProjectScope.Project project;
        final Path git;
        final Path watchRoot;
        final Set<Path> contentRoots;
        final Set<Path> gitRoots;
        final KotlinDirectoryWatcher events;
        final AtomicLong revision = new AtomicLong();
        volatile Map<Path, Object> controls;
        volatile boolean stopped;
        private CompletableFuture<Void> preparing;
        private ProjectRegistry.Resolution ready;
        private ProjectRegistry.ProjectEntry lastPrepared, fallback;
        private String fallbackCode;
        private long preparedRevision = -1, changedAt, retryAt;
        private long worktreeRevision, preparedWorktreeRevision = -1;
        private String announcedGeneration;
        private volatile String stage = "queued";
        private long preparingStarted, preparingRevision;
        private long retryDelay = TimeUnit.SECONDS.toNanos(1);

        State(ProjectScope.Project project) {
            this.project = project;
            var gitDirectory = new org.eclipse.jgit.storage.file.FileRepositoryBuilder()
                    .findGitDir(project.root().toFile()).getGitDir();
            Path canonical = project.root().toAbsolutePath().normalize();
            try { canonical = canonical.toRealPath(); } catch (IOException unavailable) { }
            watchRoot = canonical;
            try { git = gitDirectory == null ? null : gitDirectory.toPath().toRealPath(); }
            catch (IOException failure) { throw new IllegalStateException("Cannot observe Git metadata", failure); }
            events = new KotlinDirectoryWatcher(watcherFactory, this::observed, this::changed);
            var inputs = new HashSet<Path>(); inputs.add(watchRoot);
            try {
                var layout = org.treblereel.mcp.index.ProjectLayout.discoverClassesDirs(project.root(), false);
                var paths = new ArrayList<>(layout.moduleDirectories());
                layout.outputs().forEach(output -> paths.add(output.directory()));
                for (Path path : paths) if (Files.isDirectory(path)) inputs.add(path.toRealPath());
            } catch (IOException | RuntimeException unavailable) { /* Resolution reports invalid layouts. */ }
            var minimal = new ArrayList<Path>();
            inputs.stream().sorted(Comparator.comparingInt(Path::getNameCount)).forEach(path -> {
                if (minimal.stream().noneMatch(path::startsWith)) minimal.add(path);
            });
            contentRoots = Set.copyOf(minimal);
            var repositories = new HashSet<Path>();
            if (git != null) {
                repositories.add(git);
                try {
                    Path common = git.resolve(Files.readString(git.resolve("commondir")).strip()).normalize().toRealPath();
                    repositories.add(common);
                } catch (IOException ignored) { }
            }
            gitRoots = Set.copyOf(repositories);
            var roots = new HashSet<>(contentRoots); roots.addAll(gitRoots);
            events.configure(roots, this::relevant);
            controls = controls(project.root(), git);
            try { schedule(); }
            catch (RuntimeException | Error failure) { events.close(); throw failure; }
        }

        boolean relevant(Path path) {
            for (Path repository : gitRoots) if (repository.startsWith(path)) return true;
            for (Path repository : gitRoots.stream().sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                if (!path.startsWith(repository)) continue;
                String relative = repository.relativize(path).toString().replace('\\', '/');
                return relative.equals("HEAD") || relative.equals("index") || relative.equals("packed-refs")
                        || relative.equals("config") || relative.equals("refs") || relative.startsWith("refs/");
            }
            Path owner = contentRoots.stream().filter(path::startsWith)
                    .min(Comparator.comparingInt(Path::getNameCount)).orElse(null);
            if (owner == null) return false;
            String relative = owner.relativize(path).toString().replace('\\', '/');
            if (relative.isEmpty()) return true;
            if (relative.equals(".quill")) return true;
            if (relative.startsWith(".quill/")) return relative.equals(".quill/refs.json")
                    || relative.equals(".quill/build-state.json") || relative.startsWith(".quill/build-events");
            for (String segment : List.of(".git", ".gradle", "node_modules"))
                if (relative.equals(segment) || relative.startsWith(segment + "/") || relative.contains("/" + segment + "/")) return false;
            if (Files.isDirectory(path) || !Files.exists(path)) return true;
            String name = path.getFileName().toString();
            boolean output = relative.startsWith("target/") || relative.contains("/target/")
                    || relative.startsWith("build/") || relative.contains("/build/");
            return !output || name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".class")
                    || name.startsWith("quill-");
        }

        void observed(Path path) {
            if (path != null && path.startsWith(watchRoot)) {
                String relative = watchRoot.relativize(path).toString().replace('\\', '/');
                if (!relative.startsWith(".git/") && !relative.startsWith(".quill/")
                        && !Files.isDirectory(path) && !WorktreeInspector.isIndexInputPath(relative)
                        && !path.getFileName().toString().startsWith("quill-")) {
                    synchronized (this) { worktreeRevision++; }
                    return;
                }
            }
            changed();
        }

        synchronized void changed() {
            if (stopped) return;
            revision.incrementAndGet();
            changedAt = System.nanoTime();
            retryAt = 0; retryDelay = TimeUnit.SECONDS.toNanos(1);
        }

        void checkControls() {
            Map<Path, Object> next = controls(project.root(), git);
            if (!next.equals(controls)) { controls = next; changed(); }
            ProjectRegistry.ProjectEntry active;
            synchronized (this) { active = current() && !ready.projects().isEmpty() ? ready.projects().getFirst() : null; }
            if (active != null && !active.generation().matches(active.generation().path())) changed();
        }

        void tick() {
            if (stopped) return;
            checkControls();
            events.checkHealth();
            if (!events.active() && events.recover()) changed();
            synchronized (this) {
                if (preparing == null && (ready == null || preparedRevision != revision.get()
                        || ready.issues().stream().anyMatch(i -> i.code().equals("refresh_pending"))
                        || ready.projects().isEmpty() && System.nanoTime() >= retryAt)) {
                    if (System.nanoTime() - changedAt >= TimeUnit.MILLISECONDS.toNanos(100)) schedule();
                } else if (preparing == null && current() && !ready.projects().isEmpty()
                        && preparedWorktreeRevision != worktreeRevision) {
                    schedule(false);
                }

            }
        }

        synchronized void schedule() { schedule(true); }

        synchronized void schedule(boolean inputsChanged) {
            if (closed || stopped || preparing != null) return;
            long version = revision.get(), worktreeVersion = worktreeRevision;
            var completion = new CompletableFuture<Void>();
            preparing = completion;
            preparingStarted = System.nanoTime();
            preparingRevision = version;
            workers.execute(() -> {
                try { prepare(version, worktreeVersion, inputsChanged); completion.complete(null); }
                catch (Throwable failure) {
                    synchronized (this) {
                        if (!stopped && revision.get() == version) {
                            var issue = new ProjectRegistry.ProjectIssue(project.name(), project.root(), "index_unavailable", null,
                                    "Input preparation failed: " + ProjectRegistry.safeMessage(failure),
                                    "Preparation will retry automatically; no build is required", false)
                                    .readiness(ProjectRegistry.ReadinessReason.PREPARATION_RETRY_SCHEDULED,
                                            stage, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preparingStarted),
                                            TimeUnit.NANOSECONDS.toMillis(retryDelay),
                                            org.treblereel.mcp.index.ProjectIndexStore.findBestAvailableDb(project.root()) != null);
                            ready = new ProjectRegistry.Resolution(List.of(), List.of(issue.legacyMessage()), List.of(issue));
                            if (lastPrepared != null) registry.retireSuperseded(lastPrepared);
                            preparedRevision = version;
                            retryAt = System.nanoTime() + retryDelay;
                            retryDelay = Math.min(TimeUnit.SECONDS.toNanos(30), retryDelay * 2);
                        }
                    }
                    System.err.println("[quill] Preparation failed for " + project.name() + ": " + failure);
                    completion.completeExceptionally(failure);
                } finally {
                    synchronized (this) { preparing = null; notifyAll(); }
                }
            });
        }

        void prepare(long version, long worktreeVersion, boolean inputsChanged) {
            ProjectRegistry.Resolution result;
            boolean acquired = false;
            try {
                stage = "waiting_preparation_slot";
                slots.acquire(); acquired = true;
                stage = inputsChanged ? "resolving_index_and_build_events" : "refreshing_worktree_status";
                if (inputsChanged) registry.invalidateReadiness(project.root());
                result = inputsChanged ? registry.resolveUnprepared(project.name(), true) : ready;
                if (lastPrepared != null && result.issues().stream().anyMatch(i ->
                        i.code().equals("refresh_pending") || i.code().equals("index_refresh_failed")))
                    result = new ProjectRegistry.Resolution(List.of(), result.errors(), result.issues());
                if (inputsChanged && !result.projects().isEmpty()
                        && result.issues().stream().allMatch(issue -> issue.code().equals("build_required"))) {
                    var entry = result.projects().getFirst();
                    String indexed = entry.read(reader -> reader.index.getMetadata().get("last_commit"));
                    String head = GitAnalyzer.resolveHead(entry.root());
                    if (head != null && !head.equals(indexed)) {
                        stage = "refreshing_commit_index";
                        if (ProjectUpdater.updateAfterCommit(entry.root())) {
                            registry.invalidateReadiness(entry.root());
                            result = registry.resolveUnprepared(project.name(), true);
                        }
                    }
                }
                List<ProjectRegistry.ProjectEntry> prepared = new ArrayList<>();
                for (var entry : result.projects()) {
                    var previous = lastPrepared != null && lastPrepared.generation() == entry.generation() ? lastPrepared : null;
                    stage = inputsChanged ? "capturing_input_evidence" : "refreshing_worktree_status";
                    var observations = inputsChanged ? capture(entry, previous == null ? null : previous.observations(), events.active())
                            : captureWorktree(entry);
                    prepared.add(new ProjectRegistry.ProjectEntry(entry.name(), entry.root(), entry.generation(), observations));
                }
                result = new ProjectRegistry.Resolution(prepared, result.errors(), result.issues().stream()
                        .map(issue -> issue.code().equals("index_refresh_failed")
                                ? issue.readiness(issue.reason(), issue.stage(), issue.elapsedMs(),
                                        TimeUnit.NANOSECONDS.toMillis(retryDelay), issue.previousGenerationAvailable()) : issue)
                        .toList());
                // Deliver edits that raced capture before publishing the observed revision.
                if (events.active()) {
                    stage = "waiting_input_event_delivery";
                    try { events.awaitDelivery().get(500, TimeUnit.MILLISECONDS); }
                    catch (ExecutionException | TimeoutException unavailable) { events.deliveryMissing(); }
                }
                if (scope instanceof WorkspaceProjectScope workspace) {
                    var catalog = WorkspaceCoordinateCatalog.discover(workspace.manifest());
                    Map<String,Set<Path>> aliases = new HashMap<>();
                    catalog.modulesByGa().forEach((ga, modules) -> aliases.put(ga, modules.stream()
                            .map(module -> projects.projects().stream().filter(p -> p.name().equals(module.repository()))
                                    .map(ProjectScope.Project::root).findFirst().orElse(null))
                            .filter(Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet())));
                    coordinates = Map.copyOf(aliases);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Input preparation interrupted", interrupted);
            } finally {
                if (acquired) slots.release();
            }
            synchronized (this) {
                if (!stopped && lastPrepared == null && !result.projects().isEmpty())
                    lastPrepared = result.projects().getFirst();
                if (!stopped && revision.get() == version) {
                    ready = result;
                    if (result.issues().stream().anyMatch(i -> i.code().equals("refresh_pending") || i.code().equals("index_refresh_failed")))
                        ready = new ProjectRegistry.Resolution(List.of(), result.errors(), result.issues());
                    preparedRevision = version; preparedWorktreeRevision = worktreeVersion;
                    if (!result.projects().isEmpty()) {
                        lastPrepared = result.projects().getFirst();
                        fallback = null;
                        String id = result.projects().getFirst().observations().metadata().get("index_id");
                        if (!Objects.equals(id, announcedGeneration)) {
                            announcedGeneration = id;
                            System.err.println("[quill] Prepared " + project.name() + " generation " + id);
                        }
                    }
                    retryAt = System.nanoTime() + retryDelay;
                    if (result.projects().isEmpty() && result.issues().stream().noneMatch(i -> i.code().equals("refresh_pending")))
                        retryDelay = Math.min(TimeUnit.SECONDS.toNanos(30), retryDelay * 2);
                }
                if (!stopped && lastPrepared != null) registry.retireSuperseded(lastPrepared);
            }
        }

        synchronized void awaitInitial() {
            while (!stopped && lastPrepared == null && (ready == null || preparedRevision != revision.get())) {
                if (preparing == null) schedule();
                try { wait(100); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Preparation interrupted", error); }
            }
        }

        synchronized boolean current() { return ready != null && !ready.projects().isEmpty() && preparedRevision == revision.get(); }

        synchronized ProjectRegistry.Resolution result() {
            if (ready != null && preparedRevision == revision.get()) {
                if (!ready.projects().isEmpty() || lastPrepared == null) return ready;
                return previousResult(ready);
            }
            var reason = !events.active() ? ProjectRegistry.ReadinessReason.INPUT_OBSERVATION_RECOVERING
                    : preparing == null || stage.equals("waiting_preparation_slot") ? ProjectRegistry.ReadinessReason.PREPARATION_QUEUED
                    : revision.get() != preparingRevision ? ProjectRegistry.ReadinessReason.INPUTS_CHANGING
                    : ProjectRegistry.ReadinessReason.INPUT_PREPARATION_RUNNING;
            var issue = new ProjectRegistry.ProjectIssue(project.name(), project.root(), "refresh_pending", null,
                    "Input preparation: " + (preparing == null ? "queued" : stage),
                    "Retry the same query after preparation; do not narrow targets or start another build", false)
                    .readiness(reason, preparing == null ? "queued" : stage,
                            preparing == null ? 0L : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - preparingStarted),
                            1000L, lastPrepared != null || ready != null && (!ready.projects().isEmpty()
                                    || ready.issues().stream().anyMatch(ProjectRegistry.ProjectIssue::previousGenerationAvailable)));
            return previousResult(new ProjectRegistry.Resolution(List.of(), List.of(issue.legacyMessage()), List.of(issue)));
        }

        private ProjectRegistry.Resolution previousResult(ProjectRegistry.Resolution unavailable) {
            if (lastPrepared == null || unavailable.issues().isEmpty()) return unavailable;
            var issue = unavailable.issues().getFirst();
            if (fallback == null || !issue.code().equals(fallbackCode)) {
                var previous = lastPrepared.observations();
                var old = previous.freshness();
                var reasons = new LinkedHashSet<>(old.staleReasons()); reasons.add("previous_generation"); reasons.add(issue.code());
                var freshness = new MetaEnvelope(old.indexId(), old.indexedAt(), old.lastCommit(), old.currentCommit(),
                        old.commitStale(), old.worktreeDirty(), old.worktreeChangedFiles(), old.structuralChangedFiles(),
                        true, List.copyOf(reasons), true, 0, 0, 0);
                var sources = previous.sourceFreshness().deepCopy();
                ((ObjectNode) sources).put("state", "unknown");
                var compiled = previous.buildFreshness().deepCopy();
                ((ObjectNode) compiled).put("state", "unknown");
                var observations = new ProjectReadSnapshot(previous.metadata(), previous.classes(), sources, compiled,
                        previous.worktree(), freshness, previous.structuralPaths(), (modules, meta) -> {
                            try {
                                var build = (ObjectNode) JSON.readTree(previous.buildStatus().apply(modules, meta));
                                build.put("status", "refresh_pending").put("reason", issue.reason().code())
                                        .put("action", issue.reason().action).put("recommended_action", issue.recommendedAction());
                                return build.toString();
                            } catch (IOException invalid) { throw new IllegalStateException(invalid); }
                        }, previous.verificationPlan());
                ProjectDependencyQueries.reuseSnapshot(previous, observations);
                fallback = new ProjectRegistry.ProjectEntry(lastPrepared.name(), lastPrepared.root(), lastPrepared.generation(), observations);
                fallbackCode = issue.code();
            }
            return new ProjectRegistry.Resolution(List.of(fallback), List.of(), unavailable.issues());
        }

        private ProjectReadSnapshot capture(ProjectRegistry.ProjectEntry entry, ProjectReadSnapshot previous, boolean trusted) {
            stage = "loading_type_graph";
            return entry.readWithGraph(reader -> {
                Map<String,String> metadata = reader.index.getMetadata();
                var classes = previous == null ? reader.symbols.findAllClasses() : previous.classes();
                stage = "capturing_worktree";
                var worktree = WorktreeSnapshotCache.shared().refresh(entry.root());
                MetaEnvelope freshness = MetaEnvelope.from(metadata, worktree.currentCommit(), worktree.structuralFingerprint(),
                        worktree.structuralDirty(), worktree.dirty(), worktree.changes().size(), worktree.structuralChanges().size(), 0, 0);
                stage = "checking_source_inputs";
                var sources = UsageCoverage.sourceFreshness(metadata);
                String indexedInputs = metadata.get("indexed_input_fingerprint");
                boolean inputsChanged = indexedInputs != null && !indexedInputs.equals(worktree.indexInputFingerprint());
                if (!trusted || inputsChanged || !sources.path("state").asText().equals("matching")) {
                    var reasons = new ArrayList<>(freshness.staleReasons());
                    if (!trusted) reasons.add("input_watch_unavailable");
                    if (inputsChanged) reasons.add("indexed_inputs_changed");
                    if (sources.path("state").asText().equals("changed")) reasons.add(sources.path("language_scope").asText().equals("jvm") ? "indexed_jvm_sources_changed" : "indexed_java_sources_changed");
                    else if (!sources.path("state").asText().equals("matching")) reasons.add("source_snapshot_unavailable");
                    freshness = new MetaEnvelope(freshness.indexId(), freshness.indexedAt(), freshness.lastCommit(), freshness.currentCommit(),
                            freshness.commitStale(), freshness.worktreeDirty(), freshness.worktreeChangedFiles(), freshness.structuralChangedFiles(),
                            true, List.copyOf(reasons), true, 0, 0, 0);
                }
                stage = "checking_build_inputs";
                var build = BuildStatusInspector.capture(entry.root(), metadata, freshness);
                stage = "checking_compiled_outputs";
                var compiled = UsageCoverage.buildFreshness(metadata);
                // Rebuilt classes with the indexed content are newer by timestamp only.
                boolean rebuiltUnchanged = Boolean.FALSE.equals(build.fingerprintChanged()) && compiled.path("changed_after_index").asBoolean();
                if (rebuiltUnchanged) ((com.fasterxml.jackson.databind.node.ObjectNode) compiled)
                        .put("changed_after_index", false).put("content_matches_index", true);
                if (Boolean.TRUE.equals(build.fingerprintChanged()) || compiled.path("changed_after_index").asBoolean()
                        || compiled.path("state").asText().equals("unknown")) {
                    var reasons = new ArrayList<>(freshness.staleReasons()); reasons.add("compiled_snapshot_changed");
                    freshness = new MetaEnvelope(freshness.indexId(), freshness.indexedAt(), freshness.lastCommit(), freshness.currentCommit(),
                            freshness.commitStale(), freshness.worktreeDirty(), freshness.worktreeChangedFiles(), freshness.structuralChangedFiles(),
                            true, List.copyOf(reasons), true, 0, 0, 0);
                }
                stage = "preparing_verification_plan";
                var plan = VerificationPlanQueries.prepare(entry.root());
                var result = new ProjectReadSnapshot(metadata, classes, sources, compiled,
                        WorktreeStatusQueries.capture(entry.root(), metadata, worktree), freshness,
                        worktree.structuralChanges().stream().map(WorktreeInspector.Change::projectPath).toList(),
                        build::inspect, plan);
                stage = "preparing_dependency_evidence";
                ProjectDependencyQueries.prepareSnapshot(reader, entry.root(), result);
                return result;
            });
        }

        @Override public void close() {
            synchronized (this) { stopped = true; if (preparing != null) preparing.cancel(true); notifyAll(); }
            events.close();
        }
    }

    static ProjectReadSnapshot captureWorktree(ProjectRegistry.ProjectEntry entry) {
        var previous = entry.observations();
        var worktree = WorktreeSnapshotCache.shared().refresh(entry.root());
        var old = previous.freshness();
        var observed = MetaEnvelope.from(previous.metadata(), worktree.currentCommit(), worktree.structuralFingerprint(),
                worktree.structuralDirty(), worktree.dirty(), worktree.changes().size(), worktree.structuralChanges().size(), 0, 0);
        var reasons = new LinkedHashSet<>(old.staleReasons()); reasons.addAll(observed.staleReasons());
        String indexedInputs = previous.metadata().get("indexed_input_fingerprint");
        if (indexedInputs != null && !indexedInputs.equals(worktree.indexInputFingerprint())) reasons.add("indexed_inputs_changed");
        boolean stale = old.structureStale() || observed.structureStale() || !reasons.isEmpty();
        var freshness = new MetaEnvelope(old.indexId(), old.indexedAt(), old.lastCommit(), observed.currentCommit(),
                old.commitStale() || observed.commitStale(), worktree.dirty(), worktree.changes().size(), worktree.structuralChanges().size(),
                stale, List.copyOf(reasons), stale, 0, 0, 0);
        var result = new ProjectReadSnapshot(previous.metadata(), previous.classes(), previous.sourceFreshness(), previous.buildFreshness(),
                WorktreeStatusQueries.capture(entry.root(), previous.metadata(), worktree), freshness, previous.structuralPaths(),
                previous.buildStatus(), previous.verificationPlan());
        ProjectDependencyQueries.reuseSnapshot(previous, result);
        return result;
    }

    private record Stamp(Object identity, long size, Object modified, Object changed) {}
    private static Map<Path,Object> controls(Path root, Path git) {
        List<Path> files = new ArrayList<>(List.of(root.resolve(".quill/refs.json"),
                root.resolve(".quill/build-state.json"), root.resolve(".quill/build-events")));
        if (git != null) files.addAll(List.of(git.resolve("HEAD"), git.resolve("index"), git.resolve("packed-refs")));
        for (Path parent = root.getParent(); parent != null; parent = parent.getParent()) files.add(parent.resolve("pom.xml"));
        Map<Path,Object> result = new HashMap<>();
        for (Path file : files) {
            try {
                var attr = Files.readAttributes(file, BasicFileAttributes.class);
                Object changed;
                try { changed = Files.getAttribute(file, "unix:ctime"); }
                catch (UnsupportedOperationException | IllegalArgumentException ignored) { changed = attr.lastModifiedTime(); }
                result.put(file, new Stamp(attr.fileKey(), attr.size(), attr.lastModifiedTime(), changed));
            } catch (IOException | SecurityException absent) { result.put(file, "unavailable"); }
        }
        return Map.copyOf(result);
    }

    @Override public void close() {
        closed = true;
        initial.cancel(true);
        timer.shutdownNow();
        workers.shutdownNow();
        synchronized (this) {
            states.values().forEach(State::close);
            states.clear();
        }
    }
}
