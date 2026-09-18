package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.ProjectRegistry;
import org.treblereel.mcp.model.MetaEnvelope;

class ProjectIndexLifecycleTest {

    @TempDir Path tempDir;

    @Test
    void servingFallsBackAfterHeadChangesAndOnNewBranch() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        String indexedCommit;
        String branch;
        try (Git git = Git.init().setDirectory(project.toFile()).call()) {
            Files.writeString(project.resolve("pom.xml"), "<project/>");
            git.add().addFilepattern(".").call();
            indexedCommit = git.commit().setMessage("indexed")
                    .setAuthor("Test", "test@example.com").setSign(false).call().getName();
            branch = git.getRepository().getBranch();

            Path quillDir = Files.createDirectories(project.resolve(".quill"));
            String indexId = indexedCommit + "-generation";
            Path database = quillDir.resolve(indexId + ".db");
            var jdbi = QuillDatabase.create(database);
            jdbi.useHandle(handle -> {
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "index_id", indexId);
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "indexed_at", "2026-09-15T00:00:00Z");
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "last_commit", indexedCommit);
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "project_root", project.toString());
            });
            Files.writeString(quillDir.resolve("refs.json"), """
                    {
                      "@head:%s": "%s",
                      "%s": "%s"
                    }
                    """.formatted(indexedCommit, indexId, branch, indexId));

            Files.writeString(project.resolve("README.md"), "new commit\n");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("head moved")
                    .setAuthor("Test", "test@example.com").setSign(false).call();

            assertNull(ProjectIndexStore.findExactDbForHead(project));
            assertEquals(database, ProjectIndexStore.findBestAvailableDb(project));

            git.checkout().setCreateBranch(true).setName("new-branch").call();
            assertEquals(database, ProjectIndexStore.findBestAvailableDb(project),
                    "A new branch should use the newest atomically published ref");

            ProjectRegistry registry = new ProjectRegistry();
            registry.register(project);
            ProjectRegistry.Resolution resolution = registry.resolve();
            assertTrue(resolution.errors().isEmpty());
            assertEquals(1, resolution.projects().size());
            MetaEnvelope freshness = MetaEnvelope.from(
                    resolution.projects().getFirst().jdbi(), 0, 0);
            assertTrue(freshness.commitStale());
            assertTrue(freshness.structureStale());
            assertTrue(freshness.staleReasons().contains("commit_changed_after_index"));
        }
    }

    @Test
    void cleanupLruKeepsFiveNewestDatabasesAndPrunesRefs() throws Exception {
        Path quillDir = Files.createDirectories(tempDir.resolve(".quill"));
        Map<String, String> refs = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            String hash = "commit" + i;
            Path db = quillDir.resolve(hash + ".db");
            QuillDatabase.create(db).useHandle(handle -> {
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "index_id", hash);
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "last_commit", hash);
                handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                        "project_root", tempDir.toString());
            });
            Files.setLastModifiedTime(db, FileTime.fromMillis(1_000L + i));
            refs.put("branch" + i, hash);
        }

        ProjectIndexStore.cleanupLru(tempDir, refs);

        assertFalse(Files.exists(quillDir.resolve("commit0.db")));
        assertFalse(Files.exists(quillDir.resolve("commit1.db")));
        for (int i = 2; i < 7; i++) {
            assertTrue(Files.exists(quillDir.resolve("commit" + i + ".db")));
        }
        assertEquals(5, refs.size());
        assertFalse(refs.containsValue("commit0"));
        assertFalse(refs.containsValue("commit1"));
        assertEquals(refs, ProjectIndexStore.readRefs(quillDir.resolve("refs.json")));
    }

    @Test
    void concurrentInitializationsPublishOneCompleteDatabase() throws Exception {
        Path project = Path.of(System.getProperty("user.dir"));
        Path quillDir = project.resolve(".quill");
        deleteTree(quillDir);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                start.await();
                return ProjectInitializer.initialize(project, true);
            });
            var second = executor.submit(() -> {
                start.await();
                return ProjectInitializer.initialize(project, true);
            });
            start.countDown();

            assertTrue(first.get(60, TimeUnit.SECONDS));
            assertTrue(second.get(60, TimeUnit.SECONDS));
            Path db = ProjectIndexStore.findDbForHead(project);
            assertNotNull(db);
            assertTrue(IndexReader.countClasses(QuillDatabase.open(db)) > 0);
            try (var files = Files.list(quillDir)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
            }
        } finally {
            executor.shutdownNow();
            deleteTree(quillDir);
        }
    }

    @Test
    void updatePublishesNewGenerationWhilePreviousDatabaseIsBeingRead() throws Exception {
        Path project = Path.of(System.getProperty("user.dir"));
        Path quillDir = project.resolve(".quill");
        deleteTree(quillDir);
        CountDownLatch oldSnapshotRead = new CountDownLatch(1);
        CountDownLatch releaseOldSnapshot = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(ProjectInitializer.initialize(project, true));
            Path firstDatabase = ProjectIndexStore.findDbForHead(project);
            assertNotNull(firstDatabase);

            ProjectRegistry registry = new ProjectRegistry();
            registry.register(project);
            ProjectRegistry.ProjectEntry firstEntry = registry.resolve().projects().getFirst();
            var oldRead = executor.submit(() -> firstEntry.jdbi().inTransaction(handle -> {
                String before = IndexReader.getMetadata(firstEntry.jdbi()).get("index_id");
                oldSnapshotRead.countDown();
                if (!releaseOldSnapshot.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release old snapshot");
                }
                String after = IndexReader.getMetadata(firstEntry.jdbi()).get("index_id");
                return List.of(before, after);
            }));
            assertTrue(oldSnapshotRead.await(10, TimeUnit.SECONDS));

            assertTrue(ProjectInitializer.initialize(project, true));
            Path secondDatabase = ProjectIndexStore.findDbForHead(project);
            assertNotNull(secondDatabase);
            assertNotEquals(firstDatabase, secondDatabase,
                    "An update must publish an immutable database generation");

            ProjectRegistry.ProjectEntry secondEntry = registry.resolve().projects().getFirst();
            String currentIndex = IndexReader.getMetadata(secondEntry.jdbi()).get("index_id");
            assertEquals(secondDatabase.getFileName().toString().replaceFirst("\\.db$", ""),
                    currentIndex);

            releaseOldSnapshot.countDown();
            List<String> oldIndexIds = oldRead.get(10, TimeUnit.SECONDS);
            assertEquals(oldIndexIds.getFirst(), oldIndexIds.getLast(),
                    "An in-flight read must stay on its original SQLite snapshot");
            assertNotEquals(oldIndexIds.getFirst(), currentIndex,
                    "A new registry resolution must see the newly published index");
        } finally {
            releaseOldSnapshot.countDown();
            executor.shutdownNow();
            deleteTree(quillDir);
        }
    }

    @Test
    void lifecycleLockSerializesCleanBehindAnActiveIndexOperation() throws Exception {
        Files.createFile(tempDir.resolve("pom.xml"));
        Path quillDir = Files.createDirectories(tempDir.resolve(".quill"));
        Path database = Files.writeString(quillDir.resolve("active.db"), "index");
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var indexing = executor.submit(() -> ProjectIndexLock.withLock(tempDir, () -> {
                lockAcquired.countDown();
                try {
                    if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release test lock");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
                return null;
            }));
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

            var cleaning = executor.submit(() -> {
                CleanCommand command = new CleanCommand();
                command.projectPath = tempDir;
                command.call();
            });

            Thread.sleep(100);
            assertFalse(cleaning.isDone(), "clean must wait for the active index operation");
            assertTrue(Files.exists(database));

            releaseLock.countDown();
            indexing.get(5, TimeUnit.SECONDS);
            cleaning.get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(database));
            assertFalse(Files.exists(quillDir));
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
