package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectIdentityTest {
    @TempDir Path temp;

    @Test void separatesWorktreesAndNestedProjectsButSharesRepository() throws Exception {
        Path main = repository();
        Path linked = temp.resolve("linked space ü");
        git(main, "worktree", "add", "--detach", linked.toString());
        ProjectIdentity a = ProjectIdentity.resolve(main), b = ProjectIdentity.resolve(linked);
        assertEquals(a.repositoryId(), b.repositoryId());
        assertNotEquals(a.worktreeId(), b.worktreeId());
        assertNotEquals(a.projectId(), b.projectId());
        assertTrue(b.linkedWorktree());
        Path module = Files.createDirectory(linked.resolve("module"));
        ProjectIdentity child = ProjectIdentity.resolve(module);
        assertEquals(b.worktreeId(), child.worktreeId());
        assertNotEquals(b.projectId(), child.projectId());
        Files.writeString(linked.resolve("A.java"), "class A { int dirty; }");
        assertEquals(b, ProjectIdentity.resolve(linked));
        try (var repo = b.openRepository()) {
            assertEquals(linked.toRealPath(), repo.getWorkTree().toPath().toRealPath());
            assertNotNull(repo.resolve("HEAD"));
        }
    }

    @Test void canonicalizesSymlinksAndSupportsNonGitProjects() throws Exception {
        Path main = repository();
        Path alias = temp.resolve("alias");
        Files.createSymbolicLink(alias, main);
        assertEquals(ProjectIdentity.resolve(main), ProjectIdentity.resolve(alias));
        ProjectIdentity plain = ProjectIdentity.resolve(Files.createDirectory(temp.resolve("plain")));
        assertFalse(plain.hasGit());
        assertNull(plain.repositoryId());
    }

    @Test void corruptNearestMarkerNeverFallsThroughToParentRepository() throws Exception {
        Path main = repository();
        Path child = Files.createDirectory(main.resolve("child"));
        Files.writeString(child.resolve(".git"), "not a pointer");
        assertThrows(IOException.class, () -> ProjectIdentity.resolve(child));
        Files.writeString(child.resolve(".git"), "gitdir: missing");
        assertThrows(IOException.class, () -> ProjectIdentity.resolve(child));
    }

    @Test void resolvesRelativeGitPointersAndUnbornHead() throws Exception {
        Path main = Files.createDirectory(temp.resolve("unborn"));
        git(main, "init");
        assertTrue(ProjectIdentity.resolve(main).hasGit());
        Path gitdir = temp.resolve("admin");
        Files.move(main.resolve(".git"), gitdir);
        Files.writeString(main.resolve(".git"), "gitdir: ../admin\n");
        assertEquals(gitdir.toRealPath(), ProjectIdentity.resolve(main).gitDirectory());
    }

    @Test void movingLockingRemovingAndPruningDoNotReuseCheckoutIdentity() throws Exception {
        Path main = repository(), linked = temp.resolve("linked"), moved = temp.resolve("moved");
        git(main, "worktree", "add", "--detach", linked.toString());
        ProjectIdentity before = ProjectIdentity.resolve(linked);
        git(main, "worktree", "lock", linked.toString());
        assertEquals(before, ProjectIdentity.resolve(linked));
        git(main, "worktree", "unlock", linked.toString());
        git(main, "worktree", "move", linked.toString(), moved.toString());
        ProjectIdentity after = ProjectIdentity.resolve(moved);
        assertEquals(before.repositoryId(), after.repositoryId());
        assertNotEquals(before.projectId(), after.projectId());
        assertThrows(IOException.class, () -> ProjectIdentity.resolve(linked));
        git(main, "worktree", "remove", moved.toString());
        git(main, "worktree", "prune", "--expire", "now");
        git(main, "worktree", "add", "--detach", linked.toString());
        assertNotEquals(before.projectId(), ProjectIdentity.resolve(linked).projectId());
    }

    @Test void submoduleGitPointerSelectsItsOwnRepository() throws Exception {
        Path main = repository(), dependency = temp.resolve("dependency");
        git(temp, "clone", "--no-hardlinks", main.toString(), dependency.toString());
        git(main, "-c", "protocol.file.allow=always", "submodule", "add", dependency.toString(), "module");
        ProjectIdentity child = ProjectIdentity.resolve(main.resolve("module"));
        assertNotEquals(ProjectIdentity.resolve(main).repositoryId(), child.repositoryId());
        assertEquals(main.resolve("module").toRealPath(), child.worktreeRoot());
        try (var repository = child.openRepository()) { assertNotNull(repository.resolve("HEAD")); }
    }

    Path repository() throws Exception {
        Path main = Files.createDirectory(temp.resolve("main"));
        git(main, "init");
        Files.writeString(main.resolve("A.java"), "class A {}\n");
        git(main, "add", ".");
        git(main, "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-m", "initial");
        return main;
    }

    static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }
}
