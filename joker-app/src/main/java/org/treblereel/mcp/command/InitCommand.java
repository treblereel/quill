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
import org.treblereel.mcp.core.JandexScanner;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.JokerDatabase;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.DependencyRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", description = "Index a Quarkus project's CDI dependency graph")
public class InitCommand implements Runnable {

    @Option(names = "--project", description = "Path to project root")
    Path projectPath;

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

        Path dbPath = root.resolve(".joker/index.db");
        System.out.println("Writing index to " + dbPath + " ...");
        try {
            Connection conn = JokerDatabase.create(dbPath);
            try {
                String lastCommit = resolveGitHead(root);
                IndexWriter.write(conn, classes, remappedBeans,
                        resolution.injectionPoints(), remappedDeps,
                        Map.of(
                                "indexed_at", Instant.now().toString(),
                                "project_root", root.toString(),
                                "last_commit", lastCommit != null ? lastCommit : "unknown"
                        ));
            } finally {
                conn.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to write index to " + dbPath, e);
        }

        System.out.println("Done. Indexed " + classes.size() + " classes, "
                + resolution.beans().size() + " beans.");
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

    private String resolveGitHead(Path root) {
        try {
            Path headFile = root.resolve(".git/HEAD");
            if (!Files.exists(headFile)) return null;
            String head = Files.readString(headFile).trim();
            if (head.startsWith("ref: ")) {
                Path refFile = root.resolve(".git/" + head.substring(5));
                if (Files.exists(refFile)) {
                    return Files.readString(refFile).trim().substring(0, 7);
                }
            }
            return head.length() > 7 ? head.substring(0, 7) : head;
        } catch (Exception e) {
            return null;
        }
    }
}
