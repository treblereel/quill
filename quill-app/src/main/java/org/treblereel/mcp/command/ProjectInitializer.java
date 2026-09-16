package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.jdbi.v3.core.Jdbi;
import org.jboss.jandex.Index;
import org.treblereel.mcp.QuillLauncher;
import org.treblereel.mcp.core.BeanResolver;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.BytecodeDependencyScanner;
import org.treblereel.mcp.core.ClassFileSnapshot;
import org.treblereel.mcp.core.DependencyIndexer;
import org.treblereel.mcp.core.FileInventory;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.GradleProjectDiscovery;
import org.treblereel.mcp.core.JandexScanner;
import org.treblereel.mcp.core.MavenProjectDiscovery;
import org.treblereel.mcp.core.SpringResolver;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.CdiProblem;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

public class ProjectInitializer {

    public enum FailureReason {
        LOCK_FAILED,
        NO_COMPILED_CLASSES,
        MIXED_FRAMEWORKS,
        HEAD_CHANGED,
        WORKTREE_CHANGED,
        INDEX_PUBLICATION_FAILED,
        INDEXING_FAILED
    }

    public record InitializationResult(
            boolean successful, FailureReason reason, String message, long elapsedMillis,
            Map<String, Long> phaseMillis) {

        public InitializationResult(
                boolean successful, FailureReason reason, String message, long elapsedMillis) {
            this(successful, reason, message, elapsedMillis, Map.of());
        }

        public InitializationResult {
            phaseMillis = Collections.unmodifiableMap(new LinkedHashMap<>(phaseMillis));
        }

        static InitializationResult success(long startedAtNanos) {
            return new InitializationResult(true, null, "Index created successfully",
                    elapsedMillis(startedAtNanos));
        }

        static InitializationResult failure(
                FailureReason reason, String message, long startedAtNanos) {
            return new InitializationResult(false, reason, message, elapsedMillis(startedAtNanos));
        }

        public String diagnostic() {
            if (successful) return message;
            return "Initialization failed [" + reason + "]: " + message;
        }

        InitializationResult withPhaseMillis(Map<String, Long> phases) {
            return new InitializationResult(
                    successful, reason, message, elapsedMillis, phases);
        }

        public String timingsDiagnostic() {
            List<String> values = new ArrayList<>();
            phaseMillis.forEach((phase, millis) -> values.add(phase + "=" + millis + "ms"));
            values.add("total=" + elapsedMillis + "ms");
            return "Timings: " + String.join(", ", values);
        }

        private static long elapsedMillis(long startedAtNanos) {
            return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
        }
    }

    static final class PhaseTimings {
        private final Map<String, Long> phases = new LinkedHashMap<>();
        private long phaseStartedAt = System.nanoTime();

        void finish(String phase) {
            long now = System.nanoTime();
            phases.merge(phase, Math.max(0, (now - phaseStartedAt) / 1_000_000), Long::sum);
            phaseStartedAt = now;
        }

        void record(String phase, long millis) {
            phases.merge(phase, Math.max(0, millis), Long::sum);
        }

        void restart() {
            phaseStartedAt = System.nanoTime();
        }

