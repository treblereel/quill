package org.treblereel.mcp.command;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.jdbi.v3.core.Jdbi;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.treblereel.mcp.core.ApplicationContextResolver;
import org.treblereel.mcp.core.BeanResolver;
import org.treblereel.mcp.core.BuildSystem;
import org.treblereel.mcp.core.BytecodeDependencyScanner;
import org.treblereel.mcp.core.ClassFileSnapshot;
import org.treblereel.mcp.core.ClassOccurrenceScanner;
import org.treblereel.mcp.core.ConfigurationScanner;
import org.treblereel.mcp.core.DependencyIndexer;
import org.treblereel.mcp.core.FileInventory;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.JandexScanner;
import org.treblereel.mcp.core.ModuleClasspathResolver;
import org.treblereel.mcp.core.ServiceProviderScanner;
import org.treblereel.mcp.core.SpringResolver;
import org.treblereel.mcp.core.WorktreeInspector;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.CdiProblem;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ClassOccurrenceRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import org.treblereel.mcp.model.FieldAccessRecord;
import org.treblereel.mcp.model.FrameworkEndpointRecord;
import org.treblereel.mcp.model.InjectionPointRecord;
import org.treblereel.mcp.model.ModuleClasspathRecord;
import org.treblereel.mcp.model.MethodCallRecord;

public class ProjectInitializer {
    private static final int MAX_GIT_COMMITS = 5_000;

    private static final ObjectMapper JSON = new ObjectMapper();

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
        ProjectLayout.ClassesDiscovery classesDiscovery =
                ProjectLayout.discoverClassesDirs(root, true);
        List<Path> classesDirs = classesDiscovery.classesDirectories();
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
        ProjectConfiguration.prepareForIndex(root, indexOnly);
        String initialHead = GitAnalyzer.resolveHead(root);
        WorktreeInspector.Snapshot initialWorktree = WorktreeInspector.inspect(root);
        timings.finish("worktree_snapshot");

