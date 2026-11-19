package org.treblereel.mcp.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.index.ProjectIndexStore;

class IndexPublicationValidationTest {
    @TempDir Path root;

    @Test void renamedValidatedGenerationNeedsNoSqlValidationAgain() throws Exception {
        Path staged = database(root, ".staging.tmp", "published");
        boolean reliableIdentity = Files.readAttributes(staged, java.nio.file.attribute.BasicFileAttributes.class).fileKey() != null;
        try { reliableIdentity &= Files.getAttribute(staged, "unix:ctime") != null; }
        catch (UnsupportedOperationException | IllegalArgumentException | java.io.IOException unavailable) { reliableIdentity = false; }
        ProjectIndexStore.validateForPublication(staged);
        Path published = staged.resolveSibling("published.db");
        ProjectIndexStore.atomicMove(staged, published);
        ProjectIndexStore.updateRefs(root, "unknown", "published");
        long statements = statements(() -> {
            try (var metrics = new QueryMetrics()) {
                assertEquals(published, ProjectIndexStore.findBestAvailableDb(root));
            }
        });
        if (reliableIdentity) assertEquals(0, statements, "Publication must reuse integrity evidence for the same moved inode");
        else assertTrue(statements > 0, "Filesystems without guarded identity must validate the final path");
    }

    @Test void concurrentReadersShareOneIntegrityCheck() throws Exception {
        Path serialRoot = Files.createDirectories(root.resolve("serial"));
        Path parallelRoot = Files.createDirectories(root.resolve("parallel"));
        database(serialRoot, "serial.db", "serial");
        database(parallelRoot, "parallel.db", "parallel");
        new ObjectMapper().writeValue(serialRoot.resolve(".quill/refs.json").toFile(), Map.of("@worktree", "serial"));
        new ObjectMapper().writeValue(parallelRoot.resolve(".quill/refs.json").toFile(), Map.of("@worktree", "parallel"));
        long oneReader = statements(() -> {
            try (var metrics = new QueryMetrics()) { assertNotNull(ProjectIndexStore.findBestAvailableDb(serialRoot)); }
        });
        long concurrent = statements(() -> {
            int count = 8;
            var ready = new CountDownLatch(count);
            try (var executor = Executors.newFixedThreadPool(count)) {
                var jobs = new ArrayList<Future<?>>();
                for (int i = 0; i < count; i++) jobs.add(executor.submit(() -> {
                    ready.countDown();
                    try { assertTrue(ready.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException stopped) { throw new AssertionError(stopped); }
                    try (var metrics = new QueryMetrics()) { assertNotNull(ProjectIndexStore.findBestAvailableDb(parallelRoot)); }
                }));
                for (var job : jobs) job.get(10, TimeUnit.SECONDS);
            }
        });
        assertTrue(oneReader > 0);
        assertEquals(oneReader, concurrent, "Concurrent cold readers must not scan the same database repeatedly");
    }

    private Path database(Path directory, String fileName, String id) {
        Path path = directory.resolve(".quill/" + fileName);
        QuillDatabase.create(path).useHandle(handle -> {
            handle.execute("INSERT INTO metadata(key,value) VALUES ('index_id', ?)", id);
            handle.execute("INSERT INTO metadata(key,value) VALUES ('last_commit','unknown')");
        });
        return path;
    }

    private long statements(Checked work) throws Exception {
        var output = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (var capture = new PrintStream(output)) {
            System.setErr(capture);
            work.run();
        } finally { System.setErr(previous); }
        long total = 0;
        int events = 0;
        for (String line : output.toString(java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
            if (!line.startsWith("{\"event\":\"quill_query_metrics\"")) continue;
            events++;
            var event = new ObjectMapper().readTree(line);
            assertEquals("quill_query_metrics", event.path("event").asText(), line);
            total += event.path("sql_statements").asLong();
        }
        assertTrue(events > 0, output.toString());
        return total;
    }

    private interface Checked { void run() throws Exception; }
}
