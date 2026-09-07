package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.QuillDatabase;

class ProjectIndexLifecycleTest {

    @TempDir Path tempDir;

    @Test
    void cleanupLruKeepsFiveNewestDatabasesAndPrunesRefs() throws Exception {
        Path quillDir = Files.createDirectories(tempDir.resolve(".quill"));
        Map<String, String> refs = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            String hash = "commit" + i;
            Path db = Files.writeString(quillDir.resolve(hash + ".db"), hash);
            Files.setLastModifiedTime(db, FileTime.fromMillis(1_000L + i));
            refs.put("branch" + i, hash);
        }

        ProjectInitializer.cleanupLru(tempDir, refs);

        assertFalse(Files.exists(quillDir.resolve("commit0.db")));
        assertFalse(Files.exists(quillDir.resolve("commit1.db")));
        for (int i = 2; i < 7; i++) {
            assertTrue(Files.exists(quillDir.resolve("commit" + i + ".db")));
        }
        assertEquals(5, refs.size());
        assertFalse(refs.containsValue("commit0"));
        assertFalse(refs.containsValue("commit1"));
        assertEquals(refs, ProjectInitializer.readRefs(quillDir.resolve("refs.json")));
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
            Path db = ProjectInitializer.findDbForHead(project);
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
    void lifecycleLockSerializesCleanBehindAnActiveIndexOperation() throws Exception {
        Files.createFile(tempDir.resolve("pom.xml"));
        Path quillDir = Files.createDirectories(tempDir.resolve(".quill"));
        Path database = Files.writeString(quillDir.resolve("nocommit.db"), "index");
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
                command.run();
            });

            Thread.sleep(100);
            assertFalse(cleaning.isDone(), "clean must wait for the active index operation");
            assertTrue(Files.exists(database));

            releaseLock.countDown();
            indexing.get(5, TimeUnit.SECONDS);
            cleaning.get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(database));
            assertTrue(Files.exists(quillDir.resolve(ProjectIndexLock.LOCK_FILE)));
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
