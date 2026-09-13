package org.treblereel.mcp.command;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import org.treblereel.mcp.core.GitHookInstaller;
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
        COMPILATION_FAILED,
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

        Map<String, Long> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(phases));
        }
    }

    record CompilationResult(boolean successful, String message) {}

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
                return initializeLockedDetailed(
                        normalizedRoot, indexOnly, false, startedAtNanos, timings);
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
        return initializeLockedDetailed(root, indexOnly, false, System.nanoTime(),
                new PhaseTimings()).successful();
    }

    static boolean initializeLocked(Path root, boolean indexOnly, boolean compiledBeforeIndex) {
        return initializeLockedDetailed(root, indexOnly, compiledBeforeIndex,
                System.nanoTime(), new PhaseTimings()).successful();
    }

    static InitializationResult initializeLockedDetailed(
            Path root, boolean indexOnly, boolean compiledBeforeIndex) {
        long startedAtNanos = System.nanoTime();
        PhaseTimings timings = new PhaseTimings();
        try {
            return initializeLockedDetailed(
                    root, indexOnly, compiledBeforeIndex, startedAtNanos, timings)
                    .withPhaseMillis(timings.snapshot());
        } catch (RuntimeException e) {
            return InitializationResult.failure(FailureReason.INDEXING_FAILED,
                    "Unexpected indexing error for " + root + ": " + rootMessage(e),
                    startedAtNanos).withPhaseMillis(timings.snapshot());
        }
    }

    private static InitializationResult initializeLockedDetailed(
            Path root, boolean indexOnly, boolean compiledBeforeIndex, long startedAtNanos,
            PhaseTimings timings) {
        List<Path> classesDirs = findClassesDirs(root, true);
        timings.finish("class_discovery");

        if (classesDirs.isEmpty()) {
            CompilationResult compilation = compileProjectDetailed(root);
            timings.finish("compilation");
            if (!compilation.successful()) {
                return InitializationResult.failure(FailureReason.COMPILATION_FAILED,
                        compilation.message(), startedAtNanos);
            }
            compiledBeforeIndex = true;
            classesDirs = findClassesDirs(root, true);
            timings.finish("class_rediscovery");
            if (classesDirs.isEmpty()) {
                return InitializationResult.failure(FailureReason.NO_COMPILED_CLASSES,
                        "Build completed, but no main .class files were found under " + root
                                + ". Check that the project contains a Java/Kotlin JVM module and "
                                + "that its main compilation is enabled.",
                        startedAtNanos);
            }
        }

        // Compilation is a prerequisite, not part of indexing. Capture the authoritative
        // commit/worktree snapshot afterwards so generated tracked files do not make a
        // successful compile look like a concurrent mutation.
        // Quill's own managed-file changes must also happen before the snapshot; otherwise
        // the first init would invalidate itself when adding .quill/ to .gitignore.
        if (!indexOnly) {
            ensureGitignore(root);
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
        ClassFileSnapshot classFiles = ClassFileSnapshot.capture(classesDirs);
        JandexScanner.ScanResult scanResult = sourceRoots.isEmpty()
                ? JandexScanner.scan(classFiles, List.of())
                : JandexScanner.scan(classFiles, sourceRoots);

        boolean isSpring = SpringResolver.isSpringProject(scanResult.index());
        boolean isCdi = BeanResolver.isCdiProject(scanResult.index());
        timings.finish("application_index");

        if (isSpring && isCdi) {
            return InitializationResult.failure(FailureReason.MIXED_FRAMEWORKS,
                    "Mixed Spring/CDI project detected. Quill does not support both DI frameworks "
                            + "in one index; index their modules separately.",
                    startedAtNanos);
        }

        DependencyIndexer.DependencyIndexResult depResult =
                DependencyIndexer.buildDependencyIndex(root, classesDirs);
        timings.finish("dependency_index");
        if (depResult.status() != DependencyIndexer.Status.COMPLETE) {
            System.err.println("[quill] Warning: dependency index "
                    + depResult.status().name().toLowerCase() + " (" + depResult.detail() + ")");
        }

        BeanResolver.ResolutionResult resolution = isSpring
                ? SpringResolver.resolve(scanResult.index(), depResult.index())
                : BeanResolver.resolve(scanResult.index(), depResult.index());

        List<ClassRecord> classes = scanResult.classes();

        Map<String, Integer> classNameToSqliteId = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            classNameToSqliteId.put(classes.get(i).className(), i + 1);
        }

        Map<Integer, Integer> brToSqlite = new HashMap<>();
        for (var entry : resolution.classNameToId().entrySet()) {
            Integer sqliteId = classNameToSqliteId.get(entry.getKey());
            if (sqliteId != null) {
                brToSqlite.put(entry.getValue(), sqliteId);
            }
        }

        PersistedResolution persisted = remapForPersistence(resolution, brToSqlite);
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
        for (BytecodeDependencyScanner.StaticDependency dependency
                : BytecodeDependencyScanner.scan(classFiles, classNameToSqliteId.keySet())) {
            Integer from = classNameToSqliteId.get(dependency.fromClass());
            Integer to = classNameToSqliteId.get(dependency.toClass());
            if (from != null && to != null) {
                remappedDeps.add(new DependencyRecord(from, to, dependency.kind(), null,
                        dependency.occurrences()));
            }
        }

        List<ExternalDepRecord> externalDeps = JandexScanner.extractExternalDeps(scanResult.index(), classNameToSqliteId);
        timings.finish("bytecode_analysis");

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

        GitAnalyzer.GitAnalysisResult gitResult;
        if (GitAnalyzer.hasGitRepo(root)) {
            gitResult = GitAnalyzer.analyze(root, 500, sourceFileToClassId);
        } else {
            gitResult = GitAnalyzer.GitAnalysisResult.empty();
        }
        timings.finish("git_analysis");

        String lastCommit = gitResult.headHash();

        if (!headMatches(root, initialHead)
                || (initialHead != null && !initialHead.equals(lastCommit))) {
            return InitializationResult.failure(FailureReason.HEAD_CHANGED,
                    headChangedMessage("during indexing", initialHead,
                            GitAnalyzer.resolveHead(root)), startedAtNanos);
        }

        List<CdiProblem> problems = resolution.problems();
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

        String indexId = createIndexId(lastCommit);
        Path dbPath = resolveDbPath(root, indexId);
        Path stagedDb = dbPath.resolveSibling("." + dbPath.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("indexed_at", Instant.now().toString());
        metadata.put("index_id", indexId);
        metadata.put("project_root", root.toString());
        metadata.put("last_commit", lastCommit != null ? lastCommit : "unknown");
        metadata.put("indexed_worktree_fingerprint", initialWorktree.fingerprint());
        metadata.put("indexed_worktree_dirty", Boolean.toString(initialWorktree.dirty()));
        metadata.put("indexed_worktree_changed_files",
                Integer.toString(initialWorktree.changes().size()));
        metadata.put("indexed_structure_fingerprint", initialWorktree.structuralFingerprint());
        metadata.put("indexed_structural_changed_files",
                Integer.toString(initialWorktree.structuralChanges().size()));
        metadata.put("compiled_before_index", Boolean.toString(compiledBeforeIndex));
        metadata.put("structure_scope", "compiled_snapshot");
        metadata.put("framework", isSpring ? "Spring" : "CDI");
        metadata.put("dependency_index", depResult.status().name().toLowerCase());
        metadata.put("dependency_index_detail", depResult.detail());
        metadata.put("state_fingerprint",
                computeStateFingerprint(root, classesDirs, classFiles.fingerprint()));
        timings.finish("index_metadata");

        try {
            Jdbi jdbi = QuillDatabase.create(stagedDb);
            IndexWriter.writeAll(jdbi, classes, remappedBeans,
                    persisted.injectionPoints(), remappedDeps, metadata,
                    externalDeps, remappedProblems,
                    gitResult.fileStats(), gitResult.commits(), gitResult.commitFiles(),
                    inventory.files());
            boolean headChanged = !headMatches(root, initialHead);
            boolean worktreeChanged = !worktreeMatches(root, initialWorktree.fingerprint());
            if (headChanged || worktreeChanged) {
                timings.finish("database_write");
                deleteDatabaseArtifacts(stagedDb);
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
            atomicMove(stagedDb, dbPath);
            timings.finish("database_write");
        } catch (RuntimeException | IOException e) {
            timings.finish("database_write");
            deleteDatabaseArtifacts(stagedDb);
            return InitializationResult.failure(FailureReason.INDEX_PUBLICATION_FAILED,
                    "Could not publish SQLite index at " + dbPath + ": " + rootMessage(e)
                            + ". The previous index, if any, was left unchanged.",
                    startedAtNanos);
        }

        if (!headMatches(root, initialHead)) {
            timings.finish("publication");
            deleteDatabaseArtifacts(dbPath);
            return InitializationResult.failure(FailureReason.HEAD_CHANGED,
                    headChangedMessage("after index publication", initialHead,
                            GitAnalyzer.resolveHead(root))
                            + " The unpublished generation was removed; retry `quill update`.",
                    startedAtNanos);
        }

        try {
            updateRefs(root, lastCommit, indexId);
        } catch (RuntimeException e) {
            timings.finish("publication");
            deleteDatabaseArtifacts(dbPath);
            return InitializationResult.failure(FailureReason.INDEX_PUBLICATION_FAILED,
                    "Could not activate immutable index generation " + dbPath + ": "
                            + rootMessage(e)
                            + ". The previous index remains active.",
                    startedAtNanos);
        }

        if (!indexOnly && GitAnalyzer.hasGitRepo(root)) {
            GitHookInstaller.install(root, QuillLauncher.detect());
        }

        if (!indexOnly) {
            ensureClaudeMd(root);
        }
        ensureCodexConfig(root, indexOnly);
        timings.finish("publication");

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

    static List<Path> findClassesDirs(Path root) {
        return findClassesDirs(root, false);
    }

    private static List<Path> findClassesDirs(Path root, boolean refreshGradleClasspath) {
        try {
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
        } catch (IllegalArgumentException ignored) {
            // Tests and legacy layouts without a build marker use the generic scan.
        }
        return scanClassesDirs(root, ProjectInitializer::isMainClassesDir);
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
                    module.resolve("target/generated-sources/annotations"),
                    module.resolve("build/generated/sources/annotationProcessor/java/main"))) {
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

    private static boolean isMainClassesDir(Path path) {
        if (path.endsWith("target/classes")) return true;
        return isGradleMainClassesDir(path);
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

    static boolean compileProject(Path root) {
        return compileProjectDetailed(root).successful();
    }

    static CompilationResult compileProjectDetailed(Path root) {
        BuildSystem buildSystem;
        try {
            buildSystem = BuildSystem.detect(root);
        } catch (IllegalArgumentException e) {
            return new CompilationResult(false, rootMessage(e));
        }
        List<String> command = buildSystem.compileCommand(root);
        try {
            int exit = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor();
            if (exit != 0) {
                return new CompilationResult(false, buildSystem.name() + " compile command `"
                        + String.join(" ", command) + "` exited with code " + exit + " for " + root);
            }
            return new CompilationResult(true, buildSystem.name() + " compilation completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CompilationResult(false,
                    buildSystem.name() + " compile was interrupted for " + root);
        } catch (IOException e) {
            return new CompilationResult(false, "Failed to run " + buildSystem.name()
                    + " compile command `" + String.join(" ", command) + "`: " + rootMessage(e));
        }
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

    private static final int MAX_INDEXES = 5;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String createIndexId(String commitHash) {
        String snapshot = commitHash != null && !"unknown".equals(commitHash)
                ? commitHash : "nocommit";
        return snapshot + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static Path resolveDbPath(Path root, String indexId) {
        return root.resolve(".quill/" + indexId + ".db");
    }

    private static void updateRefs(Path root, String commitHash, String indexId) {
        Path refsPath = root.resolve(".quill/refs.json");
        Map<String, String> refs = readRefs(refsPath);
        if (commitHash != null && !"unknown".equals(commitHash)) {
            refs.put(headRef(commitHash), indexId);
            String branch = GitAnalyzer.resolveCurrentBranch(root);
            if (branch != null) {
                refs.put(branch, indexId);
            }
        } else {
            refs.put("@worktree", indexId);
        }
        // Publish the new pointer before pruning old generations. If cleanup cannot delete an
        // open file on Windows, the only consequence is a temporary extra cache entry.
        writeRefsOrThrow(refsPath, refs);
        cleanupLru(root, refs);
    }

    private static String headRef(String commitHash) {
        return "@head:" + commitHash;
    }

    static void cleanupLru(Path root, Map<String, String> refs) {
        Path quillDir = root.resolve(".quill");
        List<Path> databases;
        try (Stream<Path> files = Files.list(quillDir)) {
            databases = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".db"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing((Path path) -> {
                        try {
                            return Files.getLastModifiedTime(path);
                        } catch (IOException ex) {
                            return java.nio.file.attribute.FileTime.fromMillis(0);
                        }
                    }).reversed())
                    .toList();
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not inspect index cache: " + e.getMessage());
            return;
        }

        Set<String> keptHashes = new HashSet<>();
        for (int i = 0; i < databases.size(); i++) {
            Path database = databases.get(i);
            String fileName = database.getFileName().toString();
            String hash = fileName.substring(0, fileName.length() - ".db".length());
            if (i < MAX_INDEXES) {
                keptHashes.add(hash);
            } else {
                deleteDatabaseArtifacts(database);
            }
        }

        refs.entrySet().removeIf(entry -> !keptHashes.contains(entry.getValue()));
        writeRefs(root.resolve(".quill/refs.json"), refs);
    }

    public static Map<String, String> readRefs(Path refsPath) {
        if (!Files.exists(refsPath)) return new LinkedHashMap<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return MAPPER.readValue(refsPath.toFile(),
                        new TypeReference<LinkedHashMap<String, String>>() {});
            } catch (IOException e) {
                if (attempt == 2) return new LinkedHashMap<>();
                pauseForFileRelease();
            }
        }
        return new LinkedHashMap<>();
    }

    private static void writeRefs(Path refsPath, Map<String, String> refs) {
        try {
            writeRefsOrThrow(refsPath, refs);
        } catch (RuntimeException e) {
            System.err.println("[quill] Warning: could not write refs.json: " + rootMessage(e));
        }
    }

    private static void writeRefsOrThrow(Path refsPath, Map<String, String> refs) {
        Path temp = refsPath.resolveSibling("." + refsPath.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(refsPath.toAbsolutePath().normalize().getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), refs);
            atomicMoveWithRetry(temp, refsPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not write " + refsPath, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static void atomicMoveWithRetry(Path source, Path target) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                atomicMove(source, target);
                return;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt < 4) pauseForFileRelease();
            }
        }
        throw lastFailure;
    }

    private static void pauseForFileRelease() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteDatabaseArtifacts(Path dbPath) {
        for (Path path : List.of(dbPath, Path.of(dbPath + "-wal"), Path.of(dbPath + "-shm"),
                Path.of(dbPath + "-journal"))) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    public static Path findDbForHead(Path root) {
        String head = GitAnalyzer.resolveHead(root);
        Path refsPath = root.resolve(".quill/refs.json");
        Map<String, String> refs = readRefs(refsPath);
        if (head != null) {
            Path active = referencedDatabase(root, refs.get(headRef(head)), head);
            if (active != null) return active;

            String branch = GitAnalyzer.resolveCurrentBranch(root);
            Path branchDb = branch != null
                    ? referencedDatabase(root, refs.get(branch), head) : null;
            if (branchDb != null) return branchDb;

            // Compatibility with indexes created before immutable generations.
            Path exact = root.resolve(".quill/" + head + ".db");
            if (Files.exists(exact)) return exact;
            Path legacy = root.resolve(".quill/" + head.substring(0, 7) + ".db");
            if (Files.exists(legacy)) return legacy;
        } else {
            Path active = referencedDatabase(root, refs.get("@worktree"), null);
            if (active != null) return active;

            // Compatibility with indexes created before immutable generations.
            Path nocommit = root.resolve(".quill/nocommit.db");
            if (Files.exists(nocommit)) return nocommit;
        }

        return null;
    }

    private static Path referencedDatabase(
            Path root, String indexId, String expectedCommit) {
        if (indexId == null || indexId.isBlank()
                || indexId.contains("/") || indexId.contains("\\")) {
            return null;
        }
        if (expectedCommit != null
                && !indexId.equals(expectedCommit)
                && !indexId.startsWith(expectedCommit + "-")) {
            return null;
        }
        Path database = root.resolve(".quill").resolve(indexId + ".db").normalize();
        Path quillDir = root.resolve(".quill").toAbsolutePath().normalize();
        database = database.toAbsolutePath().normalize();
        return database.startsWith(quillDir) && Files.isRegularFile(database)
                ? database : null;
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
