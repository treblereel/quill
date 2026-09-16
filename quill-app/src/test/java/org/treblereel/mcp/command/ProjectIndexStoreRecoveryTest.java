package org.treblereel.mcp.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;

class ProjectIndexStoreRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void fallsBackToPreviousGenerationWhenActiveDatabaseIsCorrupt() throws Exception {
        Path previous = createDatabase("previous", "unknown");
        Path active = createDatabase("active", "unknown");
        ProjectIndexStore.updateRefs(tempDir, "unknown", "previous");
        ProjectIndexStore.updateRefs(tempDir, "unknown", "active");

        Files.writeString(active, "not a sqlite database",
                StandardOpenOption.TRUNCATE_EXISTING);

        assertEquals(previous.toAbsolutePath(),
                ProjectIndexStore.findBestAvailableDb(tempDir));
        assertEquals("previous", ProjectIndexStore.readRefs(
                tempDir.resolve(".quill/refs.json")).get("@worktree"));
        var recovery = ProjectIndexStore.readRecovery(tempDir).orElseThrow();
        assertEquals("referenced_generation_invalid", recovery.reason());
        assertEquals("previous", recovery.selectedIndexId());
    }

    @Test
    void rebuildsCorruptRefsFromDatabaseMetadata() throws Exception {
        Path database = createDatabase("usable", "unknown");
        Files.writeString(tempDir.resolve(".quill/refs.json"), "{broken");

        assertEquals(database.toAbsolutePath(),
                ProjectIndexStore.findBestAvailableDb(tempDir));
        assertEquals("usable", ProjectIndexStore.readRefs(
                tempDir.resolve(".quill/refs.json")).get("@worktree"));
        assertEquals("refs_corrupt",
                ProjectIndexStore.readRecovery(tempDir).orElseThrow().reason());
    }

    @Test
    void concurrentReadersConvergeOnOneRecoveredGeneration() throws Exception {
        Path database = createDatabase("usable", "unknown");
        Files.writeString(tempDir.resolve(".quill/refs.json"), "not-json");
        List<Callable<Path>> reads = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            reads.add(() -> ProjectIndexStore.findBestAvailableDb(tempDir));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var result : executor.invokeAll(reads)) {
                assertEquals(database.toAbsolutePath(), result.get());
            }
        }
        assertEquals("usable", ProjectIndexStore.readRefs(
                tempDir.resolve(".quill/refs.json")).get("@worktree"));
    }

    @Test
    void rejectsCorruptStagingDatabaseBeforePublication() throws Exception {
        Path staging = tempDir.resolve(".quill/.staging.tmp");
        Files.createDirectories(staging.getParent());
        Files.writeString(staging, "broken");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProjectIndexStore.validateForPublication(staging));
        assertTrue(failure.getMessage().contains("integrity validation failed"));
    }

    private Path createDatabase(String indexId, String commit) {
        Path database = tempDir.resolve(".quill/" + indexId + ".db");
        var jdbi = QuillDatabase.create(database);
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "index_id", indexId);
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "last_commit", commit);
            handle.execute("INSERT INTO metadata(key, value) VALUES (?, ?)",
                    "project_root", tempDir.toString());
        });
        return database;
    }
}
