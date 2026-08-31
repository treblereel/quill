package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
