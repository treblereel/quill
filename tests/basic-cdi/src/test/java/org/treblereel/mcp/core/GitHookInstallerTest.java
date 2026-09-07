package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHookInstallerTest {

    @TempDir Path tempDir;

    @Test
    void installCreatesHooksInGitDir() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        GitHookInstaller.install(tempDir);

        Path postCommit = hooksDir.resolve("post-commit");
        Path postMerge = hooksDir.resolve("post-merge");
        assertTrue(Files.exists(postCommit));
        assertTrue(Files.exists(postMerge));

        String content = Files.readString(postCommit);
        assertTrue(content.startsWith("#!/bin/sh"));
        assertTrue(content.contains("quill-start"));
        assertTrue(content.contains("quill update"));
        assertTrue(content.contains("--compile"));
        assertTrue(content.contains("quill-end"));
    }

    @Test
    void installIsIdempotent() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        GitHookInstaller.install(tempDir);
        GitHookInstaller.install(tempDir);

        String content = Files.readString(hooksDir.resolve("post-commit"));
        int count = countOccurrences(content, "quill-start");
        assertEquals(1, count, "Should only contain one quill block after double install");
    }

    @Test
    void installPreservesExistingHook() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path postCommit = hooksDir.resolve("post-commit");
        Files.writeString(postCommit, "#!/bin/sh\necho 'existing hook'\n");

        GitHookInstaller.install(tempDir);

        String content = Files.readString(postCommit);
        assertTrue(content.contains("existing hook"));
        assertTrue(content.contains("quill-start"));
    }

    @Test
    void uninstallRemovesQuillBlock() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path postCommit = hooksDir.resolve("post-commit");
        Files.writeString(postCommit, "#!/bin/sh\necho 'keep this'\n");

        GitHookInstaller.install(tempDir);
        assertTrue(Files.readString(postCommit).contains("quill-start"));

        GitHookInstaller.uninstall(tempDir);
        String content = Files.readString(postCommit);
        assertFalse(content.contains("quill-start"));
        assertTrue(content.contains("keep this"));
    }

    @Test
    void uninstallDeletesHookIfOnlyQuillContent() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        GitHookInstaller.install(tempDir);
        assertTrue(Files.exists(hooksDir.resolve("post-commit")));

        GitHookInstaller.uninstall(tempDir);
        assertFalse(Files.exists(hooksDir.resolve("post-commit")));
    }

    @Test
    void installDoesNothingWithoutGitDir() {
        GitHookInstaller.install(tempDir);
        assertFalse(Files.exists(tempDir.resolve(".git/hooks/post-commit")));
    }

    @Test
    void hookContainsAbsoluteProjectPath() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        GitHookInstaller.install(tempDir);

        String content = Files.readString(hooksDir.resolve("post-commit"));
        String absolutePath = tempDir.toAbsolutePath().normalize().toString();
        assertTrue(content.contains(absolutePath),
                "Hook should contain absolute project path, got: " + content);
        assertFalse(content.contains("git rev-parse"),
                "Hook should not use git rev-parse");
    }

    @Test
    void postCheckoutHookHasBranchGuard() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        GitHookInstaller.install(tempDir);

        String content = Files.readString(hooksDir.resolve("post-checkout"));
        assertTrue(content.contains("\"$3\" = \"1\""),
                "post-checkout hook should only run on branch checkout");
    }

    @Test
    void uninstallDoesNotRemoveOtherProjectBlock() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);

        GitHookInstaller.install(tempDir);
        Path postCommit = hooksDir.resolve("post-commit");
        assertTrue(Files.readString(postCommit).contains("quill-start"));

        Path otherProject = tempDir.resolve("other-project");
        Files.createDirectories(otherProject);
        GitHookInstaller.uninstall(otherProject);

        assertTrue(Files.exists(postCommit), "Hook file should still exist");
        assertTrue(Files.readString(postCommit).contains("quill-start"),
                "Quill block for original project should not be removed by uninstall of different project");
    }

    @Test
    void sharedHooksKeepIndependentProjectBlocks() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path firstProject = Files.createDirectories(tempDir.resolve("first-project"));
        Path secondProject = Files.createDirectories(tempDir.resolve("second-project"));

        GitHookInstaller.install(firstProject);
        GitHookInstaller.install(secondProject);

        Path postCommit = hooksDir.resolve("post-commit");
        String content = Files.readString(postCommit);
        assertEquals(2, countOccurrences(content, "quill-start:"));
        assertTrue(content.contains(firstProject.toAbsolutePath().normalize().toString()));
        assertTrue(content.contains(secondProject.toAbsolutePath().normalize().toString()));

        GitHookInstaller.uninstall(firstProject);
        content = Files.readString(postCommit);
        assertFalse(content.contains(firstProject.toAbsolutePath().normalize().toString()));
        assertTrue(content.contains(secondProject.toAbsolutePath().normalize().toString()),
                "Uninstalling one worktree/project must preserve the other block");
    }

    @Test
    void projectPathIsSafelyQuotedForShell() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(BuildSystem.isWindows(),
                "Executed with the POSIX shell on Unix; Windows hook content is tested separately");
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path project = Files.createDirectories(
                tempDir.resolve("project-$(touch pwned)-'quoted'"));

        GitHookInstaller.install(project);

        Path hook = hooksDir.resolve("post-commit");
        String projectPath = project.toAbsolutePath().normalize().toString();
        String content = Files.readString(hook);
        assertTrue(content.contains(GitHookInstaller.shellQuote(projectPath)));

        Process process = new ProcessBuilder("/bin/sh", hook.toString())
                .directory(tempDir.toFile())
                .start();
        assertEquals(0, process.waitFor());
        assertFalse(Files.exists(tempDir.resolve("pwned")),
                "Shell metacharacters in a project path must never execute");
    }

    @Test
    void installMigratesLegacyBlockForSameProject() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        String projectPath = tempDir.toAbsolutePath().normalize().toString();
        Path hook = hooksDir.resolve("post-commit");
        Files.writeString(hook, "#!/bin/sh\n"
                + "# --- quill-start ---\n"
                + "quill update --project \"" + projectPath + "\"\n"
                + "# --- quill-end ---\n");

        GitHookInstaller.install(tempDir);

        String content = Files.readString(hook);
        assertEquals(1, countOccurrences(content, "quill-start"));
        assertTrue(content.contains("quill-start:"));
        assertFalse(content.contains("# --- quill-start ---"));
    }

    @Test
    void installUsesPreferredAbsoluteLauncherBeforePathFallback() throws IOException {
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path launcher = Files.createFile(tempDir.resolve("quill binary"));

        GitHookInstaller.install(tempDir,
                new GitHookInstaller.Launcher(List.of(launcher.toString()), launcher));

        String content = Files.readString(hooksDir.resolve("post-commit"));
        assertTrue(content.contains(GitHookInstaller.shellQuote(launcher.toString())));
        assertTrue(content.contains("update --compile --project"));
        assertTrue(content.indexOf(launcher.toString()) < content.indexOf("command -v quill"));
    }

    @Test
    void preferredLauncherReceivesCompileUpdateArguments() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(BuildSystem.isWindows(),
                "Executed with the POSIX shell on Unix; Windows hook content is tested separately");
        Path hooksDir = tempDir.resolve(".git/hooks");
        Files.createDirectories(hooksDir);
        Path arguments = tempDir.resolve("arguments.txt");
        Path launcher = Files.writeString(tempDir.resolve("fake-quill"),
                "#!/bin/sh\nprintf '%s\\n' \"$@\" > "
                        + GitHookInstaller.shellQuote(arguments.toString()) + "\n");
        launcher.toFile().setExecutable(true);
        GitHookInstaller.install(tempDir,
                new GitHookInstaller.Launcher(List.of(launcher.toString()), launcher));

        Process hook = new ProcessBuilder("/bin/sh", hooksDir.resolve("post-commit").toString())
                .directory(tempDir.toFile()).start();
        assertEquals(0, hook.waitFor());
        for (int i = 0; i < 100 && !Files.exists(arguments); i++) Thread.sleep(10);

        assertEquals(List.of("update", "--compile", "--project",
                        tempDir.toAbsolutePath().normalize().toString()),
                Files.readAllLines(arguments));
    }

    private int countOccurrences(String text, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }
}
