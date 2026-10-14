package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinkedWorktreeGitTest {
    @TempDir Path temp;

    @Test void headHistoryAndStagingBelongToTheSelectedCheckout() throws Exception {
        ProjectIdentityTest fixture = new ProjectIdentityTest();
        fixture.temp = temp;
        Path main = fixture.repository();
        Path linked = temp.resolve("linked");
        ProjectIdentityTest.git(main, "worktree", "add", "-b", "feature", linked.toString());
        Files.writeString(linked.resolve("A.java"), "class A { int linked; }\n");
        ProjectIdentityTest.git(linked, "add", "A.java");
        assertFalse(WorktreeInspector.inspect(main).dirty());
        var staged = WorktreeInspector.inspect(linked);
        assertTrue(staged.verified());
        assertEquals("modified", staged.changes().getFirst().status());
        ProjectIdentityTest.git(linked, "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-m", "linked change");
        assertNotEquals(GitAnalyzer.resolveHead(main), GitAnalyzer.resolveHead(linked));
        assertEquals("feature", GitAnalyzer.resolveCurrentBranch(linked));
        assertEquals(2, GitAnalyzer.analyze(linked, 10, Map.of("A.java", 1)).commits().size());
        assertEquals(1, GitAnalyzer.analyze(main, 10, Map.of("A.java", 1)).commits().size());
        Files.writeString(main.resolve("A.java"), "class A { int main; }\n");
        Files.writeString(linked.resolve("New.java"), "class New {}\n");
        assertEquals("A.java", WorktreeInspector.inspect(main).changes().getFirst().projectPath());
        assertEquals("New.java", WorktreeInspector.inspect(linked).changes().getFirst().projectPath());
        ProjectIdentityTest.git(linked, "checkout", "--detach");
        assertNull(GitAnalyzer.resolveCurrentBranch(linked));
        assertNotNull(GitAnalyzer.resolveHead(linked));
    }

    @Test void invalidGitStateIsUnknownAndUnbornHeadIsReadable() throws Exception {
        Path root = Files.createDirectory(temp.resolve("root"));
        ProjectIdentityTest.git(root, "init");
        assertTrue(WorktreeInspector.inspect(root).verified());
        assertNull(WorktreeInspector.inspect(root).currentCommit());
        Files.writeString(root.resolve(".git/HEAD"), "invalid head\n");
        assertFalse(WorktreeInspector.inspect(root).verified());
        Path broken = Files.createDirectory(temp.resolve("broken"));
        Files.writeString(broken.resolve(".git"), "gitdir: missing\n");
        assertEquals("unknown", WorktreeInspector.inspect(broken).gitStatus());
    }
}
