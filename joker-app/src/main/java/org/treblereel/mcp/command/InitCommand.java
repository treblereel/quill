package org.treblereel.mcp.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.treblereel.mcp.core.BeanResolver;
import org.treblereel.mcp.core.GitAnalyzer;
import org.treblereel.mcp.core.GitHookInstaller;
import org.treblereel.mcp.core.JandexScanner;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.JokerDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", description = "Index a Quarkus project's CDI dependency graph")
public class InitCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

    @Option(names = "--no-hooks", description = "Skip git hook installation")
    boolean noHooks;

    @Override
    public void run() {
        Path root = resolveRoot();

        List<Path> classesDirs = findClassesDirs(root);

        if (classesDirs.isEmpty()) {
            System.out.println("No compiled classes found. Running Maven compile...");
            compile(root);
            classesDirs = findClassesDirs(root);
            if (classesDirs.isEmpty()) {
                System.err.println("Compilation failed — no target/classes directories under " + root);
                System.exit(2);
            }
        }

        System.out.println("Found " + classesDirs.size() + " class directory(ies):");
        classesDirs.forEach(d -> System.out.println("  " + d));
        JandexScanner.ScanResult scanResult = JandexScanner.scan(classesDirs);
        System.out.println("Found " + scanResult.classes().size() + " classes.");

        System.out.println("Resolving CDI dependencies...");
        BeanResolver.ResolutionResult resolution = BeanResolver.resolve(scanResult.index());
        System.out.println("Found " + resolution.beans().size() + " beans, "
                + resolution.injectionPoints().size() + " injection points.");

        var classes = scanResult.classes();

        // Build className -> SQLite ID mapping.
        // IndexWriter inserts classes in list order; SQLite AUTOINCREMENT assigns 1, 2, 3, ...
        Map<String, Integer> classNameToSqliteId = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            classNameToSqliteId.put(classes.get(i).className(), i + 1);
        }

        // Build BeanResolver-classId -> SQLite-classId mapping
        Map<Integer, Integer> brToSqlite = new HashMap<>();
        for (var entry : resolution.classNameToId().entrySet()) {
            Integer sqliteId = classNameToSqliteId.get(entry.getKey());
            if (sqliteId != null) {
                brToSqlite.put(entry.getValue(), sqliteId);
            }
        }

        // Remap bean classIds and declaringClassIds
        List<BeanRecord> remappedBeans = resolution.beans().stream()
                .map(b -> new BeanRecord(
                        b.id(),
                        brToSqlite.getOrDefault(b.classId(), b.classId()),
                        b.kind(), b.scope(), b.qualifiers(), b.stereotypes(),
                        b.isAlternative(), b.priority(), b.profiles(),
                        b.declaringClassId() != null
                                ? brToSqlite.getOrDefault(b.declaringClassId(), b.declaringClassId())
                                : null,
                        b.memberName(), b.beanTypes()))
                .toList();

        // Remap dependency classIds
        List<DependencyRecord> remappedDeps = resolution.dependencies().stream()
                .map(d -> new DependencyRecord(
                        brToSqlite.getOrDefault(d.fromClassId(), d.fromClassId()),
                        brToSqlite.getOrDefault(d.toClassId(), d.toClassId()),
                        d.kind(), d.injectionPointId()))
                .toList();

        System.out.println("Extracting external dependencies...");
        List<ExternalDepRecord> externalDeps = JandexScanner.extractExternalDeps(scanResult.index(), classNameToSqliteId);
        System.out.println("Found " + externalDeps.size() + " external type references.");

        ensureGitignore(root);

        Map<String, Integer> sourceFileToClassId = new HashMap<>();
        for (int i = 0; i < classes.size(); i++) {
            String sf = classes.get(i).sourceFile();
            if (sf != null) sourceFileToClassId.put(sf, i + 1);
        }

        GitAnalyzer.GitAnalysisResult gitResult;
        if (GitAnalyzer.hasGitRepo(root)) {
            System.out.println("Analyzing git history...");
            gitResult = GitAnalyzer.analyze(root, 500, sourceFileToClassId);
            System.out.println("Processed " + gitResult.commits().size() + " commits, "
                    + gitResult.fileStats().size() + " files with history.");
        } else {
            System.out.println("No git repository found. Git intelligence will be unavailable.");
            System.out.println("Consider initializing git: git init && git add -A && git commit -m 'initial'");
            gitResult = GitAnalyzer.GitAnalysisResult.empty();
        }

        String lastCommit = gitResult.headShortHash();

        Path dbPath = root.resolve(".joker/index.db");
        System.out.println("Writing index to " + dbPath + " ...");
        try {
            Connection conn = JokerDatabase.create(dbPath);
            try {
                IndexWriter.write(conn, classes, remappedBeans,
                        resolution.injectionPoints(), remappedDeps,
                        Map.of(
                                "indexed_at", Instant.now().toString(),
                                "project_root", root.toString(),
                                "last_commit", lastCommit != null ? lastCommit : "unknown"
                        ));
                if (!externalDeps.isEmpty()) {
                    IndexWriter.writeExternalDeps(conn, externalDeps);
                }
                if (!gitResult.isEmpty()) {
                    IndexWriter.writeGitData(conn, gitResult.fileStats(),
                            gitResult.commits(), gitResult.commitFiles());
                }
            } finally {
                conn.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to write index to " + dbPath, e);
        }

        if (!noHooks && GitAnalyzer.hasGitRepo(root)) {
            GitHookInstaller.install(root);
            System.out.println("Installed git hooks (post-commit, post-merge) for auto-update.");
        }

        System.out.println("Done. Indexed " + classes.size() + " classes, "
                + resolution.beans().size() + " beans"
                + (gitResult.isEmpty() ? "." : ", " + gitResult.commits().size() + " git commits."));
    }

    private Path resolveRoot() {
        if (projectPath != null) {
            Path p = projectPath.toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
        }
        return ProjectRootFinder.find(projectPath);
    }

    private List<Path> findClassesDirs(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isDirectory)
                    .filter(p -> p.endsWith("target/classes"))
                    .filter(p -> {
                        try (Stream<Path> classFiles = Files.walk(p)) {
                            return classFiles.anyMatch(f -> f.toString().endsWith(".class"));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk project tree: " + root, e);
        }
    }

    private void compile(Path root) {
        Path mvnw = root.resolve("mvnw");
        String cmd = Files.isExecutable(mvnw) ? mvnw.toString() : "mvn";
        try {
            int exit = new ProcessBuilder(cmd, "compile", "-q")
                    .directory(root.toFile())
                    .inheritIO()
                    .start()
                    .waitFor();
            if (exit != 0) {
                System.err.println("Maven compile exited with code " + exit);
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Failed to run Maven compile: " + e.getMessage());
        }
    }

    private void ensureGitignore(Path root) {
        Path gitignore = root.resolve(".gitignore");
        String entry = ".joker/";
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
            System.err.println("Warning: could not update .gitignore: " + e.getMessage());
        }
    }

}
