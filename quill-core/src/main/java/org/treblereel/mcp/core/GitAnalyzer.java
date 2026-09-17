package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.treblereel.mcp.model.GitCommitFile;
import org.treblereel.mcp.model.GitCommitRecord;
import org.treblereel.mcp.model.GitFileStats;

public final class GitAnalyzer {

    private GitAnalyzer() {}

    public record GitAnalysisResult(
            List<GitFileStats> fileStats,
            List<GitCommitRecord> commits,
            List<GitCommitFile> commitFiles,
            String headHash,
            String headShortHash,
            int scannedCommits,
            int repositoryCommits
    ) {
        public static GitAnalysisResult empty() {
            return new GitAnalysisResult(List.of(), List.of(), List.of(), null, null, 0, 0);
        }

        public boolean isEmpty() {
            return commits.isEmpty();
        }
    }

    public static GitAnalysisResult analyze(Path projectRoot, int maxCommits, Map<String, Integer> sourceFileToClassId) {
        Path gitDir = findGitDir(projectRoot);
        if (gitDir == null) {
            return GitAnalysisResult.empty();
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .readEnvironment()
                .build()) {

            ObjectId head = repo.resolve("HEAD");
            if (head == null) {
                return GitAnalysisResult.empty();
            }

            String headHash = head.getName();
            String headShortHash = headHash.substring(0, 7);

            List<RevCommit> revCommits = new ArrayList<>();
            int repositoryCommits = 0;
            try (RevWalk walk = new RevWalk(repo)) {
                walk.markStart(walk.parseCommit(head));
                for (RevCommit c : walk) {
                    repositoryCommits++;
                    if (revCommits.size() < maxCommits) revCommits.add(c);
                }
            }

            Path gitWorkTree = repo.getWorkTree().toPath().toRealPath();
            Path realProjectRoot = projectRoot.toRealPath();
            String projectPrefix = "";
            if (!realProjectRoot.equals(gitWorkTree)) {
                projectPrefix = gitWorkTree.relativize(realProjectRoot).toString().replace('\\', '/');
                if (!projectPrefix.endsWith("/")) projectPrefix += "/";
            }

            List<GitCommitRecord> commitRecords = new ArrayList<>();
            List<GitCommitFile> commitFiles = new ArrayList<>();
            Map<String, FileAgg> fileAggs = new LinkedHashMap<>();

            try (ObjectReader reader = repo.newObjectReader();
                    DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                df.setRepository(repo);
                df.setDetectRenames(true);

                for (int i = 0; i < revCommits.size(); i++) {
                    RevCommit commit = revCommits.get(i);
                    int commitId = commitRecords.size() + 1;
                    PersonIdent author = commit.getAuthorIdent();
                    int filesBeforeCommit = commitFiles.size();

                    AbstractTreeIterator parentIter;
                    if (commit.getParentCount() > 0) {
                        RevCommit parent = commit.getParent(0);
                        try (RevWalk rw = new RevWalk(repo)) {
                            parent = rw.parseCommit(parent.getId());
                        }
                        CanonicalTreeParser p = new CanonicalTreeParser();
                        p.reset(reader, parent.getTree());
                        parentIter = p;
                    } else {
                        parentIter = new EmptyTreeIterator();
                    }

                    CanonicalTreeParser commitIter = new CanonicalTreeParser();
                    commitIter.reset(reader, commit.getTree());

                    List<DiffEntry> diffs = df.scan(parentIter, commitIter);
                    for (DiffEntry diff : diffs) {
                        String filePath = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                                ? diff.getOldPath() : diff.getNewPath();
                        String changeType = diff.getChangeType().name();

                        String localPath = filePath;
                        if (!projectPrefix.isEmpty()) {
                            if (!filePath.startsWith(projectPrefix)) continue;
                            localPath = filePath.substring(projectPrefix.length());
                        }

                        Integer classId = sourceFileToClassId != null
                                ? sourceFileToClassId.get(localPath) : null;

                        commitFiles.add(new GitCommitFile(commitId, classId, filePath, changeType));

                        fileAggs.computeIfAbsent(filePath, k -> new FileAgg(classId))
                                .record(author.getName(), formatInstant(author.getWhenAsInstant()));
                    }

                    if (commitFiles.size() > filesBeforeCommit) {
                        commitRecords.add(new GitCommitRecord(
                                commitId,
                                commit.getName(),
                                commit.getName().substring(0, 7),
                                author.getName(),
                                author.getEmailAddress(),
                                formatInstant(author.getWhenAsInstant()),
                                commit.getShortMessage()
                        ));
                    }
                }
            }

            List<GitFileStats> fileStats = new ArrayList<>();
            int fsId = 1;
            for (var entry : fileAggs.entrySet()) {
                FileAgg agg = entry.getValue();
                fileStats.add(new GitFileStats(
                        fsId++,
                        entry.getKey(),
                        agg.classId,
                        agg.commitCount,
                        agg.lastModified,
                        agg.lastAuthor,
                        agg.firstCommit,
                        agg.authors.size()
                ));
            }

            return new GitAnalysisResult(fileStats, commitRecords, commitFiles, headHash,
                    headShortHash, revCommits.size(), repositoryCommits);

        } catch (IOException e) {
            throw new RuntimeException("Git analysis failed", e);
        }
    }

    public static boolean hasGitRepo(Path projectRoot) {
        return findGitDir(projectRoot) != null;
    }

    public static String resolveHead(Path projectRoot) {
        Path gitDir = findGitDir(projectRoot);
        if (gitDir == null) return null;
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .readEnvironment()
                .build()) {
            ObjectId head = repo.resolve("HEAD");
            return head != null ? head.getName() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static String resolveCurrentBranch(Path projectRoot) {
        Path gitDir = findGitDir(projectRoot);
        if (gitDir == null) return null;
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .readEnvironment()
                .build()) {
            String branch = repo.getBranch();
            if (branch != null && branch.length() == 40) return null;
            return branch;
        } catch (IOException e) {
            return null;
        }
    }

    static Path findGitDir(Path from) {
        Path dir = from;
        while (dir != null) {
            Path gitPath = dir.resolve(".git");
            if (Files.isDirectory(gitPath)) return gitPath;
            if (Files.isRegularFile(gitPath)) {
                try {
                    String content = Files.readString(gitPath).trim();
                    if (content.startsWith("gitdir:")) {
                        Path resolved = dir.resolve(content.substring("gitdir:".length()).trim()).normalize();
                        if (Files.isDirectory(resolved)) return resolved;
                    }
                } catch (IOException e) {
                    // fall through
                }
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static String formatInstant(Instant instant) {
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private static class FileAgg {
        final Integer classId;
        int commitCount;
        String lastModified;
        String lastAuthor;
        String firstCommit;
        final Set<String> authors = new LinkedHashSet<>();

        FileAgg(Integer classId) {
            this.classId = classId;
        }

        void record(String author, String date) {
            commitCount++;
            authors.add(author);
            if (lastModified == null || date.compareTo(lastModified) > 0) {
                lastModified = date;
                lastAuthor = author;
            }
            if (firstCommit == null || date.compareTo(firstCommit) < 0) {
                firstCommit = date;
            }
        }
    }
}
