package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitAnalyzerTest {

    @TempDir Path tempDir;

    @Test
    void findGitDirReturnsDirectoryGit() throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);

        Path result = GitAnalyzer.findGitDir(tempDir);
        assertEquals(gitDir, result);
    }

    @Test
    void findGitDirReturnsNullWhenNoGit() {
        assertNull(GitAnalyzer.findGitDir(tempDir));
    }

    @Test
    void findGitDirWalksUp() throws IOException {
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Path nested = tempDir.resolve("a/b/c");
        Files.createDirectories(nested);

        Path result = GitAnalyzer.findGitDir(nested);
        assertEquals(gitDir, result);
    }

    @Test
    void findGitDirFollowsWorktreeFile() throws IOException {
        Path mainGitDir = tempDir.resolve("main-repo/.git");
        Files.createDirectories(mainGitDir);

        Path worktree = tempDir.resolve("worktree");
        Files.createDirectories(worktree);
        Path worktreeGitDir = tempDir.resolve("main-repo/.git/worktrees/wt1");
        Files.createDirectories(worktreeGitDir);
        Files.writeString(worktree.resolve(".git"), "gitdir: " + worktreeGitDir);

        Path result = GitAnalyzer.findGitDir(worktree);
        assertEquals(worktreeGitDir.normalize(), result);
    }

    @Test
    void findGitDirIgnoresInvalidGitFile() throws IOException {
        Path dir = tempDir.resolve("project");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".git"), "not a gitdir reference");

        assertNull(GitAnalyzer.findGitDir(dir));
    }

    @Test
    void findGitDirIgnoresGitFilePointingNowhere() throws IOException {
        Path dir = tempDir.resolve("project");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(".git"), "gitdir: /nonexistent/path");

        assertNull(GitAnalyzer.findGitDir(dir));
    }

    @Test
    void analyzeReadsMultipleCommitsWithSharedObjectReader() throws Exception {
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            Path source = tempDir.resolve("Example.java");
            Files.writeString(source, "class Example {}\n");
            git.add().addFilepattern("Example.java").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setSign(false).call();

            Files.writeString(source, "class Example { int value; }\n");
            git.add().addFilepattern("Example.java").call();
            git.commit().setMessage("change").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
        }

        GitAnalyzer.GitAnalysisResult result = GitAnalyzer.analyze(
                tempDir, 10, Map.of("Example.java", 1));

        assertEquals(2, result.commits().size());
        assertEquals(2, result.commitFiles().size());
        assertEquals(1, result.fileStats().size());
        assertEquals(2, result.fileStats().get(0).commitCount());
    }
}
