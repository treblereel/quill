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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
import org.treblereel.mcp.core.DependencyIndexer;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.GitHookInstaller;
import org.treblereel.mcp.core.JandexScanner;
import org.treblereel.mcp.core.SpringResolver;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.CdiProblem;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

public class ProjectInitializer {

    record PersistedResolution(
            List<BeanRecord> beans,
            List<InjectionPointRecord> injectionPoints,
            List<DependencyRecord> dependencies) {}

    public static boolean initialize(Path root) {
        return initialize(root, false);
    }

    public static boolean initialize(Path root, boolean indexOnly) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        try {
            return ProjectIndexLock.withLock(
                    normalizedRoot, () -> initializeLocked(normalizedRoot, indexOnly));
        } catch (IOException e) {
            System.err.println("[quill] Could not lock index for " + normalizedRoot + ": " + e.getMessage());
            return false;
        }
    }

    static boolean initializeLocked(Path root, boolean indexOnly) {
        String initialHead = GitAnalyzer.resolveHead(root);
        List<Path> classesDirs = findClassesDirs(root);

        if (classesDirs.isEmpty()) {
            if (!compileProject(root)) return false;
            classesDirs = findClassesDirs(root);
            if (classesDirs.isEmpty()) {
                System.err.println("[quill] No compiled classes found for " + root);
                return false;
            }
        }

        System.err.println("[quill] Indexing " + root.getFileName() + " (" + classesDirs.size() + " class dirs)...");
        JandexScanner.ScanResult scanResult = JandexScanner.scan(classesDirs);

        boolean isSpring = SpringResolver.isSpringProject(scanResult.index());
        boolean isCdi = BeanResolver.isCdiProject(scanResult.index());

        if (isSpring && isCdi) {
            System.err.println("[quill] Mixed Spring/CDI project detected. "
                    + "Quill does not support projects that use both Spring DI and CDI in the same index. "
                    + "Index each framework's modules separately.");
            return false;
        }

        DependencyIndexer.DependencyIndexResult depResult =
                DependencyIndexer.buildDependencyIndex(root, classesDirs);
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

        List<DependencyRecord> remappedDeps = persisted.dependencies();

        List<ExternalDepRecord> externalDeps = JandexScanner.extractExternalDeps(scanResult.index(), classNameToSqliteId);

        if (!indexOnly) {
            ensureGitignore(root);
        }

        Map<String, Integer> sourceFileToClassId = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            String sf = classes.get(i).sourceFile();
            if (sf != null) {
                Path sfPath = Path.of(sf);
                if (sfPath.isAbsolute() && sfPath.startsWith(root)) {
                    sf = root.relativize(sfPath).toString();
                }
                sourceFileToClassId.put(sf, i + 1);
            }
        }

        GitAnalyzer.GitAnalysisResult gitResult;
        if (GitAnalyzer.hasGitRepo(root)) {
            gitResult = GitAnalyzer.analyze(root, 500, sourceFileToClassId);
        } else {
            gitResult = GitAnalyzer.GitAnalysisResult.empty();
        }

        String lastCommit = gitResult.headHash();

        if (!headMatches(root, initialHead)
                || (initialHead != null && !initialHead.equals(lastCommit))) {
            System.err.println("[quill] Git HEAD changed during indexing; discarding the stale result.");
            return false;
        }

        List<CdiProblem> problems = resolution.problems();
        List<CdiProblem> remappedProblems = problems.stream()
                .map(p -> new CdiProblem(p.id(),
                        classNameToSqliteId.get(p.className()),
                        p.className(), p.problemType(), p.message()))
                .toList();

        Path dbPath = resolveDbPath(root, lastCommit);
        Path stagedDb = dbPath.resolveSibling("." + dbPath.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        Jdbi jdbi = QuillDatabase.create(stagedDb);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("indexed_at", Instant.now().toString());
        metadata.put("project_root", root.toString());
        metadata.put("last_commit", lastCommit != null ? lastCommit : "unknown");
        metadata.put("framework", isSpring ? "Spring" : "CDI");
        metadata.put("dependency_index", depResult.status().name().toLowerCase());
        metadata.put("dependency_index_detail", depResult.detail());
        metadata.put("state_fingerprint", computeStateFingerprint(root, classesDirs));

        try {
            IndexWriter.writeAll(jdbi, classes, remappedBeans,
                    persisted.injectionPoints(), remappedDeps, metadata,
                    externalDeps, remappedProblems,
                    gitResult.fileStats(), gitResult.commits(), gitResult.commitFiles());
            if (!headMatches(root, initialHead)) {
                deleteDatabaseArtifacts(stagedDb);
                System.err.println("[quill] Git HEAD changed before index publication; retry update.");
                return false;
            }
            atomicMove(stagedDb, dbPath);
        } catch (RuntimeException | IOException e) {
            deleteDatabaseArtifacts(stagedDb);
            throw new RuntimeException("Failed to publish index at " + dbPath, e);
        }

        if (!headMatches(root, initialHead)) {
            System.err.println("[quill] Git HEAD changed after index publication; refs were not updated.");
            return false;
        }

        if (initialHead != null) {
            updateRefs(root, lastCommit);
        }

        if (!indexOnly && GitAnalyzer.hasGitRepo(root)) {
            GitHookInstaller.install(root, QuillLauncher.detect());
        }

        if (!indexOnly) {
            ensureClaudeMd(root);
        }

        String depStatus = depResult.status() == DependencyIndexer.Status.COMPLETE
                ? "" : " [deps: " + depResult.status().name().toLowerCase() + "]";
        System.err.println("[quill] Done: " + classes.size() + " classes, "
                + remappedBeans.size() + " beans"
                + (gitResult.isEmpty() ? "" : ", " + gitResult.commits().size() + " git commits")
                + depStatus + ".");
        return true;
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
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(ProjectInitializer::isMainClassesDir)
                    .filter(path -> !hasPathSegment(path, ".gradle"))
                    .filter(path -> !hasPathSegment(path, "buildSrc"))
                    .filter(p -> {
                        try (Stream<Path> classFiles = Files.walk(p)) {
                            return classFiles.anyMatch(f -> f.toString().endsWith(".class"));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean hasPathSegment(Path path, String segment) {
        for (Path part : path) {
            if (part.toString().equals(segment)) return true;
        }
        return false;
    }

    private static boolean isMainClassesDir(Path path) {
        if (path.endsWith("target/classes")) return true;
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            BuildSystem buildSystem = BuildSystem.detect(root);
            updateDigest(digest, "head", GitAnalyzer.resolveHead(root));
            updateDigest(digest, "build", DependencyIndexer.buildFingerprint(root));

            for (Path classesDir : classesDirs.stream()
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList()) {
                updateDigest(digest, "classesDir", classesDir.toString());
                try (Stream<Path> files = Files.walk(classesDir)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".class"))
                            .sorted()
                            .toList()) {
                        updateFileContent(digest, classesDir, file);
                    }
                } catch (IOException e) {
                    updateDigest(digest, "classesError", e.getClass().getName());
                }

                Path moduleDir = buildSystem.moduleDir(classesDir);
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

    private static void updateFileContent(MessageDigest digest, Path base, Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            Path normalizedBase = base.toAbsolutePath().normalize();
            String name = normalized.startsWith(normalizedBase)
                    ? normalizedBase.relativize(normalized).toString() : normalized.toString();
            updateDigest(digest, "file", name);
            try (var input = Files.newInputStream(normalized)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0);
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
        BuildSystem buildSystem = BuildSystem.detect(root);
        List<String> command = buildSystem.compileCommand(root);
        try {
            int exit = new ProcessBuilder(command)
                    .directory(root.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start()
                    .waitFor();
            if (exit != 0) {
                System.err.println("[quill] " + buildSystem.name() + " compile failed for " + root);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[quill] " + buildSystem.name() + " compile was interrupted for " + root);
            return false;
        } catch (IOException e) {
            System.err.println("[quill] Failed to run " + buildSystem.name() + " compile: " + e.getMessage());
            return false;
        }
    }

    private static final String QUILL_SECTION_MARKER = "## Quill — Codebase Intelligence (MCP)";

    private static final String QUILL_CLAUDE_MD = """

            ## Quill — Codebase Intelligence (MCP)

            This project is indexed by Quill. **Always prefer Quill MCP tools over grep/find/Explore agents** for:

            - **Searching classes:** `search_classes` — faster than grep, supports wildcard patterns (`*Service`, `*Strategy*`)
            - **Dependency analysis:** `get_dependencies` — what a class depends on and what depends on it
            - **Risk assessment:** `assess_change_risk` — fan-in/out, git churn, bus factor, coupling → risk score
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

    private static Path resolveDbPath(Path root, String commitHash) {
        String name = (commitHash != null && !"unknown".equals(commitHash))
                ? commitHash : "nocommit";
        return root.resolve(".quill/" + name + ".db");
    }

    private static void updateRefs(Path root, String commitHash) {
        Path refsPath = root.resolve(".quill/refs.json");
        Map<String, String> refs = readRefs(refsPath);
        if (commitHash != null && !"unknown".equals(commitHash)) {
            String branch = GitAnalyzer.resolveCurrentBranch(root);
            if (branch != null) {
                refs.put(branch, commitHash);
            }
        }
        cleanupLru(root, refs);
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
        try {
            return MAPPER.readValue(refsPath.toFile(),
                    new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private static void writeRefs(Path refsPath, Map<String, String> refs) {
        Path temp = refsPath.resolveSibling("." + refsPath.getFileName() + "."
                + UUID.randomUUID() + ".tmp");
        try {
            Files.createDirectories(refsPath.toAbsolutePath().normalize().getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), refs);
            atomicMove(temp, refsPath);
        } catch (IOException e) {
            System.err.println("[quill] Warning: could not write refs.json: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
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
        if (head != null) {
            Path exact = root.resolve(".quill/" + head + ".db");
            if (Files.exists(exact)) return exact;
            Path legacy = root.resolve(".quill/" + head.substring(0, 7) + ".db");
            if (Files.exists(legacy)) return legacy;
        }

        Path refsPath = root.resolve(".quill/refs.json");
        Map<String, String> refs = readRefs(refsPath);
        String branch = GitAnalyzer.resolveCurrentBranch(root);
        if (branch != null && refs.containsKey(branch)) {
            Path branchDb = root.resolve(".quill/" + refs.get(branch) + ".db");
            if (Files.exists(branchDb)) return branchDb;
        }

        if (head == null) {
            Path nocommit = root.resolve(".quill/nocommit.db");
            if (Files.exists(nocommit)) return nocommit;
        }

        return null;
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
