package org.treblereel.mcp.workspace;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Discovers Git repositories without invoking their build systems. */
public final class WorkspaceDiscovery {

    public record Repository(String name, Path root, String relativePath) {}

    public record Result(List<Repository> repositories, String fingerprint,
            List<String> diagnostics) {
        public Result {
            repositories = List.copyOf(repositories);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static final Set<String> BUILD_FILES = Set.of(
            "pom.xml", "settings.gradle", "settings.gradle.kts",
            "build.gradle", "build.gradle.kts", "gradle.properties");

    private WorkspaceDiscovery() {}

    public static Result discover(WorkspaceManifest manifest) {
        Path workspaceRoot = manifest.root();
        List<Path> roots = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Set<String> excludes = new HashSet<>(manifest.excludes());
        int maxDepth = manifest.discoveryDepth();
        try {
            Files.walkFileTree(workspaceRoot, Set.of(), maxDepth + 1,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path directory,
                                BasicFileAttributes attributes) {
                            if (!directory.equals(workspaceRoot)
                                    && excludes.contains(directory.getFileName().toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            int depth = workspaceRoot.relativize(directory).getNameCount();
                            if (depth > maxDepth) return FileVisitResult.SKIP_SUBTREE;
                            if (depth > 0 && isGitRepository(directory)) {
                                roots.add(directory.toAbsolutePath().normalize());
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException failure) {
                            diagnostics.add("Could not inspect " + file + ": "
                                    + safeMessage(failure));
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException failure) {
            diagnostics.add("Could not scan workspace " + workspaceRoot + ": "
                    + safeMessage(failure));
        }

        roots.sort(Comparator.comparing(path -> normalize(workspaceRoot.relativize(path))));
        List<Repository> repositories = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path root : roots) {
            String base = root.getFileName().toString();
            String name = base;
            int suffix = 1;
            while (!names.add(name)) name = base + "-" + suffix++;
            repositories.add(new Repository(name, root,
                    normalize(workspaceRoot.relativize(root))));
        }
        return new Result(repositories, fingerprint(workspaceRoot, repositories), diagnostics);
    }

    private static boolean isGitRepository(Path directory) {
        Path marker = directory.resolve(".git");
        return Files.isDirectory(marker) || Files.isRegularFile(marker);
    }

    private static String fingerprint(Path workspaceRoot, List<Repository> repositories) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Repository repository : repositories) {
                update(digest, repository.relativePath());
                Path git = repository.root().resolve(".git");
                updateIdentity(digest, git);
                for (String buildFile : BUILD_FILES) {
                    updateIdentity(digest, repository.root().resolve(buildFile));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateIdentity(MessageDigest digest, Path path) {
        if (!Files.exists(path)) return;
        update(digest, path.getFileName().toString());
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            update(digest, Long.toString(attributes.lastModifiedTime().toMillis()));
            update(digest, Long.toString(attributes.size()));
        } catch (IOException ignored) {
            update(digest, "unreadable");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }
}