        Map<String, Long> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(phases));
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }

    static final class BackgroundTask<T> implements AutoCloseable {
        private final String threadName;
        private final ExecutorService executor;
        private final Future<T> future;
        private volatile long elapsedNanos;

        private BackgroundTask(String threadName, Callable<T> operation) {
            this.threadName = threadName;
            executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, threadName);
                thread.setDaemon(true);
                return thread;
            });
            future = executor.submit(() -> {
                long startedAt = System.nanoTime();
                try {
                    return operation.call();
                } finally {
                    elapsedNanos = System.nanoTime() - startedAt;
                    executor.shutdown();
                }
            });
        }

        static <T> BackgroundTask<T> start(String threadName, Callable<T> operation) {
            return new BackgroundTask<>(threadName, operation);
        }

        T await() {
            try {
                return future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(threadName + " was interrupted", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException(threadName + " failed", cause);
            }
        }

        long elapsedMillis() {
            if (!future.isDone()) {
                throw new IllegalStateException(threadName + " has not completed");
            }
            return Math.max(0, elapsedNanos / 1_000_000);
        }

        @Override
        public void close() {
            if (!future.isDone()) future.cancel(true);
            executor.shutdownNow();
        }
    }

    record PersistedResolution(
            List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints,
            List<DependencyRecord> dependencies) {}

    public static boolean initialize(Path root) {
        return initialize(root, false);
    }

    public static boolean initialize(Path root, boolean indexOnly) {
        return initializeDetailed(root, indexOnly).successful();
    }

    public static InitializationResult initializeDetailed(Path root, boolean indexOnly) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        long startedAtNanos = System.nanoTime();
        PhaseTimings timings = new PhaseTimings();
        try {
            InitializationResult result = ProjectIndexLock.withLock(normalizedRoot, () -> {
                timings.finish("lock_wait");
                return initializeLockedDetailed(normalizedRoot, indexOnly, false,
                        null, startedAtNanos, timings);
            });
            return result.withPhaseMillis(timings.snapshot());
        } catch (IOException e) {
            return InitializationResult.failure(FailureReason.LOCK_FAILED,
                    "Could not lock index for " + normalizedRoot + ": " + rootMessage(e),
                    startedAtNanos).withPhaseMillis(timings.snapshot());
        } catch (RuntimeException e) {
            return InitializationResult.failure(FailureReason.INDEXING_FAILED,
                    "Unexpected indexing error for " + normalizedRoot + ": " + rootMessage(e),
                    startedAtNanos).withPhaseMillis(timings.snapshot());
        }
    }

    static boolean initializeLocked(Path root, boolean indexOnly) {
        return initializeLockedDetailed(root, indexOnly, false, null, System.nanoTime(),
                new PhaseTimings()).successful();
    }

    static boolean initializeLocked(Path root, boolean indexOnly, boolean compiledBeforeIndex) {
        return initializeLockedDetailed(root, indexOnly, compiledBeforeIndex, null,
                System.nanoTime(), new PhaseTimings()).successful();
    }

    static InitializationResult initializeLockedDetailed(
            Path root, boolean indexOnly, boolean compiledBeforeIndex) {
        long startedAtNanos = System.nanoTime();
        PhaseTimings timings = new PhaseTimings();
        try {
            return initializeLockedDetailed(
                    root, indexOnly, compiledBeforeIndex, null, startedAtNanos, timings)
                    .withPhaseMillis(timings.snapshot());
        } catch (RuntimeException e) {
            return InitializationResult.failure(FailureReason.INDEXING_FAILED,
                    "Unexpected indexing error for " + root + ": " + rootMessage(e),
                    startedAtNanos).withPhaseMillis(timings.snapshot());
        }
    }

    static InitializationResult initializeLockedDetailed(
            Path root, boolean indexOnly, boolean compiledBeforeIndex, Path incrementalBase) {
        long startedAtNanos = System.nanoTime();
        PhaseTimings timings = new PhaseTimings();
        try {
            return initializeLockedDetailed(root, indexOnly, compiledBeforeIndex, incrementalBase,
                    startedAtNanos, timings).withPhaseMillis(timings.snapshot());
        } catch (RuntimeException e) {
            return InitializationResult.failure(FailureReason.INDEXING_FAILED,
                    "Unexpected indexing error for " + root + ": " + rootMessage(e),
                    startedAtNanos).withPhaseMillis(timings.snapshot());
        }
    }

    private static InitializationResult initializeLockedDetailed(
            Path root, boolean indexOnly, boolean compiledBeforeIndex, Path incrementalBase,
            long startedAtNanos, PhaseTimings timings) {
        List<Path> classesDirs = findClassesDirs(root, true);
        timings.finish("class_discovery");

        if (classesDirs.isEmpty()) {
            return InitializationResult.failure(FailureReason.NO_COMPILED_CLASSES,
                    "No main .class files were found under " + root
                            + ". Build the project with Maven or Gradle, then run `quill init` again.",
                    startedAtNanos);
        }

        // Compilation is a prerequisite and is never started by Quill. Capture the authoritative
        // commit/worktree snapshot afterwards so generated tracked files do not make a
        // successful compile look like a concurrent mutation.
        // Quill's own managed-file changes must also happen before the snapshot; otherwise
        // the first init would invalidate itself when adding .quill/ to .gitignore.
        if (!indexOnly) {
            ensureGitignore(root);
            // Install build-success notification before capturing freshness because
            // Maven/Gradle configuration is structural input.
            BuildIntegrationInstaller.install(root);
        }
        String initialHead = GitAnalyzer.resolveHead(root);
        WorktreeInspector.Snapshot initialWorktree = WorktreeInspector.inspect(root);
        timings.finish("worktree_snapshot");

        System.err.println("[quill] Indexing " + root.getFileName() + " (" + classesDirs.size() + " class dirs)...");
        BuildSystem buildSystem = BuildSystem.detect(root);
        Map<Path, Path> classDirectoryOwners =
                DependencyIndexer.mapClassDirectoriesToModules(root, buildSystem, classesDirs);
        List<Path> moduleDirectories = classDirectoryOwners.values().stream().distinct().toList();
        List<Path> sourceRoots = findSourceRoots(moduleDirectories);
        List<Path> indexingClassDirs = List.copyOf(classesDirs);
        timings.finish("index_setup");
        ClassFileSnapshot classFiles;
        JandexScanner.ScanResult scanResult;
        boolean isSpring;
        boolean isCdi;
        DependencyIndexer.DependencyIndexResult depResult;
        try (BackgroundTask<DependencyIndexer.DependencyIndexResult> dependencyTask =
                BackgroundTask.start("quill-dependency-index",
                        () -> DependencyIndexer.buildDependencyIndex(root, indexingClassDirs))) {
            classFiles = ClassFileSnapshot.capture(indexingClassDirs);
            scanResult = sourceRoots.isEmpty()
                    ? JandexScanner.scan(classFiles, List.of(),
                            ProjectIndexStore.sourceTokenCache(root),
                            ProjectIndexStore.applicationIndexCache(root))
                    : JandexScanner.scan(classFiles, sourceRoots,
                            ProjectIndexStore.sourceTokenCache(root),
                            ProjectIndexStore.applicationIndexCache(root));
            if (scanResult.cacheHits() > 0) {
                System.err.println("[quill] Reusing application index shards "
                        + scanResult.cacheHits() + "/" + scanResult.cacheShards() + "...");
            }

            isSpring = SpringResolver.isSpringProject(scanResult.index());
            isCdi = BeanResolver.isCdiProject(scanResult.index());
            timings.finish("application_scan");

            depResult = dependencyTask.await();
            depResult.timings().forEach(timings::record);
            timings.record("dependency_total", dependencyTask.elapsedMillis());
        }
        timings.restart();
        if (depResult.status() != DependencyIndexer.Status.COMPLETE) {
            System.err.println("[quill] Warning: dependency index "
                    + depResult.status().name().toLowerCase() + " (" + depResult.detail() + ")");
        }

        List<ClassRecord> classes = scanResult.classes();

        Map<String, Integer> classNameToSqliteId = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            classNameToSqliteId.put(classes.get(i).className(), i + 1);
        }

        Map<String, Integer> sourceFileToClassId = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            String sf = classes.get(i).sourceFile();
            if (sf != null) {
                Path sfPath = Path.of(sf);
                if (sfPath.isAbsolute() && sfPath.startsWith(root)) {
                    sf = root.relativize(sfPath).toString();
                }
                sourceFileToClassId.put(sf.replace('\\', '/'), i + 1);
            }
        }
        timings.finish("analysis_setup");

        List<BeanResolver.ResolutionResult> resolutions = new ArrayList<>();
        if (isSpring) {
            resolutions.add(SpringResolver.resolve(scanResult.index(), depResult.index()));
        }
        if (isCdi || !isSpring) {
            resolutions.add(BeanResolver.resolve(scanResult.index(), depResult.index()));
        }

        List<PersistedResolution> persistedParts = new ArrayList<>();
        for (BeanResolver.ResolutionResult resolution : resolutions) {
            Map<Integer, Integer> brToSqlite = new HashMap<>();
            for (var entry : resolution.classNameToId().entrySet()) {
                Integer sqliteId = classNameToSqliteId.get(entry.getKey());
                if (sqliteId != null) {
                    brToSqlite.put(entry.getValue(), sqliteId);
                }
            }
            persistedParts.add(remapForPersistence(resolution, brToSqlite));
        }
        PersistedResolution persisted = mergePersistedResolutions(
                persistedParts, isSpring && isCdi);
        List<BeanRecord> remappedBeans = persisted.beans();

        Set<Integer> beanClassIds = new HashSet<>();
        for (BeanRecord b : remappedBeans) {
            beanClassIds.add(b.classId());
        }
        List<ClassRecord> correctedClasses = new ArrayList<>();
        for (int i = 0; i < classes.size(); i++) {
            ClassRecord c = classes.get(i);
            boolean isBean = beanClassIds.contains(i + 1);
            if (isBean != c.isBean()) {
                c = new ClassRecord(c.id(), c.className(), c.kind(), c.superclass(),
                        c.interfaces(), c.sourceFile(), c.sourceLine(), isBean, c.sourceTokens());
            }
            correctedClasses.add(c);
        }
        classes = correctedClasses;
        timings.finish("bean_resolution");

        List<DependencyRecord> remappedDeps = new ArrayList<>(persisted.dependencies());
        List<ExternalDepRecord> externalDeps;
        GitAnalyzer.GitAnalysisResult gitResult;
        try (BackgroundTask<GitAnalyzer.GitAnalysisResult> gitTask =
                BackgroundTask.start("quill-git-analysis", () -> GitAnalyzer.hasGitRepo(root)
                        ? GitAnalyzer.analyze(root, 500, sourceFileToClassId)
                        : GitAnalyzer.GitAnalysisResult.empty())) {
            for (BytecodeDependencyScanner.StaticDependency dependency
                    : BytecodeDependencyScanner.scan(classFiles, classNameToSqliteId.keySet())) {
                Integer from = classNameToSqliteId.get(dependency.fromClass());
                Integer to = classNameToSqliteId.get(dependency.toClass());
                if (from != null && to != null) {
                    remappedDeps.add(new DependencyRecord(from, to, dependency.kind(), null,
                            dependency.occurrences()));
                }
            }

            externalDeps = JandexScanner.extractExternalDeps(
                    scanResult.index(), classNameToSqliteId);
            timings.finish("bytecode_analysis");

            gitResult = gitTask.await();
            timings.record("git_analysis", gitTask.elapsedMillis());
        }
        timings.restart();

        String lastCommit = gitResult.headHash();

        if (!headMatches(root, initialHead)
                || (initialHead != null && !initialHead.equals(lastCommit))) {
            return InitializationResult.failure(FailureReason.HEAD_CHANGED,
                    headChangedMessage("during indexing", initialHead,
                            GitAnalyzer.resolveHead(root)), startedAtNanos);
        }

        List<CdiProblem> problems = resolutions.stream()
                .flatMap(resolution -> resolution.problems().stream())
                .toList();
        List<CdiProblem> remappedProblems = problems.stream()
                .map(p -> new CdiProblem(p.id(),
                        classNameToSqliteId.get(p.className()),
                        p.className(), p.problemType(), p.message()))
                .toList();

        FileInventory.Result inventory = FileInventory.build(root, moduleDirectories, sourceRoots,
                classes, gitResult.fileStats(), initialWorktree);
        classes = inventory.classes();
        Set<Integer> currentClassIds = new HashSet<>();
        for (int i = 0; i < classes.size(); i++) {
            ClassRecord cls = classes.get(i);
            if (cls.lifecycle().equals("current") && !cls.origin().equals("orphan_output")) {
                currentClassIds.add(i + 1);
            }
        }
        remappedDeps.removeIf(dependency -> !currentClassIds.contains(dependency.fromClassId())
                || !currentClassIds.contains(dependency.toClassId()));
        timings.finish("file_inventory");

        String indexId = ProjectIndexStore.createIndexId(lastCommit);
        Path dbPath = ProjectIndexStore.resolveDbPath(root, indexId);
        Path stagedDb = dbPath.resolveSibling("." + dbPath.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("indexed_at", Instant.now().toString());
        metadata.put("index_id", indexId);
        metadata.put("project_root", root.toString());
        metadata.put("last_commit", lastCommit != null ? lastCommit : "unknown");
        metadata.put("indexed_worktree_dirty", Boolean.toString(initialWorktree.dirty()));
        metadata.put("indexed_worktree_changed_files",
                Integer.toString(initialWorktree.changes().size()));
        metadata.put("indexed_structure_fingerprint", initialWorktree.structuralFingerprint());
        metadata.put("indexed_structural_changed_files",
                Integer.toString(initialWorktree.structuralChanges().size()));
        metadata.put("compiled_before_index", Boolean.toString(compiledBeforeIndex));
        metadata.put("structure_scope", "compiled_snapshot");
        metadata.put("application_index_cache_hits",
                Integer.toString(scanResult.cacheHits()));
        metadata.put("application_index_cache_shards",
                Integer.toString(scanResult.cacheShards()));
        metadata.put("framework", isSpring && isCdi ? "Mixed"
                : isSpring ? "Spring" : isCdi ? "CDI" : "Plain");
        metadata.put("dependency_index", depResult.status().name().toLowerCase());
        metadata.put("dependency_index_detail", depResult.detail());
        metadata.put("database_write_mode", incrementalBase == null ? "fresh" : "incremental");
        metadata.put("state_fingerprint",
                computeStateFingerprint(root, classesDirs, classFiles.fingerprint()));
        timings.finish("index_metadata");

        long databaseStartedAt = System.nanoTime();
        try {
            if (incrementalBase == null) {
                long schemaStartedAt = System.nanoTime();
                Jdbi jdbi = QuillDatabase.createForBulkLoad(stagedDb);
                timings.record("database_schema", elapsedMillis(schemaStartedAt));
                IndexWriter.WriteTimings writeTimings = IndexWriter.writeFresh(
                        jdbi, classes, remappedBeans,
                        persisted.injectionPoints(), remappedDeps, metadata,
                        externalDeps, remappedProblems,
                        gitResult.fileStats(), gitResult.commits(), gitResult.commitFiles(),
                        inventory.files());
                timings.record("database_inserts", writeTimings.insertsMillis());
                timings.record("database_indexes", writeTimings.indexesMillis());
                timings.record("database_transaction_overhead",
                        writeTimings.transactionOverheadMillis());
            } else {
                long cloneStartedAt = System.nanoTime();
                Files.copy(incrementalBase, stagedDb, StandardCopyOption.COPY_ATTRIBUTES);
                timings.record("database_clone", elapsedMillis(cloneStartedAt));
                Jdbi jdbi = QuillDatabase.openWritable(stagedDb);
                IndexWriter.IncrementalWriteTimings writeTimings =
                        IndexWriter.writeIncremental(jdbi, classes, remappedBeans,
                                persisted.injectionPoints(), remappedDeps, metadata,
                                externalDeps, remappedProblems,
                                gitResult.fileStats(), gitResult.commits(),
                                gitResult.commitFiles(), inventory.files());
                timings.record("database_delta", writeTimings.deltaMillis());
                timings.record("database_transaction_overhead",
                        writeTimings.transactionOverheadMillis());
                System.err.println("[quill] SQLite delta: " + writeTimings.rowsInserted()
                        + " inserted, " + writeTimings.rowsDeleted() + " deleted, "
                        + writeTimings.rowsUnchanged() + " unchanged.");
            }
            long validationStartedAt = System.nanoTime();
            boolean headChanged = !headMatches(root, initialHead);
            boolean worktreeChanged = !worktreeMatches(root, initialWorktree.fingerprint());
            timings.record("publication_validation", elapsedMillis(validationStartedAt));
            if (headChanged || worktreeChanged) {
                ProjectIndexStore.deleteDatabaseArtifacts(stagedDb);
                if (headChanged) {
                    return InitializationResult.failure(FailureReason.HEAD_CHANGED,
                            headChangedMessage("before index publication", initialHead,
                                    GitAnalyzer.resolveHead(root)), startedAtNanos);
                }
                WorktreeInspector.Snapshot currentWorktree = WorktreeInspector.inspect(root);
                return InitializationResult.failure(FailureReason.WORKTREE_CHANGED,
                        "Worktree changed before index publication; the staged index was discarded. "
                                + describeWorktree(currentWorktree) + " Retry `quill update` when "
                                + "concurrent builds or edits have finished.",
                        startedAtNanos);
            }
            long publicationStartedAt = System.nanoTime();
            ProjectIndexStore.atomicMove(stagedDb, dbPath);
            timings.record("atomic_publication", elapsedMillis(publicationStartedAt));
            timings.restart();
        } catch (RuntimeException | IOException e) {
            timings.record("database_failed", elapsedMillis(databaseStartedAt));
            timings.restart();
            ProjectIndexStore.deleteDatabaseArtifacts(stagedDb);
            return InitializationResult.failure(FailureReason.INDEX_PUBLICATION_FAILED,
                    "Could not publish SQLite index at " + dbPath + ": " + rootMessage(e)
                            + ". The previous index, if any, was left unchanged.",
                    startedAtNanos);
        }

        long validationStartedAt = System.nanoTime();
        boolean headChangedAfterPublication = !headMatches(root, initialHead);
        timings.record("publication_validation", elapsedMillis(validationStartedAt));
        if (headChangedAfterPublication) {
            ProjectIndexStore.deleteDatabaseArtifacts(dbPath);
            return InitializationResult.failure(FailureReason.HEAD_CHANGED,
                    headChangedMessage("after index publication", initialHead,
                            GitAnalyzer.resolveHead(root))
                            + " The unpublished generation was removed; retry `quill update`.",
                    startedAtNanos);
        }

        long activationStartedAt = System.nanoTime();
        try {
            ProjectIndexStore.updateRefs(root, lastCommit, indexId);
        } catch (RuntimeException e) {
            timings.record("activation", elapsedMillis(activationStartedAt));
            ProjectIndexStore.deleteDatabaseArtifacts(dbPath);
            return InitializationResult.failure(FailureReason.INDEX_PUBLICATION_FAILED,
                    "Could not activate immutable index generation " + dbPath + ": "
                            + rootMessage(e)
                            + ". The previous index remains active.",
                    startedAtNanos);
        }

        if (!indexOnly) {
            ensureClaudeMd(root);
        }
        ensureCodexConfig(root, indexOnly);
        timings.record("activation", elapsedMillis(activationStartedAt));
        timings.restart();

        String depStatus = depResult.status() == DependencyIndexer.Status.COMPLETE
                ? "" : " [deps: " + depResult.status().name().toLowerCase() + "]";
        System.err.println("[quill] Done: " + classes.size() + " classes, "
                + remappedBeans.size() + " beans"
                + (gitResult.isEmpty() ? "" : ", " + gitResult.commits().size() + " git commits")
                + depStatus + ".");
        return InitializationResult.success(startedAtNanos);
    }

    static CodexConfigInstaller.Result ensureCodexConfig(Path root, boolean indexOnly) {
        if (indexOnly) return CodexConfigInstaller.Result.SKIPPED;
        return CodexConfigInstaller.installIfPresent(root, QuillLauncher.detect());
    }

    static PersistedResolution remapForPersistence(
            BeanResolver.ResolutionResult resolution, Map<Integer, Integer> classIds) {
        Map<Integer, Integer> beanIds = new LinkedHashMap<>();
        List<BeanRecord> beans = new ArrayList<>();
        for (BeanRecord bean : resolution.beans()) {
            Integer classId = classIds.get(bean.classId());
            if (classId == null) continue;
            if (bean.declaringClassId() != null && !classIds.containsKey(bean.declaringClassId())) {
                continue;
            }

            int newId = beans.size() + 1;
            beanIds.put(bean.id(), newId);
            beans.add(new BeanRecord(
                    newId, classId, bean.kind(), bean.scope(), bean.qualifiers(), bean.stereotypes(),
                    bean.isAlternative(), bean.priority(), bean.profiles(),
                    bean.declaringClassId() != null ? classIds.get(bean.declaringClassId()) : null,
                    bean.memberName(), bean.beanTypes()));
        }

        Map<Integer, Integer> injectionPointIds = new LinkedHashMap<>();
        List<InjectionPointRecord> injectionPoints = new ArrayList<>();
        for (InjectionPointRecord ip : resolution.injectionPoints()) {
            Integer ownerBeanId = beanIds.get(ip.beanId());
            if (ownerBeanId == null) continue;

            int newId = injectionPoints.size() + 1;
            injectionPointIds.put(ip.id(), newId);
            injectionPoints.add(new InjectionPointRecord(
                    newId, ownerBeanId, ip.kind(), ip.targetType(), ip.qualifiers(), ip.fieldName(),
                    ip.resolvedBeanId() != null ? beanIds.get(ip.resolvedBeanId()) : null,
                    ip.isAmbiguous()));
        }

        List<DependencyRecord> dependencies = resolution.dependencies().stream()
                .filter(d -> classIds.containsKey(d.fromClassId())
                        && classIds.containsKey(d.toClassId()))
                .filter(d -> d.injectionPointId() == null
                        || injectionPointIds.containsKey(d.injectionPointId()))
                .map(d -> new DependencyRecord(
                        classIds.get(d.fromClassId()), classIds.get(d.toClassId()), d.kind(),
                        d.injectionPointId() != null
                                ? injectionPointIds.get(d.injectionPointId()) : null))
                .toList();

        return new PersistedResolution(beans, injectionPoints, dependencies);
    }

    static PersistedResolution mergePersistedResolutions(
            List<PersistedResolution> parts, boolean mixedFrameworks) {
        List<BeanRecord> beans = new ArrayList<>();
        List<InjectionPointRecord> injectionPoints = new ArrayList<>();
        List<DependencyRecord> dependencies = new ArrayList<>();

        for (PersistedResolution part : parts) {
            Map<Integer, Integer> beanIds = new HashMap<>();
            for (BeanRecord bean : part.beans()) {
                int id = beans.size() + 1;
                beanIds.put(bean.id(), id);
                beans.add(new BeanRecord(id, bean.classId(), bean.kind(), bean.scope(),
                        bean.qualifiers(), bean.stereotypes(), bean.isAlternative(),
                        bean.priority(), bean.profiles(), bean.declaringClassId(),
                        bean.memberName(), bean.beanTypes()));
            }

            Map<Integer, Integer> injectionPointIds = new HashMap<>();
            for (InjectionPointRecord injectionPoint : part.injectionPoints()) {
                Integer beanId = beanIds.get(injectionPoint.beanId());
                if (beanId == null) continue;
                int id = injectionPoints.size() + 1;
                injectionPointIds.put(injectionPoint.id(), id);
                injectionPoints.add(new InjectionPointRecord(id, beanId,
                        injectionPoint.kind(), injectionPoint.targetType(),
                        injectionPoint.qualifiers(), injectionPoint.fieldName(),
                        injectionPoint.resolvedBeanId() != null
                                ? beanIds.get(injectionPoint.resolvedBeanId()) : null,
                        injectionPoint.isAmbiguous()));
            }

            for (DependencyRecord dependency : part.dependencies()) {
                if (mixedFrameworks && "CLASS_REFERENCE".equals(dependency.kind())) {
                    // Both resolvers scan all non-bean signatures. The bytecode pass below
                    // supplies the shared static graph once for the complete reactor.
                    continue;
                }
                Integer injectionPointId = dependency.injectionPointId() != null
                        ? injectionPointIds.get(dependency.injectionPointId()) : null;
                if (dependency.injectionPointId() != null && injectionPointId == null) continue;
                dependencies.add(new DependencyRecord(dependency.fromClassId(),
                        dependency.toClassId(), dependency.kind(), injectionPointId,
                        dependency.occurrenceCount()));
            }
        }
        return new PersistedResolution(
                List.copyOf(beans), List.copyOf(injectionPoints), List.copyOf(dependencies));
    }

    static List<Path> findClassesDirs(Path root) {
        return findClassesDirs(root, false);
    }

    private static List<Path> findClassesDirs(Path root, boolean refreshGradleClasspath) {
        BuildSystem buildSystem = BuildSystem.detect(root);
        if (buildSystem == BuildSystem.MAVEN) {
            MavenProjectDiscovery.Discovery discovery = MavenProjectDiscovery.discover(root);
            LinkedHashSet<Path> result = new LinkedHashSet<>();
            discovery.moduleDirectories().stream()
                    .map(module -> module.resolve("target/classes"))
                    .filter(ProjectInitializer::containsClassFiles)
                    .forEach(result::add);
            if (!discovery.complete()) {
                result.addAll(scanClassesDirs(root, path -> path.endsWith("target/classes")));
            }
            return List.copyOf(result);
        }

        GradleProjectDiscovery.Discovery discovery = refreshGradleClasspath
                ? GradleProjectDiscovery.discoverAndWriteClasspath(root)
                : GradleProjectDiscovery.discover(root);
        LinkedHashSet<Path> result = discovery.classesDirectories().stream()
                .filter(ProjectInitializer::containsClassFiles)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (!discovery.complete()) {
            result.addAll(scanClassesDirs(root, ProjectInitializer::isGradleMainClassesDir));
        }
        return List.copyOf(result);
    }

    private static List<Path> scanClassesDirs(
            Path root, java.util.function.Predicate<Path> outputDirectory) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(outputDirectory)
                    .filter(path -> !hasPathSegment(path, ".gradle"))
                    .filter(path -> !hasPathSegment(path, "buildSrc"))
                    .filter(ProjectInitializer::containsClassFiles)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean containsClassFiles(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (Stream<Path> classFiles = Files.walk(directory)) {
            return classFiles.anyMatch(file -> file.toString().endsWith(".class"));
        } catch (IOException e) {
            return false;
        }
    }

    private static List<Path> findSourceRoots(List<Path> moduleDirectories) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path module : moduleDirectories) {
            for (Path candidate : List.of(
                    module.resolve("src/main/java"),
                    module.resolve("src/main/kotlin"),
                    module.resolve("target/generated-sources/annotations"),
                    module.resolve("build/generated/sources/annotationProcessor/java/main"),
                    module.resolve("build/generated/ksp/main/kotlin"))) {
                if (Files.isDirectory(candidate)) roots.add(candidate.toAbsolutePath().normalize());
            }
        }
        return List.copyOf(roots);
    }

    private static boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) return true;
        }
        return false;
    }

    private static boolean isGradleMainClassesDir(Path path) {
        Path relative;
        try {
            relative = path.toAbsolutePath().normalize();
        } catch (Exception e) {
            return false;
        }
        for (int i = 0; i + 3 < relative.getNameCount(); i++) {
            if (relative.getName(i).toString().equals("build")
                    && relative.getName(i + 1).toString().equals("classes")
                    && relative.getName(i + 3).toString().equals("main")) {
                return i + 4 == relative.getNameCount();
            }
        }
        return false;
    }

    static String computeStateFingerprint(Path root, List<Path> classesDirs) {
        return computeStateFingerprint(
                root, classesDirs, ClassFileSnapshot.capture(classesDirs).fingerprint());
    }

    private static String computeStateFingerprint(
            Path root, List<Path> classesDirs, String classContentFingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BuildSystem buildSystem = BuildSystem.detect(root);
            Map<Path, Path> classDirectoryOwners =
                    DependencyIndexer.mapClassDirectoriesToModules(root, buildSystem, classesDirs);
            updateDigest(digest, "head", GitAnalyzer.resolveHead(root));
            updateDigest(digest, "build", DependencyIndexer.buildFingerprint(
                    root, buildSystem, classDirectoryOwners.values()));
            updateDigest(digest, "classFiles", classContentFingerprint);

            for (Path classesDir : classesDirs.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList()) {
                Path moduleDir = classDirectoryOwners.get(classesDir);
                if (moduleDir == null) {
                    updateDigest(digest, "classpathMissing", classesDir.toString());
                    continue;
                }
                Path classpathFile = buildSystem.classpathFile(moduleDir);
                if (Files.isRegularFile(classpathFile)) {
                    updateFileIdentity(digest, root, classpathFile);
                    for (Path jar : DependencyIndexer.parseClasspathFile(classpathFile).stream()
                            .map(path -> path.toAbsolutePath().normalize())
                            .sorted()
                            .toList()) {
                        updateFileIdentity(digest, root, jar);
                    }
                } else {
                    updateDigest(digest, "classpathMissing", moduleDir.toString());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static boolean headMatches(Path root, String expectedHead) {
        return java.util.Objects.equals(expectedHead, GitAnalyzer.resolveHead(root));
    }

    static boolean worktreeMatches(Path root, String expectedFingerprint) {
        return java.util.Objects.equals(expectedFingerprint,
                WorktreeInspector.inspect(root).fingerprint());
    }

    private static void updateFileIdentity(MessageDigest digest, Path base, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        String name;
        try {
            Path normalizedBase = base.toAbsolutePath().normalize();
            name = normalized.startsWith(normalizedBase)
                    ? normalizedBase.relativize(normalized).toString() : normalized.toString();
            updateDigest(digest, "file", name);
            updateDigest(digest, "size", Long.toString(Files.size(normalized)));
            updateDigest(digest, "mtime", Long.toString(Files.getLastModifiedTime(normalized).toMillis()));
        } catch (IOException e) {
            updateDigest(digest, "missing", normalized.toString());
        }
    }

    private static void updateDigest(MessageDigest digest, String key, String value) {
        digest.update(key.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '=');
        if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String headChangedMessage(String phase, String expected, String actual) {
        return "Git HEAD changed " + phase + " (expected " + displayCommit(expected)
                + ", found " + displayCommit(actual)
                + "); the stale index was not activated. Retry `quill update`.";
    }

    private static String displayCommit(String commit) {
        if (commit == null || commit.isBlank()) return "no commit";
        return commit.length() > 12 ? commit.substring(0, 12) : commit;
    }

    private static String describeWorktree(WorktreeInspector.Snapshot snapshot) {
        if (snapshot.changes().isEmpty()) {
            return "No dirty paths are currently visible (the changing file may have been restored).";
        }
        int limit = Math.min(10, snapshot.changes().size());
        String paths = snapshot.changes().stream()
                .limit(limit)
                .map(change -> change.status() + ":" + change.projectPath())
                .collect(java.util.stream.Collectors.joining(", "));
        int remaining = snapshot.changes().size() - limit;
        return "Current dirty paths: " + paths
                + (remaining > 0 ? " (and " + remaining + " more)." : ".");
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static final String QUILL_SECTION_MARKER = "## Quill — Codebase Intelligence (MCP)";

    private static final String QUILL_CLAUDE_MD = """

            ## Quill — Codebase Intelligence (MCP)

            This project is indexed by Quill. **Always prefer Quill MCP tools over grep/find/Explore agents** for:

            - **Searching classes:** `search_classes` — faster than grep, supports wildcard patterns (`*Service`, `*Strategy*`)
            - **Dependency analysis:** `get_dependencies` — what a class depends on and what depends on it
            - **Risk assessment:** `assess_change_risk` — class blast radius or file risk from criticality, churn, bus factor, and coupling
            - **Project overview:** `get_overview` — call first to orient (class/bean counts, architecture hubs, problems)
            - **Git hotspots:** `find_git_hotspots` — most frequently changed files/classes
            - **Co-change analysis:** `find_co_changed_files` — files that change together (hidden coupling)
            - **Beans:** `list_beans` — list/filter beans by scope, kind, qualifier (CDI and Spring)
            - **Injection points:** `list_injection_points` — injection resolution status for a bean
            - **External deps:** `list_external_dependencies` — third-party library usage

            **Tip:** Add `"alwaysLoad": true` to the quill server in `.mcp.json` so tool schemas are loaded eagerly (no ToolSearch needed).
            """;

    private static void ensureClaudeMd(Path root) {
        Path claudeMd = root.resolve("CLAUDE.md");
        try {
            if (Files.exists(claudeMd)) {
                String content = Files.readString(claudeMd);
                if (content.contains(QUILL_SECTION_MARKER)) return;
                String separator = content.endsWith("\n") ? "" : "\n";
                Files.writeString(claudeMd, content + separator + QUILL_CLAUDE_MD);
            } else {
                Files.writeString(claudeMd, QUILL_CLAUDE_MD.stripLeading());
            }
            System.err.println("[quill] Updated CLAUDE.md with Quill tool instructions.");
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not update CLAUDE.md: " + e.getMessage());
        }
    }

    private static void ensureGitignore(Path root) {
        Path gitignore = root.resolve(".gitignore");
        String entry = ".quill/";
        try {
            if (Files.exists(gitignore)) {
                String content = Files.readString(gitignore);
                if (content.contains(entry)) return;
                String separator = content.endsWith("\n") ? "" : "\n";
                Files.writeString(gitignore, content + separator + entry + "\n");
            } else {
                Files.writeString(gitignore, entry + "\n");
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
