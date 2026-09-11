package org.treblereel.mcp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorktreeInspectorTest {

    @TempDir Path tempDir;

    @Test
    void reportsDirtyServiceDescriptorAndUntrackedFileWithinProject() throws Exception {
        Path project = tempDir.resolve("processor");
        Path service = project.resolve(
                "src/main/resources/META-INF/services/javax.annotation.processing.Processor");
        Files.createDirectories(service.getParent());
        Files.writeString(service, "example.FirstProcessor\nexample.SecondProcessor\n");

        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern("processor").call();
            git.commit().setMessage("fixture").setAuthor("Test", "test@example.com")
                    .setSign(false).call();

            WorktreeInspector.Snapshot clean = WorktreeInspector.inspect(project);
            assertFalse(clean.dirty());

            Files.writeString(service, "example.SecondProcessor\nexample.FirstProcessor\n");
            Path untracked = project.resolve("src/main/java/example/Untracked.java");
            Files.createDirectories(untracked.getParent());
            Files.writeString(untracked, "package example; class Untracked {}\n");
            Files.writeString(tempDir.resolve("outside.txt"), "must not leak into subproject\n");

            WorktreeInspector.Snapshot dirty = WorktreeInspector.inspect(project);
            assertTrue(dirty.dirty());
            assertEquals(2, dirty.changes().size());
            assertTrue(dirty.changes().stream().anyMatch(change ->
                    change.projectPath().endsWith("javax.annotation.processing.Processor")
                            && change.status().equals("modified")));
            assertTrue(dirty.changes().stream().anyMatch(change ->
                    change.projectPath().endsWith("Untracked.java")
                            && change.status().equals("untracked")));
            assertFalse(dirty.changes().stream().anyMatch(change ->
                    change.repositoryPath().equals("outside.txt")));
            assertNotEquals(clean.fingerprint(), dirty.fingerprint());
        }
    }

    @Test
    void reportsTrackedDeletion() throws Exception {
        Path source = tempDir.resolve("src/main/java/example/Deleted.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; class Deleted {}\n");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("Test", "test@example.com")
                    .setSign(false).call();
            Files.delete(source);

            WorktreeInspector.Snapshot snapshot = WorktreeInspector.inspect(tempDir);
            assertTrue(snapshot.changes().stream().anyMatch(change ->
                    change.projectPath().endsWith("Deleted.java")
                            && change.status().equals("deleted")));
        }
    }

    @Test
    void distinguishesDocumentationFromStructuralChanges() throws Exception {
        Path source = tempDir.resolve("src/main/java/example/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example; class App {}\n");
        try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("Test", "test@example.com")
                    .setSign(false).call();

            Files.writeString(tempDir.resolve("README.md"), "notes\n");
            WorktreeInspector.Snapshot docsOnly = WorktreeInspector.inspect(tempDir);
            assertTrue(docsOnly.dirty());
            assertFalse(docsOnly.structuralDirty());

            Files.writeString(source, "package example; class App { int value; }\n");
            WorktreeInspector.Snapshot sourceChanged = WorktreeInspector.inspect(tempDir);
            assertTrue(sourceChanged.structuralDirty());
            assertEquals(1, sourceChanged.structuralChanges().size());
        }
    }
}