        System.err.println("[quill] Indexing " + root.getFileName() + " (" + classesDirs.size() + " class dirs)...");
        BuildSystem buildSystem = BuildSystem.detect(root);
        Map<Path, Path> classDirectoryOwners =
                DependencyIndexer.mapClassDirectoriesToModules(root, buildSystem, classesDirs);
        List<Path> moduleDirectories = classDirectoryOwners.values().stream().distinct().toList();
        List<ModuleClasspathRecord> moduleClasspath = ModuleClasspathResolver.resolve(
                root, buildSystem, moduleDirectories);
        List<Path> sourceRoots = ProjectLayout.findSourceRoots(moduleDirectories);
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
        IndexView annotationLookup = depResult.index() == null
                ? scanResult.index()
                : CompositeIndex.create(scanResult.index(), depResult.index());
        List<ClassAnnotationRecord> classAnnotations =
                JandexScanner.extractClassAnnotations(
                        scanResult.index(), annotationLookup, classNameToSqliteId);
        List<ClassMemberRecord> classMembers =
                JandexScanner.extractClassMembers(scanResult.index(), classNameToSqliteId);
        List<FrameworkEndpointRecord> frameworkEndpoints =
                JandexScanner.extractFrameworkEndpoints(
                        scanResult.index(), classNameToSqliteId);
        List<ClassOccurrenceRecord> classOccurrences = ClassOccurrenceScanner.scan(
                root, classFiles, classDirectoryOwners, classNameToSqliteId);

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
        int serviceDescriptorCount;
        int serviceRegistrationCount;
        List<ServiceProviderScanner.Registration> serviceRegistrations;
        List<ExternalDepRecord> bytecodeServiceExternalDeps = new ArrayList<>();
        List<MethodCallRecord> methodCalls = new ArrayList<>();
        List<FieldAccessRecord> fieldAccesses = new ArrayList<>();
        GitAnalyzer.GitAnalysisResult gitResult;
        try (BackgroundTask<GitAnalyzer.GitAnalysisResult> gitTask =
                BackgroundTask.start("quill-git-analysis", () -> GitAnalyzer.hasGitRepo(root)
                        ? GitAnalyzer.analyze(root, MAX_GIT_COMMITS, sourceFileToClassId)
                        : GitAnalyzer.GitAnalysisResult.empty())) {
            BytecodeDependencyScanner.ScanResult bytecode = BytecodeDependencyScanner.analyze(
                    classFiles, classNameToSqliteId.keySet());
            for (BytecodeDependencyScanner.StaticDependency dependency
                    : bytecode.dependencies()) {
                Integer from = classNameToSqliteId.get(dependency.fromClass());
                Integer to = classNameToSqliteId.get(dependency.toClass());
                if (from != null && to != null) {
                    remappedDeps.add(new DependencyRecord(from, to, dependency.kind(), null,
                            dependency.occurrences(), dependency.evidenceLines()));
                } else if (from != null && dependency.kind().startsWith("SERVICE_")) {
                    bytecodeServiceExternalDeps.add(new ExternalDepRecord(
                            from, dependency.toClass(), dependency.kind()));
                }
            }
            for (BytecodeDependencyScanner.StaticMethodCall call : bytecode.methodCalls()) {
                Integer from = classNameToSqliteId.get(call.fromClass());
                Integer to = classNameToSqliteId.get(call.toClass());
                if (from != null && to != null) {
                    methodCalls.add(new MethodCallRecord(from, call.fromMethod(),
                            call.fromDescriptor(), to, call.toMethod(), call.toDescriptor(),
                            call.invocationKind(), call.occurrences(), call.evidenceLines()));
                }
            }
            for (BytecodeDependencyScanner.StaticFieldAccess access : bytecode.fieldAccesses()) {
                Integer from = classNameToSqliteId.get(access.fromClass());
                Integer to = classNameToSqliteId.get(access.toClass());
                if (from != null && to != null) {
                    fieldAccesses.add(new FieldAccessRecord(from, access.fromMethod(),
                            access.fromDescriptor(), to, access.fieldName(),
                            access.fieldDescriptor(), access.accessKind(), access.occurrences(),
                            access.evidenceLines()));
                }
            }
            timings.finish("bytecode_analysis");

            ServiceProviderScanner.Result services =
                    ServiceProviderScanner.scan(root, moduleDirectories);
            serviceDescriptorCount = services.descriptorCount();
            serviceRegistrationCount = services.registrations().size();
            serviceRegistrations = services.registrations();
            ServiceProviderScanner.ResolvedDependencies serviceDependencies =
                    ServiceProviderScanner.resolve(services, classNameToSqliteId);
            remappedDeps.addAll(serviceDependencies.internal());
            timings.finish("service_analysis");

            externalDeps = new ArrayList<>(JandexScanner.extractExternalDeps(
                    scanResult.index(), classNameToSqliteId));
            externalDeps.addAll(bytecodeServiceExternalDeps);
            externalDeps.addAll(serviceDependencies.external());
            timings.finish("external_dependency_analysis");

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
        ConfigurationScanner.Result configuration = ConfigurationScanner.scan(
                root, moduleDirectories, scanResult.index(), classNameToSqliteId, classes);
        List<InjectionPointRecord> contextualInjectionPoints = ApplicationContextResolver.refine(
                persisted.injectionPoints(), remappedBeans, classes, classOccurrences,
                moduleClasspath);
        Map<Integer, BeanRecord> beansById = new HashMap<>();
        remappedBeans.forEach(bean -> beansById.put(bean.id(), bean));
        remappedDeps.removeIf(dependency -> dependency.injectionPointId() != null);
        for (InjectionPointRecord point : contextualInjectionPoints) {
            BeanRecord owner = beansById.get(point.beanId());
            BeanRecord target = point.resolvedBeanId() == null
                    ? null : beansById.get(point.resolvedBeanId());
            if (owner != null && target != null) {
                remappedDeps.add(new DependencyRecord(owner.classId(), target.classId(),
                        InjectionPointRecord.STATIC_SPRING.equals(point.resolutionStrategy())
                                ? "SPRING_INJECT" : "CDI_INJECT",
                        point.id()));
            }
        }
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
        metadata.put("git_scanned_commits", Integer.toString(gitResult.scannedCommits()));
        metadata.put("git_repository_commits", Integer.toString(gitResult.repositoryCommits()));
        metadata.put("git_history_complete", Boolean.toString(
                gitResult.scannedCommits() == gitResult.repositoryCommits()));
        metadata.put("indexed_worktree_dirty", Boolean.toString(initialWorktree.dirty()));
        metadata.put("indexed_worktree_changed_files",
                Integer.toString(initialWorktree.changes().size()));
        metadata.put("indexed_structure_fingerprint", initialWorktree.structuralFingerprint());
        metadata.put("indexed_structural_changed_files",
                Integer.toString(initialWorktree.structuralChanges().size()));
        metadata.put("compiled_before_index", Boolean.toString(compiledBeforeIndex));
        metadata.put("structure_scope", "compiled_snapshot");
        metadata.put("module_discovery_scope", classesDiscovery.moduleScope());
        metadata.put("module_discovery_complete",
                Boolean.toString(classesDiscovery.complete()));
        metadata.put("application_index_cache_hits",
                Integer.toString(scanResult.cacheHits()));
        metadata.put("application_index_cache_shards",
                Integer.toString(scanResult.cacheShards()));
        metadata.put("class_occurrences", Integer.toString(classOccurrences.size()));
        metadata.put("module_contexts", Integer.toString(moduleDirectories.size()));
        metadata.put("module_classpath_entries", Integer.toString(moduleClasspath.size()));
        metadata.put("service_descriptors", Integer.toString(serviceDescriptorCount));
        metadata.put("service_registrations", Integer.toString(serviceRegistrationCount));
        metadata.put("method_calls", Integer.toString(methodCalls.size()));
        metadata.put("field_accesses", Integer.toString(fieldAccesses.size()));
        metadata.put("framework_endpoints", Integer.toString(frameworkEndpoints.size()));
        metadata.put("configuration_definitions",
                Integer.toString(configuration.definitions().size()));
        metadata.put("configuration_usages", Integer.toString(configuration.usages().size()));
        metadata.put("configuration_references_detail", configurationJson(configuration));
        metadata.put("framework_endpoints_detail", frameworkEndpointsJson(frameworkEndpoints));
        metadata.put("service_registrations_detail", serviceRegistrationsJson(serviceRegistrations));
        metadata.put("framework", isSpring && isCdi ? "Mixed"
                : isSpring ? "Spring" : isCdi ? "CDI" : "Plain");
        metadata.put("dependency_index", depResult.status().name().toLowerCase());
        metadata.put("dependency_index_detail", depResult.detail());
        metadata.put("database_write_mode", incrementalBase == null ? "fresh" : "incremental");
        metadata.put("state_fingerprint",
                ProjectLayout.computeStateFingerprint(root, classesDirs, classFiles.fingerprint()));
        timings.finish("index_metadata");

        long databaseStartedAt = System.nanoTime();
        try {
            if (incrementalBase == null) {
                long schemaStartedAt = System.nanoTime();
                Jdbi jdbi = QuillDatabase.createForBulkLoad(stagedDb);
                timings.record("database_schema", elapsedMillis(schemaStartedAt));
                IndexWriter.WriteTimings writeTimings = IndexWriter.writeFresh(
                        jdbi, classes, remappedBeans,
                        contextualInjectionPoints, remappedDeps, metadata,
                        externalDeps, remappedProblems,
                        gitResult.fileStats(), gitResult.commits(), gitResult.commitFiles(),
                        inventory.files(), classOccurrences, classAnnotations, classMembers,
                        methodCalls, fieldAccesses);
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
                                contextualInjectionPoints, remappedDeps, metadata,
                                externalDeps, remappedProblems,
                                gitResult.fileStats(), gitResult.commits(),
                                gitResult.commitFiles(), inventory.files(), classOccurrences,
                                classAnnotations, classMembers, methodCalls, fieldAccesses);
                timings.record("database_delta", writeTimings.deltaMillis());
                timings.record("database_transaction_overhead",
                        writeTimings.transactionOverheadMillis());
                System.err.println("[quill] SQLite delta: " + writeTimings.rowsInserted()
                        + " inserted, " + writeTimings.rowsDeleted() + " deleted, "
                        + writeTimings.rowsUnchanged() + " unchanged.");
            }
            IndexWriter.writeModuleClasspath(QuillDatabase.openWritable(stagedDb), moduleClasspath);
            long validationStartedAt = System.nanoTime();
            ProjectIndexStore.validateForPublication(stagedDb);
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

        ProjectConfiguration.finishInitialization(root, indexOnly);
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

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize index metadata", error);
        }
    }

    static String serviceRegistrationsJson(
            List<ServiceProviderScanner.Registration> registrations) {
        List<Map<String, Object>> rows = registrations.stream()
                .map(registration -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("serviceType", registration.serviceType());
                    row.put("providerType", registration.providerType());
                    row.put("descriptorPath", registration.descriptorPath());
                    row.put("line", registration.line());
                    return row;
                })
                .toList();
        return toJson(rows);
    }

    static String frameworkEndpointsJson(List<FrameworkEndpointRecord> endpoints) {
        List<Map<String, Object>> rows = endpoints.stream().map(endpoint -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("classId", endpoint.classId());
            row.put("className", endpoint.className());
            row.put("methodName", endpoint.methodName());
            row.put("signature", endpoint.signature());
            row.put("descriptor", endpoint.descriptor());
            row.put("framework", endpoint.framework());
            row.put("httpMethods", endpoint.httpMethods());
            row.put("classPaths", endpoint.classPaths());
            row.put("methodPaths", endpoint.methodPaths());
            row.put("annotations", endpoint.annotations());
            return row;
        }).toList();
        return toJson(rows);
    }

    static String configurationJson(ConfigurationScanner.Result configuration) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("definitions", configuration.definitions().stream().map(definition -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", definition.key());
            row.put("kind", definition.kind());
            row.put("file", definition.file());
            row.put("line", definition.line());
            row.put("module", definition.module());
            row.put("sourceSet", definition.sourceSet());
            return row;
        }).toList());
        result.put("usages", configuration.usages().stream().map(usage -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", usage.key());
            row.put("kind", usage.kind());
            row.put("classId", usage.classId());
            row.put("className", usage.className());
            if (usage.member() != null) row.put("member", usage.member());
            if (usage.parameterIndex() != null) {
                row.put("parameterIndex", usage.parameterIndex());
            }
            row.put("annotation", usage.annotation());
            if (usage.source() != null) row.put("source", usage.source());
            if (usage.module() != null) row.put("module", usage.module());
            if (usage.sourceSet() != null) row.put("sourceSet", usage.sourceSet());
            return row;
        }).toList());
        return toJson(result);
    }

    static CodexConfigInstaller.Result ensureCodexConfig(Path root, boolean indexOnly) {
        return ProjectConfiguration.ensureCodexConfig(root, indexOnly);
    }

    static PersistedResolution remapForPersistence(
            BeanResolver.ResolutionResult resolution, Map<Integer, Integer> classIds) {
        return ResolutionPersistenceMapper.remap(resolution, classIds);
    }

    static PersistedResolution mergePersistedResolutions(
            List<PersistedResolution> parts, boolean mixedFrameworks) {
        return ResolutionPersistenceMapper.merge(parts, mixedFrameworks);
    }

    static List<Path> findClassesDirs(Path root) {
        return ProjectLayout.findClassesDirs(root, false);
    }

    static String computeStateFingerprint(Path root, List<Path> classesDirs) {
        return ProjectLayout.computeStateFingerprint(root, classesDirs);
    }

    static boolean headMatches(Path root, String expectedHead) {
        return java.util.Objects.equals(expectedHead, GitAnalyzer.resolveHead(root));
    }

    static boolean worktreeMatches(Path root, String expectedFingerprint) {
        return java.util.Objects.equals(expectedFingerprint,
                WorktreeInspector.inspect(root).fingerprint());
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

}
