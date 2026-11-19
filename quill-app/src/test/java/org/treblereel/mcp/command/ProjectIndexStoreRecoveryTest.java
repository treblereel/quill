package org.treblereel.mcp.command;

import org.treblereel.mcp.index.ProjectIndexStore;

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
    void generationsOfAnotherQuillVersionAreReadAroundWithoutRewritingRefs() throws Exception {
        createDatabase("own", "unknown");
        for (int version : new int[] {org.treblereel.mcp.db.IndexSchema.VERSION + 1, org.treblereel.mcp.db.IndexSchema.VERSION - 1}) {
            Path foreign = createDatabase("foreign" + version, "unknown");
            QuillDatabase.openWritable(foreign).useHandle(handle -> handle.execute("PRAGMA user_version = " + version));
            Path refs = tempDir.resolve(".quill/refs.json");
            Files.writeString(refs, "{\"@worktree\":\"foreign" + version + "\"}");
            var written = java.nio.file.attribute.FileTime.fromMillis(1_000_000);
            Files.setLastModifiedTime(refs, written);

            assertEquals(tempDir.resolve(".quill/own.db").toAbsolutePath(), ProjectIndexStore.findBestAvailableDb(tempDir));
            assertEquals("{\"@worktree\":\"foreign" + version + "\"}", Files.readString(refs));
            assertEquals(written, Files.getLastModifiedTime(refs), "the other version's server must not be woken");
            assertTrue(ProjectIndexStore.readRecovery(tempDir).isEmpty());
        }
    }

    @Test
    void identicalRefsAndRecoveryAreNotRewritten() throws Exception {
        createDatabase("only", "unknown");
        ProjectIndexStore.updateRefs(tempDir, "unknown", "only");
        Path refs = tempDir.resolve(".quill/refs.json");
        var written = java.nio.file.attribute.FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(refs, written);
        ProjectIndexStore.updateRefs(tempDir, "unknown", "only");
        assertEquals(written, Files.getLastModifiedTime(refs));

        Files.writeString(refs, "{\"@worktree\":\"missing\"}");
        assertEquals(tempDir.resolve(".quill/only.db").toAbsolutePath(), ProjectIndexStore.findBestAvailableDb(tempDir));
        Path recovery = tempDir.resolve(".quill/recovery.json");
        Files.setLastModifiedTime(recovery, written);
        Files.writeString(refs, "{\"@worktree\":\"missing\"}");
        ProjectIndexStore.findBestAvailableDb(tempDir);
        assertEquals(written, Files.getLastModifiedTime(recovery), "the same recovery is recorded once");
    }

    @Test
    void recentlyWrittenOlderSchemaGenerationsSurviveCleanup() throws Exception {
        Path recent = createDatabase("recent-old", "unknown");
        Path abandoned = createDatabase("abandoned-old", "unknown");
        for (Path old : List.of(recent, abandoned))
            QuillDatabase.openWritable(old).useHandle(handle -> handle.execute("PRAGMA user_version = "
                    + (org.treblereel.mcp.db.IndexSchema.VERSION - 1)));
        Files.setLastModifiedTime(abandoned, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(java.time.Duration.ofDays(2))));
        createDatabase("current", "unknown");
        ProjectIndexStore.updateRefs(tempDir, "unknown", "current");

        assertTrue(Files.isRegularFile(recent), "an older Quill may still be serving it");
        assertTrue(Files.notExists(abandoned));
    }

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

    @Test
    void publishingPrunesRefsToOutdatedSchemaGenerations() throws Exception {
        Path outdated = createDatabase("outdated", "old-commit");
        ProjectIndexStore.updateRefs(tempDir, "old-commit", "outdated");
        QuillDatabase.openWritable(outdated).useHandle(
                handle -> handle.execute("PRAGMA user_version = 7"));
        Path current = createDatabase("current", "new-commit");

        ProjectIndexStore.updateRefs(tempDir, "new-commit", "current");

        var refs = ProjectIndexStore.readRefs(tempDir.resolve(".quill/refs.json"));
        assertEquals("current", refs.get("@head:new-commit"));
        assertTrue(refs.values().stream().noneMatch("outdated"::equals));
        assertEquals(current.toAbsolutePath(), ProjectIndexStore.findBestAvailableDb(tempDir));
        assertTrue(ProjectIndexStore.readRecovery(tempDir).isEmpty());
    }

    @Test
    void rejectsDataCorruptionWithReadableMetadataBeforePublicationAndDuringRecovery() throws Exception {
        Path previous = createDatabase("previous", "unknown");
        Path corrupt = createDatabase("corrupt", "unknown");
        corruptData(corrupt);
        assertThrows(IllegalStateException.class, () -> ProjectIndexStore.validateForPublication(corrupt));
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(tempDir.resolve(".quill/refs.json").toFile(), java.util.Map.of("@worktree", "corrupt"));
        assertEquals(previous.toAbsolutePath(), ProjectIndexStore.findBestAvailableDb(tempDir));
    }

    @Test
    void changingValidatedStagingBytesInvalidatesTheProofBeforeRename() throws Exception {
        Path previous = createDatabase("previous", "unknown");
        Path active = createDatabase("active", "unknown");
        Path staged = active.resolveSibling(".staging.tmp");
        Files.move(active, staged);
        long[] page = dataPage(staged);
        ProjectIndexStore.validateForPublication(staged);
        var modified = Files.getLastModifiedTime(staged);
        long size = Files.size(staged);
        damagePage(staged, page);
        Files.setLastModifiedTime(staged, modified);
        assertEquals(size, Files.size(staged));
        assertEquals(modified, Files.getLastModifiedTime(staged));
        ProjectIndexStore.atomicMove(staged, active);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(tempDir.resolve(".quill/refs.json").toFile(), java.util.Map.of("@worktree", "active"));
        assertEquals(previous.toAbsolutePath(), ProjectIndexStore.findBestAvailableDb(tempDir));
    }

    private void corruptData(Path database) throws Exception {
        damagePage(database, dataPage(database));
    }

    private long[] dataPage(Path database) {
        return QuillDatabase.openWritable(database).withHandle(handle -> {
            handle.execute("CREATE TABLE integrity_probe(value BLOB)");
            handle.execute("INSERT INTO integrity_probe VALUES (zeroblob(8192))");
            return new long[]{handle.createQuery("SELECT rootpage FROM sqlite_schema WHERE name='integrity_probe'")
                    .mapTo(Long.class).one(), handle.createQuery("PRAGMA page_size").mapTo(Long.class).one()};
        });
    }

    private void damagePage(Path database, long[] page) throws Exception {
        try (var file = new java.io.RandomAccessFile(database.toFile(), "rw")) {
            file.seek((page[0] - 1) * page[1]);
            file.writeByte(0); // Invalid B-tree page type; the metadata table remains readable.
        }
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
