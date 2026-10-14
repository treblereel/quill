package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/** Physical checkout identity. Branches and commits intentionally do not participate. */
public record ProjectIdentity(Path projectRoot, Path worktreeRoot, Path gitDirectory,
        Path commonDirectory, String repositoryId, String worktreeId, String projectId) {

    public static ProjectIdentity resolve(Path project) throws IOException {
        Path root = project.toRealPath();
        if (!Files.isDirectory(root)) throw new IOException("Project root is not a directory: " + root);
        for (Path directory = root; directory != null; directory = directory.getParent()) {
            Path marker = directory.resolve(".git");
            if (!Files.exists(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            Path git;
            if (Files.isDirectory(marker)) {
                git = marker.toRealPath();
            } else if (Files.isRegularFile(marker)) {
                String content = Files.readString(marker).strip();
                if (!content.startsWith("gitdir:") || content.substring(7).isBlank()) {
                    throw new IOException("Invalid Git directory pointer: " + marker);
                }
                git = directory.resolve(content.substring(7).strip()).toRealPath();
            } else {
                throw new IOException("Unreadable Git marker: " + marker);
            }
            Path common = git;
            Path commonPointer = git.resolve("commondir");
            if (Files.exists(commonPointer)) {
                String value = Files.readString(commonPointer).strip();
                if (value.isEmpty()) throw new IOException("Empty Git common directory pointer: " + commonPointer);
                common = git.resolve(value).toRealPath();
            }
            if (!Files.isRegularFile(git.resolve("HEAD")) || !Files.isDirectory(common.resolve("objects"))) {
                throw new IOException("Incomplete Git repository: " + marker);
            }
            String head = Files.readString(git.resolve("HEAD")).strip();
            if (!head.matches("[0-9a-fA-F]{40}")
                    && !(head.startsWith("ref: ") && Repository.isValidRefName(head.substring(5)))) {
                throw new IOException("Invalid Git HEAD: " + git);
            }
            return new ProjectIdentity(root, directory, git, common, id("repository", common),
                    id("worktree", directory, git), id("project", root, git));
        }
        return new ProjectIdentity(root, root, null, null, null,
                id("worktree", root), id("project", root));
    }

    public boolean hasGit() { return gitDirectory != null; }
    public boolean linkedWorktree() { return hasGit() && !gitDirectory.equals(commonDirectory); }

    /** Explicit paths prevent inherited GIT_* environment variables from changing the checkout. */
    public Repository openRepository() throws IOException {
        if (!hasGit()) throw new IOException("Project has no Git repository: " + projectRoot);
        return new FileRepositoryBuilder().setGitDir(gitDirectory.toFile())
                .setGitCommonDir(commonDirectory.toFile()).setWorkTree(worktreeRoot.toFile())
                .setIndexFile(gitDirectory.resolve("index").toFile()).setMustExist(true).build();
    }

    public static Path canonical(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path ancestor = normalized;
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.getParent();
        try {
            return ancestor == null ? normalized
                    : ancestor.toRealPath().resolve(ancestor.relativize(normalized)).normalize();
        } catch (IOException failure) { return normalized; }
    }

    private static String id(String kind, Path... paths) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path path : paths) {
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
                digest.update((attributes.creationTime() + ":" + attributes.fileKey())
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return kind + ":" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
