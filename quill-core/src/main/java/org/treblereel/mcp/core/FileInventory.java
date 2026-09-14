package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.FileRecord;
import org.treblereel.mcp.model.GitFileStats;

/** Builds stable project/repository path identities for source, generated and historical files. */
public final class FileInventory {

    private FileInventory() {}

    public record Result(List<FileRecord> files, List<ClassRecord> classes) {}

    public static Result build(Path projectRoot, List<Path> moduleDirectories,
            List<Path> sourceRoots, List<ClassRecord> classes, List<GitFileStats> gitFiles,
            WorktreeInspector.Snapshot worktree) {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path repositoryRoot = worktree.repositoryRoot() != null
                ? worktree.repositoryRoot().toAbsolutePath().normalize() : root;
        String projectPrefix = root.startsWith(repositoryRoot)
                ? normalize(repositoryRoot.relativize(root)) : "";
        if (!projectPrefix.isEmpty()) projectPrefix += "/";

        Map<String, MutableFile> byRepositoryPath = new LinkedHashMap<>();
        Map<String, String> worktreeStatuses = worktree.statusesByRepositoryPath();

        Set<Path> rootsToScan = new LinkedHashSet<>(sourceRoots);
        for (Path module : moduleDirectories) {
            rootsToScan.add(module.resolve("src/main/resources"));
        }
        for (Path sourceRoot : rootsToScan) {
            addTree(root, repositoryRoot, sourceRoot, byRepositoryPath, worktreeStatuses);
        }

        for (WorktreeInspector.Change change : worktree.changes()) {
            byRepositoryPath.compute(change.repositoryPath(), (ignored, existing) -> {
                MutableFile file = existing != null ? existing
                        : new MutableFile(change.projectPath(), change.repositoryPath(),
                                kind(change.projectPath()), origin(change.projectPath()),
                                change.status().equals("deleted") ? "deleted" : "current", null);
                file.worktreeStatus = change.status();
                if (change.status().equals("deleted")) file.lifecycle = "deleted";
                return file;
            });
        }

        for (GitFileStats stats : gitFiles) {
            String repositoryPath = normalize(Path.of(stats.filePath()));
            String projectPath = projectPath(repositoryPath, projectPrefix);
            if (projectPath == null) continue;
            Path currentPath = repositoryRoot.resolve(repositoryPath).normalize();
            boolean exists = currentPath.startsWith(repositoryRoot) && Files.exists(currentPath);
            byRepositoryPath.putIfAbsent(repositoryPath,
                    new MutableFile(projectPath, repositoryPath, kind(projectPath), origin(projectPath),
                            exists ? "current" : "historical", worktreeStatuses.get(repositoryPath)));
        }

        List<MutableFile> ordered = byRepositoryPath.values().stream()
                .sorted(Comparator.comparing(file -> file.repositoryPath))
                .toList();
        List<FileRecord> files = new ArrayList<>();
        Map<String, Integer> fileIdsByProjectPath = new LinkedHashMap<>();
        int nextId = 1;
        for (MutableFile file : ordered) {
            files.add(new FileRecord(nextId, file.projectPath, file.repositoryPath, file.kind,
                    file.origin, file.lifecycle, file.worktreeStatus));
            fileIdsByProjectPath.put(file.projectPath, nextId++);
        }

        List<ClassRecord> classifiedClasses = new ArrayList<>();
        for (ClassRecord cls : classes) {
            String source = normalizeSource(root, cls.sourceFile());
            if (source == null) source = inferTopLevelSource(cls.className(), fileIdsByProjectPath.keySet());
            Integer fileId = source != null ? fileIdsByProjectPath.get(source) : null;
            String classOrigin = source != null ? origin(source) : "orphan_output";
            String lifecycle = fileId != null ? files.get(fileId - 1).lifecycle() : "current";
            classifiedClasses.add(new ClassRecord(cls.id(), cls.className(), cls.kind(),
                    cls.superclass(), cls.interfaces(), source, cls.sourceLine(), cls.isBean(),
                    cls.sourceTokens(), fileId, classOrigin, lifecycle));
        }
        return new Result(List.copyOf(files), List.copyOf(classifiedClasses));
    }

    private static void addTree(Path projectRoot, Path repositoryRoot, Path tree,
            Map<String, MutableFile> files, Map<String, String> worktreeStatuses) {
        if (!Files.isDirectory(tree)) return;
        try (Stream<Path> walk = Files.walk(tree)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                Path absolute = file.toAbsolutePath().normalize();
                if (!absolute.startsWith(projectRoot) || !absolute.startsWith(repositoryRoot)) continue;
                String projectPath = normalize(projectRoot.relativize(absolute));
                String repositoryPath = normalize(repositoryRoot.relativize(absolute));
                files.put(repositoryPath, new MutableFile(projectPath, repositoryPath,
                        kind(projectPath), origin(projectPath), "current",
                        worktreeStatuses.get(repositoryPath)));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to inventory " + tree, e);
        }
    }

    private static String normalizeSource(Path projectRoot, String sourceFile) {
        if (sourceFile == null) return null;
        Path source = Path.of(sourceFile).normalize();
        if (source.isAbsolute()) {
            if (!source.startsWith(projectRoot)) return null;
            source = projectRoot.relativize(source);
        }
        return normalize(source);
    }

    private static String inferTopLevelSource(String className, Set<String> projectPaths) {
        String topLevel = className.contains("$")
                ? className.substring(0, className.indexOf('$')) : className;
        String base = topLevel.replace('.', '/');
        return projectPaths.stream()
                .filter(path -> path.endsWith(base + ".java") || path.endsWith(base + ".kt"))
                .findFirst().orElse(null);
    }

    private static String projectPath(String repositoryPath, String projectPrefix) {
        if (projectPrefix.isEmpty()) return repositoryPath;
        return repositoryPath.startsWith(projectPrefix)
                ? repositoryPath.substring(projectPrefix.length()) : null;
    }

    private static String kind(String path) {
        if (path.endsWith(".java")) return "java";
        if (path.endsWith(".kt")) return "kotlin";
        if (path.contains("/META-INF/services/") || path.startsWith("META-INF/services/")) {
            return "service_descriptor";
        }
        return path.contains("/resources/") ? "resource" : "file";
    }

    private static String origin(String path) {
        if (path.contains("/target/generated-sources/") || path.startsWith("target/generated-sources/")
                || path.contains("/build/generated/") || path.startsWith("build/generated/")) {
            return "generated";
        }
        if (kind(path).equals("resource") || kind(path).equals("service_descriptor")) return "resource";
        return "source";
    }

    private static String normalize(Path path) {
        return path.normalize().toString().replace('\\', '/');
    }

    private static final class MutableFile {
        final String projectPath;
        final String repositoryPath;
        final String kind;
        final String origin;
        String lifecycle;
        String worktreeStatus;

        private MutableFile(String projectPath, String repositoryPath, String kind, String origin,
                String lifecycle, String worktreeStatus) {
            this.projectPath = projectPath;
            this.repositoryPath = repositoryPath;
            this.kind = kind;
            this.origin = origin;
            this.lifecycle = lifecycle;
            this.worktreeStatus = worktreeStatus;
        }
    }
}
